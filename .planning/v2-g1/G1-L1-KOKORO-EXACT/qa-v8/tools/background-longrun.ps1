[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $LogcatPath,

    [ValidateRange(1, 120)]
    [int] $Minutes = 10
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logPath = (Resolve-Path -LiteralPath $LogcatPath).Path
$package = 'dev.aurorama.kokoro.harness'
$utf8 = [Text.UTF8Encoding]::new($false)
$samplePath = Join-Path $outputPath 'background-longrun-samples.jsonl'
$summaryPath = Join-Path $outputPath 'background-longrun.json'
$startPath = Join-Path $outputPath 'background-longrun-start.json'
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')
. (Join-Path $PSScriptRoot 'qa-evidence-contract.ps1')

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

function Normalize-SettingValue([string] $Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq 'null') {
        return ''
    }
    return $Value.Trim()
}

function Restore-MonitorStayOn {
    if ($script:originalStayOnWasUnset) {
        return Invoke-Adb @(
            'shell', 'settings', 'delete',
            'global', 'stay_on_while_plugged_in')
    }
    return Invoke-Adb @(
        'shell', 'settings', 'put',
        'global', 'stay_on_while_plugged_in', $script:originalStayOn)
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
    $serviceEvidence = Get-LiveForegroundServiceEvidence $serviceText
    $mediaEvidence = Get-MediaSessionEvidence `
        -Text $mediaText `
        -Packages @($package)
    $activeMainSessions = @(
        $mediaEvidence.activeSessions |
            Where-Object package -eq $package
    )
    $mediaStates = @(
        $activeMainSessions |
            ForEach-Object { $_.playbackStateLabel }
    )
    [ordered]@{
        at = $now.ToString('o')
        elapsedSeconds = [Math]::Round(($now - $ScreenOffAt).TotalSeconds, 3)
        appPid = if ($appPid) { $appPid } else { $null }
        wakefulness = $wakefulness
        screenAsleep = $wakefulness -match 'Asleep|Dozing'
        servicePresent = [bool]$serviceEvidence.servicePresent
        serviceForeground = [bool]$serviceEvidence.isForeground
        serviceMediaPlaybackType =
            [bool]$serviceEvidence.explicitMediaPlaybackType
        serviceTypeFieldPresent =
            [bool]$serviceEvidence.explicitTypeFieldPresent
        mediaSessionPresent = $activeMainSessions.Count -eq 1
        mediaSessionActive = $activeMainSessions.Count -eq 1
        activeMediaSessionCount = $activeMainSessions.Count
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
$originalStayOnWasUnset =
    [string]::IsNullOrWhiteSpace((Normalize-SettingValue $originalStayOn))
$stayOnNeedsRestore = $false
try {
    $setStayOn = Invoke-Adb @(
        'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '0')
    $stayOnNeedsRestore = $true
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
$logcatStartPath = Join-Path (Split-Path -Parent $logPath) 'logcat-start.json'
if (-not (Test-Path -LiteralPath $logcatStartPath)) {
    throw 'Device clock baseline is unavailable: logcat-start.json is missing'
}
$logcatStart = Get-Content -LiteralPath $logcatStartPath -Raw -Encoding utf8 |
    ConvertFrom-Json
if ($null -eq $logcatStart.deviceClockDriftMilliseconds) {
    throw 'Device clock baseline is unavailable: drift is missing'
}
$deviceClockDriftMilliseconds =
    [long]$logcatStart.deviceClockDriftMilliseconds
$hostLogStartedAt = [DateTimeOffset]::Parse(
    [string]$logcatStart.hostStartedAt)
$deviceLogStartedAt = [datetime]::ParseExact(
    [string]$logcatStart.estimatedDeviceLogStartedAt,
    'yyyy-MM-dd HH:mm:ss.fff',
    [Globalization.CultureInfo]::InvariantCulture)
$screenOffAtDevice =
    $deviceLogStartedAt.Add(
        [DateTimeOffset]::new($screenOffAt) - $hostLogStartedAt)
Start-Sleep -Seconds 4
$offSample = Get-RuntimeSample $screenOffAt
if (-not $offSample.screenAsleep) {
    $null = Invoke-Adb @('shell', 'input', 'keyevent', '224')
    throw "Screen did not enter Asleep: $($offSample.wakefulness)"
}

$startEvidence = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.background-longrun-start.v1'
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    screenOffAt = $screenOffAt.ToString('o')
    screenOffAtDevice = $screenOffAtDevice.ToString('o')
    deviceClockDriftMilliseconds = $deviceClockDriftMilliseconds
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
[IO.File]::WriteAllText(
    $startPath,
    ($startEvidence | ConvertTo-Json -Depth 20),
    $utf8)
[IO.File]::WriteAllText($samplePath, '', $utf8)

$samples = [Collections.Generic.List[object]]::new()
$targetSeconds = $Minutes * 60
$segments = Get-QaLongRunSegments -TargetSeconds $targetSeconds
$sampleIntervalSeconds = 60
$requiredSampleEndSeconds = $targetSeconds
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
} while ($sample.elapsedSeconds -lt $requiredSampleEndSeconds)

$windowEnd = Get-Date
$windowEndDevice =
    $deviceLogStartedAt.Add(
        [DateTimeOffset]::new($windowEnd) - $hostLogStartedAt)
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
    if ($null -eq $eventAt -or $eventAt -gt $windowEndDevice) {
        continue
    }
    $eventType = if ($line -match 'AUDIO_TRACK_STARTED') { 'STARTED' } else { 'COMPLETED' }
    $audioEvents.Add([ordered]@{
        at = $eventAt.ToString('o')
        offsetSeconds = [Math]::Round(
            ($eventAt - $screenOffAtDevice).TotalSeconds,
            3)
        eventType = $eventType
        line = $line
    })
}

$windowEvents = @($audioEvents | Where-Object { $_.offsetSeconds -ge 0 })
$maximumGapMs = $null
$maximumGapFrom = $null
$maximumGapTo = $null
$maximumGapScope = $null
$headAudioGapMs = $null
$tailAudioGapMs = $null
$interEventAudioGapMs = $null
if ($windowEvents.Count -gt 0) {
    $firstWindowEvent = $windowEvents[0]
    $lastWindowEvent = $windowEvents[$windowEvents.Count - 1]
    $firstWindowEventAt = [datetime]::Parse($firstWindowEvent.at)
    $lastWindowEventAt = [datetime]::Parse($lastWindowEvent.at)
    $headAudioGapMs = [long](
        ($firstWindowEventAt - $screenOffAtDevice).TotalMilliseconds)
    $tailAudioGapMs = [long](
        ($windowEndDevice - $lastWindowEventAt).TotalMilliseconds)
    $headFrom = [ordered]@{
        at = $screenOffAtDevice.ToString('o')
        eventType = 'SCREEN_OFF'
        line = $null
    }
    $tailTo = [ordered]@{
        at = $windowEndDevice.ToString('o')
        eventType = 'WINDOW_END'
        line = $null
    }
    $gapCandidates = [Collections.Generic.List[object]]::new()
    $gapCandidates.Add([ordered]@{
        scope = 'head'
        gapMs = $headAudioGapMs
        from = $headFrom
        to = $firstWindowEvent
    })
    for ($eventIndex = 1; $eventIndex -lt $windowEvents.Count; $eventIndex++) {
        $fromAt = [datetime]::Parse($windowEvents[$eventIndex - 1].at)
        $toAt = [datetime]::Parse($windowEvents[$eventIndex].at)
        $gapCandidates.Add([ordered]@{
            scope = 'inter-event'
            gapMs = [long]($toAt - $fromAt).TotalMilliseconds
            from = $windowEvents[$eventIndex - 1]
            to = $windowEvents[$eventIndex]
        })
    }
    $gapCandidates.Add([ordered]@{
        scope = 'tail'
        gapMs = $tailAudioGapMs
        from = $lastWindowEvent
        to = $tailTo
    })
    $interEventAudioGapMs = @(
        $gapCandidates |
            Where-Object scope -eq 'inter-event' |
            Measure-Object -Property gapMs -Maximum
    )[0].Maximum
    $maximumGap = $gapCandidates |
        Sort-Object gapMs -Descending |
        Select-Object -First 1
    if ($null -ne $maximumGap) {
        $maximumGapMs = [long]$maximumGap.gapMs
        $maximumGapScope = [string]$maximumGap.scope
        $maximumGapFrom = $maximumGap.from
        $maximumGapTo = $maximumGap.to
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
            $eventAt -ge $screenOffAtDevice -and
            $eventAt -le $windowEndDevice
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
$segmentFirst = @(
    $windowEvents |
        Where-Object {
            $_.offsetSeconds -ge $segments.first.startSeconds -and
            $_.offsetSeconds -lt $segments.first.endSeconds
        }
)
$segmentMid = @(
    $windowEvents |
        Where-Object {
            $_.offsetSeconds -ge $segments.middle.startSeconds -and
            $_.offsetSeconds -lt $segments.middle.endSeconds
        }
)
$segmentFinal = @(
    $windowEvents |
        Where-Object {
            $_.offsetSeconds -ge $segments.final.startSeconds -and
            $_.offsetSeconds -le $segments.final.endSeconds
        }
)
$screenViolationCount = @($samples | Where-Object { -not $_.screenAsleep }).Count
$foregroundViolationCount = @(
    $samples |
        Where-Object { -not $_.servicePresent -or -not $_.serviceForeground }
).Count
$mediaViolationCount = @($samples | Where-Object { -not $_.mediaSessionPresent }).Count
$notificationViolationCount = @($samples | Where-Object { -not $_.notificationPresent }).Count
$pidViolationCount = @($samples | Where-Object { -not $_.appPid }).Count
$audioEventsPath = Join-Path $outputPath 'background-longrun-audio-events.json'
[IO.File]::WriteAllText(
    $audioEventsPath,
    (($audioEvents | ConvertTo-Json -Depth 8)),
    $utf8)
$finalWake = Invoke-Adb @('shell', 'input', 'keyevent', '224')
Start-Sleep -Seconds 3
$restoreStayOn = Restore-MonitorStayOn
$stayOnNeedsRestore = $false
$restoredStayOn = Normalize-SettingValue (
    Read-Setting 'global' 'stay_on_while_plugged_in')
$expectedRestoredStayOn = if ($originalStayOnWasUnset) {
    ''
} else {
    Normalize-SettingValue $originalStayOn
}
$finalPower = (Invoke-Adb @('shell', 'dumpsys', 'power')).text
$finalWakefulness = [regex]::Match($finalPower, 'mWakefulness=([^\s]+)').Groups[1].Value
$windowPolicy = (Invoke-Adb @('shell', 'dumpsys', 'window', 'policy')).text
$keyguardShowing = $windowPolicy -match 'KeyguardStateMonitor[\s\S]*?mIsShowing=true|showing=true'

$passed =
    $samples.Count -ge [Math]::Floor($targetSeconds / $sampleIntervalSeconds) -and
    $screenViolationCount -eq 0 -and
    $foregroundViolationCount -eq 0 -and
    $mediaViolationCount -eq 0 -and
    $notificationViolationCount -eq 0 -and
    $pidViolationCount -eq 0 -and
    $completionLines.Count -eq 0 -and
    $terminalStateSamples.Count -eq 0 -and
    $segmentFirst.Count -gt 0 -and
    $segmentMid.Count -gt 0 -and
    $segmentFinal.Count -gt 0 -and
    $fatalSamples.Count -eq 0 -and
    $restoreStayOn.exitCode -eq 0 -and
    $restoredStayOn -eq $expectedRestoredStayOn

$summary = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.background-longrun.v1'
    status = if ($passed) {
        "PASS_${Minutes}MIN_REAL_AUDIOTRACK_OWNERSHIP"
    } else {
        'FAIL'
    }
    serial = $Serial
    screenOffAt = $screenOffAt.ToString('o')
    windowEnd = $windowEnd.ToString('o')
    windowEndDevice = $windowEndDevice.ToString('o')
    requestedMinutes = $Minutes
    targetSeconds = $targetSeconds
    segmentWindows = $segments
    observedSeconds = [Math]::Round(($windowEnd - $screenOffAt).TotalSeconds, 3)
    lineOffset = $lineOffset
    firstAudioLine = $firstAudioLine.Line
    sampleCount = $samples.Count
    screenViolationCount = $screenViolationCount
    foregroundViolationCount = $foregroundViolationCount
    mediaSessionViolationCount = $mediaViolationCount
    notificationViolationCount = $notificationViolationCount
    pidViolationCount = $pidViolationCount
    terminalStateSampleCount = $terminalStateSamples.Count
    completionCountInWindow = $completionLines.Count
    audioEventCountInWindow = $windowEvents.Count
    audioStartCountInWindow = @($windowEvents | Where-Object eventType -eq 'STARTED').Count
    audioCompletionCountInWindow = @($windowEvents | Where-Object eventType -eq 'COMPLETED').Count
    firstSegmentEventCount = $segmentFirst.Count
    midSegmentEventCount = $segmentMid.Count
    finalSegmentEventCount = $segmentFinal.Count
    maximumAudioEventGapMs = $maximumGapMs
    maximumAudioGapScope = $maximumGapScope
    headAudioGapMs = $headAudioGapMs
    tailAudioGapMs = $tailAudioGapMs
    interEventAudioGapMs = $interEventAudioGapMs
    maximumGapFrom = $maximumGapFrom
    maximumGapTo = $maximumGapTo
    finalQueueIndex = $finalState.queueIndex
    finalQueueSize = $finalState.queueSize
    initialQueueIndex = $initialState.queueIndex
    initialQueueSize = $initialState.queueSize
    initialLongRunAudioMillis = $initialState.longRunAudioMillis
    finalLongRunAudioMillis = $finalState.longRunAudioMillis
    finalStateStatus = $finalState.status
    finalStateActiveAudioOwner = $finalState.activeAudioOwner
    finalStateAudioTrackPlaying = $finalState.audioTrackPlaying
    finalSleepingSample = $finalSleepingSample
    fatalAnrInputTimeoutLines = @($fatalSamples | ForEach-Object Line)
    originalStayOn = $originalStayOn
    originalStayOnWasUnset = $originalStayOnWasUnset
    restoredStayOn = $restoredStayOn
    expectedRestoredStayOn = $expectedRestoredStayOn
    stayOnRestoreExitCode = $restoreStayOn.exitCode
    stayOnRestored = $restoredStayOn -eq $expectedRestoredStayOn
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
} finally {
    if ($stayOnNeedsRestore) {
        try {
            $null = Restore-MonitorStayOn
        } catch {
            # Preserve the original failure; finalize-device will detect restoration drift.
        }
    }
}
$summary | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
