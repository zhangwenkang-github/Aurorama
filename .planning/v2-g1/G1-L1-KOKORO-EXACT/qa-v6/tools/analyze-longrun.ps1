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
$outputPath = Join-Path $root 'long-run-30m.json'
$utf8 = [Text.UTF8Encoding]::new($false)

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

$windowEvents = @($events | Where-Object { [double]$_.offsetSeconds -ge 0 })
$firstSegment = @(
    $windowEvents |
        Where-Object {
            [double]$_.offsetSeconds -ge 0 -and
            [double]$_.offsetSeconds -lt 60
        }
)
$midSegment = @(
    $windowEvents |
        Where-Object {
            [double]$_.offsetSeconds -ge 870 -and
            [double]$_.offsetSeconds -lt 930
        }
)
$finalSegment = @(
    $windowEvents |
        Where-Object { [double]$_.offsetSeconds -ge 1740 }
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
$checks = [ordered]@{
    observedAtLeast1800Seconds =
        [double]$scriptSummary.observedSeconds -ge 1800
    atLeast30MinuteSamples = $samples.Count -ge 30
    everySampleExactAsleep =
        @($samples | Where-Object { $_.wakefulness -ne 'Asleep' }).Count -eq 0
    everySampleForegroundService =
        @($samples | Where-Object { -not $_.serviceForeground }).Count -eq 0
    runtimeForegroundTypeFieldUnavailable =
        @($samples | Where-Object { -not $_.serviceMediaPlaybackType }).Count -eq
            $samples.Count
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
    schema = 'aurorama.kokoro-qa-v6.long-run-analysis.v1'
    verdict = if ($passed) { 'PASS' } else { 'FAIL' }
    status =
        if ($passed) {
            'PASS_30MIN_ALL_ASLEEP_REAL_AUDIOTRACK_QUEUE_PROGRESSION'
        } else {
            'FAIL'
        }
    scriptRawSummaryVerdict = $scriptSummary.status
    scriptFalseNegativeReason =
        'API 33 dumpsys activity services for this service exposes isForeground=true but no types= field; mediaPlayback type is independently fixed by the installed APK manifest as 0x00000002.'
    sourceScriptStillHadApi33ParserAssertion = $true
    window = [ordered]@{
        screenOffAt = $scriptSummary.screenOffAt
        windowEnd = $scriptSummary.windowEnd
        observedSeconds = $scriptSummary.observedSeconds
        sampleCount = $samples.Count
        wakefulnessValues = @($samples | Group-Object wakefulness | ForEach-Object {
            [ordered]@{ value = $_.Name; count = $_.Count }
        })
        foregroundSamples = @($samples | Where-Object serviceForeground).Count
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
        startCount = $audioStartCount
        completionCount = $audioCompletionCount
        eventCount = $windowEvents.Count
        firstSegment = [ordered]@{
            windowSeconds = '0-60'
            starts = @($firstSegment | Where-Object eventType -eq 'STARTED').Count
            completions = @($firstSegment | Where-Object eventType -eq 'COMPLETED').Count
        }
        midSegment = [ordered]@{
            windowSeconds = '870-930'
            starts = @($midSegment | Where-Object eventType -eq 'STARTED').Count
            completions = @($midSegment | Where-Object eventType -eq 'COMPLETED').Count
        }
        finalSegment = [ordered]@{
            windowSeconds = '1740-1800'
            starts = @($finalSegment | Where-Object eventType -eq 'STARTED').Count
            completions = @($finalSegment | Where-Object eventType -eq 'COMPLETED').Count
        }
        maximumEventGapMs = $scriptSummary.maximumAudioEventGapMs
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
