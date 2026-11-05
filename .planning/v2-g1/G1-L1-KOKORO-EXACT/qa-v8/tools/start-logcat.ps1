[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot
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

if (Test-Path -LiteralPath $pidPath) {
    $oldPid = [int]([IO.File]::ReadAllText($pidPath, [Text.Encoding]::ASCII).Trim())
    $oldProcess = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
    if ($null -ne $oldProcess) {
        throw "A logcat process is already active for this evidence root: $oldPid"
    }
}

Remove-Item -LiteralPath $stdoutPath, $stderrPath, $pidPath -Force -ErrorAction SilentlyContinue
$startedAt = Get-Date
$process = Start-Process -FilePath 'adb.exe' `
    -ArgumentList @('-s', $Serial, 'logcat', '-v', 'threadtime') `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath
Start-Sleep -Seconds 2
if ($process.HasExited) {
    $stderr = if (Test-Path -LiteralPath $stderrPath) {
        [IO.File]::ReadAllText($stderrPath, [Text.Encoding]::UTF8)
    } else {
        ''
    }
    throw "adb logcat exited immediately: $stderr"
}

[IO.File]::WriteAllText(
    $pidPath,
    [string]$process.Id,
    [Text.Encoding]::ASCII)
try {
    $clockSampleStartedAt = Get-Date
    $deviceEpochText = (
        & adb.exe -s $Serial shell date '+%s' 2>&1 |
            Out-String
    ).Trim()
    $clockSampleFinishedAt = Get-Date
    $deviceLogcatDate = (
        & adb.exe -s $Serial shell date '+%Y-%m-%d %H:%M:%S%z' 2>&1 |
            Out-String
    ).Trim()
    $deviceEpochSeconds = [long](
        [regex]::Match($deviceEpochText, '^\s*(\d+)').Groups[1].Value)
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
    $metadata = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.logcat-start.v2'
        serial = $Serial
        hostStartedAt = $startedAt.ToString('o')
        hostProcessId = $process.Id
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
        deviceLogcatDate = $deviceLogcatDate
        deviceEpochSeconds = $deviceEpochSeconds
        hostClockSampleStartedAt = $clockSampleStartedAt.ToString('o')
        hostClockSampleFinishedAt = $clockSampleFinishedAt.ToString('o')
        deviceClockDriftMilliseconds = $deviceClockDriftMilliseconds
        estimatedDeviceLogStartedAt =
            $estimatedDeviceStart.ToString('yyyy-MM-dd HH:mm:ss.fff')
    }
    [IO.File]::WriteAllText(
        $startPath,
        (($metadata | ConvertTo-Json -Depth 6) + "`n"),
        $utf8)
} catch {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
    throw
}
$metadata | ConvertTo-Json -Depth 6
