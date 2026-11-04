[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('8e875894', '43af8627')]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet('K60', 'Pad5')]
    [string] $Label,

    [Parameter(Mandatory = $true)]
    [ValidateSet('zf', 'zm', 'warm-zf', 'probe-zf', 'both')]
    [string] $Mode,

    [Parameter(Mandatory = $true)]
    [string] $Phase,

    [int] $TimeoutSeconds = 180,

    [switch] $CaptureAudioOwner,

    [int] $ScreenOffAfterSeconds = 0
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$QaRoot = $PSScriptRoot
$deviceRoot = Join-Path $QaRoot "device-$Label-$Serial"
$null = New-Item -ItemType Directory -Path $deviceRoot -Force
$commandLog = Join-Path $QaRoot 'commands.log'
$package = 'dev.aurorama.kokoro.harness'
$remoteRoot = "/sdcard/Android/data/$package/files"
$remoteUiXml = "/sdcard/kokoro_qav2_ui_$Serial.xml"

function Write-Utf8([string] $Path, [AllowNull()][object] $Value) {
    $text = if ($null -eq $Value) { '' } else { [string]$Value }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-Log([string] $Message) {
    $line = (Get-Date).ToString('o') + ' [' + $Serial + '] ' + $Message + [Environment]::NewLine
    [IO.File]::AppendAllText($commandLog, $line, $Utf8NoBom)
}

function Adb([string[]] $Arguments) {
    $output = @(& adb.exe -s $Serial @Arguments 2>&1)
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = ($output -join [Environment]::NewLine)
        Lines = $output
    }
}

function FirstMatch([string] $Text, [string] $Pattern) {
    $match = [regex]::Match($Text, $Pattern)
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return $null
}

function Get-Pid {
    $result = Adb @('shell', 'pidof', $package)
    $value = $result.Text.Trim()
    if ($value -match '^(\d+)') { return [int]$Matches[1] }
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

function Test-RemoteFile([string] $Path) {
    & adb.exe -s $Serial shell test -f $Path *> $null
    return ($LASTEXITCODE -eq 0)
}

function Parse-Wav([string] $Path) {
    $stream = [IO.File]::OpenRead($Path)
    $reader = [IO.BinaryReader]::new($stream)
    try {
        $riff = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
        $null = $reader.ReadUInt32()
        $wave = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
        $format = $null
        $dataBytes = $null
        while ($stream.Position + 8 -le $stream.Length) {
            $chunk = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
            $size = [long]$reader.ReadUInt32()
            if ($chunk -eq 'fmt ') {
                $audioFormat = $reader.ReadUInt16()
                $channels = $reader.ReadUInt16()
                $sampleRate = $reader.ReadUInt32()
                $byteRate = $reader.ReadUInt32()
                $null = $reader.ReadUInt16()
                $bits = $reader.ReadUInt16()
                $format = [ordered]@{
                    audioFormat = $audioFormat
                    channels = $channels
                    sampleRate = $sampleRate
                    byteRate = $byteRate
                    bitsPerSample = $bits
                }
                $remaining = $size - 16
                if ($remaining -gt 0) { $null = $stream.Seek($remaining, [IO.SeekOrigin]::Current) }
            } elseif ($chunk -eq 'data') {
                $dataBytes = $size
                $null = $stream.Seek($size, [IO.SeekOrigin]::Current)
            } else {
                $null = $stream.Seek($size + ($size % 2), [IO.SeekOrigin]::Current)
            }
        }
        return [ordered]@{
            path = $Path
            bytes = (Get-Item -LiteralPath $Path).Length
            sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
            riff = $riff
            wave = $wave
            audioFormat = $format.audioFormat
            channels = $format.channels
            sampleRate = $format.sampleRate
            byteRate = $format.byteRate
            bitsPerSample = $format.bitsPerSample
            dataBytes = $dataBytes
            durationSeconds = if ($null -ne $dataBytes -and $format.byteRate -gt 0) {
                [Math]::Round($dataBytes / [double]$format.byteRate, 3)
            } else { $null }
        }
    } finally {
        $reader.Dispose()
        $stream.Dispose()
    }
}

function Get-ModeFiles([string] $SelectedMode) {
    switch ($SelectedMode) {
        'zf' { return @{ Metrics = 'metrics_zf.json'; Outputs = @('zf_001_sid3.wav') } }
        'zm' { return @{ Metrics = 'metrics_zm.json'; Outputs = @('zm_010_sid59.wav') } }
        'warm-zf' { return @{ Metrics = 'metrics_warm-zf.json'; Outputs = @('warmup_zf_001_sid3.wav') } }
        'probe-zf' { return @{ Metrics = 'metrics_probe-zf.json'; Outputs = @('zf_001_sid3_probe.wav') } }
        'both' { return @{ Metrics = 'metrics_both.json'; Outputs = @('zf_001_sid3.wav', 'zm_010_sid59.wav') } }
    }
}

function Capture-AudioOwner([string] $OwnerPhase) {
    $audioText = (Adb @('shell', 'dumpsys', 'audio')).Text
    $mediaText = (Adb @('shell', 'dumpsys', 'media_session')).Text
    $audioLines = @($audioText -split "`r?`n" | Where-Object {
        $_ -match 'Audio Focus|focus|source:|clientId|ownerPid|AudioPlaybackConfiguration|playerIId|state:|uid='
    } | Select-Object -First 300)
    $mediaLines = @($mediaText -split "`r?`n" | Where-Object {
        $_ -match 'Sessions Stack|active|package=|packageName|dev\.aurorama\.kokoro\.harness'
    } | Select-Object -First 220)
    $audioPath = Join-Path $deviceRoot "audio-$Label-$OwnerPhase.txt"
    $mediaPath = Join-Path $deviceRoot "media-session-$Label-$OwnerPhase.txt"
    Write-Utf8 $audioPath ($audioLines -join [Environment]::NewLine)
    Write-Utf8 $mediaPath ($mediaLines -join [Environment]::NewLine)
    return [ordered]@{
        packageInAudioDump = [bool]($audioText -match [regex]::Escape($package))
        packageInMediaSessionDump = [bool]($mediaText -match [regex]::Escape($package))
        audioPath = $audioPath
        mediaSessionPath = $mediaPath
    }
}

$files = Get-ModeFiles $Mode
$null = Stop-Harness
foreach ($name in @($files.Metrics) + @($files.Outputs) + @('metrics_failure.json')) {
    $null = Adb @('shell', 'rm', '-f', "$remoteRoot/$name")
}
$null = Adb @('logcat', '-c')
$remoteFilesBefore = (Adb @('shell', 'find', $remoteRoot, '-maxdepth', '2', '-type', 'f')).Text
Write-Utf8 (Join-Path $deviceRoot "files-before-$Label-$Phase.txt") $remoteFilesBefore

$launch = Adb @('shell', 'am', 'start', '-W', '-n', "$package/.HarnessActivity", '--es', 'mode', $Mode)
Write-Utf8 (Join-Path $deviceRoot "launch-$Label-$Phase.txt") $launch.Text
Write-Log "ADB shell am start -W -n $package/.HarnessActivity --es mode $Mode"
$clock = [Diagnostics.Stopwatch]::StartNew()
$samples = [Collections.Generic.List[object]]::new()
$missingPss = 0
$audioOwner = $null
$screenOffApplied = $false
$nextTargetMs = 0.0
$completed = $false

while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    $elapsed = [Math]::Round($clock.Elapsed.TotalSeconds, 3)
    $procId = Get-Pid
    if ($null -ne $procId) {
        $memText = (Adb @('shell', 'dumpsys', 'meminfo', $package)).Text
        $pssRaw = FirstMatch $memText 'TOTAL PSS:\s*([0-9,]+)'
        if (-not $pssRaw) { $pssRaw = FirstMatch $memText '(?m)^\s*TOTAL:\s*([0-9,]+)' }
        $pssKb = if ($pssRaw) { [long]($pssRaw -replace ',', '') } else { $null }
        if ($null -eq $pssKb) { $missingPss++ }
        $statusText = (Adb @('shell', 'cat', "/proc/$procId/status")).Text
        $vmRssKb = FirstMatch $statusText 'VmRSS:\s*(\d+)\s*kB'
        $vmHwmKb = FirstMatch $statusText 'VmHWM:\s*(\d+)\s*kB'
        $vmSizeKb = FirstMatch $statusText 'VmSize:\s*(\d+)\s*kB'
        $samples.Add([pscustomobject]@{
            timestamp = (Get-Date).ToString('o')
            elapsedSeconds = $elapsed
            pid = $procId
            totalPssKb = $pssKb
            vmRssKb = if ($vmRssKb) { [long]$vmRssKb } else { $null }
            vmHwmKb = if ($vmHwmKb) { [long]$vmHwmKb } else { $null }
            vmSizeKb = if ($vmSizeKb) { [long]$vmSizeKb } else { $null }
        })
    }
    if ($CaptureAudioOwner -and $null -eq $audioOwner -and $elapsed -ge 5) {
        $audioOwner = Capture-AudioOwner $Phase
        Write-Log "DUMP audio + media_session phase=$Phase"
    }
    if ($ScreenOffAfterSeconds -gt 0 -and -not $screenOffApplied -and $elapsed -ge $ScreenOffAfterSeconds) {
        $null = Adb @('shell', 'input', 'keyevent', '223')
        $screenOffApplied = $true
        $powerText = (Adb @('shell', 'dumpsys', 'power')).Text
        Write-Log "SCREEN_OFF phase=$Phase wakefulness=$(FirstMatch $powerText 'mWakefulness=([^\s]+)')"
    }
    if (Test-RemoteFile "$remoteRoot/$($files.Metrics)") {
        $completed = $true
        break
    }
    if ($null -eq $procId -and (Test-RemoteFile "$remoteRoot/metrics_failure.json")) {
        break
    }
    $nextTargetMs += 1200.0
    $sleepMs = [int][Math]::Max(50, $nextTargetMs - $clock.Elapsed.TotalMilliseconds)
    Start-Sleep -Milliseconds $sleepMs
}
$clock.Stop()
if ($screenOffApplied) {
    $null = Adb @('shell', 'input', 'keyevent', '224')
    Start-Sleep -Seconds 1
    $null = Adb @('shell', 'wm', 'dismiss-keyguard')
    Write-Log "SCREEN_RESTORE phase=$Phase"
}

$csv = Join-Path $deviceRoot "pss-$Label-$Phase.csv"
$lines = @('timestamp,elapsed_seconds,pid,total_pss_kb,vm_rss_kb,vm_hwm_kb,vm_size_kb')
foreach ($sample in $samples) {
    $lines += '{0},{1},{2},{3},{4},{5},{6}' -f `
        $sample.timestamp, $sample.elapsedSeconds, $sample.pid, $sample.totalPssKb,
        $sample.vmRssKb, $sample.vmHwmKb, $sample.vmSizeKb
}
Write-Utf8 $csv ($lines -join [Environment]::NewLine)

$metrics = $null
$metricsPath = $null
$wavs = @()
if (Test-RemoteFile "$remoteRoot/$($files.Metrics)") {
    $metricsPath = Join-Path $deviceRoot "$Phase-$($files.Metrics)"
    $null = Adb @('pull', "$remoteRoot/$($files.Metrics)", $metricsPath)
    $metrics = Get-Content -LiteralPath $metricsPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 30
    foreach ($output in $files.Outputs) {
        if (Test-RemoteFile "$remoteRoot/$output") {
            $localWav = Join-Path $deviceRoot "$Phase-$output"
            $null = Adb @('pull', "$remoteRoot/$output", $localWav)
            $wavs += Parse-Wav $localWav
        }
    }
} elseif (Test-RemoteFile "$remoteRoot/metrics_failure.json") {
    $metricsPath = Join-Path $deviceRoot "$Phase-metrics_failure.json"
    $null = Adb @('pull', "$remoteRoot/metrics_failure.json", $metricsPath)
    $metrics = Get-Content -LiteralPath $metricsPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 30
}

$allLog = (Adb @('logcat', '-d', '-v', 'threadtime')).Lines
$filteredLog = @($allLog | Where-Object {
    [string]$_ -match 'KOKORO_HARNESS|dev\.aurorama\.kokoro\.harness|FATAL EXCEPTION|ANR in|Input dispatching timed out|NoClassDefFoundError|java\.util\.HexFormat|AudioTrack|AudioFocus'
})
$logPath = Join-Path $deviceRoot "logcat-$Label-$Phase.txt"
Write-Utf8 $logPath ($filteredLog -join [Environment]::NewLine)
$crashLog = (Adb @('logcat', '-b', 'crash', '-d', '-v', 'threadtime')).Lines
$crashFiltered = @($crashLog | Where-Object {
    [string]$_ -match 'KOKORO_HARNESS|dev\.aurorama\.kokoro\.harness|FATAL EXCEPTION|ANR in|NoClassDefFoundError|java\.util\.HexFormat'
})
$crashPath = Join-Path $deviceRoot "crash-$Label-$Phase.txt"
Write-Utf8 $crashPath ($crashFiltered -join [Environment]::NewLine)

$pssValues = @($samples | Where-Object { $null -ne $_.totalPssKb } | ForEach-Object { [long]$_.totalPssKb })
$gaps = @()
for ($i = 1; $i -lt $samples.Count; $i++) {
    $gaps += [double]$samples[$i].elapsedSeconds - [double]$samples[$i - 1].elapsedSeconds
}
$forbiddenLogHits = @($filteredLog + $crashFiltered | Select-String -Pattern '\bsid\s*[=:]\s*58\b|\bzm_009\b|speaker\s+58\b')
$apiCompatHits = @($filteredLog + $crashFiltered | Select-String -Pattern 'NoClassDefFoundError|java\.util\.HexFormat')
$result = [ordered]@{
    schema = 'aurorama.kokoro-exact.independent-qa.v2.generation'
    serial = $Serial
    label = $Label
    phase = $Phase
    mode = $Mode
    startedAt = (Get-Date).ToString('o')
    completed = $completed
    elapsedWallSeconds = [Math]::Round($clock.Elapsed.TotalSeconds, 3)
    launchOutput = $launch.Text
    sampleCount = $samples.Count
    missingPssSamples = $missingPss
    samplingIntervalTargetSeconds = 1.2
    maxObservedSampleGapSeconds = if ($gaps.Count) { [Math]::Round(($gaps | Measure-Object -Maximum).Maximum, 3) } else { $null }
    peakPssKb = if ($pssValues.Count) { ($pssValues | Measure-Object -Maximum).Maximum } else { $null }
    minPssKb = if ($pssValues.Count) { ($pssValues | Measure-Object -Minimum).Minimum } else { $null }
    audioOwner = $audioOwner
    screenOffAfterSeconds = $ScreenOffAfterSeconds
    metricsPath = $metricsPath
    metrics = $metrics
    wavs = $wavs
    pssCsv = $csv
    logcat = $logPath
    crashLog = $crashPath
    apiCompatHits = $apiCompatHits.Count
    speaker58RuntimeHits = $forbiddenLogHits.Count
}
$resultPath = Join-Path $deviceRoot "run-$Label-$Phase.json"
$resultText = $result | ConvertTo-Json -Depth 30
Write-Utf8 $resultPath $resultText
Write-Log "RUN $Label phase=$Phase mode=$Mode completed=$completed samples=$($samples.Count) gapsMax=$($result.maxObservedSampleGapSeconds) peakPssKb=$($result.peakPssKb) apiCompatHits=$($result.apiCompatHits) forbiddenHits=$($result.speaker58RuntimeHits)"
$null = Stop-Harness
Write-Log "FORCE_STOP $Label phase=$Phase"
$resultText
