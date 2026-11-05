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

function Get-StartedAudioPlaybacks([string] $Text, [string] $Uid) {
    return @(
        $Text -split "`r?`n" |
            Where-Object {
                $_ -match 'AudioPlaybackConfiguration' -and
                $_ -match ('u/pid:' + [regex]::Escape($Uid) + '/\d+') -and
                $_ -match '\bstate:started\b'
            } |
            ForEach-Object {
                [ordered]@{
                    line = $_
                    contentType = [regex]::Match(
                        $_,
                        'content=([A-Z_]+)').Groups[1].Value
                }
            }
    )
}

function Send-Command([string] $Command) {
    return Invoke-Adb @(
        'shell', 'am', 'start', '-W', '--activity-single-top',
        '-n', "$package/.HarnessActivity",
        '--es', 'command', $Command)
}

function Wait-ForPattern(
    [int] $Offset,
    [string] $Pattern,
    [int] $TimeoutSeconds
) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $line = $null
    do {
        Start-Sleep -Milliseconds 250
        $lines = @(
            Get-Content -LiteralPath $logPath -Encoding utf8 |
                Select-Object -Skip $Offset
        )
        $line = $lines | Select-String -Pattern $Pattern | Select-Object -First 1
    } while ($null -eq $line -and (Get-Date) -lt $deadline)
    return $line
}

$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$offset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$ttsLaunch = Send-Command 'play_full'
if ($ttsLaunch.exitCode -ne 0) {
    throw "Initial TTS launch failed: $($ttsLaunch.text)"
}
$firstTtsStart = Wait-ForPattern $offset 'AUDIO_TRACK_STARTED owner=TTS' 60
if ($null -eq $firstTtsStart) {
    throw 'Initial TTS did not start real audio'
}
$initialState = Read-LatestState @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $offset
)

$musicCommandOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$musicLaunch = Send-Command 'music_play'
if ($musicLaunch.exitCode -ne 0) {
    throw "music_play failed: $($musicLaunch.text)"
}
Start-Sleep -Seconds 2
$musicState = Read-LatestState @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $musicCommandOffset
)
$audioDuringMusic = (Invoke-Adb @('shell', 'dumpsys', 'audio')).text

$ttsCommandOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$secondTtsLaunch = Send-Command 'play_sample'
if ($secondTtsLaunch.exitCode -ne 0) {
    throw "Second TTS launch failed: $($secondTtsLaunch.text)"
}
$secondTtsStart = Wait-ForPattern $ttsCommandOffset 'AUDIO_TRACK_STARTED owner=TTS' 60
if ($null -eq $secondTtsStart) {
    throw 'TTS did not take ownership back from music'
}
$ttsOverMusicState = Read-LatestState @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $ttsCommandOffset
)
$audioDuringTtsOverMusic = (Invoke-Adb @('shell', 'dumpsys', 'audio')).text
$installGatePath = Join-Path $outputPath 'install-gate.json'
$mainUid = if (Test-Path -LiteralPath $installGatePath) {
    [string](Get-Content -LiteralPath $installGatePath -Raw -Encoding utf8 |
        ConvertFrom-Json).main.uid
} else {
    ''
}
$musicPlaybacks = Get-StartedAudioPlaybacks $audioDuringMusic $mainUid
$ttsPlaybacks = Get-StartedAudioPlaybacks $audioDuringTtsOverMusic $mainUid
$mediaDuringText = (Invoke-Adb @(
    'shell', 'dumpsys', 'media_session')).text
$mediaDuring = Get-MediaSessionEvidence `
    -Text $mediaDuringText `
    -Packages @($package)
$activeMainSessionsDuring = @(
    $mediaDuring.activeSessions |
        Where-Object package -eq $package
)

$stopOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$stopLaunch = Send-Command 'stop'
if ($stopLaunch.exitCode -ne 0) {
    throw "TTS stop failed: $($stopLaunch.text)"
}
Start-Sleep -Seconds 2
$afterStopState = Read-LatestState @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $stopOffset
)
Start-Sleep -Seconds 5
$afterStopLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $stopOffset
)
$musicRestartAfterStop = @(
    $afterStopLines |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=MUSIC'
)
$allLines = @(Get-Content -LiteralPath $logPath -Encoding utf8 | Select-Object -Skip $offset)
$musicStarts = @($allLines | Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=MUSIC')
$ttsStarts = @($allLines | Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS')
$arbitrationLines = @(
    $allLines |
        Select-String -Pattern @(
            'MUSIC_PAUSED_FOR_TTS',
            'TTS_TERMINATED_BEFORE_MUSIC',
            'MUSIC_TRACK_REQUEST',
            'TTS_STOPPED music=',
            'AUDIO_TRACK_STARTED owner=(TTS|MUSIC)'
        ) |
        ForEach-Object Line
)
$serviceText = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $package)).text
$mediaText = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
$mediaAfter = Get-MediaSessionEvidence `
    -Text $mediaText `
    -Packages @($package)
$activeMainSessionsAfter = @(
    $mediaAfter.activeSessions |
        Where-Object package -eq $package
)

$checks = [ordered]@{
    initialTtsOwner =
        $initialState.activeAudioOwner -eq 'TTS' -and
        $initialState.status -in @('PLAYING', 'PREPARING')
    musicTerminatedTts =
        $musicState.activeAudioOwner -eq 'MUSIC' -and
        $musicState.status -eq 'PLAYING'
    ttsPausedMusic =
        $ttsOverMusicState.activeAudioOwner -eq 'TTS' -and
        $ttsOverMusicState.musicStatus -eq 'PAUSED'
    musicQueueRetained = [int]$ttsOverMusicState.musicIndex -eq [int]$musicState.musicIndex
    stopLeftMusicPaused =
        $afterStopState.status -eq 'STOPPED' -and
        $afterStopState.activeAudioOwner -eq 'NONE' -and
        $afterStopState.musicStatus -eq 'PAUSED'
    noMusicAutoplayAfterTtsStop = $musicRestartAfterStop.Count -eq 0
    realMusicAudioObserved = $musicStarts.Count -gt 0
    realTtsAudioObserved = $ttsStarts.Count -gt 0
    singleStartedAudioPlaybackDuringMusic =
        $mainUid -match '^\d+$' -and
        $musicPlaybacks.Count -eq 1 -and
        $musicPlaybacks[0].contentType -eq 'CONTENT_TYPE_MUSIC'
    singleStartedAudioPlaybackDuringTts =
        $mainUid -match '^\d+$' -and
        $ttsPlaybacks.Count -eq 1 -and
        $ttsPlaybacks[0].contentType -eq 'CONTENT_TYPE_SPEECH'
    singleActiveMainMediaSessionDuring = $activeMainSessionsDuring.Count -eq 1
    noActiveMainMediaSessionAfterStop = $activeMainSessionsAfter.Count -eq 0
    serviceRetainsOnlyPausedOwner = $serviceText -notmatch 'isForeground=true'
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.music-tts-arbitration.v1'
    status = if ($passed) { 'PASS_SINGLE_AUDIO_OWNER_MUSIC_REMAINS_PAUSED' } else { 'FAIL' }
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    initialState = $initialState
    musicState = $musicState
    ttsOverMusicState = $ttsOverMusicState
    afterTtsStopState = $afterStopState
    musicRestartAfterStopLines = @($musicRestartAfterStop | ForEach-Object Line)
    arbitrationLines = $arbitrationLines
    mediaSessionDuring = $mediaDuring
    mediaSessionAfter = $mediaAfter
    audioPlaybackConfigurations = [ordered]@{
        uid = $mainUid
        music = $musicPlaybacks
        ttsOverMusic = $ttsPlaybacks
    }
    checks = $checks
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'music-tts-arbitration.json'),
    ($result | ConvertTo-Json -Depth 20),
    $utf8)
$result | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
