[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $LogcatPath,

    [Parameter(Mandatory = $true)]
    [long] $MainUid,

    [Parameter(Mandatory = $true)]
    [long] $PeerUid
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logPath = (Resolve-Path -LiteralPath $LogcatPath).Path
$mainPackage = 'dev.aurorama.kokoro.harness'
$peerPackage = 'dev.aurorama.kokoro.focuspeer'
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

$null = Invoke-Adb @('shell', 'am', 'force-stop', $peerPackage)
$null = Invoke-Adb @('shell', 'am', 'force-stop', $mainPackage)
$lineOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$mainLaunch = Invoke-Adb @(
    'shell', 'am', 'start', '-W', '--activity-single-top',
    '-n', "$mainPackage/.HarnessActivity",
    '--es', 'command', 'play_full')
if ($mainLaunch.exitCode -ne 0) {
    throw "Main play_full launch failed: $($mainLaunch.text)"
}

$deadline = (Get-Date).AddSeconds(60)
$firstStartLine = $null
do {
    Start-Sleep -Milliseconds 300
    $tail = @(Get-Content -LiteralPath $logPath -Encoding utf8 | Select-Object -Skip $lineOffset)
    $firstStartLine = $tail |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
        Select-Object -First 1
    if ($tail -match 'RUNTIME_FAILED') {
        throw 'Main runtime failed before focus test'
    }
} while ($null -eq $firstStartLine -and (Get-Date) -lt $deadline)
if ($null -eq $firstStartLine) {
    throw 'Main TTS did not start real AudioTrack before peer request'
}
$beforePeerState = Read-LatestState $tail
$beforeService = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $mainPackage)).text
$beforeMedia = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text

$peerLaunchAt = Get-Date
$peerLaunch = Invoke-Adb @(
    'shell', 'am', 'start', '-W', '-n',
    "$peerPackage/.FocusPeerActivity")
if ($peerLaunch.exitCode -ne 0) {
    throw "Focus peer launch failed: $($peerLaunch.text)"
}
Start-Sleep -Milliseconds 700
$audioDuringPeer = (Invoke-Adb @('shell', 'dumpsys', 'audio')).text
$focusLossDeadline = (Get-Date).AddSeconds(8)
$focusLossLine = $null
do {
    Start-Sleep -Milliseconds 250
    $peerWindowLines = @(Get-Content -LiteralPath $logPath -Encoding utf8 | Select-Object -Skip $lineOffset)
    $focusLossLine = $peerWindowLines |
        Select-String -Pattern 'KOKORO_RUNTIME: AUDIO_FOCUS_CHANGE change=-2 policy=LOSS_TRANSIENT' |
        Select-Object -First 1
} while ($null -eq $focusLossLine -and (Get-Date) -lt $focusLossDeadline)
if ($null -eq $focusLossLine) {
    throw 'Main TTS did not receive system LOSS_TRANSIENT from focus peer'
}
Start-Sleep -Seconds 11
$peerCompleteAt = Get-Date
Start-Sleep -Seconds 8
$allLines = @(Get-Content -LiteralPath $logPath -Encoding utf8 | Select-Object -Skip $lineOffset)
$afterPeerState = Read-LatestState $allLines
$peerReleaseLine = $allLines |
    Select-String -SimpleMatch 'PEER_FOCUS_ABANDONED' |
    Select-Object -Last 1
$mainPausedLine = $allLines |
    Select-String -Pattern 'KOKORO_RUNTIME: TTS_PAUSED' |
    Select-Object -First 1
$focusLossHandlerLine = $allLines |
    Select-String -SimpleMatch 'KOKORO_RUNTIME: AUDIO_FOCUS_LOSS source=system_change_-2' |
    Select-Object -First 1
$mainAbandonLine = $allLines |
    Select-String -SimpleMatch 'KOKORO_RUNTIME: AUDIO_FOCUS_ABANDONED' |
    Select-Object -First 1
$peerRequestLines = @(
    $allLines |
        Select-String -Pattern (
            'PEER_FOCUS_REQUEST result=1 focus=GAIN_TRANSIENT uid=' +
            [regex]::Escape([string]$PeerUid))
)
$peerAudioLines = @(
    $allLines |
        Select-String -SimpleMatch 'PEER_AUDIO_TRACK_STARTED' |
        ForEach-Object Line
)
$startsAfterRelease = @()
if ($null -ne $peerReleaseLine) {
    $releasePosition = [array]::IndexOf($allLines, $peerReleaseLine.Line)
    $startsAfterRelease = @(
        $allLines |
            Select-Object -Skip ($releasePosition + 1) |
            Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS'
    )
}
$afterService = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $mainPackage)).text
$afterMedia = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
$afterServiceEvidence = Get-LiveForegroundServiceEvidence $afterService
$afterMediaEvidence = Get-MediaSessionEvidence `
    -Text $afterMedia `
    -Packages @($mainPackage)
$activeMainSessions = @(
    $afterMediaEvidence.activeSessions |
        Where-Object package -eq $mainPackage
)
$installGatePath = Join-Path $outputPath 'install-gate.json'
$manifestMediaPlaybackType =
    (Test-Path -LiteralPath $installGatePath) -and
    [bool](
        (Get-Content -LiteralPath $installGatePath -Raw -Encoding utf8 |
            ConvertFrom-Json).mediaPlaybackForegroundServiceTypeMatches)
$focusStackSources = @(
    $audioDuringPeer -split "`r?`n" |
        Where-Object { $_ -match '^\s*source:' }
)
$focusStackLines = @(
    $audioDuringPeer -split "`r?`n" |
        Where-Object {
            $_ -match 'Audio Focus stack entries|^\s*source:|pack: dev\.aurorama\.kokoro|uid: (?:' +
                [regex]::Escape([string]$MainUid) + '|' +
                [regex]::Escape([string]$PeerUid) + ')'
        } |
        Select-Object -First 80
)
$topFocusSource = if ($focusStackSources.Count -gt 0) {
    $focusStackSources[$focusStackSources.Count - 1]
} else {
    ''
}
$peerIsTopAudioFocusSource =
    $topFocusSource -match [regex]::Escape($peerPackage) -or
    $topFocusSource -match ('\buid:\s*' + [regex]::Escape([string]$PeerUid) + '\b')
$null = Invoke-Adb @('shell', 'am', 'force-stop', $peerPackage)

$checks = [ordered]@{
    mainWasPlayingBeforePeer =
        $beforePeerState.status -in @('PLAYING', 'PREPARING') -and
        $beforePeerState.activeAudioOwner -eq 'TTS' -and
        $beforePeerState.focusHeld -eq $true
    peerFocusGranted = $peerRequestLines.Count -gt 0
    audioFocusStackContainsPeer = $peerIsTopAudioFocusSource
    peerRealAudioStarted = $peerAudioLines.Count -gt 0
    systemOriginLossTransient = $null -ne $focusLossLine
    mainPausedOnLoss =
        $null -ne $focusLossHandlerLine -and
        $afterPeerState.status -eq 'PAUSED'
    mainAbandonedFocus = $null -ne $mainAbandonLine
    peerReleasedFocus = $null -ne $peerReleaseLine
    remainedPausedAfterPeerRelease =
        $afterPeerState.status -eq 'PAUSED' -and
        $afterPeerState.autoPlay -eq $false
    audioTrackStoppedAfterPeerRelease = $afterPeerState.audioTrackPlaying -eq $false
    focusReleasedAfterPeerRelease = $afterPeerState.focusHeld -eq $false
    noMainAudioRestartAfterPeerRelease = $startsAfterRelease.Count -eq 0
    mainForegroundStillOwned =
        [bool]$afterServiceEvidence.servicePresent -and
        [bool]$afterServiceEvidence.isForeground -and
        $manifestMediaPlaybackType
    singleActiveMainMediaSession =
        $activeMainSessions.Count -eq 1
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.os-focus-peer.v1'
    status = if ($passed) { 'PASS_SYSTEM_FOCUS_LOSS_NO_AUTOPLAY' } else { 'FAIL' }
    serial = $Serial
    peerLaunchAt = $peerLaunchAt.ToString('o')
    peerCompletionObservedAt = $peerCompleteAt.ToString('o')
    firstStartLine = $firstStartLine.Line
    peerFocusRequestLines = @($peerRequestLines | ForEach-Object Line)
    peerAudioStartLines = $peerAudioLines
    focusLossLine = if ($focusLossLine) { $focusLossLine.Line } else { $null }
    focusLossHandlerLine =
        if ($focusLossHandlerLine) { $focusLossHandlerLine.Line } else { $null }
    mainPausedLine = if ($mainPausedLine) { $mainPausedLine.Line } else { $null }
    mainAbandonFocusLine = if ($mainAbandonLine) { $mainAbandonLine.Line } else { $null }
    peerReleaseLine = if ($peerReleaseLine) { $peerReleaseLine.Line } else { $null }
    audioFocusStackDuringPeer = @(
        $focusStackLines
    )
    audioFocusTopSource = $topFocusSource
    beforePeerState = $beforePeerState
    afterPeerState = $afterPeerState
    foregroundService = $afterServiceEvidence
    mediaSession = $afterMediaEvidence
    manifestMediaPlaybackForegroundType = $manifestMediaPlaybackType
    checks = $checks
    mainStartsAfterPeerRelease = @($startsAfterRelease | ForEach-Object Line)
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'focus-peer.json'),
    ($result | ConvertTo-Json -Depth 20),
    $utf8)
$result | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
