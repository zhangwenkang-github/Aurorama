[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('8e875894')]
    [string] $Serial
)

$ErrorActionPreference = 'Stop'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$QaRoot = $PSScriptRoot
$deviceRoot = Join-Path $QaRoot 'device-K60-8e875894'
$commandLog = Join-Path $QaRoot 'commands.log'
$package = 'dev.aurorama.kokoro.harness'
$remoteRoot = "/sdcard/Android/data/$package/files"

function Write-Utf8([string] $Path, [AllowNull()][object] $Value) {
    $text = if ($null -eq $Value) { '' } else { [string]$Value }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-Log([string] $Message) {
    [IO.File]::AppendAllText(
        $commandLog,
        (Get-Date).ToString('o') + ' [' + $Serial + '] ' + $Message + [Environment]::NewLine,
        $Utf8NoBom)
}

function Adb([string[]] $Arguments) {
    $output = @(& adb.exe -s $Serial @Arguments 2>&1)
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = ($output -join [Environment]::NewLine)
    }
}

function Get-Pid {
    $text = (Adb @('shell', 'pidof', $package)).Text.Trim()
    if ($text -match '^(\d+)') { return [int]$Matches[1] }
    return $null
}

function Stop-Harness {
    $null = Adb @('shell', 'am', 'force-stop', $package)
    for ($i = 0; $i -lt 40; $i++) {
        if ($null -eq (Get-Pid)) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Files {
    $listing = (Adb @('shell', 'ls', '-laR', $remoteRoot)).Text
    $names = @((Adb @('shell', 'find', $remoteRoot, '-maxdepth', '2', '-type', 'f')).Text -split "`r?`n" |
        Where-Object { $_ })
    $sizes = @{}
    foreach ($name in $names) {
        $sizes[$name] = (Adb @('shell', 'stat', '-c', '%s', $name)).Text.Trim()
    }
    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        listing = $listing
        files = $names
        sizes = $sizes
        wavFiles = @($names | Where-Object { $_ -match '\.wav$' })
        tempFiles = @($names | Where-Object { $_ -match '\.tmp$' })
        metricsFiles = @($names | Where-Object { $_ -match 'metrics_' })
    }
}

$active = @(Get-Content -LiteralPath 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md' -Encoding utf8 |
    Where-Object { $_ -match '^### .* — ACTIVE(?:\s|$)' })
if ($active.Count -ne 1 -or $active[0] -notmatch 'QAV2-K60-8e875894') { throw 'Lock gate failed' }

$null = Stop-Harness
$null = Adb @('shell', 'rm', '-rf', $remoteRoot)
Write-Log 'CANCEL cleared harness-only external root'
$null = Adb @('logcat', '-c')
$launch = Adb @('shell', 'am', 'start', '-W', '-n', "$package/.HarnessActivity", '--es', 'mode', 'zf')
Write-Log 'CANCEL start mode=zf'
$deadline = (Get-Date).AddSeconds(30)
do {
    Start-Sleep -Milliseconds 250
    $pidDuring = Get-Pid
} while ($null -eq $pidDuring -and (Get-Date) -lt $deadline)
if ($null -eq $pidDuring) { throw 'Harness PID did not appear' }
Start-Sleep -Seconds 8
$pidAtStop = Get-Pid
$filesBeforeStop = Files
$forceStop = Adb @('shell', 'am', 'force-stop', $package)
Write-Log 'CANCEL force-stop during inference'
$stopDeadline = (Get-Date).AddSeconds(12)
do {
    Start-Sleep -Milliseconds 300
    $pidAfterStop = Get-Pid
} while ($null -ne $pidAfterStop -and (Get-Date) -lt $stopDeadline)
Start-Sleep -Seconds 2
$filesImmediate = Files
Start-Sleep -Seconds 6
$filesSettled = Files
$logcat = @((Adb @('logcat', '-d', '-v', 'threadtime')).Text -split "`r?`n" |
    Where-Object {
        $_ -match 'KOKORO_HARNESS|dev\.aurorama\.kokoro\.harness|FATAL EXCEPTION|ANR in|NoClassDefFoundError|java\.util\.HexFormat'
    })
Write-Utf8 (Join-Path $deviceRoot 'logcat-K60-cancel-force-stop.txt') ($logcat -join [Environment]::NewLine)
$result = [ordered]@{
    schema = 'aurorama.kokoro-exact.independent-qa.v2.cancel'
    device = 'K60'
    serial = $Serial
    launchOutput = $launch.Text
    pidDuring = $pidDuring
    pidAtStop = $pidAtStop
    forceStopExit = $forceStop.ExitCode
    forceStopOutput = $forceStop.Text.Trim()
    pidAfterStop = $pidAfterStop
    forceStopConverged = ($null -eq $pidAfterStop)
    filesBeforeStop = $filesBeforeStop
    filesImmediate = $filesImmediate
    filesSettled = $filesSettled
    targetWavPresent = (@($filesSettled.wavFiles | Where-Object { $_ -match '/zf_001_sid3\.wav$' }).Count -gt 0)
    temporaryFilesPresent = ($filesSettled.tempFiles.Count -gt 0)
    targetMetricsPresent = (@($filesSettled.metricsFiles | Where-Object { $_ -match '/metrics_zf\.json$' }).Count -gt 0)
    failureMetricsPresent = (@($filesSettled.metricsFiles | Where-Object { $_ -match '/metrics_failure\.json$' }).Count -gt 0)
    internalCancelPresent = $false
    cancelSemantics = 'NOT_IMPLEMENTED / FORCED_CANCEL'
    logcat = (Join-Path $deviceRoot 'logcat-K60-cancel-force-stop.txt')
}
Write-Utf8 (Join-Path $deviceRoot 'cancel-K60.json') ($result | ConvertTo-Json -Depth 20)
Write-Log "CANCEL end converged=$($result.forceStopConverged) targetWav=$($result.targetWavPresent) temp=$($result.temporaryFilesPresent) targetMetrics=$($result.targetMetricsPresent)"
$result | ConvertTo-Json -Depth 20
