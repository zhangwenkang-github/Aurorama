[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $LogcatPath,

    [int] $Minutes = 30
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logPath = (Resolve-Path -LiteralPath $LogcatPath).Path
$package = 'dev.aurorama.kokoro.harness'
$utf8 = [Text.UTF8Encoding]::new($false)
$samplePath = Join-Path $outputPath 'background-longrun-samples.jsonl'
$summaryPath = Join-Path $outputPath 'background-longrun.json'
$startPath = Join-Path $outputPath 'background-longrun-start.json'

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Read-Setting([string] $Namespace, [string] $Key) {
    return ((Invoke-Adb @('shell', 'settings', 'get', $Namespace, $Key)).text).Trim()
}

function Read-DeviceTimestamp([string] $Line) {
    $match = [regex]::Match(
        $Line,
        '^(?:\d+\s+)?(\d\d)-(\d\d)\s+(\d\d:\d\d:\d\d\.\d{3})')
    if (-not $match.Success) {
        return $null
    }
    $timestamp = "$(Get-Date -Format yyyy)-$($match.Groups[1].Value)-$($match.Groups[2].Value) $($match.Groups[3].Value)"
    return [datetime]::ParseExact(
        $timestamp,
        'yyyy-MM-dd HH:mm:ss.fff',
        [Globalization.CultureInfo]::InvariantCulture)
}

function Read-LatestState([string[]] $Lines) {
    $matches = @(
        $Lines |
            Select-String -Pattern 'KOKORO_RUNTIME: STATE (\{[^\r\n]+\})'
    )
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

function Get-RuntimeSample([datetime] $ScreenOffAt) {
    $now = Get-Date
    $powerText = (Invoke-Adb @('shell', 'dumpsys', 'power')).text
    $serviceText = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $package)).text
    $mediaText = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
    $notificationText = (Invoke-Adb @('shell', 'dumpsys', 'notification', '--noredact')).text
    $appPid = ((Invoke-Adb @('shell', 'pidof', $package)).text).Trim()
    $lines = @(Get-Content -LiteralPath $logPath -Encoding utf8)
    $state = Read-LatestState $lines
    $wakefulness = [regex]::Match($powerText, 'mWakefulness=([^\s]+)').Groups[1].Value
    $mediaStates = @(
        [regex]::Matches($mediaText, 'state=PlaybackState \{state=([A-Z_]+)') |
            ForEach-Object { $_.Groups[1].Value }
    )
    [ordered]@{
        at = $now.ToString('o')
        elapsedSeconds = [Math]::Round(($now - $ScreenOffAt).TotalSeconds, 3)
        appPid = if ($appPid) { $appPid } else { $null }
        wakefulness = $wakefulness
        screenAsleep = $wakefulness -eq 'Asleep'
        serviceForeground = $serviceText -match 'isForeground=true'
        serviceMediaPlaybackType = $serviceText -match 'types=0x00000002'
        mediaSessionPresent = $mediaText -match 'Kokoro isolated runtime'
        mediaSessionActive = $mediaText -match 'Kokoro isolated runtime[\s\S]{0,1200}active=true|active=true[\s\S]{0,1200}Kokoro isolated runtime'
        mediaStates = $mediaStates
        notificationPresent = $notificationText -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
        status = if ($state) { [string]$state.status } else { $null }
        activeAudioOwner = if ($state) { [string]$state.activeAudioOwner } else { $null }
        queueIndex = if ($state) { [int]$state.queueIndex } else { $null }
        queueSize = if ($state) { [int]$state.queueSize } else { $null }
        longRunAudioMillis = if ($state) { [long]$state.longRunAudioMillis } else { $null }
        focusHeld = if ($state) { [bool]$state.focusHeld } else { $null }
        audioTrackPlaying = if ($state) { [bool]$state.audioTrackPlaying } else { $null }
        autoPlay = if ($state) { [bool]$state.autoPlay } else { $null }
        lastError = if ($state) { [string]$state.lastError } else { $null }
    }
}

$null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse')
$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$originalStayOn = Read-Setting 'global' 'stay_on_while_plugged_in'
$setStayOn = Invoke-Adb @(
    'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '0')
$monitorStayOn = Read-Setting 'global' 'stay_on_while_plugged_in'
if ($setStayOn.exitCode -ne 0 -or $monitorStayOn -ne '0') {
    throw "Could not neutralize stay-on while plugged in: $monitorStayOn"
}

$lineOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$launch = Invoke-Adb @(
    'shell', 'am', 'start', '-W', '--activity-single-top',
    '-n', "$package/.HarnessActivity",
    '--es', 'command', 'play_long')
if ($launch.exitCode -ne 0) {
    throw "play_long launch failed: $($launch.text)"
}

$deadline = (Get-Date).AddMinutes(2)
$firstAudioLine = $null
do {
    Start-Sleep -Milliseconds 300
    $newLines = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $lineOffset
    )
    $firstAudioLine = $newLines |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
        Select-Object -First 1
    if ($newLines -match 'RUNTIME_FAILED') {
        throw 'play_long failed before first AudioTrack'
    }
} while ($null -eq $firstAudioLine -and (Get-Date) -lt $deadline)
if ($null -eq $firstAudioLine) {
    throw 'No real TTS AudioTrack started before screen-off'
}

$initialLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $lineOffset
)
$initialState = Read-LatestState $initialLines
$screenOff = Invoke-Adb @('shell', 'input', 'keyevent', '26')
$screenOffAt = Get-Date
Start-Sleep -Seconds 4
$offSample = Get-RuntimeSample $screenOffAt
if (-not $offSample.screenAsleep) {
    $null = Invoke-Adb @('shell', 'input', 'keyevent', '224')
    $null = Invoke-Adb @(
        'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', $originalStayOn)
    throw "Screen did not enter Asleep: $($offSample.wakefulness)"
}

$startEvidence = [ordered]@{
    schema = 'aurorama.kokoro-qa-v6.background-longrun-start.v1'
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    screenOffAt = $screenOffAt.ToString('o')
    requestedMinutes = $Minutes
    originalStayOn = $originalStayOn
    monitorStayOn = $monitorStayOn
    logLineOffset = $lineOffset
    launchExitCode = $launch.exitCode
    launchOutput = $launch.text
    firstAudioLine = $firstAudioLine.Line
    initialState = $initialState
    screenOffSample = $offSample
}
if (
    $null -eq $initialState -or
    $initialState.speaker -ne 'zf_001' -or
    [int]$initialState.sid -ne 3
) {
    throw "Long-run voice gate failed: expected zf_001/sid3"
}
[IO.File]::WriteAllText(
    $startPath,
    ($startEvidence | ConvertTo-Json -Depth 20),
    $utf8)
[IO.File]::WriteAllText($samplePath, '', $utf8)

$samples = [Collections.Generic.List[object]]::new()
$targetSeconds = $Minutes * 60
$sampleIntervalSeconds = 60
$sampleNumber = 0
do {
    $sampleNumber++
    $waitSeconds = [Math]::Max(
        1,
        [Math]::Min(
            $sampleIntervalSeconds,
            [int][Math]::Ceiling(($screenOffAt.AddSeconds($sampleNumber * $sampleIntervalSeconds) - (Get-Date)).TotalSeconds)))
    Start-Sleep -Seconds $waitSeconds
    $sample = Get-RuntimeSample $screenOffAt
    $samples.Add($sample)
    [IO.File]::AppendAllText(
        $samplePath,
        ($sample | ConvertTo-Json -Compress -Depth 12) + "`n",
        $utf8)
    Write-Output (
        'CHECKPOINT elapsed={0:N0}s pid={1} asleep={2} fgs={3} media={4} queue={5}/{6} audioMs={7} status={8} owner={9}' -f
            $sample.elapsedSeconds,
            $sample.appPid,
            $sample.screenAsleep,
            $sample.serviceForeground,
            ($sample.mediaStates -join ','),
            $sample.queueIndex,
            $sample.queueSize,
            $sample.longRunAudioMillis,
            $sample.status,
            $sample.activeAudioOwner)
} while ($sample.elapsedSeconds -lt $targetSeconds)

$windowEnd = Get-Date
$allWindowLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $lineOffset
)
$audioEvents = [Collections.Generic.List[object]]::new()
foreach ($line in $allWindowLines) {
    if ($line -notmatch 'AUDIO_TRACK_(STARTED|COMPLETED) owner=TTS') {
        continue
    }
    $eventAt = Read-DeviceTimestamp $line
    if ($null -eq $eventAt -or $eventAt -gt $windowEnd) {
        continue
    }
    $eventType = if ($line -match 'AUDIO_TRACK_STARTED') { 'STARTED' } else { 'COMPLETED' }
    $audioEvents.Add([ordered]@{
        at = $eventAt.ToString('o')
        offsetSeconds = [Math]::Round(($eventAt - $screenOffAt).TotalSeconds, 3)
        eventType = $eventType
        line = $line
    })
}

$windowEvents = @($audioEvents | Where-Object { $_.offsetSeconds -ge 0 })
$boundaryEvents = @(
    $audioEvents |
        Where-Object { $_.offsetSeconds -lt 0 } |
        Select-Object -Last 1
) + @($windowEvents)
$maximumGapMs = $null
$maximumGapFrom = $null
$maximumGapTo = $null
for ($eventIndex = 1; $eventIndex -lt $boundaryEvents.Count; $eventIndex++) {
    $fromAt = [datetime]::Parse($boundaryEvents[$eventIndex - 1].at)
    $toAt = [datetime]::Parse($boundaryEvents[$eventIndex].at)
    $gapMs = [long]($toAt - $fromAt).TotalMilliseconds
    if ($null -eq $maximumGapMs -or $gapMs -gt $maximumGapMs) {
        $maximumGapMs = $gapMs
        $maximumGapFrom = $boundaryEvents[$eventIndex - 1]
        $maximumGapTo = $boundaryEvents[$eventIndex]
    }
}

$finalSleepingSample = $samples[$samples.Count - 1]
$finalState = Read-LatestState $allWindowLines
$completionLines = @(
    $allWindowLines |
        Select-String -SimpleMatch 'TTS_COMPLETED' |
        Where-Object {
            $eventAt = Read-DeviceTimestamp $_.Line
            $null -ne $eventAt -and
            $eventAt -ge $screenOffAt -and
            $eventAt -le $windowEnd
        }
)
$terminalStateSamples = @(
    $samples |
        Where-Object {
            $_.status -in @('STOPPED', 'COMPLETED', 'ERROR', 'IDLE') -or
            $_.activeAudioOwner -ne 'TTS'
        }
)
$fatalSamples = @(
    $allWindowLines |
        Select-String -Pattern 'FATAL EXCEPTION|ANR in dev\.aurorama\.kokoro\.harness|InputTimeout|Input dispatching timed out'
)
$segmentFirst = @($windowEvents | Where-Object { $_.offsetSeconds -ge 0 -and $_.offsetSeconds -lt 60 })
$segmentMid = @($windowEvents | Where-Object { $_.offsetSeconds -ge 870 -and $_.offsetSeconds -lt 930 })
$segmentFinal = @($windowEvents | Where-Object { $_.offsetSeconds -ge 1740 })
$screenViolationCount = @($samples | Where-Object { -not $_.screenAsleep }).Count
$foregroundViolationCount = @($samples | Where-Object { -not $_.serviceForeground }).Count
$mediaViolationCount = @($samples | Where-Object { -not $_.mediaSessionPresent }).Count
$mediaActiveViolationCount = @($samples | Where-Object { -not $_.mediaSessionActive }).Count
$notificationViolationCount = @($samples | Where-Object { -not $_.notificationPresent }).Count
$pidViolationCount = @($samples | Where-Object { -not $_.appPid }).Count
$uniquePidCount = @(
    $samples |
        ForEach-Object appPid |
        Sort-Object -Unique
).Count
$queueValues = @($samples | ForEach-Object { [int]$_.queueIndex })
$queueMonotonic = $true
for ($queueIndex = 1; $queueIndex -lt $queueValues.Count; $queueIndex++) {
    if ($queueValues[$queueIndex] -lt $queueValues[$queueIndex - 1]) {
        $queueMonotonic = $false
    }
}
$queueProgressed =
    $null -ne $finalState -and
    [int]$finalState.queueIndex -gt [int]$initialState.queueIndex
$segmentFirstStarts = @($segmentFirst | Where-Object eventType -eq 'STARTED').Count
$segmentFirstCompletions = @($segmentFirst | Where-Object eventType -eq 'COMPLETED').Count
$segmentMidStarts = @($segmentMid | Where-Object eventType -eq 'STARTED').Count
$segmentMidCompletions = @($segmentMid | Where-Object eventType -eq 'COMPLETED').Count
$segmentFinalStarts = @($segmentFinal | Where-Object eventType -eq 'STARTED').Count
$segmentFinalCompletions = @($segmentFinal | Where-Object eventType -eq 'COMPLETED').Count
$audioStartCount = @($windowEvents | Where-Object eventType -eq 'STARTED').Count
$audioCompletionCount = @($windowEvents | Where-Object eventType -eq 'COMPLETED').Count
$audioCountInvariant =
    $audioStartCount -gt 0 -and
    $audioCompletionCount -gt 0 -and
    [Math]::Abs($audioStartCount - $audioCompletionCount) -le 1
$segmentAudioEvidence =
    $segmentFirstStarts -gt 0 -and $segmentFirstCompletions -gt 0 -and
    $segmentMidStarts -gt 0 -and $segmentMidCompletions -gt 0 -and
    $segmentFinalStarts -gt 0 -and $segmentFinalCompletions -gt 0
$audioEventsPath = Join-Path $outputPath 'background-longrun-audio-events.json'
[IO.File]::WriteAllText(
    $audioEventsPath,
    (($audioEvents | ConvertTo-Json -Depth 8)),
    $utf8)
$finalWake = Invoke-Adb @('shell', 'input', 'keyevent', '224')
Start-Sleep -Seconds 3
$restoreStayOn = Invoke-Adb @(
    'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', $originalStayOn)
$restoredStayOn = Read-Setting 'global' 'stay_on_while_plugged_in'
$finalPower = (Invoke-Adb @('shell', 'dumpsys', 'power')).text
$finalWakefulness = [regex]::Match($finalPower, 'mWakefulness=([^\s]+)').Groups[1].Value
$windowPolicy = (Invoke-Adb @('shell', 'dumpsys', 'window', 'policy')).text
$keyguardShowing = $windowPolicy -match 'KeyguardStateMonitor[\s\S]*?mIsShowing=true|showing=true'

$passed =
    $samples.Count -ge [Math]::Floor($targetSeconds / $sampleIntervalSeconds) -and
    $screenViolationCount -eq 0 -and
    $foregroundViolationCount -eq 0 -and
    $mediaViolationCount -eq 0 -and
    $mediaActiveViolationCount -eq 0 -and
    $notificationViolationCount -eq 0 -and
    $pidViolationCount -eq 0 -and
    $uniquePidCount -eq 1 -and
    $queueMonotonic -and
    $queueProgressed -and
    $audioCountInvariant -and
    $segmentAudioEvidence -and
    $completionLines.Count -eq 0 -and
    $terminalStateSamples.Count -eq 0 -and
    $segmentFirst.Count -gt 0 -and
    $segmentMid.Count -gt 0 -and
    $segmentFinal.Count -gt 0 -and
    $fatalSamples.Count -eq 0 -and
    $restoredStayOn -eq $originalStayOn

$summary = [ordered]@{
    schema = 'aurorama.kokoro-qa-v6.background-longrun.v1'
    status = if ($passed) { 'PASS_30MIN_REAL_AUDIOTRACK_OWNERSHIP' } else { 'FAIL' }
    serial = $Serial
    screenOffAt = $screenOffAt.ToString('o')
    windowEnd = $windowEnd.ToString('o')
    requestedMinutes = $Minutes
    observedSeconds = [Math]::Round(($windowEnd - $screenOffAt).TotalSeconds, 3)
    lineOffset = $lineOffset
    firstAudioLine = $firstAudioLine.Line
    sampleCount = $samples.Count
    screenViolationCount = $screenViolationCount
    foregroundViolationCount = $foregroundViolationCount
    mediaSessionViolationCount = $mediaViolationCount
    mediaSessionActiveViolationCount = $mediaActiveViolationCount
    notificationViolationCount = $notificationViolationCount
    pidViolationCount = $pidViolationCount
    uniquePidCount = $uniquePidCount
    pidValues = @($samples | ForEach-Object appPid | Sort-Object -Unique)
    queueMonotonic = $queueMonotonic
    queueProgressed = $queueProgressed
    initialQueueIndex = $initialState.queueIndex
    terminalStateSampleCount = $terminalStateSamples.Count
    completionCountInWindow = $completionLines.Count
    audioEventCountInWindow = $windowEvents.Count
    audioStartCountInWindow = $audioStartCount
    audioCompletionCountInWindow = $audioCompletionCount
    audioCountInvariant = $audioCountInvariant
    firstSegmentEventCount = $segmentFirst.Count
    midSegmentEventCount = $segmentMid.Count
    finalSegmentEventCount = $segmentFinal.Count
    firstSegmentStarts = $segmentFirstStarts
    firstSegmentCompletions = $segmentFirstCompletions
    midSegmentStarts = $segmentMidStarts
    midSegmentCompletions = $segmentMidCompletions
    finalSegmentStarts = $segmentFinalStarts
    finalSegmentCompletions = $segmentFinalCompletions
    segmentAudioEvidence = $segmentAudioEvidence
    maximumAudioEventGapMs = $maximumGapMs
    maximumGapFrom = $maximumGapFrom
    maximumGapTo = $maximumGapTo
    finalQueueIndex = $finalState.queueIndex
    finalQueueSize = $finalState.queueSize
    finalLongRunAudioMillis = $finalState.longRunAudioMillis
    finalStateStatus = $finalState.status
    finalStateActiveAudioOwner = $finalState.activeAudioOwner
    finalStateAudioTrackPlaying = $finalState.audioTrackPlaying
    finalSleepingSample = $finalSleepingSample
    fatalAnrInputTimeoutLines = @($fatalSamples | ForEach-Object Line)
    originalStayOn = $originalStayOn
    restoredStayOn = $restoredStayOn
    stayOnRestored = $restoredStayOn -eq $originalStayOn
    finalWakeExitCode = $finalWake.exitCode
    finalWakefulness = $finalWakefulness
    keyguardShowingAfterWake = $keyguardShowing
    samplesPath = $samplePath
    startEvidencePath = $startPath
    audioEventsPath = $audioEventsPath
}
[IO.File]::WriteAllText(
    $summaryPath,
    ($summary | ConvertTo-Json -Depth 20),
    $utf8)
$summary | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
