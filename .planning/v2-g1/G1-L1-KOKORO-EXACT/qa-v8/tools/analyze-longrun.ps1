[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $LongRunRoot,

    [Parameter(Mandatory = $true)]
    [string] $InstallAuditPath
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $LongRunRoot).Path
$samplesPath = Join-Path $root 'background-longrun-samples.jsonl'
$startPath = Join-Path $root 'background-longrun-start.json'
$eventsPath = Join-Path $root 'background-longrun-audio-events.json'
$scriptSummaryPath = Join-Path $root 'background-longrun.json'
$utf8 = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'qa-evidence-contract.ps1')

$samples = @(
    Get-Content -LiteralPath $samplesPath -Encoding utf8 |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json }
)
$start = Get-Content -LiteralPath $startPath -Raw -Encoding utf8 |
    ConvertFrom-Json
$events = @(
    Get-Content -LiteralPath $eventsPath -Raw -Encoding utf8 |
        ConvertFrom-Json
)
$scriptSummary = Get-Content -LiteralPath $scriptSummaryPath -Raw -Encoding utf8 |
    ConvertFrom-Json
$installAudit = Get-Content -LiteralPath $InstallAuditPath -Raw -Encoding utf8 |
    ConvertFrom-Json
$requestedMinutes = [int]$scriptSummary.requestedMinutes
if ($requestedMinutes -le 0) {
    throw "background-longrun.json has invalid requestedMinutes: $requestedMinutes"
}
$targetSeconds = $requestedMinutes * 60
$segments = Get-QaLongRunSegments -TargetSeconds $targetSeconds
$outputPath = Join-Path $root "long-run-${requestedMinutes}m.json"

$windowEvents = @($events | Where-Object { [double]$_.offsetSeconds -ge 0 })
$firstSegment = @(
    $windowEvents |
        Where-Object {
            [double]$_.offsetSeconds -ge $segments.first.startSeconds -and
            [double]$_.offsetSeconds -lt $segments.first.endSeconds
        }
)
$midSegment = @(
    $windowEvents |
        Where-Object {
            [double]$_.offsetSeconds -ge $segments.middle.startSeconds -and
            [double]$_.offsetSeconds -lt $segments.middle.endSeconds
        }
)
$finalSegment = @(
    $windowEvents |
        Where-Object {
            [double]$_.offsetSeconds -ge $segments.final.startSeconds -and
            [double]$_.offsetSeconds -le $segments.final.endSeconds
        }
)
$audioStartCount = @(
    $windowEvents | Where-Object eventType -eq 'STARTED'
).Count
$audioCompletionCount = @(
    $windowEvents | Where-Object eventType -eq 'COMPLETED'
).Count
$uniquePidCount = @(
    $samples | ForEach-Object appPid | Sort-Object -Unique
).Count
$queueValues = @($samples | ForEach-Object { [int]$_.queueIndex })
$queueMonotonic = $true
for ($index = 1; $index -lt $queueValues.Count; $index++) {
    if ($queueValues[$index] -lt $queueValues[$index - 1]) {
        $queueMonotonic = $false
    }
}
$terminalSamples = @(
    $samples |
        Where-Object {
            $_.status -in @('STOPPED', 'COMPLETED', 'ERROR', 'IDLE') -or
            $_.activeAudioOwner -ne 'TTS'
        }
)
$screenOffAt = [datetimeoffset]::Parse([string]$scriptSummary.screenOffAt)
$sampleLastAt = if ($samples.Count -gt 0) {
    [datetimeoffset]::Parse([string]$samples[$samples.Count - 1].at)
} else {
    $null
}
$lastSampleFromScreenOffSeconds = if ($null -ne $sampleLastAt) {
    [Math]::Round(($sampleLastAt - $screenOffAt).TotalSeconds, 3)
} else {
    $null
}
$initialLongRunAudioMillis =
    [long]$scriptSummary.initialLongRunAudioMillis
$finalLongRunAudioMillis =
    [long]$scriptSummary.finalLongRunAudioMillis
$audioMillisValues = @(
    $samples |
        ForEach-Object {
            if ($null -ne $_.longRunAudioMillis) {
                [long]$_.longRunAudioMillis
            }
        }
)
$audioMillisMonotonic = $true
for ($index = 1; $index -lt $audioMillisValues.Count; $index++) {
    if ($audioMillisValues[$index] -lt $audioMillisValues[$index - 1]) {
        $audioMillisMonotonic = $false
        break
    }
}
$checks = [ordered]@{
    observedAtLeastTargetSeconds =
        [double]$scriptSummary.observedSeconds -ge $targetSeconds
    lastSampleAtLeastTargetSecondsAfterScreenOff =
        $null -ne $lastSampleFromScreenOffSeconds -and
        [double]$lastSampleFromScreenOffSeconds -ge $targetSeconds
    atLeastTargetMinuteSamples =
        $samples.Count -ge [Math]::Floor($targetSeconds / 60)
    everySampleNonEmptyPid =
        @($samples | Where-Object {
            [string]::IsNullOrWhiteSpace([string]$_.appPid)
        }).Count -eq 0
    everySampleExactAsleep =
        @($samples | Where-Object { $_.wakefulness -ne 'Asleep' }).Count -eq 0
    everySampleForegroundService =
        @($samples | Where-Object {
            -not $_.servicePresent -or -not $_.serviceForeground
        }).Count -eq 0
    exposedForegroundTypeAlwaysMediaPlayback =
        @($samples | Where-Object {
            $_.serviceTypeFieldPresent -and
            -not $_.serviceMediaPlaybackType
        }).Count -eq 0
    manifestMediaPlaybackForegroundType =
        [int]$installAudit.mediaPlaybackForegroundServiceTypeMatches -eq 1
    everySampleActiveMediaSession =
        @($samples | Where-Object {
            -not $_.mediaSessionPresent -or -not $_.mediaSessionActive
        }).Count -eq 0
    everySampleNotificationRecord =
        @($samples | Where-Object { -not $_.notificationPresent }).Count -eq 0
    singleStablePid = $uniquePidCount -eq 1
    queueMonotonic = $queueMonotonic
    queueProgressed =
        [int]$scriptSummary.finalQueueIndex -gt
            [int]$scriptSummary.initialQueueIndex
    longRunAudioMillisProgressed =
        $finalLongRunAudioMillis -gt $initialLongRunAudioMillis -and
        $finalLongRunAudioMillis -gt 0
    longRunAudioMillisMonotonic = $audioMillisMonotonic
    headAudioGapWithin15000Ms =
        $null -ne $scriptSummary.headAudioGapMs -and
        [long]$scriptSummary.headAudioGapMs -ge 0 -and
        [long]$scriptSummary.headAudioGapMs -le 15000
    tailAudioGapWithin15000Ms =
        $null -ne $scriptSummary.tailAudioGapMs -and
        [long]$scriptSummary.tailAudioGapMs -ge 0 -and
        [long]$scriptSummary.tailAudioGapMs -le 15000
    interEventAudioGapWithin15000Ms =
        $null -ne $scriptSummary.interEventAudioGapMs -and
        [long]$scriptSummary.interEventAudioGapMs -ge 0 -and
        [long]$scriptSummary.interEventAudioGapMs -le 15000
    maximumAudioGapWithin15000Ms =
        $null -ne $scriptSummary.maximumAudioEventGapMs -and
        [long]$scriptSummary.maximumAudioEventGapMs -le 15000
    noTerminalSample = $terminalSamples.Count -eq 0
    noTtsCompletionInWindow =
        [int]$scriptSummary.completionCountInWindow -eq 0
    realAudioStartsAndCompletions =
        $audioStartCount -gt 0 -and
        $audioCompletionCount -gt 0 -and
        [Math]::Abs($audioStartCount - $audioCompletionCount) -le 1
    firstSegmentAudioEvidence =
        @($firstSegment | Where-Object eventType -eq 'STARTED').Count -gt 0 -and
        @($firstSegment | Where-Object eventType -eq 'COMPLETED').Count -gt 0
    midSegmentAudioEvidence =
        @($midSegment | Where-Object eventType -eq 'STARTED').Count -gt 0 -and
        @($midSegment | Where-Object eventType -eq 'COMPLETED').Count -gt 0
    finalSegmentAudioEvidence =
        @($finalSegment | Where-Object eventType -eq 'STARTED').Count -gt 0 -and
        @($finalSegment | Where-Object eventType -eq 'COMPLETED').Count -gt 0
    noFatalAnrInputTimeout =
        @($scriptSummary.fatalAnrInputTimeoutLines).Count -eq 0
    stayOnRestored = [bool]$scriptSummary.stayOnRestored
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.long-run-analysis.v1'
    verdict = if ($passed) { 'PASS' } else { 'FAIL' }
    status =
        if ($passed) {
            "PASS_${requestedMinutes}MIN_ALL_ASLEEP_REAL_AUDIOTRACK_QUEUE_PROGRESSION"
        } else {
            'FAIL'
        }
    scriptRawSummaryVerdict = $scriptSummary.status
    scriptFalseNegativeReason =
        'API 33 dumpsys activity services for this service exposes isForeground=true but no types= field; mediaPlayback type is independently fixed by the installed APK manifest as 0x00000002.'
    sourceScriptStillHadApi33ParserAssertion = $true
    window = [ordered]@{
        requestedMinutes = $requestedMinutes
        targetSeconds = $targetSeconds
        screenOffAt = $scriptSummary.screenOffAt
        windowEnd = $scriptSummary.windowEnd
        observedSeconds = $scriptSummary.observedSeconds
        lastSampleAt = if ($sampleLastAt) { $sampleLastAt.ToString('o') } else { $null }
        lastSampleFromScreenOffSeconds = $lastSampleFromScreenOffSeconds
        sampleCount = $samples.Count
        wakefulnessValues = @($samples | Group-Object wakefulness | ForEach-Object {
            [ordered]@{ value = $_.Name; count = $_.Count }
        })
        foregroundSamples = @($samples | Where-Object serviceForeground).Count
        samplesWithExplicitTypeField =
            @($samples | Where-Object serviceTypeFieldPresent).Count
        runtimeForegroundTypeFieldUnavailable =
            @($samples | Where-Object { -not $_.serviceTypeFieldPresent }).Count -eq
                $samples.Count
        activeMediaSessionSamples = @($samples | Where-Object mediaSessionActive).Count
        notificationSamples = @($samples | Where-Object notificationPresent).Count
        uniquePidCount = $uniquePidCount
        pidValues = @($samples | ForEach-Object appPid | Sort-Object -Unique)
        terminalStateSampleCount = $terminalSamples.Count
        ttsCompletionCountInWindow = $scriptSummary.completionCountInWindow
        finalQueueIndex = $scriptSummary.finalQueueIndex
        finalQueueSize = $scriptSummary.finalQueueSize
        finalLongRunAudioMillis = $scriptSummary.finalLongRunAudioMillis
        finalStatus = $scriptSummary.finalStateStatus
        finalActiveAudioOwner = $scriptSummary.finalStateActiveAudioOwner
        finalAudioTrackPlaying = $scriptSummary.finalStateAudioTrackPlaying
    }
    audio = [ordered]@{
        segmentWindows = $segments
        startCount = $audioStartCount
        completionCount = $audioCompletionCount
        eventCount = $windowEvents.Count
        firstSegment = [ordered]@{
            windowSeconds = $segments.first.label
            starts = @($firstSegment | Where-Object eventType -eq 'STARTED').Count
            completions = @($firstSegment | Where-Object eventType -eq 'COMPLETED').Count
        }
        midSegment = [ordered]@{
            windowSeconds = $segments.middle.label
            starts = @($midSegment | Where-Object eventType -eq 'STARTED').Count
            completions = @($midSegment | Where-Object eventType -eq 'COMPLETED').Count
        }
        finalSegment = [ordered]@{
            windowSeconds = $segments.final.label
            starts = @($finalSegment | Where-Object eventType -eq 'STARTED').Count
            completions = @($finalSegment | Where-Object eventType -eq 'COMPLETED').Count
        }
        maximumEventGapMs = $scriptSummary.maximumAudioEventGapMs
        maximumEventGapScope = $scriptSummary.maximumAudioGapScope
        headGapMs = $scriptSummary.headAudioGapMs
        interEventGapMs = $scriptSummary.interEventAudioGapMs
        tailGapMs = $scriptSummary.tailAudioGapMs
        maximumAllowedEventGapMs = 15000
        maximumGapFrom = $scriptSummary.maximumGapFrom
        maximumGapTo = $scriptSummary.maximumGapTo
    }
    checks = $checks
    crashGate = [ordered]@{
        fatalAnrInputTimeoutCount =
            @($scriptSummary.fatalAnrInputTimeoutLines).Count
        lines = @($scriptSummary.fatalAnrInputTimeoutLines)
    }
    evidence = [ordered]@{
        samples = $samplesPath
        start = $startPath
        audioEvents = $eventsPath
        originalScriptSummary = $scriptSummaryPath
    }
}
[IO.File]::WriteAllText(
    $outputPath,
    ($result | ConvertTo-Json -Depth 20),
    $utf8)
$result | ConvertTo-Json -Depth 10
if (-not $passed) {
    exit 1
}
