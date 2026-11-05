[CmdletBinding()]
param(
    [string] $Filter = '*'
)

$ErrorActionPreference = 'Stop'
$toolsRoot = Split-Path -Parent $PSScriptRoot
$pwshPath = (Get-Command 'pwsh.exe' -ErrorAction Stop).Source
$utf8 = [Text.UTF8Encoding]::new($false)
$script:passed = 0
$script:failed = 0

function Assert-True {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal {
    param(
        $Actual,
        $Expected,
        [string] $Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message; expected=$Expected actual=$Actual"
    }
}

function New-TestRoot {
    $root = Join-Path ([IO.Path]::GetTempPath()) (
        'aurorama-qa-v8-tools-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
    return $root
}

function Remove-TestRoot {
    param([string] $Root)

    if ([string]::IsNullOrWhiteSpace($Root) -or
        -not (Test-Path -LiteralPath $Root)) {
        return
    }
    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolvedRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedRoot) -notlike 'aurorama-qa-v8-tools-*') {
        throw "Refusing to remove unexpected test root: $resolvedRoot"
    }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
            return
        } catch {
            if ($attempt -eq 19) {
                throw
            }
            Start-Sleep -Milliseconds 100
        }
    }
}

function Read-SharedText {
    param([string] $Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return ''
    }
    $stream = [IO.FileStream]::new(
        $Path,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
    try {
        $reader = [IO.StreamReader]::new(
            $stream,
            [Text.Encoding]::UTF8,
            $true,
            4096,
            $true)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Wait-ForFile {
    param(
        [string] $Path,
        [int] $TimeoutSeconds = 5
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (-not (Test-Path -LiteralPath $Path) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 50
    }
    return Test-Path -LiteralPath $Path
}

function Stop-TestProcess {
    param([int] $ProcessId)

    if ($ProcessId -gt 0) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-CapturedProcess {
    param(
        [string] $FilePath,
        [string[]] $Arguments,
        [int] $TimeoutSeconds = 8
    )

    $stdoutPath = Join-Path $script:testRoot 'capture.stdout.log'
    $stderrPath = Join-Path $script:testRoot 'capture.stderr.log'
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath
    $completed = $process.WaitForExit($TimeoutSeconds * 1000)
    if (-not $completed) {
        try {
            $process.Kill($true)
        } catch {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    $process.WaitForExit(3000) | Out-Null
    $result = [pscustomobject]@{
        TimedOut = -not $completed
        ExitCode = if ($completed) { [int]$process.ExitCode } else { $null }
        Stdout = Read-SharedText $stdoutPath
        Stderr = Read-SharedText $stderrPath
    }
    $process.Dispose()
    return $result
}

function Test-Case {
    param(
        [string] $Name,
        [scriptblock] $Body
    )

    if ($Name -notlike $Filter) {
        return
    }
    $script:testRoot = New-TestRoot
    try {
        & $Body
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        Write-Output (
            "FAIL $Name :: $($_.Exception.Message) :: " +
            $_.InvocationInfo.PositionMessage.Replace("`r", ' ').Replace("`n", ' '))
    } finally {
        Remove-TestRoot $script:testRoot
        $script:testRoot = $null
    }
}

Test-Case 'start-logcat returns with live stream and owns only its PID' {
    $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $oldClock = $env:FAKE_ADB_CLOCK
    $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
    $ownedPid = 0
    $unrelatedPid = 0
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $env:FAKE_ADB_CLOCK = 'normal'
        $unrelated = Start-Process `
            -FilePath $pwshPath `
            -ArgumentList @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', $fakeAdbScript,
                '-s', 'unrelated-serial', 'logcat'
            ) `
            -WindowStyle Hidden `
            -PassThru
        $unrelatedPid = $unrelated.Id
        $result = Invoke-CapturedProcess `
            -FilePath $pwshPath `
            -Arguments @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', (Join-Path $toolsRoot 'start-logcat.ps1'),
                '-Serial', 'owned-serial',
                '-OutputRoot', $evidenceRoot,
                '-AdbExecutable', $fakeAdbScript
            ) `
            -TimeoutSeconds 8
        Assert-True (-not $result.TimedOut) (
            "start-logcat inherited an open pipe; stderr=$($result.Stderr)")
        Assert-Equal $result.ExitCode 0 (
            "start-logcat exit code; stderr=$($result.Stderr)")
        Assert-True (Wait-ForFile $metadataPath 3) 'logcat metadata was not written'
        $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding utf8 |
            ConvertFrom-Json
        $ownedPid = [int]$metadata.hostProcessId
        Assert-True ($ownedPid -gt 0) 'owned logcat PID is missing'
        Assert-True ($null -ne (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue)) (
            'owned logcat exited unexpectedly')
        Assert-True ($null -ne (Get-Process -Id $unrelatedPid -ErrorAction SilentlyContinue)) (
            'unrelated logcat was terminated')
    } finally {
        Stop-TestProcess $ownedPid
        Stop-TestProcess $unrelatedPid
        $env:FAKE_ADB_MARKER = $oldMarker
        $env:FAKE_ADB_CLOCK = $oldClock
    }
}

Test-Case 'start-logcat clock timeout cleans only the launched stream' {
    $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $oldClock = $env:FAKE_ADB_CLOCK
    $ownedPid = 0
    $unrelatedPid = 0
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $env:FAKE_ADB_CLOCK = 'hang'
        $unrelated = Start-Process `
            -FilePath $pwshPath `
            -ArgumentList @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', $fakeAdbScript,
                '-s', 'unrelated-serial', 'logcat'
            ) `
            -WindowStyle Hidden `
            -PassThru
        $unrelatedPid = $unrelated.Id
        $result = Invoke-CapturedProcess `
            -FilePath $pwshPath `
            -Arguments @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', (Join-Path $toolsRoot 'start-logcat.ps1'),
                '-Serial', 'owned-serial',
                '-OutputRoot', $evidenceRoot,
                '-AdbExecutable', $fakeAdbScript,
                '-ClockTimeoutSeconds', '1'
            ) `
            -TimeoutSeconds 8
        Assert-True (-not $result.TimedOut) 'start-logcat timeout path did not return'
        Assert-True ($result.ExitCode -ne 0) 'start-logcat timeout was swallowed'
        Assert-True (-not (Test-Path -LiteralPath $metadataPath)) (
            'failed start left logcat metadata')
        Assert-True ($null -ne (Get-Process -Id $unrelatedPid -ErrorAction SilentlyContinue)) (
            'timeout cleanup killed an unrelated process')
    } finally {
        Stop-TestProcess $ownedPid
        Stop-TestProcess $unrelatedPid
        $env:FAKE_ADB_MARKER = $oldMarker
        $env:FAKE_ADB_CLOCK = $oldClock
    }
}

Test-Case 'start-logcat immediate failure leaves no owned process' {
    $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $oldClock = $env:FAKE_ADB_CLOCK
    $oldLogcat = $env:FAKE_ADB_LOGCAT
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $env:FAKE_ADB_CLOCK = 'normal'
        $env:FAKE_ADB_LOGCAT = 'exit1'
        $result = Invoke-CapturedProcess `
            -FilePath $pwshPath `
            -Arguments @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', (Join-Path $toolsRoot 'start-logcat.ps1'),
                '-Serial', 'owned-serial',
                '-OutputRoot', $evidenceRoot,
                '-AdbExecutable', $fakeAdbScript,
                '-LaunchProbeSeconds', '1'
            ) `
            -TimeoutSeconds 8
        Assert-True (-not $result.TimedOut) 'immediate-failure path did not return'
        Assert-True ($result.ExitCode -ne 0) 'immediate logcat failure was swallowed'
        Assert-True (-not (Test-Path -LiteralPath (
            Join-Path $evidenceRoot 'logs\runtime-logcat.pid'))) (
            'immediate failure left PID metadata')
        Assert-True (-not (Test-Path -LiteralPath (
            Join-Path $evidenceRoot 'logs\logcat-start.json'))) (
            'immediate failure left start metadata')
    } finally {
        $env:FAKE_ADB_MARKER = $oldMarker
        $env:FAKE_ADB_CLOCK = $oldClock
        $env:FAKE_ADB_LOGCAT = $oldLogcat
    }
}

Test-Case 'start-logcat ownership mismatch refuses cleanup and leaves recorded process' {
    $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
    $evidenceRoot = Join-Path $script:testRoot 'qa-v9\evidence'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
    $pidPath = Join-Path $evidenceRoot 'logs\runtime-logcat.pid'
    $stdoutPath = Join-Path $script:testRoot 'start.stdout.log'
    $stderrPath = Join-Path $script:testRoot 'start.stderr.log'
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $oldClock = $env:FAKE_ADB_CLOCK
    $startProcess = $null
    $ownedPid = 0
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $env:FAKE_ADB_CLOCK = 'hang'
        $startProcess = Start-Process `
            -FilePath $pwshPath `
            -ArgumentList @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', (Join-Path $toolsRoot 'start-logcat.ps1'),
                '-Serial', 'owned-serial',
                '-OutputRoot', $evidenceRoot,
                '-AdbExecutable', $fakeAdbScript,
                '-LaunchProbeSeconds', '1',
                '-ClockTimeoutSeconds', '1'
            ) `
            -WindowStyle Hidden `
            -PassThru `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath
        Assert-True (Wait-ForFile $metadataPath 4) (
            'start-logcat did not publish ownership metadata before the clock probe')
        Assert-True (Wait-ForFile $pidPath 2) 'start-logcat PID metadata was not published'
        $ownedPid = [int]([IO.File]::ReadAllText($pidPath, [Text.Encoding]::ASCII))
        $metadataText = [IO.File]::ReadAllText($metadataPath, [Text.Encoding]::UTF8)
        [IO.File]::WriteAllText(
            $metadataPath,
            ($metadataText -replace
                '("serial"\s*:\s*")[^"]+(")',
                '${1}wrong-serial${2}'),
            $utf8)
        Assert-True ($startProcess.WaitForExit(10000)) (
            'start-logcat mismatch path did not return')
        Assert-True ($startProcess.ExitCode -ne 0) (
            'start-logcat mismatch path was swallowed')
        Assert-True ($null -ne (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue)) (
            'mismatched ownership metadata still killed the recorded process')
        Assert-True (Test-Path -LiteralPath $metadataPath) (
            'mismatched ownership evidence was removed')
    } finally {
        if ($null -ne $startProcess) {
            try {
                $startProcess.Kill($true)
            } catch {
                Stop-TestProcess $startProcess.Id
            }
            $startProcess.Dispose()
        }
        Stop-TestProcess $ownedPid
        $env:FAKE_ADB_MARKER = $oldMarker
        $env:FAKE_ADB_CLOCK = $oldClock
    }
}

Test-Case 'bounded process preserves exit code and output streams' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $stdoutPath = Join-Path $script:testRoot 'bounded.stdout.log'
    $stderrPath = Join-Path $script:testRoot 'bounded.stderr.log'
    $result = Invoke-QaBoundedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $PSScriptRoot 'fixtures\bounded-exit.ps1'),
            '-ExitCode', '7'
        ) `
        -StandardOutputPath $stdoutPath `
        -StandardErrorPath $stderrPath `
        -TimeoutSeconds 3 `
        -KillResidualProcesses
    Assert-True (-not $result.TimedOut) 'bounded process reported timeout'
    Assert-Equal $result.ExitCode 7 'bounded process exit code'
    Assert-True ((Read-SharedText $stdoutPath) -match 'bounded-exit-stdout') (
        'bounded process stdout was not retained')
    Assert-True ((Read-SharedText $stderrPath) -match 'bounded-exit-stderr') (
        'bounded process stderr was not retained')
}

Test-Case 'bounded process returns and cleans a live child' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $stdoutPath = Join-Path $script:testRoot 'bounded.stdout.log'
    $stderrPath = Join-Path $script:testRoot 'bounded.stderr.log'
    $childPidPath = Join-Path $script:testRoot 'child.pid'
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $result = Invoke-QaBoundedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $PSScriptRoot 'fixtures\bounded-spawn-child.ps1'),
            '-ChildPidPath', $childPidPath
        ) `
        -StandardOutputPath $stdoutPath `
        -StandardErrorPath $stderrPath `
        -TimeoutSeconds 3 `
        -KillResidualProcesses
    $stopwatch.Stop()
    Assert-True (-not $result.TimedOut) 'parent step reported timeout'
    Assert-Equal $result.ExitCode 0 'parent step exit code'
    Assert-True ($stopwatch.Elapsed.TotalSeconds -lt 5) (
        "bounded process waited for lingering child: $($stopwatch.Elapsed.TotalSeconds)s")
    Assert-True (Wait-ForFile $childPidPath 3) 'child PID was not recorded'
    $childPid = [int]([IO.File]::ReadAllText($childPidPath, [Text.Encoding]::ASCII))
    Assert-True ($null -eq (Get-Process -Id $childPid -ErrorAction SilentlyContinue)) (
        "lingering child $childPid survived")
    Assert-True (@($result.CleanedProcessIds) -contains $childPid) (
        'cleaned child PID was not reported')
}

Test-Case 'bounded timeout fails closed and kills the tree' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $stdoutPath = Join-Path $script:testRoot 'bounded.stdout.log'
    $stderrPath = Join-Path $script:testRoot 'bounded.stderr.log'
    $childPidPath = Join-Path $script:testRoot 'child.pid'
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $result = Invoke-QaBoundedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $PSScriptRoot 'fixtures\bounded-timeout.ps1'),
            '-ChildPidPath', $childPidPath
        ) `
        -StandardOutputPath $stdoutPath `
        -StandardErrorPath $stderrPath `
        -TimeoutSeconds 1 `
        -KillResidualProcesses
    $stopwatch.Stop()
    Assert-True $result.TimedOut 'timeout was swallowed'
    Assert-Equal $result.ExitCode 124 'timeout fail-closed exit code'
    Assert-True ($stopwatch.Elapsed.TotalSeconds -lt 6) (
        "timeout cleanup exceeded bound: $($stopwatch.Elapsed.TotalSeconds)s")
    Assert-True (Wait-ForFile $childPidPath 3) 'timeout child PID was not recorded'
    $childPid = [int]([IO.File]::ReadAllText($childPidPath, [Text.Encoding]::ASCII))
    Assert-True ($null -eq (Get-Process -Id $childPid -ErrorAction SilentlyContinue)) (
        "timeout child $childPid survived")
}

Test-Case 'owned logcat stop validates identity and kills only the recorded stream' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $ownedPid = 0
    $unrelatedPid = 0
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $unrelated = Start-Process `
            -FilePath $pwshPath `
            -ArgumentList @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', $fakeAdbScript,
                '-s', 'unrelated-serial', 'logcat'
            ) `
            -WindowStyle Hidden `
            -PassThru
        $unrelatedPid = $unrelated.Id
        $startResult = Invoke-CapturedProcess `
            -FilePath $pwshPath `
            -Arguments @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', (Join-Path $toolsRoot 'start-logcat.ps1'),
                '-Serial', 'owned-serial',
                '-OutputRoot', $evidenceRoot,
                '-AdbExecutable', $fakeAdbScript
            ) `
            -TimeoutSeconds 8
        Assert-Equal $startResult.ExitCode 0 (
            "start-logcat setup failed: $($startResult.Stderr)")
        $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
        $ownedPid = [int](
            (Get-Content -LiteralPath $metadataPath -Raw -Encoding utf8 |
                ConvertFrom-Json).hostProcessId)
        $metadataText = Get-Content `
            -LiteralPath $metadataPath `
            -Raw `
            -Encoding utf8
        [IO.File]::WriteAllText(
            $metadataPath,
            ($metadataText -replace
                '("serial"\s*:\s*")[^"]+(")',
                '${1}wrong-serial${2}'),
            $utf8)
        $rejected = Stop-QaOwnedProcess `
            -MetadataPath $metadataPath `
            -ExpectedSerial 'owned-serial' `
            -ExpectedEvidenceRoot $evidenceRoot
        Assert-True (-not $rejected.stopped) 'mismatched owner metadata was accepted'
        Assert-True ($null -ne (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue)) (
            'mismatched metadata killed the process')
        Assert-True ($null -ne (Get-Process -Id $unrelatedPid -ErrorAction SilentlyContinue)) (
            'owned-stop validation touched an unrelated process')
        [IO.File]::WriteAllText(
            $metadataPath,
            ($metadataText -replace
                '("serial"\s*:\s*")[^"]+(")',
                '${1}owned-serial${2}'),
            $utf8)
        $stopped = Stop-QaOwnedProcess `
            -MetadataPath $metadataPath `
            -ExpectedSerial 'owned-serial' `
            -ExpectedEvidenceRoot $evidenceRoot
        Assert-True $stopped.stopped (
            "owned logcat was not stopped; reason=$($stopped.reason) " +
            "identity=$($stopped.identity | ConvertTo-Json -Compress)")
        Assert-True ($null -eq (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue)) (
            'owned logcat survived validated stop')
        Assert-True ($null -ne (Get-Process -Id $unrelatedPid -ErrorAction SilentlyContinue)) (
            'validated owned stop killed an unrelated logcat')
    } finally {
        Stop-TestProcess $ownedPid
        Stop-TestProcess $unrelatedPid
        $env:FAKE_ADB_MARKER = $oldMarker
    }
}

Test-Case 'owned logcat stop rejects exited and simulated reused processes' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
    $evidenceRoot = Join-Path $script:testRoot 'qa-v9\evidence'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $ownedPid = 0
    $unrelatedPid = 0
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $startResult = Invoke-CapturedProcess `
            -FilePath $pwshPath `
            -Arguments @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', (Join-Path $toolsRoot 'start-logcat.ps1'),
                '-Serial', 'owned-serial',
                '-OutputRoot', $evidenceRoot,
                '-AdbExecutable', $fakeAdbScript
            ) `
            -TimeoutSeconds 8
        Assert-Equal $startResult.ExitCode 0 (
            "start-logcat setup failed: $($startResult.Stderr)")
        $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
        $metadataText = [IO.File]::ReadAllText($metadataPath, [Text.Encoding]::UTF8)
        $metadata = $metadataText | ConvertFrom-Json
        $ownedPid = [int]$metadata.hostProcessId
        Stop-TestProcess $ownedPid
        Assert-True (Wait-QaProcessesGone -ProcessIds @($ownedPid)) (
            'owned process did not exit before the reuse simulation')
        $alreadyExited = Stop-QaOwnedProcess `
            -MetadataPath $metadataPath `
            -ExpectedSerial 'owned-serial' `
            -ExpectedEvidenceRoot $evidenceRoot
        Assert-True $alreadyExited.alreadyExited (
            "exited owner was not reported safely; reason=$($alreadyExited.reason)")

        $unrelated = Start-Process `
            -FilePath $pwshPath `
            -ArgumentList @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', $fakeAdbScript,
                '-s', 'unrelated-serial', 'logcat'
            ) `
            -WindowStyle Hidden `
            -PassThru
        $unrelatedPid = $unrelated.Id
        [IO.File]::WriteAllText(
            $metadataPath,
            ($metadataText -replace
                '("hostProcessId"\s*:\s*)\d+',
                ('${1}' + $unrelatedPid)),
            $utf8)
        $reused = Stop-QaOwnedProcess `
            -MetadataPath $metadataPath `
            -ExpectedSerial 'owned-serial' `
            -ExpectedEvidenceRoot $evidenceRoot
        Assert-True (-not $reused.stopped) (
            'simulated reused PID was accepted as the owned process')
        Assert-True ($null -ne (Get-Process -Id $unrelatedPid -ErrorAction SilentlyContinue)) (
            'simulated reused PID was killed')
    } finally {
        Stop-TestProcess $ownedPid
        Stop-TestProcess $unrelatedPid
        $env:FAKE_ADB_MARKER = $oldMarker
    }
}

Test-Case 'matrix step preserves real exit code and separate streams' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $rawRoot = Join-Path $script:testRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    $entry = Invoke-QaMatrixStep `
        -Name 'exit-seven' `
        -ScriptPath (Join-Path $PSScriptRoot 'fixtures\bounded-exit.ps1') `
        -Arguments @{ ExitCode = 7 } `
        -Kind 'device' `
        -RawRoot $rawRoot `
        -PwshPath $pwshPath `
        -TimeoutSeconds 3
    Assert-Equal $entry.exitCode 7 'matrix step exit code'
    Assert-Equal $entry.status 'FAIL' 'matrix step status'
    Assert-True ((Read-SharedText $entry.stdoutPath) -match 'bounded-exit-stdout') (
        'matrix step stdout stream missing')
    Assert-True ((Read-SharedText $entry.stderrPath) -match 'bounded-exit-stderr') (
        'matrix step stderr stream missing')
}

Test-Case 'matrix timeout is fail-closed and leaves no step child' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $rawRoot = Join-Path $script:testRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    $childPidPath = Join-Path $script:testRoot 'timeout child.pid'
    $entry = Invoke-QaMatrixStep `
        -Name 'timeout-step' `
        -ScriptPath (Join-Path $PSScriptRoot 'fixtures\bounded-timeout.ps1') `
        -Arguments @{ ChildPidPath = $childPidPath } `
        -Kind 'device' `
        -RawRoot $rawRoot `
        -PwshPath $pwshPath `
        -TimeoutSeconds 1
    Assert-Equal $entry.exitCode 124 'matrix timeout exit code'
    Assert-Equal $entry.status 'FAIL_TIMEOUT' 'matrix timeout status'
    Assert-True $entry.timedOut 'matrix timeout flag'
    Assert-True (Wait-ForFile $childPidPath 3) 'matrix child PID missing'
    $childPid = [int]([IO.File]::ReadAllText($childPidPath, [Text.Encoding]::ASCII))
    Assert-True ($null -eq (Get-Process -Id $childPid -ErrorAction SilentlyContinue)) (
        "matrix timeout child $childPid survived")
    Assert-Equal @($entry.residualProcessIds).Count 0 'matrix residual count'
}

Test-Case 'matrix step preserves validated owned logcat from prepare' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $rawRoot = Join-Path $script:testRoot 'raw'
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    New-Item -ItemType Directory -Path $rawRoot, $evidenceRoot | Out-Null
    $markerPath = Join-Path $script:testRoot 'fake-adb-calls.log'
    $oldMarker = $env:FAKE_ADB_MARKER
    $ownedPid = 0
    try {
        $env:FAKE_ADB_MARKER = $markerPath
        $fakeAdbScript = Join-Path $PSScriptRoot 'fixtures\fake-adb.ps1'
        $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
        $entry = Invoke-QaMatrixStep `
            -Name 'prepare-like' `
            -ScriptPath (Join-Path $PSScriptRoot 'fixtures\matrix-start-logcat.ps1') `
            -Arguments @{
                OutputRoot = $evidenceRoot
                AdbExecutable = $fakeAdbScript
            } `
            -Kind 'device' `
            -RawRoot $rawRoot `
            -PwshPath $pwshPath `
            -TimeoutSeconds 8 `
            -AllowedResidualMetadataPath $metadataPath `
            -AllowedResidualSerial 'owned-serial' `
            -AllowedResidualEvidenceRoot $evidenceRoot
        Assert-Equal $entry.exitCode 0 (
            "prepare-like step failed: $(Read-SharedText $entry.stderrPath)")
        Assert-True (Wait-ForFile $metadataPath 3) 'prepare-like metadata missing'
        $ownedPid = [int](
            (Get-Content -LiteralPath $metadataPath -Raw -Encoding utf8 |
                ConvertFrom-Json -DateKind String).hostProcessId)
        Assert-True ($null -ne (Get-Process -Id $ownedPid -ErrorAction SilentlyContinue)) (
            'matrix residual cleanup killed its owned logcat')
        Assert-True (@($entry.allowedResidualProcessIds) -contains $ownedPid) (
            'matrix step did not record the preserved logcat owner')
        $unexpectedResidual = @(
            $entry.residualProcessIds |
                Where-Object { $_ -notin @($entry.protectedProcessIds) }
        )
        Assert-Equal $unexpectedResidual.Count 0 (
            "matrix step left unowned residual: $($unexpectedResidual -join ',')")
    } finally {
        $metadataPath = Join-Path $evidenceRoot 'logs\logcat-start.json'
        if (Test-Path -LiteralPath $metadataPath) {
            $null = Stop-QaOwnedProcess `
                -MetadataPath $metadataPath `
                -ExpectedSerial 'owned-serial' `
                -ExpectedEvidenceRoot $evidenceRoot
        }
        Stop-TestProcess $ownedPid
        $env:FAKE_ADB_MARKER = $oldMarker
    }
}

Test-Case 'remaining seconds returns bounded integer for past future and edge values' {
    . (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
    $now = Get-Date
    Assert-Equal (Get-QaRemainingSeconds -Deadline ($now.AddSeconds(10.9)) -Maximum 30) 10 (
        'future remaining seconds')
    Assert-Equal (Get-QaRemainingSeconds -Deadline ($now.AddSeconds(0.1)) -Maximum 30) 1 (
        'sub-second remaining seconds')
    Assert-Equal (Get-QaRemainingSeconds -Deadline ($now.AddSeconds(-10)) -Maximum 30) 1 (
        'expired remaining seconds')
    Assert-Equal (Get-QaRemainingSeconds -Deadline ($now.AddHours(1)) -Maximum 30) 30 (
        'maximum remaining seconds')
    Assert-Equal (Get-QaRemainingSeconds -Deadline $null -Maximum 30) 1 (
        'null remaining seconds')
    $value = Get-QaRemainingSeconds -Deadline ($now.AddMinutes(1)) -Maximum 7
    Assert-True ($value -is [int]) 'remaining seconds is not an integer'
}

Test-Case 'postprocess defers manifest until commands are frozen' {
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    $rawRoot = Join-Path $evidenceRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'commands.json'),
        "[]`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'commands.log'),
        '',
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $evidenceRoot 'SHA256SUMS'),
        'stale manifest must be removed' + "`n",
        $utf8)
    $result = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $toolsRoot 'postprocess-evidence.ps1'),
            '-EvidenceRoot', $evidenceRoot
        ) `
        -TimeoutSeconds 30
    Assert-Equal $result.ExitCode 0 (
        "postprocess failed: $($result.Stderr)")
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $evidenceRoot 'SHA256SUMS'))) (
        'postprocess wrote a stale manifest before the command entry was frozen')
    $summaryPath = Join-Path $evidenceRoot 'postprocess.json'
    $summary = Get-Content `
        -LiteralPath $summaryPath `
        -Raw `
        -Encoding utf8 |
        ConvertFrom-Json -DateKind String
    Assert-Equal $summary.manifest.mode 'deferred_to_final_seal' (
        'postprocess manifest state')
}

Test-Case 'evidence manifest is complete stable and detects command drift' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    $rawRoot = Join-Path $evidenceRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    $commandsPath = Join-Path $rawRoot 'commands.json'
    [IO.File]::WriteAllText($commandsPath, "[{`"exitCode`":0}]`n", $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'commands.log'),
        "{`"exitCode`":0}`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $evidenceRoot 'matrix-result.json'),
        "{`"status`":`"FIXTURE`"}`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $evidenceRoot 'postprocess.json'),
        "{`"status`":`"FIXTURE`"}`n",
        $utf8)
    $first = Write-QaEvidenceManifest -Root $evidenceRoot
    $firstHash = (Get-FileHash -LiteralPath $first.path -Algorithm SHA256).Hash
    $firstChecks = @(Test-QaEvidenceManifest -Root $evidenceRoot)
    Assert-Equal $firstChecks.Count 1 'manifest verify output count'
    $firstCheck = $firstChecks[0]
    Assert-True ([bool]$firstCheck.valid) (
        "fresh manifest failed verification: $($firstCheck.errors -join '; ')")
    Assert-True ($first.entries -contains 'raw/commands.json') (
        'commands.json missing from manifest')
    Assert-True ($first.entries -notcontains 'SHA256SUMS') (
        'manifest included itself')
    Assert-True ($first.entries -notcontains 'final-seal.json') (
        'manifest included final-seal.json')
    [IO.File]::WriteAllText($commandsPath, "[{`"exitCode`":7}]`n", $utf8)
    $driftCheck = Test-QaEvidenceManifest -Root $evidenceRoot
    Assert-True (-not $driftCheck.valid) 'manifest drift was not detected'
    Assert-True (($driftCheck.errors -join '; ') -match 'raw/commands\.json') (
        'manifest drift did not identify commands.json')
    [IO.File]::WriteAllText($commandsPath, "[{`"exitCode`":0}]`n", $utf8)
    $null = Write-QaEvidenceManifest -Root $evidenceRoot
    $secondHash = (Get-FileHash -LiteralPath $first.path -Algorithm SHA256).Hash
    Assert-Equal $secondHash $firstHash 'manifest regeneration was not idempotent'
}

Test-Case 'tool digest is recursive deterministic and self-excluded' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $fixtureTools = Join-Path $script:testRoot 'tools'
    $nested = Join-Path $fixtureTools 'tests'
    New-Item -ItemType Directory -Path $nested | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixtureTools 'z.ps1'), 'Write-Output 1', $utf8)
    [IO.File]::WriteAllText((Join-Path $fixtureTools 'a.ps1'), 'Write-Output 2', $utf8)
    [IO.File]::WriteAllText((Join-Path $nested 'nested.ps1'), 'Write-Output 3', $utf8)
    [IO.File]::WriteAllText((Join-Path $fixtureTools 'tools.sha256'), 'stale', $utf8)
    $digest = Get-QaToolDigest -ToolsRoot $fixtureTools
    Assert-Equal $digest.entryCount 3 'tool digest entry count'
    Assert-Equal ($digest.entries -join '|') 'a.ps1|tests/nested.ps1|z.ps1' (
        'tool digest path ordering')
    $expectedBytes = $utf8.GetBytes($digest.text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $expectedHash = [Convert]::ToHexString($sha.ComputeHash($expectedBytes)).
            ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    Assert-Equal $digest.sha256 $expectedHash 'tool digest aggregate hash'
    $again = Get-QaToolDigest -ToolsRoot $fixtureTools
    Assert-Equal $again.text $digest.text 'tool digest was not deterministic'
}

Test-Case 'QA-V9 lock gate accepts only the unique current run' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $evidenceRoot = Join-Path $script:testRoot 'qa-v9\device-test'
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
    Assert-Equal (Resolve-QaRunId -EvidenceRoot $evidenceRoot) 'QA-V9' (
        'evidence root run id')
    $plainEvidenceRoot = Join-Path $script:testRoot 'device-test'
    New-Item -ItemType Directory -Path $plainEvidenceRoot | Out-Null
    Assert-Equal (Resolve-QaRunId `
        -EvidenceRoot $plainEvidenceRoot `
        -RequestedRunId 'QA_V10') (
        'QA-V10') 'explicit run id normalization'

    $cases = @(
        @{
            Name = 'unique-current'
            Text = "### QA-v9 integration test-serial — ACTIVE`n"
            Passed = $true
        },
        @{
            Name = 'old-qa-v8'
            Text = "### QA-v8 historical test-serial — ACTIVE`n"
            Passed = $false
        },
        @{
            Name = 'k60-early-lock'
            Text = "### QA-v9 integration other-serial — ACTIVE`n"
            Passed = $false
        },
        @{
            Name = 'cross-run'
            Text = (
                "### QA-v8 historical test-serial — ACTIVE`n" +
                "### QA-v9 integration test-serial — ACTIVE`n")
            Passed = $false
        }
    )
    foreach ($case in $cases) {
        $lockPath = Join-Path $script:testRoot "$($case.Name).md"
        [IO.File]::WriteAllText($lockPath, $case.Text, $utf8)
        $gate = Test-QaRunLock `
            -DeviceLockPath $lockPath `
            -Serial 'test-serial' `
            -RunId 'QA-V9'
        Assert-Equal $gate.runId 'QA-V9' "$($case.Name) gate run id"
        Assert-Equal $gate.passed $case.Passed "$($case.Name) gate result"
    }
}

Test-Case 'QA-V9 static preflight preserves canonical tool manifest' {
    $canonicalPath = Join-Path (Split-Path -Parent $toolsRoot) 'tools.sha256'
    $beforeItem = Get-Item -LiteralPath $canonicalPath
    $beforeBytes = [IO.File]::ReadAllBytes($canonicalPath)
    $beforeHash = (Get-FileHash -LiteralPath $canonicalPath -Algorithm SHA256).
        Hash.ToLowerInvariant()
    $evidenceRoot = Join-Path $script:testRoot 'qa-v9\evidence'
    $lockPath = Join-Path $script:testRoot 'device-lock.md'
    $mainApk = Join-Path $script:testRoot 'main.apk'
    $peerApk = Join-Path $script:testRoot 'peer.apk'
    [IO.File]::WriteAllBytes($mainApk, [byte[]](1, 2, 3))
    [IO.File]::WriteAllBytes($peerApk, [byte[]](4, 5, 6))
    [IO.File]::WriteAllText(
        $lockPath,
        "### QA-v9 static preflight test-serial — ACTIVE`n",
        $utf8)
    $result = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $toolsRoot 'run-device-matrix.ps1'),
            '-Serial', 'test-serial',
            '-OutputRoot', $evidenceRoot,
            '-MainApk', $mainApk,
            '-PeerApk', $peerApk,
            '-DeviceLockPath', $lockPath,
            '-RunId', 'QA-V9',
            '-PreflightOnly'
        ) `
        -TimeoutSeconds 30
    $afterItem = Get-Item -LiteralPath $canonicalPath
    $afterBytes = [IO.File]::ReadAllBytes($canonicalPath)
    $afterHash = (Get-FileHash -LiteralPath $canonicalPath -Algorithm SHA256).
        Hash.ToLowerInvariant()
    Assert-Equal $result.ExitCode 0 (
        "QA-V9 static preflight failed: $($result.Stderr)")
    Assert-Equal $afterHash $beforeHash 'canonical tool manifest hash changed'
    Assert-True ([Linq.Enumerable]::SequenceEqual(
        [byte[]]$beforeBytes,
        [byte[]]$afterBytes)) 'canonical tool manifest bytes changed'
    Assert-Equal $afterItem.LastWriteTimeUtc $beforeItem.LastWriteTimeUtc (
        'canonical tool manifest mtime changed')
    $preflightPath = Join-Path $evidenceRoot 'raw\preflight.json'
    $preflight = Get-Content -LiteralPath $preflightPath -Raw -Encoding utf8 |
        ConvertFrom-Json -DateKind String
    Assert-Equal $preflight.runId 'QA-V9' 'preflight run id'
    Assert-True ([bool]$preflight.passed) 'preflight gate did not pass'
    Assert-True (Test-Path -LiteralPath (Join-Path $evidenceRoot 'tools.sha256')) (
        'current-run tool manifest snapshot was not written')
}

Test-Case 'commands freeze detects post-freeze writes' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    $rawRoot = Join-Path $evidenceRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    $commandsJson = Join-Path $rawRoot 'commands.json'
    $commandsLog = Join-Path $rawRoot 'commands.log'
    [IO.File]::WriteAllText($commandsJson, "[{`"exitCode`":0}]`n", $utf8)
    [IO.File]::WriteAllText($commandsLog, "{`"exitCode`":0}`n", $utf8)
    $freeze = Write-QaCommandsFreeze -EvidenceRoot $evidenceRoot
    Assert-True $freeze.entryCount -eq 1 'commands freeze entry count'
    $valid = Test-QaCommandsFreeze -EvidenceRoot $evidenceRoot
    Assert-True $valid.valid 'fresh commands freeze failed'
    [IO.File]::AppendAllText($commandsLog, "{`"exitCode`":7}`n", $utf8)
    $invalid = Test-QaCommandsFreeze -EvidenceRoot $evidenceRoot
    Assert-True (-not $invalid.valid) 'post-freeze command write was not detected'
    Assert-True (($invalid.reason -join ',') -match 'COMMANDS_LOG_HASH_MISMATCH') (
        'commands freeze mismatch reason')
}

Test-Case 'commands json remains an array for empty and single-entry states' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $empty = ConvertTo-QaCommandsJson -Entries @()
    $emptyParsed = $empty | ConvertFrom-Json -DateKind String
    Assert-Equal @($emptyParsed).Count 0 'empty commands JSON array count'
    $single = ConvertTo-QaCommandsJson -Entries @(
        [ordered]@{ name = 'one'; exitCode = 7 }
    )
    $singleParsed = $single | ConvertFrom-Json -DateKind String
    Assert-Equal @($singleParsed).Count 1 'single commands JSON array count'
    Assert-Equal $singleParsed[0].exitCode 7 'single commands JSON exit code'
}

Test-Case 'seal writes final manifest idempotently after frozen commands' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    $rawRoot = Join-Path $evidenceRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    $commandsJson = Join-Path $rawRoot 'commands.json'
    $commandsLog = Join-Path $rawRoot 'commands.log'
    [IO.File]::WriteAllText($commandsJson, "[{`"exitCode`":0}]`n", $utf8)
    [IO.File]::WriteAllText($commandsLog, "{`"exitCode`":0}`n", $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'postprocess-evidence.stdout.log'),
        "{`"status`":`"PASS`"}`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $evidenceRoot 'matrix-result.json'),
        "{`"status`":`"FIXTURE`"}`n",
        $utf8)
    $null = Write-QaCommandsFreeze -EvidenceRoot $evidenceRoot
    $sealArgs = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', (Join-Path $toolsRoot 'seal-evidence.ps1'),
        '-EvidenceRoot', $evidenceRoot
    )
    $first = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments $sealArgs `
        -TimeoutSeconds 30
    Assert-Equal $first.ExitCode 0 "first seal failed: $($first.Stderr)"
    $manifestPath = Join-Path $evidenceRoot 'SHA256SUMS'
    $firstHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
    $verification = Test-QaEvidenceManifest -Root $evidenceRoot
    Assert-True $verification.valid (
        "sealed manifest failed verification: $($verification.errors -join '; ')")
    Assert-True ($verification.entries -contains 'raw/commands-frozen.json') (
        'commands freeze was not covered by final manifest')
    $second = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments $sealArgs `
        -TimeoutSeconds 30
    Assert-Equal $second.ExitCode 0 "second seal failed: $($second.Stderr)"
    $secondHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
    Assert-Equal $secondHash $firstHash 'seal was not idempotent'
    [IO.File]::AppendAllText($commandsLog, "{`"late`":true}`n", $utf8)
    $drift = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments $sealArgs `
        -TimeoutSeconds 30
    Assert-True ($drift.ExitCode -ne 0) 'seal accepted post-freeze command drift'
}

Test-Case 'ten-minute long-run segments and analysis are dynamic' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $segments = Get-QaLongRunSegments -TargetSeconds 600
    Assert-Equal $segments.first.label '0-60' 'first segment label'
    Assert-Equal $segments.middle.label '270-330' 'middle segment label'
    Assert-Equal $segments.final.label '540-600' 'final segment label'
    $longRunRoot = Join-Path $script:testRoot 'long-run'
    New-Item -ItemType Directory -Path $longRunRoot | Out-Null
    $screenOff = [datetimeoffset]::Parse('2026-11-05T00:00:00+00:00')
    $samples = @(
        for ($minute = 1; $minute -le 10; $minute++) {
            [ordered]@{
                at = $screenOff.AddMinutes($minute).ToString('o')
                appPid = 4242
                queueIndex = $minute - 1
                queueSize = 20
                status = 'RUNNING'
                activeAudioOwner = 'TTS'
                wakefulness = 'Asleep'
                servicePresent = $true
                serviceForeground = $true
                serviceTypeFieldPresent = $false
                serviceMediaPlaybackType = $false
                mediaSessionPresent = $true
                mediaSessionActive = $true
                notificationPresent = $true
                longRunAudioMillis = $minute * 60000
            }
        }
    )
    [IO.File]::WriteAllText(
        (Join-Path $longRunRoot 'background-longrun-samples.jsonl'),
        (($samples | ForEach-Object { $_ | ConvertTo-Json -Compress }) -join "`n") + "`n",
        $utf8)
    $events = @(
        foreach ($offset in @(1, 300, 590)) {
            [ordered]@{
                at = $screenOff.AddSeconds($offset).ToString('o')
                offsetSeconds = $offset
                eventType = 'STARTED'
            }
            [ordered]@{
                at = $screenOff.AddSeconds($offset + 2).ToString('o')
                offsetSeconds = $offset + 2
                eventType = 'COMPLETED'
            }
        }
    )
    [IO.File]::WriteAllText(
        (Join-Path $longRunRoot 'background-longrun-audio-events.json'),
        ($events | ConvertTo-Json -Depth 5),
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $longRunRoot 'background-longrun-start.json'),
        "{}`n",
        $utf8)
    $scriptSummary = [ordered]@{
        screenOffAt = $screenOff.ToString('o')
        windowEnd = $screenOff.AddMinutes(10).ToString('o')
        observedSeconds = 600
        requestedMinutes = 10
        status = 'PASS_10MIN_REAL_AUDIOTRACK_OWNERSHIP'
        completionCountInWindow = 0
        headAudioGapMs = 1000
        tailAudioGapMs = 1000
        interEventAudioGapMs = 1000
        maximumAudioEventGapMs = 1000
        maximumAudioGapScope = 'inter-event'
        initialQueueIndex = 0
        finalQueueIndex = 9
        finalQueueSize = 20
        initialLongRunAudioMillis = 60000
        finalLongRunAudioMillis = 600000
        finalStateStatus = 'RUNNING'
        finalStateActiveAudioOwner = 'TTS'
        finalStateAudioTrackPlaying = $true
        fatalAnrInputTimeoutLines = @()
        stayOnRestored = $true
    }
    [IO.File]::WriteAllText(
        (Join-Path $longRunRoot 'background-longrun.json'),
        (($scriptSummary | ConvertTo-Json -Depth 10) + "`n"),
        $utf8)
    $installAuditPath = Join-Path $script:testRoot 'install-gate.json'
    [IO.File]::WriteAllText(
        $installAuditPath,
        "{`"mediaPlaybackForegroundServiceTypeMatches`":true}`n",
        $utf8)
    $result = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $toolsRoot 'analyze-longrun.ps1'),
            '-LongRunRoot', $longRunRoot,
            '-InstallAuditPath', $installAuditPath
        ) `
        -TimeoutSeconds 20
    Assert-Equal $result.ExitCode 0 (
        "10-minute analysis failed: $($result.Stderr)")
    $resultPath = Join-Path $longRunRoot 'long-run-10m.json'
    Assert-True (Test-Path -LiteralPath $resultPath) 'dynamic analysis output missing'
    $analysis = Get-Content `
        -LiteralPath $resultPath `
        -Raw `
        -Encoding utf8 |
        ConvertFrom-Json -DateKind String
    Assert-Equal $analysis.verdict 'PASS' '10-minute analysis verdict'
    Assert-Equal $analysis.audio.midSegment.windowSeconds '270-330' (
        'analysis middle segment')
    Assert-Equal $analysis.audio.finalSegment.windowSeconds '540-600' (
        'analysis final segment')
}

Test-Case 'current-run crash gate rejects every fatal signal' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $base = @{
        CurrentRunWindowValid = $true
        SourceQueriesPassed = $true
        FDebugCount = 0
        NativeTombstoneCount = 0
        JavaFatalCount = 0
        AnrCount = 0
        InputTimeoutCount = 0
        Reason5Count = 0
    }
    Assert-True (Test-QaCurrentRunCrashGate @base) 'clean current-run gate failed'
    foreach ($failure in @(
        @{ Name = 'CurrentRunWindowValid'; Value = $false },
        @{ Name = 'SourceQueriesPassed'; Value = $false },
        @{ Name = 'FDebugCount'; Value = 1 },
        @{ Name = 'NativeTombstoneCount'; Value = 1 },
        @{ Name = 'JavaFatalCount'; Value = 1 },
        @{ Name = 'AnrCount'; Value = 1 },
        @{ Name = 'InputTimeoutCount'; Value = 1 },
        @{ Name = 'Reason5Count'; Value = 1 }
    )) {
        $failed = @{} + $base
        $failed[$failure.Name] = $failure.Value
        Assert-True (-not (Test-QaCurrentRunCrashGate @failed)) (
            "crash gate swallowed $($failure.Name)")
    }
}

Test-Case 'finalize safety gate rejects blocked cleanup restore speaker and network failures' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $base = @{
        Blocked = $false
        CrashGatePassed = $true
        CleanupArtifactsPassed = $true
        RestorePassed = $true
        SpeakerGatePassed = $true
        UidNetworkZero = $true
    }
    Assert-True (Test-QaFinalizeSafetyGate @base) 'clean finalize gate failed'
    foreach ($failure in @(
        @{ Name = 'Blocked'; Value = $true },
        @{ Name = 'CrashGatePassed'; Value = $false },
        @{ Name = 'CleanupArtifactsPassed'; Value = $false },
        @{ Name = 'RestorePassed'; Value = $false },
        @{ Name = 'SpeakerGatePassed'; Value = $false },
        @{ Name = 'UidNetworkZero'; Value = $false }
    )) {
        $failed = @{} + $base
        $failed[$failure.Name] = $failure.Value
        Assert-True (-not (Test-QaFinalizeSafetyGate @failed)) (
            "finalize gate swallowed $($failure.Name)")
    }
}

Test-Case 'seal rejects unmasked secret material' {
    . (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
    $evidenceRoot = Join-Path $script:testRoot 'evidence'
    $rawRoot = Join-Path $evidenceRoot 'raw'
    New-Item -ItemType Directory -Path $rawRoot | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'commands.json'),
        "[]`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'commands.log'),
        '',
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'postprocess-evidence.stdout.log'),
        "{}`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $evidenceRoot 'matrix-result.json'),
        "{}`n",
        $utf8)
    [IO.File]::WriteAllText(
        (Join-Path $evidenceRoot 'secret.txt'),
        "Authorization: Bearer regression-secret-value`n",
        $utf8)
    $null = Write-QaCommandsFreeze -EvidenceRoot $evidenceRoot
    $result = Invoke-CapturedProcess `
        -FilePath $pwshPath `
        -Arguments @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', (Join-Path $toolsRoot 'seal-evidence.ps1'),
            '-EvidenceRoot', $evidenceRoot
        ) `
        -TimeoutSeconds 30
    Assert-True ($result.ExitCode -ne 0) 'seal accepted unmasked secret material'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $evidenceRoot 'SHA256SUMS'))) (
        'secret failure still wrote a final manifest')
}

Write-Output "SUMMARY passed=$script:passed failed=$script:failed"
if ($script:failed -ne 0) {
    exit 1
}
