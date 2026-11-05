[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $LogcatPath
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logPath = (Resolve-Path -LiteralPath $LogcatPath).Path
$package = 'dev.aurorama.kokoro.harness'
$utf8 = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Read-LatestState([string[]] $Lines) {
    $matches = @($Lines | Select-String -Pattern 'KOKORO_RUNTIME: STATE (\{[^\r\n]+\})')
    if ($matches.Count -eq 0) {
        return $null
    }
    try {
        return $matches[$matches.Count - 1].Matches[0].Groups[1].Value |
            ConvertFrom-Json -Depth 20
    } catch {
        return $null
    }
}

$beforeLines = @(Get-Content -LiteralPath $logPath -Encoding utf8)
$before = Read-LatestState $beforeLines
if ($null -eq $before -or $before.status -ne 'PAUSED') {
    throw "Recreate precondition is not a persisted PAUSED state: $($before.status)"
}
$beforeStableIndex = [int]$before.stableIndex
$beforeSentence = [string]$before.stableSentence

$forceStop = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$forceStopDeadline = (Get-Date).AddSeconds(10)
$pidAfterStop = ''
$serviceAfterStop = ''
$forceStopPollCount = 0
do {
    $forceStopPollCount++
    Start-Sleep -Milliseconds 500
    $pidAfterStop = ((Invoke-Adb @('shell', 'pidof', $package)).text).Trim()
    $serviceAfterStop = (Invoke-Adb @(
        'shell', 'dumpsys', 'activity', 'services', $package)).text
    $serviceCleared =
        $serviceAfterStop -notmatch 'ServiceRecord\{.*KokoroRuntimeService'
} while (
    (
        -not [string]::IsNullOrWhiteSpace($pidAfterStop) -or
        -not $serviceCleared
    ) -and
    (Get-Date) -lt $forceStopDeadline
)

$launchOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$launch = Invoke-Adb @(
    'shell', 'am', 'start', '-W', '--activity-single-top',
    '-n', "$package/.HarnessActivity")
if ($launch.exitCode -ne 0) {
    throw "Manual Activity launch failed: $($launch.text)"
}

$deadline = (Get-Date).AddSeconds(30)
$restoreLine = $null
do {
    Start-Sleep -Milliseconds 250
    $launchLines = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $launchOffset
    )
    $restoreLine = $launchLines |
        Select-String -SimpleMatch 'KOKORO_RUNTIME: RESUME_RESTORED_PAUSED' |
        Select-Object -First 1
    if ($launchLines -match 'KOKORO_RUNTIME: RESUME_REJECTED') {
        throw 'Valid paused checkpoint was unexpectedly rejected after force-stop'
    }
} while ($null -eq $restoreLine -and (Get-Date) -lt $deadline)
if ($null -eq $restoreLine) {
    throw 'Paused checkpoint was not restored within 30 seconds'
}

Start-Sleep -Seconds 8
$allLaunchLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $launchOffset
)
$after = Read-LatestState $allLaunchLines
$audioStartsAfterLaunch = @(
    $allLaunchLines |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS'
)
$serviceAfter = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $package)).text
$mediaAfter = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
$notificationAfter = (Invoke-Adb @('shell', 'dumpsys', 'notification', '--noredact')).text
$pidAfterLaunch = ((Invoke-Adb @('shell', 'pidof', $package)).text).Trim()
$serviceEvidence = Get-LiveForegroundServiceEvidence $serviceAfter
$mediaEvidence = Get-MediaSessionEvidence `
    -Text $mediaAfter `
    -Packages @($package)
$activeMainSessions = @(
    $mediaEvidence.activeSessions |
        Where-Object package -eq $package
)
$installGatePath = Join-Path $outputPath 'install-gate.json'
$manifestMediaPlaybackType =
    (Test-Path -LiteralPath $installGatePath) -and
    [bool](
        (Get-Content -LiteralPath $installGatePath -Raw -Encoding utf8 |
            ConvertFrom-Json).mediaPlaybackForegroundServiceTypeMatches)

$checks = [ordered]@{
    forceStopClearedPid = [string]::IsNullOrWhiteSpace($pidAfterStop)
    forceStopClearedService =
        $serviceAfterStop -notmatch 'ServiceRecord\{.*KokoroRuntimeService'
    restoreLogObserved = $null -ne $restoreLine
    restoredPaused = $after.status -eq 'PAUSED'
    sameStableIndex = [int]$after.stableIndex -eq $beforeStableIndex
    sameStableSentence = [string]$after.stableSentence -eq $beforeSentence
    noAutoplay = $after.autoPlay -eq $false
    noAudioTrackAfterEightSeconds = $audioStartsAfterLaunch.Count -eq 0
    focusNotHeld = $after.focusHeld -eq $false
    audioTrackNotPlaying = $after.audioTrackPlaying -eq $false
    foregroundPausedOwnerKept =
        [bool]$serviceEvidence.servicePresent -and
        [bool]$serviceEvidence.isForeground -and
        $manifestMediaPlaybackType
    mediaSessionPaused =
        $activeMainSessions.Count -eq 1 -and
        $activeMainSessions[0].playbackStateLabel -eq 'PAUSED'
    notificationPresent =
        $notificationAfter -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
    processAlive = -not [string]::IsNullOrWhiteSpace($pidAfterLaunch)
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.recreate-paused.v1'
    status = if ($passed) { 'PASS_PAUSED_RECONSTRUCTION_NO_AUTOPLAY' } else { 'FAIL' }
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    before = $before
    forceStopExitCode = $forceStop.exitCode
    forceStopPollCount = $forceStopPollCount
    pidAfterForceStop = if ($pidAfterStop) { $pidAfterStop } else { $null }
    manualLaunchExitCode = $launch.exitCode
    manualLaunchOutput = $launch.text
    restoreLine = if ($restoreLine) { $restoreLine.Line } else { $null }
    after = $after
    foregroundService = $serviceEvidence
    mediaSession = $mediaEvidence
    manifestMediaPlaybackForegroundType = $manifestMediaPlaybackType
    audioStartsAfterManualLaunch = @($audioStartsAfterLaunch | ForEach-Object Line)
    checks = $checks
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'recreate-paused.json'),
    ($result | ConvertTo-Json -Depth 20),
    $utf8)
$result | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
