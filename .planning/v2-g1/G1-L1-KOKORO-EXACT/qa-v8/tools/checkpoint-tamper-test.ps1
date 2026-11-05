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
$prefsRelative = 'shared_prefs/kokoro_runtime_state.xml'
$originalPath = Join-Path $outputPath 'checkpoint-original.xml'
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

function Get-RemotePrefsHash {
    $result = Invoke-Adb @(
        'shell', 'run-as', $package, 'sha256sum', $prefsRelative)
    $match = [regex]::Match($result.text, '\b([0-9a-f]{64})\b')
    if ($result.exitCode -ne 0 -or -not $match.Success) {
        throw "Could not hash remote checkpoint: $($result.text)"
    }
    return $match.Groups[1].Value
}

function Write-RemotePrefs([string] $Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    $encoded = [Convert]::ToBase64String($bytes)
    $remoteCommand =
        "run-as $package sh -c 'echo $encoded | base64 -d > $prefsRelative'"
    & adb.exe -s $Serial shell $remoteCommand 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to write remote checkpoint from $Path"
    }
}

function Test-RejectedRestore(
    [string] $Label,
    [string] $TamperedPath,
    [string] $ExpectedErrorPattern
) {
    $null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
    Write-RemotePrefs $TamperedPath
    $expectedHash = (Get-FileHash -LiteralPath $TamperedPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $remoteHash = Get-RemotePrefsHash
    if ($remoteHash -ne $expectedHash) {
        throw "$Label checkpoint write hash mismatch: $remoteHash != $expectedHash"
    }

    $offset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
    $launch = Invoke-Adb @(
        'shell', 'am', 'start', '-W', '--activity-single-top',
        '-n', "$package/.HarnessActivity")
    if ($launch.exitCode -ne 0) {
        throw "$Label manual launch failed: $($launch.text)"
    }
    $deadline = (Get-Date).AddSeconds(20)
    $rejectionLine = $null
    do {
        Start-Sleep -Milliseconds 250
        $lines = @(
            Get-Content -LiteralPath $logPath -Encoding utf8 |
                Select-Object -Skip $offset
        )
        $rejectionLine = $lines |
            Select-String -SimpleMatch 'KOKORO_RUNTIME: RESUME_REJECTED' |
            Select-Object -First 1
    } while ($null -eq $rejectionLine -and (Get-Date) -lt $deadline)
    Start-Sleep -Seconds 3
    $lines = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $offset
    )
    $state = Read-LatestState $lines
    $audioStarts = @($lines | Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS')
    $serviceText = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $package)).text
    $mediaText = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
    $mediaEvidence = Get-MediaSessionEvidence `
        -Text $mediaText `
        -Packages @($package)
    $activeMainSessions = @(
        $mediaEvidence.activeSessions |
            Where-Object package -eq $package
    )
    $checks = [ordered]@{
        tamperedFileHashMatched = $remoteHash -eq $expectedHash
        rejectionLogObserved = $null -ne $rejectionLine
        stateIsIdle = $state.status -eq 'IDLE'
        queueNotLoaded = $state.queueLoaded -eq $false
        errorMatches =
            $null -ne $state -and
            [string]$state.lastError -match $ExpectedErrorPattern
        noAudioTrackStarted = $audioStarts.Count -eq 0
        noForegroundOwner = $serviceText -notmatch 'isForeground=true'
        noActiveMediaSession = $activeMainSessions.Count -eq 0
    }
    $result = [ordered]@{
        label = $Label
        tamperedPath = $TamperedPath
        tamperedSha256 = $expectedHash
        remoteSha256 = $remoteHash
        manualLaunchExitCode = $launch.exitCode
        rejectionLine = if ($rejectionLine) { $rejectionLine.Line } else { $null }
        state = $state
        audioStartsAfterLaunch = @($audioStarts | ForEach-Object Line)
        mediaSession = $mediaEvidence
        checks = $checks
        passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
    }

    $null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
    Write-RemotePrefs $originalPath
    $restoredHash = Get-RemotePrefsHash
    $originalHash = (Get-FileHash -LiteralPath $originalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $result.restoredOriginalHash = $restoredHash
    $result.originalHash = $originalHash
    $result.restorePassed = $restoredHash -eq $originalHash
    [IO.File]::WriteAllText(
        (Join-Path $outputPath "tamper-$Label.json"),
        ($result | ConvertTo-Json -Depth 20),
        $utf8)
    if (-not $result.passed -or -not $result.restorePassed) {
        throw "$Label tamper test failed"
    }
    return $result
}

$originalHash = Get-RemotePrefsHash
$hostOriginalHash = (Get-FileHash -LiteralPath $originalPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($originalHash -ne $hostOriginalHash) {
    throw "Original checkpoint backup mismatch: $originalHash != $hostOriginalHash"
}

$originalXml = [IO.File]::ReadAllText($originalPath, [Text.Encoding]::UTF8)
$tamperedPackBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes('tampered-pack'))
$tamperedSpeakerBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes('zm_009'))

$packXml = [regex]::Replace(
    $originalXml,
    'pack=[A-Za-z0-9+/=]+',
    "pack=$tamperedPackBase64")
$packPath = Join-Path $outputPath 'checkpoint-tamper-pack.xml'
[IO.File]::WriteAllText($packPath, $packXml, $utf8)

$speakerXml = [regex]::Replace(
    $originalXml,
    'speaker=[A-Za-z0-9+/=]+',
    "speaker=$tamperedSpeakerBase64")
$speakerXml = [regex]::Replace(
    $speakerXml,
    'sid=3(?=&#10;status=)',
    'sid=58')
$speakerPath = Join-Path $outputPath 'checkpoint-tamper-speaker.xml'
[IO.File]::WriteAllText($speakerPath, $speakerXml, $utf8)

$packResult = Test-RejectedRestore `
    -Label 'pack' `
    -TamperedPath $packPath `
    -ExpectedErrorPattern 'pack|checkpoint|packId'
$speakerResult = Test-RejectedRestore `
    -Label 'speaker' `
    -TamperedPath $speakerPath `
    -ExpectedErrorPattern 'speaker|sid|zm_009|58'

$summary = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.checkpoint-tamper.v1'
    status =
        if ($packResult.passed -and $speakerResult.passed -and
            $packResult.restorePassed -and $speakerResult.restorePassed) {
            'PASS_FAIL_CLOSED_PACK_AND_SPEAKER'
        } else {
            'FAIL'
        }
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    originalSha256 = $hostOriginalHash
    pack = $packResult
    speaker = $speakerResult
    finalRestoredSha256 = Get-RemotePrefsHash
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'checkpoint-tamper.json'),
    ($summary | ConvertTo-Json -Depth 20),
    $utf8)
$summary | ConvertTo-Json -Depth 8
if ($summary.status -eq 'FAIL') {
    exit 1
}
