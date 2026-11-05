[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [datetimeoffset] $ScreenOffAt,

    [string] $LogcatPath = '',

    [string] $OutputPrefix = 'long-run-5m-final'
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = Join-Path $taskRoot 'runtime-closure-v2\device-K60-8e875894'
$logcatPath =
    if ([string]::IsNullOrWhiteSpace($LogcatPath)) {
        Join-Path $evidenceRoot 'logcat-runtime.log'
    } else {
        (Resolve-Path -LiteralPath $LogcatPath).Path
    }
$samplesPath = Join-Path $evidenceRoot "$OutputPrefix-samples.jsonl"
$summaryPath = Join-Path $evidenceRoot "$OutputPrefix.json"
Remove-Item -LiteralPath $samplesPath, $summaryPath -Force -ErrorAction SilentlyContinue

$samples = [Collections.Generic.List[object]]::new()
$lastSample = $null
for ($sampleNo = 0; $sampleNo -le 11; $sampleNo++) {
    $targetSeconds = $sampleNo * 30
    $waitMilliseconds =
        ($ScreenOffAt.AddSeconds($targetSeconds) - [DateTimeOffset]::Now).TotalMilliseconds
    if ($waitMilliseconds -gt 0) {
        Start-Sleep -Milliseconds ([int][Math]::Min(30000, $waitMilliseconds))
    }

    $elapsed = ([DateTimeOffset]::Now - $ScreenOffAt).TotalSeconds
    $wakefulness =
        (adb -s $Serial shell dumpsys power |
            Select-String -Pattern 'mWakefulness=' |
            Select-Object -First 1).Line.Trim()
    $runtimeId = (adb -s $Serial shell pidof dev.aurorama.kokoro.harness).Trim()
    $serviceText =
        adb -s $Serial shell dumpsys activity services dev.aurorama.kokoro.harness |
            Out-String
    $isForeground = $serviceText -match 'isForeground=true'
    $mediaPlaybackType = $serviceText -match 'types=0x00000002'
    $logText = Get-Content -LiteralPath $logcatPath -Raw -Encoding utf8
    $starts = ([regex]::Matches($logText, 'AUDIO_TRACK_STARTED owner=TTS')).Count
    $completions =
        ([regex]::Matches($logText, 'AUDIO_TRACK_COMPLETED owner=TTS')).Count
    $stateMatches =
        [regex]::Matches($logText, 'KOKORO_RUNTIME: STATE (\{[^\r\n]+\})')
    $state = $null
    if ($stateMatches.Count -gt 0) {
        try {
            $state =
                $stateMatches[$stateMatches.Count - 1].Groups[1].Value |
                    ConvertFrom-Json
        } catch {
            $state = $null
        }
    }

    $sample = [ordered]@{
        at = (Get-Date).ToString('o')
        elapsedSeconds = [Math]::Round($elapsed, 3)
        wakefulness = $wakefulness
        runtimePid = $runtimeId
        serviceForeground = $isForeground
        mediaPlaybackType = $mediaPlaybackType
        audioStarts = $starts
        audioCompletions = $completions
        stateStatus = if ($null -ne $state) { $state.status } else { $null }
        queueIndex = if ($null -ne $state) { $state.queueIndex } else { $null }
        longRunAudioMillis =
            if ($null -ne $state) { $state.longRunAudioMillis } else { $null }
        screenAsleep = $wakefulness -match 'Asleep'
    }
    $samples.Add($sample)
    $sample | ConvertTo-Json -Compress |
        Add-Content -LiteralPath $samplesPath -Encoding utf8
    $sample | ConvertTo-Json -Compress
    $lastSample = $sample

    if (-not $sample.screenAsleep) {
        throw "Screen left Asleep at sample $sampleNo"
    }
    if (-not $sample.serviceForeground) {
        throw "Foreground service disappeared at sample $sampleNo"
    }
    if ($runtimeId -notmatch '^\d+$') {
        throw "Runtime PID disappeared at sample $sampleNo"
    }
    if ($elapsed -ge 300) {
        break
    }
}

$summary = [ordered]@{
    status =
        if ($lastSample.elapsedSeconds -ge 300 -and
            $lastSample.audioStarts -gt $samples[0].audioStarts) {
            'OBSERVED_5_MIN_AUDIO_OWNER_PASS'
        } else {
            'FAIL'
        }
    screenOffAt = $ScreenOffAt.ToString('o')
    observedSeconds = $lastSample.elapsedSeconds
    firstAudioStarts = $samples[0].audioStarts
    lastAudioStarts = $lastSample.audioStarts
    additionalAudioStarts =
        $lastSample.audioStarts - $samples[0].audioStarts
    firstAudioCompletions = $samples[0].audioCompletions
    lastAudioCompletions = $lastSample.audioCompletions
    finalQueueIndex = $lastSample.queueIndex
    finalLongRunAudioMillis = $lastSample.longRunAudioMillis
    allSamplesScreenAsleep =
        @($samples | Where-Object { -not $_.screenAsleep }).Count -eq 0
    allSamplesForeground =
        @($samples | Where-Object { -not $_.serviceForeground }).Count -eq 0
    sampleCount = $samples.Count
    samples = $samplesPath
}
$summary | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath $summaryPath -Encoding utf8
$summary | ConvertTo-Json -Depth 6
