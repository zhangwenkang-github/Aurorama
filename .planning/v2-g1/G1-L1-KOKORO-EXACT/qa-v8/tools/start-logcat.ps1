[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [string] $AdbExecutable = 'adb.exe',

    [string[]] $AdbArgumentPrefix = @(),

    [ValidateRange(1, 120)]
    [int] $LaunchProbeSeconds = 2,

    [ValidateRange(1, 120)]
    [int] $ClockTimeoutSeconds = 10
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logRoot = Join-Path $outputPath 'logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$stdoutPath = Join-Path $logRoot 'runtime-logcat.log'
$stderrPath = Join-Path $logRoot 'runtime-logcat.err'
$pidPath = Join-Path $logRoot 'runtime-logcat.pid'
$startPath = Join-Path $logRoot 'logcat-start.json'
$utf8 = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'qa-process-lifecycle.ps1')
$requestedAdbPath = if (Test-Path -LiteralPath $AdbExecutable) {
    (Resolve-Path -LiteralPath $AdbExecutable).Path
} else {
    (Get-Command $AdbExecutable -ErrorAction Stop).Source
}
if ($requestedAdbPath.EndsWith('.ps1', [StringComparison]::OrdinalIgnoreCase)) {
    $adbCommand = (Get-Command 'pwsh.exe' -ErrorAction Stop).Source
    $adbArgumentPrefix = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $requestedAdbPath
    ) + @($AdbArgumentPrefix)
} else {
    $adbCommand = $requestedAdbPath
    $adbArgumentPrefix = @($AdbArgumentPrefix)
}

function Invoke-AdbCaptureBounded {
    param(
        [string[]] $Arguments,
        [string] $Name,
        [int] $TimeoutSeconds
    )

    $captureOut = Join-Path $logRoot "$Name.stdout.log"
    $captureErr = Join-Path $logRoot "$Name.stderr.log"
    $launchArguments = @($adbArgumentPrefix) + @($Arguments)
    $captureProcess = Start-Process `
        -FilePath $adbCommand `
        -ArgumentList $launchArguments `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $captureOut `
        -RedirectStandardError $captureErr
    try {
        if (-not $captureProcess.WaitForExit($TimeoutSeconds * 1000)) {
            try {
                $captureProcess.Kill($true)
            } catch {
                throw [TimeoutException]::new(
                    "Failed to terminate timed-out adb clock command: $Name")
            }
            $captureProcess.WaitForExit(3000) | Out-Null
            throw [TimeoutException]::new(
                "adb clock command timed out after $TimeoutSeconds seconds: $Name")
        }
        $captureProcess.WaitForExit()
        $captureExitCode = [int]$captureProcess.ExitCode
        $captureText = if (Test-Path -LiteralPath $captureOut) {
            [IO.File]::ReadAllText($captureOut, [Text.Encoding]::UTF8).Trim()
        } else {
            ''
        }
        $captureError = if (Test-Path -LiteralPath $captureErr) {
            [IO.File]::ReadAllText($captureErr, [Text.Encoding]::UTF8).Trim()
        } else {
            ''
        }
        if ($captureExitCode -ne 0) {
            throw "adb clock command failed ($captureExitCode): $captureError"
        }
        return $captureText
    } finally {
        $captureProcess.Dispose()
    }
}

if (Test-Path -LiteralPath $pidPath) {
    $oldPid = [int]([IO.File]::ReadAllText($pidPath, [Text.Encoding]::ASCII).Trim())
    throw "Stale logcat PID metadata requires explicit ownership validation: $oldPid"
}

Remove-Item `
    -LiteralPath $stdoutPath, $stderrPath, $pidPath, $startPath `
    -Force `
    -ErrorAction SilentlyContinue
$startedAt = Get-Date
$logcatArguments = @($adbArgumentPrefix) + @(
    '-s', $Serial, 'logcat', '-v', 'threadtime')
$process = Start-Process -FilePath $adbCommand `
    -ArgumentList $logcatArguments `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath
$hostProcessId = [int]$process.Id
$metadataPublished = $false
$metadata = $null
try {
    Start-Sleep -Seconds $LaunchProbeSeconds
    $process.Refresh()
    if ($process.HasExited) {
        $process.WaitForExit()
        $stderr = if (Test-Path -LiteralPath $stderrPath) {
            [IO.File]::ReadAllText($stderrPath, [Text.Encoding]::UTF8)
        } else {
            ''
        }
        throw "adb logcat exited immediately ($($process.ExitCode)): $stderr"
    }

    $hostProcessCim = Get-CimInstance `
        -ClassName Win32_Process `
        -Filter "ProcessId = $hostProcessId" `
        -ErrorAction SilentlyContinue
    if ($null -eq $hostProcessCim -or
        $null -eq $hostProcessCim.CreationDate -or
        [string]::IsNullOrWhiteSpace([string]$hostProcessCim.CommandLine)) {
        throw "Unable to establish logcat process identity for PID $hostProcessId"
    }
    $hostProcessCommandLine = [string]$hostProcessCim.CommandLine
    $hostProcessCommandLineSha256 = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($hostProcessCommandLine))).
        ToLowerInvariant()
    $hostProcessStartedAtUtc =
        ([datetime]$hostProcessCim.CreationDate).ToUniversalTime().ToString('o')
    $metadata = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.logcat-start.v3'
        serial = $Serial
        evidenceRoot = $outputPath
        hostStartedAt = $startedAt.ToString('o')
        hostProcessId = $hostProcessId
        hostProcessStartedAtUtc = $hostProcessStartedAtUtc
        hostProcessCommandLineSha256 = $hostProcessCommandLineSha256
        adbExecutable = $requestedAdbPath
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
        deviceLogcatDate = $null
        deviceEpochSeconds = $null
        hostClockSampleStartedAt = $null
        hostClockSampleFinishedAt = $null
        deviceClockDriftMilliseconds = $null
        estimatedDeviceLogStartedAt = $null
    }
    [IO.File]::WriteAllText(
        $startPath,
        (($metadata | ConvertTo-Json -Depth 6) + "`n"),
        $utf8)
    $metadataPublished = $true
    [IO.File]::WriteAllText(
        $pidPath,
        [string]$hostProcessId,
        [Text.Encoding]::ASCII)
    $process.Dispose()
    $process = $null

    $clockSampleStartedAt = Get-Date
    $deviceEpochText = Invoke-AdbCaptureBounded `
        -Arguments @('-s', $Serial, 'shell', 'date', '+%s') `
        -Name 'logcat-clock-epoch' `
        -TimeoutSeconds $ClockTimeoutSeconds
    $clockSampleFinishedAt = Get-Date
    $deviceLogcatDate = Invoke-AdbCaptureBounded `
        -Arguments @('-s', $Serial, 'shell', 'date', '+%Y-%m-%d %H:%M:%S%z') `
        -Name 'logcat-clock-zone' `
        -TimeoutSeconds $ClockTimeoutSeconds
    $deviceEpochSeconds = [long](
        [regex]::Match($deviceEpochText, '^\s*(\d+)').Groups[1].Value)
    if ($deviceEpochSeconds -le 0) {
        throw "adb clock command returned no Unix epoch: $deviceEpochText"
    }
    $hostMidpointMilliseconds = [long](
        (
            [DateTimeOffset]::new($clockSampleStartedAt).ToUnixTimeMilliseconds() +
            [DateTimeOffset]::new($clockSampleFinishedAt).ToUnixTimeMilliseconds()
        ) / 2)
    $deviceClockDriftMilliseconds =
        ($deviceEpochSeconds * 1000L) - $hostMidpointMilliseconds
    $deviceZoneMatch = [regex]::Match(
        $deviceLogcatDate,
        '([+-])(\d\d)(\d\d)\s*$')
    $deviceUtcOffset = if ($deviceZoneMatch.Success) {
        $sign = if ($deviceZoneMatch.Groups[1].Value -eq '-') { -1 } else { 1 }
        [TimeSpan]::FromMinutes(
            $sign * (
                [int]$deviceZoneMatch.Groups[2].Value * 60 +
                [int]$deviceZoneMatch.Groups[3].Value))
    } else {
        [TimeZoneInfo]::Local.GetUtcOffset($startedAt)
    }
    $estimatedDeviceStart = [DateTimeOffset]::FromUnixTimeMilliseconds(
        [DateTimeOffset]::new($startedAt).ToUnixTimeMilliseconds() +
        $deviceClockDriftMilliseconds
    ).ToOffset($deviceUtcOffset)
    $metadata.deviceLogcatDate = $deviceLogcatDate
    $metadata.deviceEpochSeconds = $deviceEpochSeconds
    $metadata.hostClockSampleStartedAt = $clockSampleStartedAt.ToString('o')
    $metadata.hostClockSampleFinishedAt = $clockSampleFinishedAt.ToString('o')
    $metadata.deviceClockDriftMilliseconds = $deviceClockDriftMilliseconds
    $metadata.estimatedDeviceLogStartedAt =
        $estimatedDeviceStart.ToString('yyyy-MM-dd HH:mm:ss.fff')
    [IO.File]::WriteAllText(
        $startPath,
        (($metadata | ConvertTo-Json -Depth 6) + "`n"),
        $utf8)
} catch {
    $cleanupPassed = $true
    if ($metadataPublished) {
        try {
            $cleanup = Stop-QaOwnedProcess `
                -MetadataPath $startPath `
                -ExpectedSerial $Serial `
                -ExpectedEvidenceRoot $outputPath
            $cleanupPassed =
                [bool]$cleanup.stopped -or [bool]$cleanup.alreadyExited
        } catch {
            $cleanupPassed = $false
        }
    }
    if ($null -ne $process) {
        try {
            if (-not $metadataPublished -and -not $process.HasExited) {
                $process.Kill($true)
            }
        } catch {
            if (-not $metadataPublished) {
                $cleanupPassed = $false
            }
        } finally {
            $process.Dispose()
        }
    }
    if ($cleanupPassed) {
        Remove-Item `
            -LiteralPath $pidPath, $startPath `
            -Force `
            -ErrorAction SilentlyContinue
    }
    throw
}
$metadata | ConvertTo-Json -Depth 6
