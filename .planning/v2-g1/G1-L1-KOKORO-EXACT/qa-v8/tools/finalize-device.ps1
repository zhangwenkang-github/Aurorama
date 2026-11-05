[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $LogcatPath,

    [ValidateRange(1, 30)]
    [int] $StageTimeoutSeconds = 30,

    [long] $MaxWorkingSetBytes = 2147483648,

    [long] $MaxCaptureBytes = 67108864,

    [int] $MaxEvidenceLines = 500
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logPath = [IO.Path]::GetFullPath($LogcatPath)
$logRoot = Split-Path -Parent $logPath
$mainPackage = 'dev.aurorama.kokoro.harness'
$peerPackage = 'dev.aurorama.kokoro.focuspeer'
$testPackages = @($mainPackage, $peerPackage)
$productionPackages = @(
    'io.github.zhangwenkang.aurorama',
    'io.github.zhangwenkang.aurorama.debug'
)
$utf8 = [Text.UTF8Encoding]::new($false)
$resultPath = Join-Path $outputPath 'finalize-device.json'
$progressPath = Join-Path $outputPath 'finalize-progress.jsonl'
$tempRoot = Join-Path (
    [IO.Path]::GetTempPath()) (
    'aurorama-qa-v8-finalize-' + [guid]::NewGuid().ToString('N'))
$script:captureCounter = 0
$script:blockedReasons = [Collections.Generic.List[object]]::new()
$script:stages = [Collections.Generic.List[object]]::new()
$script:blocked = $false
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')
. (Join-Path $PSScriptRoot 'qa-process-lifecycle.ps1')
. (Join-Path $PSScriptRoot 'qa-evidence-contract.ps1')
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
[IO.File]::WriteAllText($progressPath, '', $utf8)

function Get-HostMemorySnapshot {
    $hostProcess = Get-Process -Id $PID -ErrorAction Stop
    return [ordered]@{
        workingSetBytes = [long]$hostProcess.WorkingSet64
        privateBytes = [long]$hostProcess.PrivateMemorySize64
    }
}

function Add-BlockedReason {
    param(
        [string] $Stage,
        [string] $Reason,
        [string] $Command
    )

    $script:blocked = $true
    $duplicate = @(
        $script:blockedReasons |
            Where-Object {
                $_.stage -eq $Stage -and
                $_.reason -eq $Reason -and
                $_.command -eq $Command
            }
    ).Count -gt 0
    if (-not $duplicate) {
        $script:blockedReasons.Add([ordered]@{
            stage = $Stage
            reason = $Reason
            command = $Command
            at = (Get-Date).ToString('o')
            memory = Get-HostMemorySnapshot
        })
    }
}

function Test-StageBudget {
    param(
        [string] $Stage,
        [datetime] $Deadline,
        [string] $Command,
        [switch] $IgnoreMemoryLimit
    )

    if (-not $IgnoreMemoryLimit) {
        $memory = Get-HostMemorySnapshot
        if ($memory.workingSetBytes -gt $MaxWorkingSetBytes) {
            Add-BlockedReason `
                -Stage $Stage `
                -Reason 'WORKING_SET_LIMIT' `
                -Command $Command
            throw [TimeoutException]::new(
                "Working set limit exceeded: $($memory.workingSetBytes)")
        }
    }
    if ((Get-Date) -ge $Deadline) {
        Add-BlockedReason -Stage $Stage -Reason 'STAGE_TIMEOUT' -Command $Command
        throw [TimeoutException]::new("Stage deadline exceeded: $Stage")
    }
}

function Write-ProgressEntry {
    param(
        [string] $Stage,
        [string] $Command,
        [datetime] $StartedAt,
        [datetime] $FinishedAt,
        [object] $MemoryBefore,
        [object] $MemoryAfter,
        [string] $Status,
        [string] $ErrorText
    )

    $entry = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.finalize-progress.v1'
        stage = $Stage
        command = $Command
        startedAt = $StartedAt.ToString('o')
        finishedAt = $FinishedAt.ToString('o')
        durationMs = [long]($FinishedAt - $StartedAt).TotalMilliseconds
        memoryBefore = $MemoryBefore
        memoryAfter = $MemoryAfter
        maxWorkingSetBytes = $MaxWorkingSetBytes
        stageTimeoutSeconds = $StageTimeoutSeconds
        status = $Status
        error = $ErrorText
    }
    $script:stages.Add($entry)
    [IO.File]::AppendAllText(
        $progressPath,
        (($entry | ConvertTo-Json -Compress -Depth 8) + "`n"),
        $utf8)
}

function Invoke-Stage {
    param(
        [string] $Name,
        [string] $Command,
        [scriptblock] $Action,
        [switch] $ForceWhenBlocked
    )

    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
    [GC]::Collect()
    $startedAt = Get-Date
    $deadline = $startedAt.AddSeconds($StageTimeoutSeconds)
    $memoryBefore = Get-HostMemorySnapshot
    $status = 'PASS'
    $errorText = $null
    $result = $null
    try {
        if ($script:blocked -and -not $ForceWhenBlocked) {
            $status = 'SKIPPED_AFTER_BLOCK'
        } else {
            if (-not $ForceWhenBlocked -and
                $memoryBefore.workingSetBytes -gt $MaxWorkingSetBytes) {
                Add-BlockedReason `
                    -Stage $Name `
                    -Reason 'WORKING_SET_LIMIT_BEFORE_STAGE' `
                    -Command $Command
                $status = 'BLOCKED'
            } else {
                $result = & $Action $deadline
                Test-StageBudget `
                    -Stage $Name `
                    -Deadline $deadline `
                    -Command $Command
            }
        }
    } catch {
        $status = 'BLOCKED'
        $errorText = $_.Exception.Message
        Add-BlockedReason -Stage $Name -Reason $errorText -Command $Command
    } finally {
        $memoryAfter = Get-HostMemorySnapshot
        if ($memoryAfter.workingSetBytes -gt $MaxWorkingSetBytes) {
            $script:blocked = $true
        }
        Write-ProgressEntry `
            -Stage $Name `
            -Command $Command `
            -StartedAt $startedAt `
            -FinishedAt (Get-Date) `
            -MemoryBefore $memoryBefore `
            -MemoryAfter $memoryAfter `
            -Status $status `
            -ErrorText $errorText
    }
    return $result
}

function Get-RemainingSeconds([datetime] $Deadline, [int] $Maximum = 30) {
    return Get-QaRemainingSeconds -Deadline $Deadline -Maximum $Maximum
}

function Invoke-AdbToFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Stage,

        [Parameter(Mandatory = $true)]
        [string] $Command,

        [Parameter(Mandatory = $true)]
        [string[]] $AdbArgs,

        [Parameter(Mandatory = $true)]
        [datetime] $Deadline,

        [long] $CaptureLimitBytes = 8388608,

        [switch] $IgnoreMemoryLimit
    )

    Test-StageBudget `
        -Stage $Stage `
        -Deadline $Deadline `
        -Command $Command `
        -IgnoreMemoryLimit:$IgnoreMemoryLimit
    $script:captureCounter++
    $stdoutPath = Join-Path $tempRoot (
        '{0:D3}-{1}.stdout' -f $script:captureCounter, $Stage)
    $stderrPath = Join-Path $tempRoot (
        '{0:D3}-{1}.stderr' -f $script:captureCounter, $Stage)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'adb.exe'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in (@('-s', $Serial) + @($AdbArgs))) {
        $startInfo.ArgumentList.Add([string]$argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $stdoutStream = [IO.File]::Open(
        $stdoutPath,
        [IO.FileMode]::Create,
        [IO.FileAccess]::Write,
        [IO.FileShare]::ReadWrite)
    $stderrStream = [IO.File]::Open(
        $stderrPath,
        [IO.FileMode]::Create,
        [IO.FileAccess]::Write,
        [IO.FileShare]::ReadWrite)
    $stdoutTask = $null
    $stderrTask = $null
    $timedOut = $false
    $captureLimited = $false
    try {
        $null = $process.Start()
        $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
        $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
        while (-not $process.HasExited) {
            if ((Get-Date) -ge $Deadline) {
                $timedOut = $true
                break
            }
            if (-not $IgnoreMemoryLimit) {
                $memory = Get-HostMemorySnapshot
                if ($memory.workingSetBytes -gt $MaxWorkingSetBytes) {
                    Add-BlockedReason `
                        -Stage $Stage `
                        -Reason 'WORKING_SET_LIMIT_DURING_ADB' `
                        -Command $Command
                    $script:blocked = $true
                    break
                }
            }
            if ($stdoutStream.Length -gt $CaptureLimitBytes) {
                $captureLimited = $true
                break
            }
            Start-Sleep -Milliseconds 250
            $process.Refresh()
        }
        if (-not $process.HasExited) {
            try {
                $process.Kill($true)
            } catch {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
            $process.WaitForExit(3000) | Out-Null
        } else {
            $process.WaitForExit()
        }
    } finally {
        foreach ($task in @($stdoutTask, $stderrTask)) {
            if ($null -ne $task) {
                try {
                    $task.GetAwaiter().GetResult()
                } catch {
                    # Process termination may close the pipe before the copy completes.
                }
            }
        }
        $stdoutStream.Dispose()
        $stderrStream.Dispose()
    }
    if ($timedOut) {
        Add-BlockedReason -Stage $Stage -Reason 'COMMAND_TIMEOUT' -Command $Command
    }
    if ($captureLimited) {
        Add-BlockedReason `
            -Stage $Stage `
            -Reason 'CAPTURE_SIZE_LIMIT' `
            -Command $Command
    }
    $stdoutBytes = (Get-Item -LiteralPath $stdoutPath).Length
    $stderrBytes = (Get-Item -LiteralPath $stderrPath).Length
    return [pscustomobject]@{
        exitCode = if ($process.HasExited) { $process.ExitCode } else { $null }
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
        stdoutBytes = [long]$stdoutBytes
        stderrBytes = [long]$stderrBytes
        timedOut = $timedOut
        captureLimited = $captureLimited
        blocked = $timedOut -or $captureLimited -or $script:blocked
    }
}

function Read-TextBounded {
    param(
        [string] $Path,
        [string] $Stage,
        [datetime] $Deadline,
        [int] $MaxChars = 2097152,
        [switch] $IgnoreMemoryLimit
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return ''
    }
    $builder = [Text.StringBuilder]::new()
    $reader = [IO.StreamReader]::new(
        $Path,
        [Text.Encoding]::UTF8,
        $true)
    try {
        $buffer = [char[]]::new(8192)
        while (($read = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if ($builder.Length + $read -gt $MaxChars) {
                Add-BlockedReason `
                    -Stage $Stage `
                    -Reason 'TEXT_READ_LIMIT' `
                    -Command "read $Path"
                break
            }
            [void]$builder.Append($buffer, 0, $read)
            if ($builder.Length % 65536 -lt 8192) {
                Test-StageBudget `
                    -Stage $Stage `
                    -Deadline $Deadline `
                    -Command "read $Path" `
                    -IgnoreMemoryLimit:$IgnoreMemoryLimit
            }
        }
    } finally {
        $reader.Dispose()
    }
    return $builder.ToString()
}

function Invoke-AdbBounded {
    param(
        [string] $Stage,
        [string] $Command,
        [string[]] $AdbArgs,
        [datetime] $Deadline,
        [long] $CaptureLimitBytes = 8388608,
        [int] $MaxChars = 2097152,
        [switch] $IgnoreMemoryLimit
    )

    $capture = Invoke-AdbToFile `
        -Stage $Stage `
        -Command $Command `
        -AdbArgs $AdbArgs `
        -Deadline $Deadline `
        -CaptureLimitBytes $CaptureLimitBytes `
        -IgnoreMemoryLimit:$IgnoreMemoryLimit
    $text = Read-TextBounded `
        -Path $capture.stdoutPath `
        -Stage $Stage `
        -Deadline $Deadline `
        -MaxChars $MaxChars `
        -IgnoreMemoryLimit:$IgnoreMemoryLimit
    return [ordered]@{
        exitCode = $capture.exitCode
        text = $text
        bytes = $capture.stdoutBytes
        timedOut = $capture.timedOut
        captureLimited = $capture.captureLimited
    }
}

function Read-Setting {
    param(
        [string] $Namespace,
        [string] $Key,
        [string] $Stage,
        [datetime] $Deadline
    )

    $result = Invoke-AdbBounded `
        -Stage $Stage `
        -Command "settings get $Namespace $Key" `
        -AdbArgs @('shell', 'settings', 'get', $Namespace, $Key) `
        -Deadline $Deadline `
        -CaptureLimitBytes 65536 `
        -MaxChars 65536
    return ([string]$result.text).Trim()
}

function Convert-LogcatTimestamp(
    [string] $Line,
    [datetime] $Reference
) {
    $short = [regex]::Match(
        $Line,
        '^(?:\d+\s+)?(?<month>\d\d)-(?<day>\d\d)\s+(?<time>\d\d:\d\d:\d\d\.\d{3})')
    if ($short.Success) {
        $value = '{0:D4}-{1}-{2} {3}' -f
            $Reference.Year,
            $short.Groups['month'].Value,
            $short.Groups['day'].Value,
            $short.Groups['time'].Value
        return [datetime]::ParseExact(
            $value,
            'yyyy-MM-dd HH:mm:ss.fff',
            [Globalization.CultureInfo]::InvariantCulture)
    }
    $full = [regex]::Match(
        $Line,
        '^(?<stamp>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d{3})?)')
    if ($full.Success) {
        $formats = [string[]]@(
            'yyyy-MM-dd HH:mm:ss',
            'yyyy-MM-dd HH:mm:ss.fff'
        )
        return [datetime]::ParseExact(
            $full.Groups['stamp'].Value,
            $formats,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None)
    }
    return $null
}

function Test-AppScope(
    [string] $Line,
    [Collections.Generic.HashSet[int]] $AppPids
) {
    if ($Line -match 'dev\.aurorama\.kokoro\.(harness|focuspeer)' -or
        $Line -match 'KOKORO_RUNTIME') {
        return $true
    }
    foreach ($appPid in $AppPids) {
        if ($Line -match ('(?:^|\s)' + $appPid + '(?:\s|$)')) {
            return $true
        }
    }
    return $false
}

function Add-CappedLine(
    [Collections.Generic.List[string]] $Lines,
    [string] $Line
) {
    if ($Lines.Count -lt $MaxEvidenceLines) {
        $Lines.Add($Line)
    }
}

function Read-HostLogEvidence {
    param(
        [string] $Path,
        [datetime] $DeviceStart,
        [datetime] $Deadline
    )

    $appPids = [Collections.Generic.HashSet[int]]::new()
    $fatalLines = [Collections.Generic.List[string]]::new()
    $inputLines = [Collections.Generic.List[string]]::new()
    $unresolvedFatal = [Collections.Generic.List[string]]::new()
    $unresolvedInput = [Collections.Generic.List[string]]::new()
    $fatalCount = 0
    $inputTimeoutCount = 0
    $fDebugLines = [Collections.Generic.List[string]]::new()
    $speakerLines = [Collections.Generic.List[string]]::new()
    $approvedZf = 0
    $approvedZm = 0
    $forbiddenRequests = 0
    $forbiddenLiterals = 0
    $scannedLines = 0
    $current = $false
    $reader = [IO.StreamReader]::new(
        $Path,
        [Text.Encoding]::UTF8,
        $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $scannedLines++
            if (($scannedLines % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage 'host-log' `
                    -Deadline $Deadline `
                    -Command 'stream runtime-logcat.log'
            }
            $at = Convert-LogcatTimestamp $line $DeviceStart
            if ($null -ne $at) {
                $current = $at -ge $DeviceStart.AddSeconds(-1)
            }
            if (-not $current) {
                continue
            }
            $pidMatch = [regex]::Match(
                $line,
                'KOKORO_RUNTIME: STATE \{[^\r\n]*?"pid":(?<pid>\d+)')
            if ($pidMatch.Success) {
                $null = $appPids.Add([int]$pidMatch.Groups['pid'].Value)
            }
            if ($line -match 'SERVICE_CREATED pid=(?<pid>\d+)') {
                $null = $appPids.Add([int]$Matches['pid'])
            }
            if ($line -match 'FATAL EXCEPTION') {
                if (Test-AppScope $line $appPids) {
                    $fatalCount++
                    Add-CappedLine $fatalLines $line
                } else {
                    Add-CappedLine $unresolvedFatal $line
                }
            }
            if ($line -match 'InputTimeout|Input dispatching timed out') {
                if (Test-AppScope $line $appPids) {
                    $inputTimeoutCount++
                    Add-CappedLine $inputLines $line
                } else {
                    Add-CappedLine $unresolvedInput $line
                }
            }
            if ($line -match '\sF DEBUG\s+:' -and
                (Test-AppScope $line $appPids)) {
                Add-CappedLine $fDebugLines $line
            }
            if ($line -match 'TTS_SENTENCE_REQUEST') {
                if ($line -match 'speaker=zf_001 sid=3') {
                    $approvedZf++
                }
                if ($line -match 'speaker=zm_010 sid=59') {
                    $approvedZm++
                }
                if ($line -match '(speaker=zm_009|sid=58)') {
                    $forbiddenRequests++
                }
                Add-CappedLine $speakerLines $line
            }
            if ($line -match 'speaker 58|zm_009') {
                $forbiddenLiterals++
                Add-CappedLine $speakerLines $line
            }
        }
    } finally {
        $reader.Dispose()
    }
    foreach ($line in $unresolvedFatal) {
        if (Test-AppScope $line $appPids) {
            $fatalCount++
            Add-CappedLine $fatalLines $line
        }
    }
    foreach ($line in $unresolvedInput) {
        if (Test-AppScope $line $appPids) {
            $inputTimeoutCount++
            Add-CappedLine $inputLines $line
        }
    }
    return [ordered]@{
        scannedLines = $scannedLines
        appPids = @($appPids | Sort-Object)
        fatalLines = $fatalLines
        inputTimeoutLines = $inputLines
        fDebugLines = @($fDebugLines)
        fatalCount = $fatalCount
        inputTimeoutCount = $inputTimeoutCount
        fDebugCount = $fDebugLines.Count
        forbiddenTtsRequestCount = $forbiddenRequests
        forbiddenLiteralCount = $forbiddenLiterals
        approvedRequestCounts = [ordered]@{
            zf001Sid3 = $approvedZf
            zm010Sid59 = $approvedZm
        }
        speakerLines = @($speakerLines)
    }
}

function Read-NetStatsFile {
    param(
        [string] $Path,
        [long] $Uid,
        [datetime] $Deadline
    )

    $identityRx = [long]0
    $identityTx = [long]0
    $identityBlocks = 0
    $mapRows = 0
    $mapRx = [long]0
    $mapTx = [long]0
    $identityActive = $false
    $inMap = $false
    $lineCount = 0
    $reader = [IO.StreamReader]::new(
        $Path,
        [Text.Encoding]::UTF8,
        $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage 'netstats' `
                    -Deadline $Deadline `
                    -Command 'stream dumpsys netstats detail'
            }
            if ($line -match ('^\s*ident=.*\buid=' + $Uid + '\b')) {
                $identityBlocks++
                $identityActive = $true
            } elseif ($line -match '^\s*ident=') {
                $identityActive = $false
            }
            if ($identityActive) {
                foreach ($match in [regex]::Matches($line, '\brxBytes=(\d+)')) {
                    $identityRx += [long]$match.Groups[1].Value
                }
                foreach ($match in [regex]::Matches($line, '\btxBytes=(\d+)')) {
                    $identityTx += [long]$match.Groups[1].Value
                }
            }
            if ($line -match '^\s*mAppUidStatsMap:\s*$') {
                $inMap = $true
                continue
            }
            if ($inMap -and
                $line -match '^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$') {
                if ([long]$Matches[1] -eq $Uid) {
                    $mapRows++
                    $mapRx += [long]$Matches[2]
                    $mapTx += [long]$Matches[4]
                }
                continue
            }
            if ($inMap -and $line -match '^\s*m[A-Za-z].*:\s*') {
                $inMap = $false
            }
        }
    } finally {
        $reader.Dispose()
    }
    $rx = $identityRx + $mapRx
    $tx = $identityTx + $mapTx
    return [ordered]@{
        uid = $Uid
        identityBlockCount = $identityBlocks
        appUidStatsMapRowCount = $mapRows
        totalRxBytes = $rx
        totalTxBytes = $tx
        totalBytes = $rx + $tx
        scannedLines = $lineCount
    }
}

function Read-CrashBufferFile {
    param(
        [string] $Path,
        [datetime] $DeviceStart,
        [datetime] $Deadline
    )

    $fDebugLines = [Collections.Generic.List[string]]::new()
    $packageEvidence = $false
    $current = $false
    $lineCount = 0
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage 'crash-scan' `
                    -Deadline $Deadline `
                    -Command 'stream logcat -b crash'
            }
            $at = Convert-LogcatTimestamp $line $DeviceStart
            if ($null -ne $at) {
                $current = $at -ge $DeviceStart.AddSeconds(-1)
            }
            if (-not $current -or $line -notmatch '\sF DEBUG\s+:') {
                continue
            }
            Add-CappedLine $fDebugLines $line
            if ($line -match 'dev\.aurorama\.kokoro\.(harness|focuspeer)|kokoro-tts-runt') {
                $packageEvidence = $true
            }
        }
    } finally {
        $reader.Dispose()
    }
    return [ordered]@{
        fDebugCount = if ($packageEvidence) { $fDebugLines.Count } else { 0 }
        lines = @($fDebugLines)
        scannedLines = $lineCount
    }
}

function Read-EventBufferFile {
    param(
        [string] $Path,
        [datetime] $DeviceStart,
        [datetime] $Deadline
    )

    $anrLines = [Collections.Generic.List[string]]::new()
    $lineCount = 0
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage 'crash-scan' `
                    -Deadline $Deadline `
                    -Command 'stream logcat -b events'
            }
            $at = Convert-LogcatTimestamp $line $DeviceStart
            if ($null -eq $at -or $at -lt $DeviceStart.AddSeconds(-1)) {
                continue
            }
            if ($line -match '\bam_anr\b' -and
                $line -match 'dev\.aurorama\.kokoro\.harness') {
                Add-CappedLine $anrLines $line
            }
        }
    } finally {
        $reader.Dispose()
    }
    return [ordered]@{
        anrCount = $anrLines.Count
        lines = @($anrLines)
    }
}

function Read-DropboxTombstoneFile {
    param(
        [string] $Path,
        [datetime] $DeviceStart,
        [datetime] $Deadline
    )

    $relevantLines = [Collections.Generic.List[string]]::new()
    $current = $false
    $packageEvidenceFound = $false
    $lineCount = 0
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage 'crash-scan' `
                    -Deadline $Deadline `
                    -Command 'stream SYSTEM_TOMBSTONE dropbox'
            }
            $at = Convert-LogcatTimestamp $line $DeviceStart
            if ($null -ne $at) {
                $current = $at -ge $DeviceStart.AddSeconds(-1)
            }
            if (-not $current) {
                continue
            }
            if ($line -match 'dev\.aurorama\.kokoro\.(harness|focuspeer)|kokoro-tts-runt') {
                $packageEvidenceFound = $true
                Add-CappedLine $relevantLines $line
            } elseif ($packageEvidenceFound -and
                $line -match 'SYSTEM_TOMBSTONE|Cmdline:|pid:|>>> ') {
                Add-CappedLine $relevantLines $line
            }
        }
    } finally {
        $reader.Dispose()
    }
    return [ordered]@{
        tombstoneCount = if ($packageEvidenceFound) { 1 } else { 0 }
        lines = @($relevantLines)
    }
}

function Read-ExitInfoFile {
    param(
        [string] $Path,
        [datetime] $DeviceStart,
        [datetime] $Deadline
    )

    $currentBlocks = [Collections.Generic.List[object]]::new()
    $historicalReason5 = 0
    $lineCount = 0
    $active = $null
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    $flush = {
        param($Block)
        if ($null -eq $Block) {
            return
        }
        $isCurrent =
            $null -ne $Block.timestamp -and
            $Block.timestamp -ge $DeviceStart.AddSeconds(-1)
        if ($isCurrent) {
            if ($Block.reason -eq 5 -and
                (
                    [string]::IsNullOrWhiteSpace([string]$Block.process) -or
                    $Block.process -eq $mainPackage
                )) {
                $script:exitInfoCurrentReason5++
            }
            if ($currentBlocks.Count -lt 20) {
                $currentBlocks.Add([pscustomobject]$Block)
            }
        } elseif ($Block.reason -eq 5) {
            $script:exitInfoHistoricalReason5++
        }
    }
    $script:exitInfoCurrentReason5 = 0
    $script:exitInfoHistoricalReason5 = 0
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage 'crash-scan' `
                    -Deadline $Deadline `
                    -Command 'stream ActivityManager exit-info'
            }
            if ($line -match '^\s*ApplicationExitInfo #(?<index>\d+):') {
                & $flush $active
                $active = [ordered]@{
                    index = [int]$Matches['index']
                    timestamp = $null
                    pid = $null
                    process = $null
                    reason = $null
                    status = $null
                    lines = [Collections.Generic.List[string]]::new()
                }
            }
            if ($null -eq $active) {
                continue
            }
            if ($active.lines.Count -lt 80) {
                $active.lines.Add($line)
            }
            $timestamp = [regex]::Match(
                $line,
                'timestamp=(?<stamp>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d{3})?)(?:\s|$)')
            if ($timestamp.Success) {
                $active.timestamp = Convert-LogcatTimestamp `
                    $timestamp.Groups['stamp'].Value `
                    (Get-Date)
            }
            $pidMatch = [regex]::Match($line, '\bpid=(?<pid>\d+)')
            if ($pidMatch.Success) {
                $active.pid = [int]$pidMatch.Groups['pid'].Value
            }
            $processMatch = [regex]::Match(
                $line,
                '\bprocess=(?<process>[^\s]+)\s+reason=(?<reason>\d+)')
            if ($processMatch.Success) {
                $active.process = $processMatch.Groups['process'].Value
                $active.reason = [int]$processMatch.Groups['reason'].Value
            }
            $statusMatch = [regex]::Match($line, '\bstatus=(?<status>-?\d+)')
            if ($statusMatch.Success) {
                $active.status = [int]$statusMatch.Groups['status'].Value
            }
        }
        & $flush $active
    } finally {
        $reader.Dispose()
    }
    return [ordered]@{
        currentBlocks = @($currentBlocks)
        currentReason5Count = $script:exitInfoCurrentReason5
        historicalReason5Count = $script:exitInfoHistoricalReason5
        scannedLines = $lineCount
    }
}

function Read-ServiceRecordCount {
    param(
        [string] $Path,
        [datetime] $Deadline,
        [string] $Stage,
        [switch] $IgnoreMemoryLimit
    )

    $count = 0
    $lineCount = 0
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage $Stage `
                    -Deadline $Deadline `
                    -Command 'stream activity services' `
                    -IgnoreMemoryLimit:$IgnoreMemoryLimit
            }
            if ($line -match 'ServiceRecord\{' -and
                $line -match 'dev\.aurorama\.kokoro\.(harness|focuspeer)') {
                $count++
            }
        }
    } finally {
        $reader.Dispose()
    }
    return $count
}

function Read-NotificationRecordCount {
    param(
        [string] $Path,
        [datetime] $Deadline,
        [string] $Stage,
        [switch] $IgnoreMemoryLimit
    )

    $count = 0
    $lineCount = 0
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage $Stage `
                    -Deadline $Deadline `
                    -Command 'stream notification dump' `
                    -IgnoreMemoryLimit:$IgnoreMemoryLimit
            }
            if ($line -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.(harness|focuspeer)') {
                $count++
            }
        }
    } finally {
        $reader.Dispose()
    }
    return $count
}

function Read-ActiveSessionCount {
    param(
        [string] $Path,
        [datetime] $Deadline,
        [string] $Stage,
        [switch] $IgnoreMemoryLimit
    )

    $activeKeys = [Collections.Generic.HashSet[string]]::new()
    $inStack = $false
    $currentPackage = $null
    $currentOwner = $null
    $lineCount = 0
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineCount++
            if (($lineCount % 1000) -eq 0) {
                Test-StageBudget `
                    -Stage $Stage `
                    -Deadline $Deadline `
                    -Command 'stream media_session Sessions Stack' `
                    -IgnoreMemoryLimit:$IgnoreMemoryLimit
            }
            if ($line -match '^\s*Sessions Stack - have \d+ sessions:') {
                $inStack = $true
                $currentPackage = $null
                $currentOwner = $null
                continue
            }
            if ($line -match '^\s*Audio playback \(lastly played comes first\)' -or
                $line -match '^\s*Record for full_user=') {
                $inStack = $false
                $currentPackage = $null
                $currentOwner = $null
                continue
            }
            if (-not $inStack) {
                continue
            }
            $owner = [regex]::Match($line, '\bownerPid=(\d+)')
            if ($owner.Success) {
                $currentOwner = $owner.Groups[1].Value
            }
            $package = [regex]::Match(
                $line,
                '^\s*package=(dev\.aurorama\.kokoro\.(?:harness|focuspeer))\s*$')
            if ($package.Success) {
                $currentPackage = $package.Groups[1].Value
            }
            if ($line -match '^\s*active=true\s*$' -and
                $currentPackage) {
                $null = $activeKeys.Add(
                    "$currentOwner|$currentPackage")
            }
        }
    } finally {
        $reader.Dispose()
    }
    return $activeKeys.Count
}

function Read-PackageIdentityStreamed {
    param(
        [string] $Package,
        [string] $Stage,
        [datetime] $Deadline
    )

    $pathResult = Invoke-AdbBounded `
        -Stage $Stage `
        -Command "pm path $Package" `
        -AdbArgs @('shell', 'pm', 'path', $Package) `
        -Deadline $Deadline `
        -CaptureLimitBytes 65536 `
        -MaxChars 65536
    $basePath = @(
        $pathResult.text -split "`r?`n" |
            Where-Object { $_ -match '^package:' } |
            ForEach-Object { $_ -replace '^package:', '' }
    ) | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($basePath)) {
        return [ordered]@{
            package = $Package
            installed = $false
            basePath = $null
            bytes = $null
            sha256 = $null
            metadata = @()
        }
    }
    $shaResult = Invoke-AdbBounded `
        -Stage $Stage `
        -Command "sha256sum $basePath" `
        -AdbArgs @('shell', 'sha256sum', $basePath) `
        -Deadline $Deadline `
        -CaptureLimitBytes 65536 `
        -MaxChars 65536
    $sizeResult = Invoke-AdbBounded `
        -Stage $Stage `
        -Command "stat $basePath" `
        -AdbArgs @('shell', 'stat', '-c', '%s', $basePath) `
        -Deadline $Deadline `
        -CaptureLimitBytes 65536 `
        -MaxChars 65536
    $dumpCapture = Invoke-AdbToFile `
        -Stage $Stage `
        -Command "dumpsys package $Package" `
        -AdbArgs @('shell', 'dumpsys', 'package', $Package) `
        -Deadline $Deadline `
        -CaptureLimitBytes 16777216
    $metadata = [Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $dumpCapture.stdoutPath) {
        $reader = [IO.StreamReader]::new(
            $dumpCapture.stdoutPath,
            [Text.Encoding]::UTF8,
            $true)
        $dumpLineCount = 0
        try {
            while ($null -ne ($line = $reader.ReadLine())) {
                $dumpLineCount++
                if (($dumpLineCount % 1000) -eq 0) {
                    Test-StageBudget `
                        -Stage $Stage `
                        -Deadline $Deadline `
                        -Command "stream dumpsys package $Package"
                }
                if ($line -match 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|codePath=|primaryCpuAbi=|android\.permission\.(FOREGROUND_SERVICE|FOREGROUND_SERVICE_MEDIA_PLAYBACK|POST_NOTIFICATIONS|WAKE_LOCK|INTERNET)(:|$)') {
                    if ($metadata.Count -lt 100) {
                        $metadata.Add($line)
                    }
                }
            }
        } finally {
            $reader.Dispose()
        }
    }
    $sizeText = ([string]$sizeResult.text).Trim()
    $sha = [regex]::Match(
        [string]$shaResult.text,
        '\b([0-9a-fA-F]{64})\b').Groups[1].Value.ToLowerInvariant()
    return [ordered]@{
        package = $Package
        installed = $true
        basePath = $basePath
        bytes = if ($sizeText -match '^\d+$') { [long]$sizeText } else { $null }
        sha256 = $sha
        metadata = @($metadata)
    }
}

function Read-ProductionPackagesStreamed {
    param(
        [datetime] $Deadline,
        [string] $Stage
    )

    $result = [Collections.Generic.List[object]]::new()
    foreach ($package in $productionPackages) {
        $result.Add((
            Read-PackageIdentityStreamed `
                -Package $package `
                -Stage $Stage `
                -Deadline $Deadline
        ))
    }
    return @($result)
}

$logcatMetadataPath = Join-Path $logRoot 'logcat-start.json'
$logcatMetadata = if (Test-Path -LiteralPath $logcatMetadataPath) {
    Get-Content -LiteralPath $logcatMetadataPath -Raw -Encoding utf8 |
        ConvertFrom-Json
} else {
    $null
}
$hostStartedAtText = if ($null -ne $logcatMetadata) {
    [string]$logcatMetadata.hostStartedAt
} else {
    ''
}
$hostStartedAtOffset = if ($hostStartedAtText) {
    try {
        [DateTimeOffset]::Parse($hostStartedAtText)
    } catch {
        $null
    }
} else {
    $null
}
$estimatedDeviceStartedAtText = if ($null -ne $logcatMetadata) {
    [string]$logcatMetadata.estimatedDeviceLogStartedAt
} else {
    ''
}
$deviceRunStartedAt = if ($estimatedDeviceStartedAtText) {
    try {
        [datetime]::ParseExact(
            $estimatedDeviceStartedAtText,
            'yyyy-MM-dd HH:mm:ss.fff',
            [Globalization.CultureInfo]::InvariantCulture)
    } catch {
        $null
    }
} else {
    $null
}
$deviceClockDriftMilliseconds = if ($null -ne $logcatMetadata) {
    $logcatMetadata.deviceClockDriftMilliseconds
} else {
    $null
}
$hostLogPresent = Test-Path -LiteralPath $logPath
$currentRunWindowValid =
    $null -ne $hostStartedAtOffset -and
    $null -ne $deviceRunStartedAt -and
    $hostLogPresent

$pidsBeforeCleanup = [ordered]@{}
$mainUid = $null
$netstats = $null
$hostEvidence = $null
$crashEvidence = $null

$preCleanup = Invoke-Stage `
    -Name 'pre-cleanup' `
    -Command 'pidof netstats capture and force-stop' `
    -Action {
        param($deadline)
        $pids = [ordered]@{}
        foreach ($package in $testPackages) {
            $pidResult = Invoke-AdbBounded `
                -Stage 'pre-cleanup' `
                -Command "pidof $package" `
                -AdbArgs @('shell', 'pidof', $package) `
                -Deadline $deadline `
                -CaptureLimitBytes 65536 `
                -MaxChars 65536
            $pids[$package] = ([string]$pidResult.text).Trim()
        }
        $uidResult = Invoke-AdbBounded `
            -Stage 'pre-cleanup' `
            -Command 'cmd package list packages -U' `
            -AdbArgs @(
                'shell', 'cmd', 'package', 'list', 'packages', '-U', $mainPackage) `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536
        $uid = if ([string]$uidResult.text -match '\buid:(?<uid>\d+)') {
            [long]$Matches['uid']
        } else {
            $null
        }
        $null = Invoke-AdbBounded `
            -Stage 'pre-cleanup' `
            -Command "am force-stop $mainPackage" `
            -AdbArgs @('shell', 'am', 'force-stop', $mainPackage) `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536
        $null = Invoke-AdbBounded `
            -Stage 'pre-cleanup' `
            -Command "am force-stop $peerPackage" `
            -AdbArgs @('shell', 'am', 'force-stop', $peerPackage) `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536
        $netstatsResult = $null
        if ($null -ne $uid) {
            $netCapture = Invoke-AdbToFile `
                -Stage 'netstats' `
                -Command 'dumpsys netstats detail' `
                -AdbArgs @('shell', 'dumpsys', 'netstats', 'detail') `
                -Deadline $deadline `
                -CaptureLimitBytes $MaxCaptureBytes
            if (-not $netCapture.blocked -and
                $netCapture.exitCode -eq 0) {
                $netstatsResult = Read-NetStatsFile `
                    -Path $netCapture.stdoutPath `
                    -Uid $uid `
                    -Deadline $deadline
            }
        }
        [ordered]@{
            pids = $pids
            mainUid = $uid
            netstats = $netstatsResult
        }
    }
if ($null -ne $preCleanup) {
    $pidsBeforeCleanup = $preCleanup.pids
    $mainUid = $preCleanup.mainUid
    $netstats = $preCleanup.netstats
}

$logcatStopped = Invoke-Stage `
    -Name 'stop-logcat' `
    -Command 'stop host adb logcat process and flush' `
    -ForceWhenBlocked `
    -Action {
        param($deadline)
        $stopped = if ($null -eq $logcatMetadata) {
            [pscustomobject][ordered]@{
                stopped = $true
                alreadyExited = $true
                reason = 'NO_OWNED_LOGCAT_STARTED'
                processId = $null
            }
        } else {
            Stop-QaOwnedProcess `
                -MetadataPath $logcatMetadataPath `
                -ExpectedSerial $Serial `
                -ExpectedEvidenceRoot $outputPath
        }
        if (-not $stopped.stopped) {
            Add-BlockedReason `
                -Stage 'stop-logcat' `
                -Reason $stopped.reason `
                -Command 'validated host adb logcat stop'
            throw "Owned logcat stop failed closed: $($stopped.reason)"
        }
        Test-StageBudget `
            -Stage 'stop-logcat' `
            -Deadline $deadline `
            -Command 'stop host adb logcat process' `
            -IgnoreMemoryLimit
        $remainingSeconds = Get-RemainingSeconds -Deadline $deadline
        Start-Sleep -Milliseconds (
            [int][Math]::Min(500, 100 * [Math]::Max(1, $remainingSeconds)))
        $stopped
    }

if (-not $script:blocked -and $currentRunWindowValid) {
    $hostEvidence = Invoke-Stage `
        -Name 'host-log' `
        -Command 'stream runtime-logcat.log with current-run filter' `
        -Action {
            param($deadline)
            Read-HostLogEvidence `
                -Path $logPath `
                -DeviceStart $deviceRunStartedAt `
                -Deadline $deadline
        }
}

if (-not $script:blocked) {
    $crashEvidence = Invoke-Stage `
        -Name 'crash-scan' `
        -Command 'stream crash/events/exit-info/SYSTEM_TOMBSTONE' `
        -Action {
            param($deadline)
            $sourceExits = [ordered]@{}
            $crashCapture = Invoke-AdbToFile `
                -Stage 'crash-scan' `
                -Command 'logcat -b crash -d -v threadtime' `
                -AdbArgs @(
                    'shell', 'logcat', '-b', 'crash', '-d', '-v', 'threadtime') `
                -Deadline $deadline `
                -CaptureLimitBytes $MaxCaptureBytes
            $sourceExits.crashBuffer = $crashCapture.exitCode
            $eventsCapture = Invoke-AdbToFile `
                -Stage 'crash-scan' `
                -Command 'logcat -b events -d -v threadtime' `
                -AdbArgs @(
                    'shell', 'logcat', '-b', 'events', '-d', '-v', 'threadtime') `
                -Deadline $deadline `
                -CaptureLimitBytes $MaxCaptureBytes
            $sourceExits.eventsBuffer = $eventsCapture.exitCode
            $exitCapture = Invoke-AdbToFile `
                -Stage 'crash-scan' `
                -Command "dumpsys activity exit-info $mainPackage" `
                -AdbArgs @(
                    'shell', 'dumpsys', 'activity', 'exit-info', $mainPackage) `
                -Deadline $deadline `
                -CaptureLimitBytes $MaxCaptureBytes
            $sourceExits.exitInfo = $exitCapture.exitCode
            $dropboxCapture = Invoke-AdbToFile `
                -Stage 'crash-scan' `
                -Command 'dumpsys dropbox --print SYSTEM_TOMBSTONE' `
                -AdbArgs @(
                    'shell', 'dumpsys', 'dropbox', '--print', 'SYSTEM_TOMBSTONE') `
                -Deadline $deadline `
                -CaptureLimitBytes $MaxCaptureBytes
            $sourceExits.dropbox = $dropboxCapture.exitCode
            $sourcesPassed = @(
                $sourceExits.Values |
                    Where-Object { $_ -ne 0 }
            ).Count -eq 0
            $crash = if (-not $crashCapture.blocked -and
                $crashCapture.exitCode -eq 0) {
                Read-CrashBufferFile `
                    -Path $crashCapture.stdoutPath `
                    -DeviceStart $deviceRunStartedAt `
                    -Deadline $deadline
            } else {
                [ordered]@{ fDebugCount = 0; lines = @() }
            }
            $events = if (-not $eventsCapture.blocked -and
                $eventsCapture.exitCode -eq 0) {
                Read-EventBufferFile `
                    -Path $eventsCapture.stdoutPath `
                    -DeviceStart $deviceRunStartedAt `
                    -Deadline $deadline
            } else {
                [ordered]@{ anrCount = 0; lines = @() }
            }
            $exitInfo = if (-not $exitCapture.blocked -and
                $exitCapture.exitCode -eq 0) {
                Read-ExitInfoFile `
                    -Path $exitCapture.stdoutPath `
                    -DeviceStart $deviceRunStartedAt `
                    -Deadline $deadline
            } else {
                [ordered]@{
                    currentBlocks = @()
                    currentReason5Count = 0
                    historicalReason5Count = 0
                }
            }
            $dropbox = if (-not $dropboxCapture.blocked -and
                $dropboxCapture.exitCode -eq 0) {
                Read-DropboxTombstoneFile `
                    -Path $dropboxCapture.stdoutPath `
                    -DeviceStart $deviceRunStartedAt `
                    -Deadline $deadline
            } else {
                [ordered]@{ tombstoneCount = 0; lines = @() }
            }
            [ordered]@{
                sourceExitCodes = $sourceExits
                sourcesPassed = $sourcesPassed
                crash = $crash
                events = $events
                exitInfo = $exitInfo
                dropbox = $dropbox
            }
        }
}

$clearResults = [ordered]@{}
$uninstallResults = [ordered]@{}
$externalCleanup = [ordered]@{}
$cleanupStage = Invoke-Stage `
    -Name 'minimal-cleanup' `
    -Command 'force-stop clear uninstall external/remote cleanup' `
    -ForceWhenBlocked `
    -Action {
        param($deadline)
        $clear = [ordered]@{}
        $uninstall = [ordered]@{}
        $external = [ordered]@{}
        foreach ($package in $testPackages) {
            $null = Invoke-AdbBounded `
                -Stage 'minimal-cleanup' `
                -Command "am force-stop $package" `
                -AdbArgs @('shell', 'am', 'force-stop', $package) `
                -Deadline $deadline `
                -CaptureLimitBytes 65536 `
                -MaxChars 65536 `
                -IgnoreMemoryLimit
        }
        foreach ($package in $testPackages) {
            $clear[$package] = Invoke-AdbBounded `
                -Stage 'minimal-cleanup' `
                -Command "pm clear $package" `
                -AdbArgs @('shell', 'pm', 'clear', $package) `
                -Deadline $deadline `
                -CaptureLimitBytes 65536 `
                -MaxChars 65536 `
                -IgnoreMemoryLimit
            $uninstall[$package] = Invoke-AdbBounded `
                -Stage 'minimal-cleanup' `
                -Command "uninstall --user 0 $package" `
                -AdbArgs @('uninstall', '--user', '0', $package) `
                -Deadline $deadline `
                -CaptureLimitBytes 65536 `
                -MaxChars 65536 `
                -IgnoreMemoryLimit
            $external[$package] = [ordered]@{}
            foreach ($root in @(
                '/sdcard/Android/data',
                '/sdcard/Android/media',
                '/sdcard/Android/obb'
            )) {
                $path = "$root/$package"
                $remove = Invoke-AdbBounded `
                    -Stage 'minimal-cleanup' `
                    -Command "rm -rf $path" `
                    -AdbArgs @('shell', 'rm', '-rf', $path) `
                    -Deadline $deadline `
                    -CaptureLimitBytes 65536 `
                    -MaxChars 65536 `
                    -IgnoreMemoryLimit
                $external[$package][$path] = [ordered]@{
                    removeExitCode = $remove.exitCode
                }
            }
        }
        foreach ($path in @(
            '/sdcard/qa_v8_ui_cancel.xml',
            '/sdcard/qa_v8_notification_controls.xml',
            '/sdcard/qa_v8_miui_install.xml',
            '/sdcard/qa_v8_resource_ui.xml',
            '/sdcard/qa_v8_resource_notification.xml'
        )) {
            $null = Invoke-AdbBounded `
                -Stage 'minimal-cleanup' `
                -Command "rm -f $path" `
                -AdbArgs @('shell', 'rm', '-f', $path) `
                -Deadline $deadline `
                -CaptureLimitBytes 65536 `
                -MaxChars 65536 `
                -IgnoreMemoryLimit
        }
        $null = Invoke-AdbBounded `
            -Stage 'minimal-cleanup' `
            -Command 'remove qa_v8 state xml helpers' `
            -AdbArgs @(
                'shell',
                "sh -c 'rm -f /sdcard/qa_v8_state_*.xml'") `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536 `
            -IgnoreMemoryLimit
        [ordered]@{
            clear = $clear
            uninstall = $uninstall
            external = $external
        }
    }
if ($null -ne $cleanupStage) {
    $clearResults = $cleanupStage.clear
    $uninstallResults = $cleanupStage.uninstall
    $externalCleanup = $cleanupStage.external
}

$convergence = Invoke-Stage `
    -Name 'convergence' `
    -Command 'bounded package pid service notification media cleanup checks' `
    -ForceWhenBlocked `
    -Action {
        param($deadline)
        $remainingSeconds = Get-RemainingSeconds -Deadline $deadline
        $sleepSeconds = [Math]::Min(
            3,
            [Math]::Max(1, [int]$remainingSeconds))
        Start-Sleep -Seconds ([int]$sleepSeconds)
        $packageList = Invoke-AdbBounded `
            -Stage 'convergence' `
            -Command 'pm list packages' `
            -AdbArgs @('shell', 'pm', 'list', 'packages') `
            -Deadline $deadline `
            -CaptureLimitBytes 4194304 `
            -MaxChars 4194304 `
            -IgnoreMemoryLimit
        $pids = [ordered]@{}
        foreach ($package in $testPackages) {
            $pidResult = Invoke-AdbBounded `
                -Stage 'convergence' `
                -Command "pidof $package" `
                -AdbArgs @('shell', 'pidof', $package) `
                -Deadline $deadline `
                -CaptureLimitBytes 65536 `
                -MaxChars 65536 `
                -IgnoreMemoryLimit
            $pids[$package] = ([string]$pidResult.text).Trim()
        }
        $serviceCounts = [ordered]@{}
        foreach ($package in $testPackages) {
            $capture = Invoke-AdbToFile `
                -Stage 'convergence' `
                -Command "dumpsys activity services $package" `
                -AdbArgs @(
                    'shell', 'dumpsys', 'activity', 'services', $package) `
                -Deadline $deadline `
                -CaptureLimitBytes 16777216 `
                -IgnoreMemoryLimit
            $serviceCounts[$package] =
                Read-ServiceRecordCount `
                    -Path $capture.stdoutPath `
                    -Deadline $deadline `
                    -Stage 'convergence' `
                    -IgnoreMemoryLimit
        }
        $externalPresence = [ordered]@{}
        $internalProbes = [ordered]@{}
        foreach ($package in $testPackages) {
            $externalPresence[$package] = [ordered]@{}
            foreach ($path in $externalCleanup[$package].Keys) {
                $probe = Invoke-AdbBounded `
                    -Stage 'convergence' `
                    -Command "test -e $path" `
                    -AdbArgs @('shell', 'test', '-e', $path) `
                    -Deadline $deadline `
                    -CaptureLimitBytes 65536 `
                    -MaxChars 65536 `
                    -IgnoreMemoryLimit
                $externalPresence[$package][$path] = $probe.exitCode -eq 0
            }
            $internalProbes[$package] = @(
                foreach ($path in @(
                    "/data/user/0/$package",
                    "/data/user_de/0/$package",
                    "/data/data/$package",
                    "/data/user/0/$package/cache",
                    "/data/user/0/$package/data",
                    "/data/user_de/0/$package/code_cache"
                )) {
                    $probe = Invoke-AdbBounded `
                        -Stage 'convergence' `
                        -Command "test -e $path" `
                        -AdbArgs @('shell', 'test', '-e', $path) `
                        -Deadline $deadline `
                        -CaptureLimitBytes 65536 `
                        -MaxChars 65536 `
                        -IgnoreMemoryLimit
                    [ordered]@{
                        path = $path
                        present = $probe.exitCode -eq 0
                        exitCode = $probe.exitCode
                    }
                }
            )
        }
        $notificationCapture = Invoke-AdbToFile `
            -Stage 'convergence' `
            -Command 'dumpsys notification --noredact' `
            -AdbArgs @(
                'shell', 'dumpsys', 'notification', '--noredact') `
            -Deadline $deadline `
            -CaptureLimitBytes $MaxCaptureBytes `
            -IgnoreMemoryLimit
        $notificationCount = Read-NotificationRecordCount `
            -Path $notificationCapture.stdoutPath `
            -Deadline $deadline `
            -Stage 'convergence' `
            -IgnoreMemoryLimit
        $mediaCapture = Invoke-AdbToFile `
            -Stage 'convergence' `
            -Command 'dumpsys media_session' `
            -AdbArgs @('shell', 'dumpsys', 'media_session') `
            -Deadline $deadline `
            -CaptureLimitBytes 16777216 `
            -IgnoreMemoryLimit
        $activeSessionCount = Read-ActiveSessionCount `
            -Path $mediaCapture.stdoutPath `
            -Deadline $deadline `
            -Stage 'convergence' `
            -IgnoreMemoryLimit
        $tempResult = Invoke-AdbBounded `
            -Stage 'convergence' `
            -Command 'find qa_v8 helpers' `
            -AdbArgs @(
                'shell',
                "sh -c 'find /sdcard -maxdepth 1 -name qa_v8_* -print'") `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536 `
            -IgnoreMemoryLimit
        [ordered]@{
            packageList = [string]$packageList.text
            pids = $pids
            serviceCounts = $serviceCounts
            externalPresence = $externalPresence
            internalProbes = $internalProbes
            notificationCount = $notificationCount
            activeSessionCount = $activeSessionCount
            remoteTemporaryEntries = ([string]$tempResult.text).Trim()
        }
    }

$beforePath = Join-Path $outputPath 'before.json'
$before = if (Test-Path -LiteralPath $beforePath) {
    Get-Content -LiteralPath $beforePath -Raw -Encoding utf8 | ConvertFrom-Json
} else {
    $null
}

$restoreAndProduction = Invoke-Stage `
    -Name 'restore-production' `
    -Command 'bounded restoration and production package comparison' `
    -ForceWhenBlocked `
    -Action {
        param($deadline)
        $network = [ordered]@{
            airplaneMode = Read-Setting `
                'global' 'airplane_mode_on' 'restore-production' $deadline
            wifiOn = Read-Setting `
                'global' 'wifi_on' 'restore-production' $deadline
            mobileData = Read-Setting `
                'global' 'mobile_data' 'restore-production' $deadline
        }
        $connectivity = Invoke-AdbBounded `
            -Stage 'restore-production' `
            -Command 'dumpsys connectivity active network' `
            -AdbArgs @('shell', 'dumpsys', 'connectivity') `
            -Deadline $deadline `
            -CaptureLimitBytes 8388608 `
            -MaxChars 2097152 `
            -IgnoreMemoryLimit
        $network.activeDefaultNetwork = [regex]::Match(
            [string]$connectivity.text,
            'Active default network:\s*([^\s]+)').Groups[1].Value
        $volumeText = Invoke-AdbBounded `
            -Stage 'restore-production' `
            -Command 'media_session volume get' `
            -AdbArgs @(
                'shell', 'cmd', 'media_session',
                'volume', '--stream', '3', '--get') `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536 `
            -IgnoreMemoryLimit
        $volumeMatch = [regex]::Match(
            [string]$volumeText.text,
            'volume is (\d+) in range \[(\d+)\.\.(\d+)\]')
        $volume = if ($volumeMatch.Success) {
            [int]$volumeMatch.Groups[1].Value
        } else {
            $null
        }
        $powerText = Invoke-AdbBounded `
            -Stage 'restore-production' `
            -Command 'dumpsys power' `
            -AdbArgs @('shell', 'dumpsys', 'power') `
            -Deadline $deadline `
            -CaptureLimitBytes 8388608 `
            -MaxChars 2097152 `
            -IgnoreMemoryLimit
        $windowText = Invoke-AdbBounded `
            -Stage 'restore-production' `
            -Command 'dumpsys window policy' `
            -AdbArgs @('shell', 'dumpsys', 'window', 'policy') `
            -Deadline $deadline `
            -CaptureLimitBytes 8388608 `
            -MaxChars 2097152 `
            -IgnoreMemoryLimit
        $keyguard = [regex]::Match(
            [string]$windowText.text,
            'mIsShowing=(true|false)').Groups[1].Value
        if (-not $keyguard) {
            $keyguard = [regex]::Match(
                [string]$windowText.text,
                '\bshowing=(true|false)').Groups[1].Value
        }
        $currentUser = Invoke-AdbBounded `
            -Stage 'restore-production' `
            -Command 'am get-current-user' `
            -AdbArgs @('shell', 'am', 'get-current-user') `
            -Deadline $deadline `
            -CaptureLimitBytes 65536 `
            -MaxChars 65536 `
            -IgnoreMemoryLimit
        $production = Read-ProductionPackagesStreamed `
            -Deadline $deadline `
            -Stage 'restore-production'
        [ordered]@{
            network = $network
            volume = $volume
            wakefulness = [regex]::Match(
                [string]$powerText.text,
                'mWakefulness=([^\s]+)').Groups[1].Value
            screenOffTimeout = Read-Setting `
                'system' 'screen_off_timeout' 'restore-production' $deadline
            lockAfterTimeout = Read-Setting `
                'secure' 'lock_screen_lock_after_timeout' `
                'restore-production' $deadline
            stayOn = Read-Setting `
                'global' 'stay_on_while_plugged_in' `
                'restore-production' $deadline
            keyguard = $keyguard
            currentUser = ([string]$currentUser.text).Trim()
            production = @($production)
        }
    }

$fatalExceptionCount = if ($hostEvidence) {
    [int]$hostEvidence.fatalCount
} else {
    0
}
$inputTimeoutCount = if ($hostEvidence) {
    [int]$hostEvidence.inputTimeoutCount
} else {
    0
}
$fDebugCount = 0
if ($hostEvidence) {
    $fDebugCount += [int]$hostEvidence.fDebugCount
}
if ($crashEvidence) {
    $fDebugCount += [int]$crashEvidence.crash.fDebugCount
}
$appAnrCount = if ($crashEvidence) {
    [int]$crashEvidence.events.anrCount
} else {
    0
}
$nativeTombstoneCount = if ($crashEvidence) {
    [int]$crashEvidence.dropbox.tombstoneCount
} else {
    0
}
$reason5Count = if ($crashEvidence) {
    [int]$crashEvidence.exitInfo.currentReason5Count
} else {
    0
}
$historicalReason5Count = if ($crashEvidence) {
    [int]$crashEvidence.exitInfo.historicalReason5Count
} else {
    0
}
$sourceQueriesPassed = if ($crashEvidence) {
    [bool]$crashEvidence.sourcesPassed
} else {
    $false
}
$crashGatePassed = Test-QaCurrentRunCrashGate `
    -CurrentRunWindowValid $currentRunWindowValid `
    -SourceQueriesPassed $sourceQueriesPassed `
    -FDebugCount $fDebugCount `
    -NativeTombstoneCount $nativeTombstoneCount `
    -JavaFatalCount $fatalExceptionCount `
    -AnrCount $appAnrCount `
    -InputTimeoutCount $inputTimeoutCount `
    -Reason5Count $reason5Count

$productionMatches = [Collections.Generic.List[object]]::new()
if ($null -ne $restoreAndProduction) {
    foreach ($afterPackage in @($restoreAndProduction.production)) {
        $beforePackage = if ($null -ne $before) {
            $before.productionPackages |
                Where-Object package -eq $afterPackage.package |
                Select-Object -First 1
        } else {
            $null
        }
        $beforeHash = if ($null -ne $beforePackage) {
            if ($beforePackage.sha256 -is [string]) {
                [string]$beforePackage.sha256
            } elseif ($null -ne $beforePackage.sha256.text) {
                [regex]::Match(
                    [string]$beforePackage.sha256.text,
                    '\b([0-9a-fA-F]{64})\b').Groups[1].Value.ToLowerInvariant()
            } else {
                ''
            }
        } else {
            ''
        }
        $beforeMetadata = if ($null -ne $beforePackage) {
            @($beforePackage.versionLine)
        } else {
            @()
        }
        $beforeInstalled =
            $null -ne $beforePackage -and
            -not [string]::IsNullOrWhiteSpace([string]$beforePackage.basePath)
        $productionMatches.Add([ordered]@{
            package = $afterPackage.package
            installedBefore = $beforeInstalled
            installedAfter = [bool]$afterPackage.installed
            hashBefore = $beforeHash
            hashAfter = $afterPackage.sha256
            hashMatch =
                -not [string]::IsNullOrWhiteSpace($beforeHash) -and
                $beforeHash -eq $afterPackage.sha256
            pathBefore = if ($beforePackage) {
                [string]$beforePackage.basePath
            } else {
                ''
            }
            pathAfter = [string]$afterPackage.basePath
            pathMatch =
                [string]$beforePackage.basePath -eq
                [string]$afterPackage.basePath
            metadataMatch =
                (@($beforeMetadata) -join "`n") -eq
                (@($afterPackage.metadata) -join "`n")
        })
    }
}
$productionAllMatch =
    $productionMatches.Count -eq $productionPackages.Count -and
    @(
        $productionMatches |
            Where-Object {
                $_.installedBefore -ne $_.installedAfter -or
                ($_.installedAfter -and (
                    -not $_.hashMatch -or
                    -not $_.pathMatch -or
                    -not $_.metadataMatch
                ))
            }
    ).Count -eq 0

$packagesAbsent = $true
$pidsAbsent = $true
$serviceRecordsAbsent = $true
$notificationAbsent = $true
$activeSessionsAbsent = $true
$remoteTemporaryAbsent = $true
if ($null -ne $convergence) {
    $packagesAbsent = @(
        $testPackages |
            Where-Object {
                [string]$convergence.packageList -match
                    ('package:' + [regex]::Escape($_))
            }
    ).Count -eq 0
    $pidsAbsent = @(
        $testPackages |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace(
                    [string]$convergence.pids[$_])
            }
    ).Count -eq 0
    $serviceRecordsAbsent = @(
        $convergence.serviceCounts.Values |
            Where-Object { [int]$_ -ne 0 }
    ).Count -eq 0
    $notificationAbsent = [int]$convergence.notificationCount -eq 0
    $activeSessionsAbsent = [int]$convergence.activeSessionCount -eq 0
    $remoteTemporaryAbsent =
        [string]::IsNullOrWhiteSpace(
            [string]$convergence.remoteTemporaryEntries)
}
$externalRemoveSucceeded = @(
    $externalCleanup.Values |
        ForEach-Object { $_.Values } |
        Where-Object { $_.removeExitCode -ne 0 }
).Count -eq 0
$externalPresenceAbsent =
    $null -ne $convergence -and
    @(
        $convergence.externalPresence.Values |
            ForEach-Object { $_.Values } |
            Where-Object { $_ }
    ).Count -eq 0
$externalPathsAbsent =
    $externalRemoveSucceeded -and $externalPresenceAbsent
$internalDataAbsent =
    $null -ne $convergence -and
    @(
        $convergence.internalProbes.Values |
            ForEach-Object { @($_) } |
            Where-Object present
    ).Count -eq 0
$clearSucceeded = @(
    $testPackages |
        Where-Object {
            $clearResults[$_].exitCode -ne 0 -or
            [string]$clearResults[$_].text -notmatch '(?m)^Success\s*$'
        }
).Count -eq 0
$uninstallSucceeded = @(
    $testPackages |
        Where-Object {
            $uninstallResults[$_].exitCode -ne 0 -or
            [string]$uninstallResults[$_].text -notmatch '(?m)^Success\s*$'
        }
).Count -eq 0

$networkMatch = $false
$volumeMatch = $false
$screenMatch = $false
$keyguardMatch = $false
$currentUserMatch = $false
if ($null -ne $before -and $null -ne $restoreAndProduction) {
    $beforeActiveNetwork = [regex]::Match(
        (@($before.activeNetworkLines) -join "`n"),
        'Active default network:\s*(\d+)').Groups[1].Value -match '^\d+$'
    $afterActiveNetwork =
        $restoreAndProduction.network.activeDefaultNetwork -match '^\d+$'
    $networkMatch =
        $restoreAndProduction.network.airplaneMode -eq
            [string]$before.airplaneMode -and
        $restoreAndProduction.network.wifiOn -eq
            [string]$before.wifiOn -and
        $restoreAndProduction.network.mobileData -eq
            [string]$before.mobileData -and
        $beforeActiveNetwork -eq $afterActiveNetwork
    $beforeVolume =
        if ($null -ne $before.musicVolumeSetting -and
            -not [string]::IsNullOrWhiteSpace(
                [string]$before.musicVolumeSetting)) {
            [int]$before.musicVolumeSetting
        } else {
            $null
        }
    $volumeMatch =
        $null -ne $beforeVolume -and
        $null -ne $restoreAndProduction.volume -and
        [int]$beforeVolume -eq [int]$restoreAndProduction.volume
    $screenMatch =
        $restoreAndProduction.screenOffTimeout -eq
            [string]$before.screenOffTimeout -and
        $restoreAndProduction.lockAfterTimeout -eq
            [string]$before.lockAfterTimeout -and
        $restoreAndProduction.stayOn -eq [string]$before.stayOn -and
        $restoreAndProduction.wakefulness -eq
            [string]$before.wakefulness
    $keyguardMatch =
        $restoreAndProduction.keyguard -eq [string]$before.keyguardShowing
    $currentUserMatch =
        $restoreAndProduction.currentUser -eq [string]$before.currentUser
}

$speakerGatePassed = if ($hostEvidence) {
    [int]$hostEvidence.forbiddenTtsRequestCount -eq 0 -and
    [int]$hostEvidence.approvedRequestCounts.zf001Sid3 -gt 0 -and
    [int]$hostEvidence.approvedRequestCounts.zm010Sid59 -gt 0
} else {
    $false
}
$netstatsZero =
    $null -ne $netstats -and
    (
        [int]$netstats.identityBlockCount -gt 0 -or
        [int]$netstats.appUidStatsMapRowCount -gt 0
    ) -and
    [long]$netstats.totalBytes -eq 0
$cleanupArtifactsPassed =
    $packagesAbsent -and
    $pidsAbsent -and
    $serviceRecordsAbsent -and
    $notificationAbsent -and
    $activeSessionsAbsent -and
    $remoteTemporaryAbsent -and
    $externalPathsAbsent -and
    $internalDataAbsent -and
    $clearSucceeded -and
    $uninstallSucceeded
$restorePassed =
    $productionAllMatch -and
    $networkMatch -and
    $volumeMatch -and
    $screenMatch -and
    $keyguardMatch -and
    $currentUserMatch
$cleanupPassed = $cleanupArtifactsPassed -and $restorePassed

$passed = Test-QaFinalizeSafetyGate `
    -Blocked $script:blocked `
    -CrashGatePassed $crashGatePassed `
    -CleanupArtifactsPassed $cleanupArtifactsPassed `
    -RestorePassed $restorePassed `
    -SpeakerGatePassed $speakerGatePassed `
    -UidNetworkZero $netstatsZero
$status = if ($script:blocked) {
    'FINALIZE_BLOCKED'
} elseif ($passed) {
    'PASS_CURRENT_RUN_CRASH_CLEANUP_AND_RESTORE'
} else {
    'FAIL'
}

$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.finalize-device.v3'
    status = $status
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    executionLimits = [ordered]@{
        stageTimeoutSeconds = $StageTimeoutSeconds
        maxWorkingSetBytes = $MaxWorkingSetBytes
        maxCaptureBytes = $MaxCaptureBytes
        maxEvidenceLines = $MaxEvidenceLines
        boundedStreaming = $true
        tempCaptureRoot = $tempRoot
    }
    blockedReasons = @($script:blockedReasons)
    stages = @($script:stages)
    progressPath = $progressPath
    currentRunFilter = [ordered]@{
        hostStartedAt = $hostStartedAtText
        estimatedDeviceLogStartedAt = $estimatedDeviceStartedAtText
        deviceClockDriftMilliseconds = $deviceClockDriftMilliseconds
        valid = $currentRunWindowValid
        hostLogPresent = $hostLogPresent
        appPids = if ($hostEvidence) { $hostEvidence.appPids } else { @() }
        coversAllMatrixRounds = $true
        oldCrashBufferAndDropboxEntriesExcluded = $true
    }
    finalNetstats = $netstats
    finalUidNetstatsZero = $netstatsZero
    crashGate = [ordered]@{
        passed = $crashGatePassed
        currentRunWindowValid = $currentRunWindowValid
        sourceQueriesPassed = $sourceQueriesPassed
        sourceExitCodes = if ($crashEvidence) {
            $crashEvidence.sourceExitCodes
        } else {
            $null
        }
        fDebugCount = $fDebugCount
        nativeTombstoneCount = $nativeTombstoneCount
        javaFatalCount = $fatalExceptionCount
        anrCount = $appAnrCount
        inputTimeoutCount = $inputTimeoutCount
        applicationExitReason5Count = $reason5Count
        historicalApplicationExitReason5Excluded = $historicalReason5Count
        currentExitInfoBlocks = if ($crashEvidence) {
            @($crashEvidence.exitInfo.currentBlocks)
        } else {
            @()
        }
        filteredLines = [ordered]@{
            fDebug = if ($hostEvidence) {
                @($hostEvidence.fDebugLines)
            } else {
                @()
            }
            tombstone = if ($crashEvidence) {
                @($crashEvidence.dropbox.lines)
            } else {
                @()
            }
            fatal = if ($hostEvidence) {
                @($hostEvidence.fatalLines)
            } else {
                @()
            }
            anr = if ($crashEvidence) {
                @($crashEvidence.events.lines)
            } else {
                @()
            }
            inputTimeout = if ($hostEvidence) {
                @($hostEvidence.inputTimeoutLines)
            } else {
                @()
            }
        }
    }
    speakerGate = [ordered]@{
        passed = $speakerGatePassed
        approvedRequestCounts = if ($hostEvidence) {
            $hostEvidence.approvedRequestCounts
        } else {
            $null
        }
        forbiddenTtsRequestCount = if ($hostEvidence) {
            [int]$hostEvidence.forbiddenTtsRequestCount
        } else {
            0
        }
        forbiddenLiteralCount = if ($hostEvidence) {
            [int]$hostEvidence.forbiddenLiteralCount
        } else {
            0
        }
        filteredLines = if ($hostEvidence) {
            @($hostEvidence.speakerLines)
        } else {
            @()
        }
    }
    cleanup = [ordered]@{
        passed = $cleanupPassed
        preCleanupPids = $pidsBeforeCleanup
        clearResults = $clearResults
        uninstallResults = $uninstallResults
        externalPaths = $externalCleanup
        externalPathsAbsent = $externalPathsAbsent
        internalDataAbsent = $internalDataAbsent
        internalProbes = if ($convergence) {
            $convergence.internalProbes
        } else {
            $null
        }
        packagesAbsent = $packagesAbsent
        pidsAbsent = $pidsAbsent
        serviceRecordsAbsent = $serviceRecordsAbsent
        notificationAbsent = $notificationAbsent
        activeSessionsAbsent = $activeSessionsAbsent
        remoteTemporaryAbsent = $remoteTemporaryAbsent
        productionPackages = @($productionMatches)
        productionAllMatch = $productionAllMatch
        convergence = $convergence
        logcatStopped = $logcatStopped
    }
    restoration = [ordered]@{
        values = $restoreAndProduction
        networkMatch = $networkMatch
        volumeMatch = $volumeMatch
        screenMatch = $screenMatch
        keyguardMatch = $keyguardMatch
        currentUserMatch = $currentUserMatch
    }
    checks = [ordered]@{
        notBlocked = -not $script:blocked
        currentRunCrashGate = $crashGatePassed
        cleanupArtifacts = $cleanupArtifactsPassed
        restore = $restorePassed
        cleanup = $cleanupPassed
        speakerGate = $speakerGatePassed
        zeroUidNetwork = $netstatsZero
    }
}

[IO.File]::WriteAllText(
    $resultPath,
    (($result | ConvertTo-Json -Depth 30) + "`n"),
    $utf8)

try {
    Get-ChildItem -LiteralPath $tempRoot -File -Force -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    [IO.Directory]::Delete($tempRoot, $false)
} catch {
    # Temporary captures are outside the evidence root; cleanup is best effort.
}

$result | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
