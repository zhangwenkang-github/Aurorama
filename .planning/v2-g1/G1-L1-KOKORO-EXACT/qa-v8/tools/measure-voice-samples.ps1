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

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
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
    $stateMatches = @(
        $Lines |
            Select-String -Pattern 'KOKORO_RUNTIME: STATE (\{[^\r\n]+\})'
    )
    if ($stateMatches.Count -eq 0) {
        return $null
    }
    try {
        return $stateMatches[$stateMatches.Count - 1].Matches[0].Groups[1].Value |
            ConvertFrom-Json -Depth 12
    } catch {
        return $null
    }
}

function Get-AppPid {
    return ((Invoke-Adb @('shell', 'pidof', $package)).text).Trim()
}

function Run-Sample(
    [string] $Label,
    [string] $ExpectedSpeaker,
    [int] $ExpectedSid
) {
    $null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse')
    $lineOffset = if (Test-Path -LiteralPath $logPath) {
        (Get-Content -LiteralPath $logPath -Encoding utf8).Count
    } else {
        0
    }
    $launchAt = Get-Date
    $launch = Invoke-Adb @(
        'shell', 'am', 'start', '-W', '--activity-single-top',
        '-n', "$package/.HarnessActivity",
        '--es', 'command', 'play_sample')
    if ($launch.exitCode -ne 0) {
        throw "$Label launch failed: $($launch.text)"
    }

    $deadline = (Get-Date).AddMinutes(4)
    $pssSamples = [Collections.Generic.List[object]]::new()
    $relevantLines = [Collections.Generic.List[string]]::new()
    $lastState = $null
    $completed = $false
    $failed = $false
    $lastPssAt = [datetime]::MinValue

    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $newLines = @(
            Get-Content -LiteralPath $logPath -Encoding utf8 |
                Select-Object -Skip $lineOffset
        )
        $phaseLines = @(
            $newLines |
                Select-String -Pattern @(
                    'KOKORO_RUNTIME: COMMAND play_sample',
                    'TTS_LOADED',
                    'TTS_SENTENCE_REQUEST',
                    'AUDIO_TRACK_STARTED owner=TTS',
                    'AUDIO_TRACK_COMPLETED owner=TTS',
                    'TTS_COMPLETED',
                    'RUNTIME_FAILED',
                    'KOKORO_RUNTIME: STATE '
                ) |
                ForEach-Object Line
        )
        $relevantLines.Clear()
        foreach ($line in $phaseLines) {
            $relevantLines.Add($line)
        }
        $lastState = Read-LatestState $phaseLines
        $completed = $phaseLines -match 'KOKORO_RUNTIME: TTS_COMPLETED' |
            Where-Object { $_ } |
            Select-Object -First 1
        $failed = $phaseLines -match 'RUNTIME_FAILED|RESUME_REJECTED' |
            Where-Object { $_ } |
            Select-Object -First 1
        if ($failed) {
            break
        }

        $appPid = Get-AppPid
        if ($appPid -and ((Get-Date) - $lastPssAt).TotalSeconds -ge 1.5) {
            $lastPssAt = Get-Date
            $memText = (Invoke-Adb @('shell', 'dumpsys', 'meminfo', $appPid)).text
            $pssMatch = [regex]::Match($memText, 'TOTAL PSS:\s*([\d,]+)')
            $rssMatch = [regex]::Match($memText, 'TOTAL RSS:\s*([\d,]+)')
            if ($pssMatch.Success) {
                $pssSamples.Add([ordered]@{
                    at = (Get-Date).ToString('o')
                    elapsedSeconds = [Math]::Round(((Get-Date) - $launchAt).TotalSeconds, 3)
                    totalPssKb = [long](($pssMatch.Groups[1].Value -replace ',', ''))
                    totalRssKb = if ($rssMatch.Success) {
                        [long](($rssMatch.Groups[1].Value -replace ',', ''))
                    } else {
                        $null
                    }
                })
            }
        }
        if ($completed) {
            Start-Sleep -Milliseconds 1200
            break
        }
    }

    $finalLines = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $lineOffset
    )
    $requestLine = $finalLines |
        Select-String -SimpleMatch "TTS_SENTENCE_REQUEST" |
        Where-Object {
            $_.Line -match ('speaker=' + [regex]::Escape($ExpectedSpeaker)) -and
            $_.Line -match ('sid=' + $ExpectedSid + '(?:\s|$)')
        } |
        Select-Object -First 1
    $startLine = $finalLines |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
        Select-Object -First 1
    $completionLine = $finalLines |
        Select-String -SimpleMatch 'AUDIO_TRACK_COMPLETED owner=TTS' |
        Select-Object -First 1
    $ttsCompleteLine = $finalLines |
        Select-String -SimpleMatch 'TTS_COMPLETED' |
        Select-Object -First 1
    $lastState = Read-LatestState $finalLines

    $requestAt = if ($null -ne $requestLine) { Read-DeviceTimestamp $requestLine.Line } else { $null }
    $startAt = if ($null -ne $startLine) { Read-DeviceTimestamp $startLine.Line } else { $null }
    $trackDurationMillis = if ($null -ne $startLine) {
        $durationMatch = [regex]::Match($startLine.Line, 'durationMillis=(\d+)')
        if ($durationMatch.Success) { [long]$durationMatch.Groups[1].Value } else { $null }
    } else {
        $null
    }
    $inferenceMillis = if ($null -ne $requestAt -and $null -ne $startAt) {
        [long]($startAt - $requestAt).TotalMilliseconds
    } else {
        $null
    }
    $rtf = if ($inferenceMillis -and $trackDurationMillis) {
        [Math]::Round(
            [double]$inferenceMillis / [double]$trackDurationMillis,
            6)
    } else {
        $null
    }
    $peakPss = if ($pssSamples.Count -gt 0) {
        [long]((
            $pssSamples |
                ForEach-Object { [long]$_.totalPssKb } |
                Measure-Object -Maximum
        ).Maximum)
    } else {
        $null
    }
    $result = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.voice-sample.v1'
        label = $Label
        serial = $Serial
        expectedSpeaker = $ExpectedSpeaker
        expectedSid = $ExpectedSid
        launchExitCode = $launch.exitCode
        launchAt = $launchAt.ToString('o')
        completed = [bool]$completed
        failed = [bool]$failed
        requestLine = if ($requestLine) { $requestLine.Line } else { $null }
        audioStartLine = if ($startLine) { $startLine.Line } else { $null }
        audioCompletionLine = if ($completionLine) { $completionLine.Line } else { $null }
        ttsCompletionLine = if ($ttsCompleteLine) { $ttsCompleteLine.Line } else { $null }
        firstAudioMs = $inferenceMillis
        inferenceMs = $inferenceMillis
        audioDurationMs = $trackDurationMillis
        rtf = $rtf
        peakPssKb = $peakPss
        pssSampleCount = $pssSamples.Count
        pssSamples = @($pssSamples)
        finalState = $lastState
        relevantRuntimeLines = @(
            $finalLines |
                Select-String -Pattern @(
                    'KOKORO_RUNTIME: COMMAND',
                    'TTS_LOADED',
                    'TTS_SENTENCE_REQUEST',
                    'AUDIO_TRACK_STARTED owner=TTS',
                    'AUDIO_TRACK_COMPLETED owner=TTS',
                    'TTS_COMPLETED',
                    'RUNTIME_FAILED',
                    'KOKORO_RUNTIME: STATE '
                ) |
                ForEach-Object Line
        )
    }
    [IO.File]::WriteAllText(
        (Join-Path $outputPath "voice-$Label.json"),
        ($result | ConvertTo-Json -Depth 20),
        $utf8)
    $result | ConvertTo-Json -Depth 5
    if (-not $result.completed -or $result.failed -or $null -eq $result.requestLine -or
        $null -eq $result.audioStartLine -or $null -eq $result.audioCompletionLine) {
        throw "$Label voice sample did not meet execution gates"
    }
}

$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
Run-Sample -Label 'zf_001' -ExpectedSpeaker 'zf_001' -ExpectedSid 3

$toggle = Invoke-Adb @(
    'shell', 'am', 'start', '-W', '--activity-single-top',
    '-n', "$package/.HarnessActivity",
    '--es', 'command', 'toggle_voice')
if ($toggle.exitCode -ne 0) {
    throw "toggle_voice failed: $($toggle.text)"
}
$toggleDeadline = (Get-Date).AddSeconds(20)
$toggleObserved = $false
do {
    Start-Sleep -Milliseconds 300
    $tail = @(Get-Content -LiteralPath $logPath -Tail 120 -Encoding utf8)
    $state = Read-LatestState $tail
    $toggleObserved =
        $null -ne $state -and
        $state.speaker -eq 'zm_010' -and
        [int]$state.sid -eq 59
} while (-not $toggleObserved -and (Get-Date) -lt $toggleDeadline)
if (-not $toggleObserved) {
    throw 'Approved voice toggle to zm_010/sid59 was not observed'
}

Run-Sample -Label 'zm_010' -ExpectedSpeaker 'zm_010' -ExpectedSid 59
