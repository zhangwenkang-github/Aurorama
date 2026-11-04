[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$QaRoot = $PSScriptRoot
$TaskRoot = (Resolve-Path -LiteralPath (Join-Path $QaRoot '..')).Path
$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $TaskRoot '..\..\..')).Path
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$K60Root = Join-Path $QaRoot 'device-K60-8e875894'
$PadRoot = Join-Path $QaRoot 'device-Pad5-43af8627'
$Apk = Join-Path $TaskRoot 'harness\build\outputs\kokoro-isolated-harness-debug.apk'
$Archive = Join-Path $TaskRoot 'assets\kokoro-multi-lang-v1_1.tar.bz2'
$Aar = Join-Path $TaskRoot 'assets\sherpa-onnx-2.0.1.aar'
$LockFile = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'

function Read-Json {
    param([string] $Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Rel {
    param([string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
    return [IO.Path]::GetRelativePath($RepoRoot, [IO.Path]::GetFullPath($Path)).Replace('\', '/')
}

function FileEvidence {
    param([string] $Path)
    $item = Get-Item -LiteralPath $Path
    return [ordered]@{
        path = Rel $item.FullName
        bytes = $item.Length
        sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Convert-Run {
    param([object] $Run)
    if ($null -eq $Run) { return $null }
    $result = if ($Run.metrics -and $Run.metrics.results) { $Run.metrics.results[0] } else { $null }
    return [ordered]@{
        phase = $Run.phase
        mode = $Run.mode
        completed = $Run.completed
        elapsedWallSeconds = $Run.elapsedWallSeconds
        launch = $Run.launchOutput
        sampleCount = $Run.sampleCount
        missingPssSamples = $Run.missingPssSamples
        samplingIntervalTargetSeconds = $Run.samplingIntervalTargetSeconds
        maxObservedSampleGapSeconds = $Run.maxObservedSampleGapSeconds
        peakPssKb = $Run.peakPssKb
        minPssKb = $Run.minPssKb
        pssCsv = Rel $Run.pssCsv
        logcat = Rel $Run.logcat
        metrics = if ($Run.metricsPath) { Rel $Run.metricsPath } else { $null }
        status = if ($Run.metrics) { $Run.metrics.status } else { $null }
        errorType = if ($Run.metrics) { $Run.metrics.errorType } else { $null }
        error = if ($Run.metrics) { $Run.metrics.error } else { $null }
        speaker = if ($result) { $result.speaker } else { $null }
        sid = if ($result) { $result.sid } else { $null }
        textCharacters = if ($Run.metrics) { $Run.metrics.textCharacters } else { $null }
        textSha256 = if ($Run.metrics) { $Run.metrics.textSha256 } else { $null }
        parameters = if ($Run.metrics) { $Run.metrics.parameters } else { $null }
        ttsLang = if ($Run.metrics) { $Run.metrics.ttsLang } else { $null }
        modelLoadMillis = if ($Run.metrics) { $Run.metrics.modelLoadMillis } else { $null }
        firstAudioMillis = if ($result) { $result.firstAudioMillis } else { $null }
        inferenceMillis = if ($result) { $result.inferenceMillis } else { $null }
        audioDurationMillis = if ($result) { $result.audioDurationMillis } else { $null }
        rtf = if ($result) { $result.rtf } else { $null }
        outputBytes = if ($result) { $result.outputBytes } else { $null }
        outputSha256 = if ($result) { $result.outputSha256 } else { $null }
        wavs = @($Run.wavs | ForEach-Object {
            [ordered]@{
                path = Rel $_.path
                bytes = $_.bytes
                sha256 = $_.sha256
                sampleRate = $_.sampleRate
                channels = $_.channels
                bitsPerSample = $_.bitsPerSample
                durationSeconds = $_.durationSeconds
            }
        })
    }
}

function Compare-PackageState {
    param([object] $Before, [object] $After)
    $fields = @('installed', 'versionName', 'versionCode', 'codePath', 'firstInstallTime', 'lastUpdateTime', 'dataDir', 'primaryCpuAbi', 'userId')
    $differences = @()
    foreach ($packageKey in @('release', 'debug')) {
        foreach ($field in $fields) {
            $beforeValue = [string]$Before.packages.$packageKey.$field
            $afterValue = [string]$After.packages.$packageKey.$field
            if ($beforeValue -ne $afterValue) {
                $differences += [ordered]@{
                    package = $packageKey
                    field = $field
                    before = $beforeValue
                    after = $afterValue
                }
            }
        }
    }
    return $differences
}

function Parse-NetStatsForUid {
    param([string] $Path, [long] $Uid)
    $text = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    $lines = $text -split "`r?`n"
    $inIdentity = $false
    $identityBlocks = 0
    $identityRx = [long]0
    $identityTx = [long]0
    foreach ($line in $lines) {
        if ($line -match '^\s*ident=.*\buid=' + $Uid + '\b') {
            $inIdentity = $true
            $identityBlocks++
        } elseif ($line -match '^\s*ident=') {
            $inIdentity = $false
        }
        if ($inIdentity) {
            foreach ($match in [regex]::Matches($line, '\brb=(\d+)')) {
                $identityRx += [long]$match.Groups[1].Value
            }
            foreach ($match in [regex]::Matches($line, '\btb=(\d+)')) {
                $identityTx += [long]$match.Groups[1].Value
            }
        }
    }
    $mapMatches = @([regex]::Matches(
        $text,
        '(?m)^\s+' + $Uid + '\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$'))
    $mapPresent = $mapMatches.Count -gt 0
    $mapRx = if ($mapPresent) { [long]$mapMatches[-1].Groups[1].Value } else { [long]0 }
    $mapTx = if ($mapPresent) { [long]$mapMatches[-1].Groups[3].Value } else { [long]0 }
    return [ordered]@{
        uid = $Uid
        identityBlockCount = $identityBlocks
        identityRxBytes = $identityRx
        identityTxBytes = $identityTx
        identityTotalBytes = $identityRx + $identityTx
        appUidStatsMapPresent = $mapPresent
        appUidStatsMapRxBytes = $mapRx
        appUidStatsMapTxBytes = $mapTx
        appUidStatsMapTotalBytes = $mapRx + $mapTx
        uidCounterSetPresent = [bool]($text -match ('(?m)^\s+uid=' + $Uid + '\s+set=\d+\s*$'))
    }
}

function Get-LockSummary {
    $lines = Get-Content -LiteralPath $LockFile
    $start = ($lines | Select-String '^## 当前占用').LineNumber
    $end = ($lines | Select-String '^## ' | Where-Object LineNumber -gt $start | Select-Object -First 1).LineNumber
    if (-not $end) { $end = $lines.Count + 1 }
    $activeEntries = @()
    for ($i = $start; $i -lt ($end - 1); $i++) {
        if ($lines[$i] -match '^### .* — ACTIVE$') { $activeEntries += $lines[$i] }
    }
    return [ordered]@{
        file = $LockFile
        activeCount = $activeEntries.Count
        activeEntries = $activeEntries
        finalSha256 = (Get-FileHash -LiteralPath $LockFile -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$k60Base = Read-Json (Join-Path $K60Root 'summary-K60.json')
$k60Remaining = Read-Json (Join-Path $K60Root 'summary-K60-remaining.json')
$k60Before = Read-Json (Join-Path $K60Root 'snapshot-before-K60.json')
$k60After = Read-Json (Join-Path $K60Root 'snapshot-after-K60.json')
$k60Cleanup = Read-Json (Join-Path $K60Root 'cleanup-K60.json')
$padBefore = Read-Json (Join-Path $PadRoot 'snapshot-before-Pad5.json')
$padAfter = Read-Json (Join-Path $PadRoot 'snapshot-after-Pad5.json')
$padCleanup = Read-Json (Join-Path $PadRoot 'cleanup-Pad5.json')
$padRun = Read-Json (Join-Path $PadRoot 'run-Pad5-first-zf.json')

$netBeforePath = Join-Path $K60Root 'netstats-K60-zero-network-before.txt'
$netAfterPath = Join-Path $K60Root 'netstats-K60-zero-network-after.txt'
$k60Uid = [long]$k60Remaining.zeroNetwork.uid
$netBefore = Parse-NetStatsForUid -Path $netBeforePath -Uid $k60Uid
$netAfter = Parse-NetStatsForUid -Path $netAfterPath -Uid $k60Uid
$netSummary = [ordered]@{
    device = 'K60'
    serial = '8e875894'
    uid = $k60Uid
    airplaneModeBefore = $k60Remaining.zeroNetwork.airplaneStateBefore.airplaneMode
    airplaneModeDuring = $k60Remaining.zeroNetwork.airplaneStateDuring.airplaneMode
    activeDefaultNetworkDuring = @($k60Remaining.zeroNetwork.airplaneStateDuring.connectivity | Select-Object -First 1)
    wifiOnDuring = $k60Remaining.zeroNetwork.airplaneStateDuring.wifiOnSetting
    mobileDataSettingDuring = $k60Remaining.zeroNetwork.airplaneStateDuring.mobileDataSetting
    before = $netBefore
    after = $netAfter
    identityRxDeltaBytes = $netAfter.identityRxBytes - $netBefore.identityRxBytes
    identityTxDeltaBytes = $netAfter.identityTxBytes - $netBefore.identityTxBytes
    identityTotalDeltaBytes = $netAfter.identityTotalBytes - $netBefore.identityTotalBytes
    appUidStatsMapRxDeltaBytes = $netAfter.appUidStatsMapRxBytes - $netBefore.appUidStatsMapRxBytes
    appUidStatsMapTxDeltaBytes = $netAfter.appUidStatsMapTxBytes - $netBefore.appUidStatsMapTxBytes
    appUidStatsMapTotalDeltaBytes = $netAfter.appUidStatsMapTotalBytes - $netBefore.appUidStatsMapTotalBytes
    run = Convert-Run $k60Remaining.zeroNetwork.run
    beforeEvidence = Rel $netBeforePath
    afterEvidence = Rel $netAfterPath
}
$netSummaryPath = Join-Path $K60Root 'netstats-K60-zero-network-summary-v2.json'
$netSummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $netSummaryPath -Encoding utf8

$narrationPath = Join-Path $TaskRoot 'samples\narration.txt'
$narration = [IO.File]::ReadAllText($narrationPath, [Text.Encoding]::UTF8).Trim()
$narrationHan = ([regex]::Matches($narration, '[\p{IsCJKUnifiedIdeographs}]')).Count
$longFixturePath = Join-Path $QaRoot 'long-text.txt'
$longFixture = [IO.File]::ReadAllText($longFixturePath, [Text.Encoding]::UTF8).Trim()
$longFixtureSha = (Get-FileHash -LiteralPath $longFixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
$onnxAudit = Read-Json (Join-Path $TaskRoot 'onnx-audit.json')
$id2speaker = [string]$onnxAudit.metadata.id2speaker
$aarEntryText = @(& 'F:\Develop\Android\Android Studio\jbr\bin\jar.exe' tf $Aar 2>&1)
$aarAbis = @($aarEntryText | Where-Object { $_ -match '^jni/[^/]+/.*\.so$' } |
    ForEach-Object { if ($_ -match '^jni/([^/]+)/') { $Matches[1] } } | Sort-Object -Unique)
$aarNativeCount = @($aarEntryText | Where-Object { $_ -match '^jni/[^/]+/.*\.so$' }).Count
$aaptPermissions = @(& 'F:\Develop\Android\AndroidSDK\build-tools\37.0.0\aapt2.exe' dump permissions $Apk 2>&1)

$gitLines = @(& git -C $RepoRoot status --porcelain=v1 --untracked-files=all)
$gitUntracked = @($gitLines | Where-Object { $_ -match '^\?\? ' } | ForEach-Object { $_.Substring(3) })
$gitOutside = @($gitUntracked | Where-Object { $_ -notlike '.planning/v2-g1/G1-L1-KOKORO-EXACT/*' })

$k60Data = [ordered]@{
    serial = '8e875894'
    model = $k60Before.identity.model
    api = [int]$k60Before.identity.api
    androidRelease = $k60Before.identity.androidRelease
    abi = $k60Before.identity.runtimeAbi
    install = $k60Base.install
    firstAudio = [ordered]@{
        zf_001 = Convert-Run $k60Base.zf
        zm_010 = Convert-Run $k60Base.zm
    }
    longText = [ordered]@{
        executedVia = 'existing probe-zf arbitrary-text override'
        fixture = Rel $longFixturePath
        fixtureSha256 = $longFixtureSha
        textCharacters = $longFixture.Length
        hanCharacters = ([regex]::Matches($longFixture, '[\p{IsCJKUnifiedIdeographs}]')).Count
        run = Convert-Run $k60Remaining.longText.run
    }
    backgroundLock = [ordered]@{
        executedShortSupplement = $true
        wakefulnessObserved = 'Asleep'
        elapsedWallSeconds = $k60Base.backgroundShort.elapsedWallSeconds
        sampleCount = $k60Base.backgroundShort.sampleCount
        peakPssKb = $k60Base.backgroundShort.peakPssKb
        completedWithin180Seconds = $k60Base.backgroundShort.completed
        result = 'BLOCKED: NO_BACKGROUND_OWNER'
        reason = 'Manifest/source have no service or foreground owner; one-shot Activity has no 30-minute continuous playback/owner path.'
        evidence = @(
            (Rel (Join-Path $K60Root 'pss-K60-background-short.csv')),
            (Rel (Join-Path $K60Root 'logcat-K60-background-short.txt'))
        )
    }
    processRecreation = [ordered]@{
        result = 'BLOCKED: NO_RESUME_STATE'
        interrupted = $k60Base.interruptedCancel
        completed = $k60Base.completedRestart
        reason = 'No persistent state or resume entry; restart enters preparing and starts a new default both run.'
        evidence = @(
            (Rel (Join-Path $K60Root 'recreate-K60-interrupted.json')),
            (Rel (Join-Path $K60Root 'recreate-K60-completed.json'))
        )
    }
    zeroNetwork = $netSummary
    cancel = [ordered]@{
        internalCancel = 'NOT_IMPLEMENTED'
        hardCancel = 'FORCED_CANCEL'
        pidDuring = $k60Base.interruptedCancel.pidDuring
        pidAfterForceStop = $k60Base.interruptedCancel.pidAfterStop
        partialWavOrTemporaryFileObserved = $false
        metricsPresentAfterForceStop = @($k60Base.interruptedCancel.remoteFilesAfterStop.files | Where-Object { $_ -match 'metrics_zm|zm_010' }).Count -gt 0
        evidence = Rel (Join-Path $K60Root 'cancel-K60-force-stop-files.json')
    }
    audioOwner = [ordered]@{
        result = 'BLOCKED: HARNESS_NOT_AUDIO_OWNER'
        packageInAudioDumpDuringInference = $k60Base.zm.audioOwner.packageInAudioDump
        packageInMediaSessionDumpDuringInference = $k60Base.zm.audioOwner.packageInMediaSessionDump
        manifestServiceCount = 0
        conclusion = 'WAV writer only; no AudioTrack, audio focus, service, or MediaSession ownership to test.'
        evidence = @(
            (Rel $k60Base.zm.audioOwner.audioEvidence),
            (Rel $k60Base.zm.audioOwner.mediaSessionEvidence)
        )
    }
    speaker58 = [ordered]@{
        staticWhitelistRejectsZm009Sid58 = $true
        runtimeForbiddenHits = $k60Remaining.speaker58RuntimeHits
        allowedGenerated = @(
            [ordered]@{ speaker = 'zf_001'; sid = 3 },
            [ordered]@{ speaker = 'zm_010'; sid = 59 }
        )
        evidence = @((Rel (Join-Path $K60Root 'logcat-K60-first-zf.txt')), (Rel (Join-Path $K60Root 'logcat-K60-first-zm.txt')))
    }
    cleanup = $k60Cleanup
}

$padData = [ordered]@{
    serial = '43af8627'
    model = $padBefore.identity.model
    api = [int]$padBefore.identity.api
    androidRelease = $padBefore.identity.androidRelease
    abi = $padBefore.identity.runtimeAbi
    install = [ordered]@{
        result = 'PASS'
        exit = 0
        installedBytes = 426980914
        installedSha256 = '3aeb62d819890fb93ce2e0b6301f14c20ff68f2a448fa530bc9ad947b1dfb671'
        evidence = @(
            (Rel (Join-Path $PadRoot 'install-Pad5-stdout.txt')),
            (Rel (Join-Path $PadRoot 'install-Pad5-stderr.txt'))
        )
    }
    firstAudio = [ordered]@{
        zf_001 = Convert-Run $padRun
        zm_010 = [ordered]@{
            result = 'NOT_RUN_COMPLETE_AFTER_P1_STOP'
            note = 'The QA script automatically launched zm after the zf timeout, but the operator stopped on the newly discovered compatibility P1 before a completed metrics/WAV result was saved.'
        }
    }
    compatibilityFailure = [ordered]@{
        result = 'FAIL'
        type = $padRun.metrics.errorType
        message = $padRun.metrics.error
        firstAudioGenerated = $false
        modelLoadObserved = $false
        declaredMinSdk = 26
        deviceApi = 33
        rootCauseBoundary = 'java.util.HexFormat is unavailable on API 33 and is invoked by the harness text SHA-256 path before model load.'
        evidence = @(
            (Rel (Join-Path $PadRoot 'first-zf-metrics_failure.json')),
            (Rel (Join-Path $PadRoot 'logcat-Pad5-first-zf.txt')),
            (Rel (Join-Path $PadRoot 'pss-Pad5-first-zf.csv'))
        )
    }
    pssValidity = [ordered]@{
        result = 'NOT_VALID_FOR_INFERENCE'
        sampleCount = $padRun.sampleCount
        peakPssKb = $padRun.peakPssKb
        maxObservedSampleGapSeconds = $padRun.maxObservedSampleGapSeconds
        note = 'Activity process stayed alive after worker failure; this PSS is process residency, not model inference peak.'
    }
    stoppedBranches = @('long-text', 'background-lock', 'process-recreation', 'zero-network', 'graceful-cancel', 'audio-owner')
    cleanup = $padCleanup
}

$matrix = @(
    [ordered]@{
        item = '1. install and first audio (zf_001 sid3 / zm_010 sid59)'
        k60 = [ordered]@{ executed = $true; result = 'PASS'; detail = 'both voices, 58.075 s / 58.725 s, within 45-60 s contract' }
        pad5 = [ordered]@{ executed = $true; result = 'FAIL_P1'; detail = 'HexFormat NoClassDefFoundError before model load; zm incomplete after stop' }
    },
    [ordered]@{
        item = '2. interval PSS peak'
        k60 = [ordered]@{ executed = $true; result = 'OBSERVED_NOT_HARDWARE_PEAK'; detail = 'zf 928419 KB; zm 926473 KB; max sample gap 1.511 s' }
        pad5 = [ordered]@{ executed = $true; result = 'NOT_VALID_FOR_INFERENCE'; detail = 'worker crashed; 97315 KB is Activity residency only' }
    },
    [ordered]@{
        item = '3. long text'
        k60 = [ordered]@{ executed = $true; result = 'PASS_VIA_EXISTING_OVERRIDE'; detail = '401 chars / 356 Han; 116.300 s; RTF 0.765262' }
        pad5 = [ordered]@{ executed = $false; result = 'BLOCKED_P1'; detail = 'stopped before long-text branch' }
    },
    [ordered]@{
        item = '4. background + lock screen'
        k60 = [ordered]@{ executed = $true; result = 'BLOCKED: NO_BACKGROUND_OWNER'; detail = '181.5 s asleep, no completion, no service/foreground owner' }
        pad5 = [ordered]@{ executed = $false; result = 'BLOCKED_P1'; detail = 'stopped before branch' }
    },
    [ordered]@{
        item = '5. process recreation/resume'
        k60 = [ordered]@{ executed = $true; result = 'BLOCKED: NO_RESUME_STATE'; detail = 'interrupted and completed force-stop both restart at preparing' }
        pad5 = [ordered]@{ executed = $false; result = 'BLOCKED_P1'; detail = 'stopped before branch' }
    },
    [ordered]@{
        item = '6. airplane / UID zero network'
        k60 = [ordered]@{ executed = $true; result = 'PASS_OBSERVED'; detail = 'UID 10304; active network none; identity and app-map byte deltas 0' }
        pad5 = [ordered]@{ executed = $false; result = 'BLOCKED_P1'; detail = 'stopped before branch' }
    },
    [ordered]@{
        item = '7. cancel'
        k60 = [ordered]@{ executed = $true; result = 'INTERNAL_NOT_IMPLEMENTED / HARD_FORCED_CANCEL'; detail = 'PID gone; no partial WAV/temp output' }
        pad5 = [ordered]@{ executed = $false; result = 'BLOCKED_P1'; detail = 'stopped before branch' }
    },
    [ordered]@{
        item = '8. audio focus / single owner'
        k60 = [ordered]@{ executed = $true; result = 'BLOCKED: HARNESS_NOT_AUDIO_OWNER'; detail = 'no harness entry in audio/media_session; no playback/focus API path' }
        pad5 = [ordered]@{ executed = $false; result = 'BLOCKED_P1'; detail = 'stopped before branch' }
    },
    [ordered]@{
        item = '9. speaker58 / zm_009 regression'
        k60 = [ordered]@{ executed = $true; result = 'PASS_IN_EXECUTED_SCOPE'; detail = 'static reject + runtime hits 0; only sid 3/59 generated' }
        pad5 = [ordered]@{ executed = $false; result = 'NOT_RUN_COMPLETE'; detail = 'static source is shared, but no Pad inference result' }
    },
    [ordered]@{
        item = '10. cleanup / production package stability'
        k60 = [ordered]@{ executed = $true; result = 'PASS'; detail = 'harness uninstalled; external files absent; release/debug exact MATCH' }
        pad5 = [ordered]@{ executed = $true; result = 'PASS'; detail = 'harness uninstalled; external files absent; release exact MATCH; debug remained absent' }
    }
)

$deviceSnapshots = [ordered]@{
    schema = 'aurorama.kokoro-exact.qa-device-snapshots.v1'
    generatedAt = (Get-Date).ToString('o')
    k60 = [ordered]@{
        before = $k60Before
        after = $k60After
        packageDifferences = @(Compare-PackageState -Before $k60Before -After $k60After)
        cleanup = $k60Cleanup
        rawBefore = Rel (Join-Path $K60Root 'snapshot-before-K60.json')
        rawAfter = Rel (Join-Path $K60Root 'snapshot-after-K60.json')
    }
    pad5 = [ordered]@{
        before = $padBefore
        after = $padAfter
        packageDifferences = @(Compare-PackageState -Before $padBefore -After $padAfter)
        cleanup = $padCleanup
        rawBefore = Rel (Join-Path $PadRoot 'snapshot-before-Pad5.json')
        rawAfter = Rel (Join-Path $PadRoot 'snapshot-after-Pad5.json')
    }
}
$deviceSnapshotsPath = Join-Path $QaRoot 'device-snapshots.json'
$deviceSnapshots | ConvertTo-Json -Depth 18 | Set-Content -LiteralPath $deviceSnapshotsPath -Encoding utf8

$p0p3 = [ordered]@{
    P0 = @()
    P1 = @(
        [ordered]@{
            id = 'QA-KOKORO-P1-API-COMPAT'
            scope = 'isolated harness'
            finding = 'Pad 5 API 33 cannot resolve java.util.HexFormat although APK declares minSdk 26.'
            impact = 'Both target voices cannot produce first audio on Pad 5; two-device QA gate is blocked.'
            evidence = @(
                (Rel (Join-Path $PadRoot 'first-zf-metrics_failure.json')),
                (Rel (Join-Path $PadRoot 'logcat-Pad5-first-zf.txt'))
            )
        }
    )
    P2 = @()
    P3 = @(
        [ordered]@{
            id = 'QA-KOKORO-P3-PSS-SEMANTICS'
            finding = 'Independent K60 interval peaks are 928419/926473 KB and differ materially from the implementation summary 736167 KB; interval sampling is still not a hardware-counter peak.'
        },
        [ordered]@{
            id = 'QA-KOKORO-P3-TTS-LANG'
            finding = 'K60 metrics reports ttsLang=en-us while the harness config requests zh; no subjective/pronunciation judgment was made.'
        }
    )
}

$report = [ordered]@{
    schema = 'aurorama.kokoro-exact.independent-qa.v2'
    task = 'V2-G1-L1-KOKORO-EXACT'
    generatedAt = (Get-Date).ToString('o')
    verdict = 'BLOCKED'
    verdictCode = 'BLOCKED_P1_HARNESS_API_COMPAT'
    summary = 'K60 completed both approved voices, long-text override, PSS sampling, forced cancel, zero-network observation and cleanup. Pad 5 installs but fails before first audio on API 33 because the harness references java.util.HexFormat despite minSdk 26; execution stopped on the P1 gate.'
    git = [ordered]@{
        branch = (& git -C $RepoRoot branch --show-current).Trim()
        head = (& git -C $RepoRoot rev-parse HEAD).Trim()
        parent = (& git -C $RepoRoot rev-parse HEAD^).Trim()
        trackedChanges = @($gitLines | Where-Object { $_ -notmatch '^\?\?' }).Count
        untrackedAtGeneration = $gitUntracked.Count
        outsideEvidenceRootAtGeneration = $gitOutside
        evidenceRoot = '.planning/v2-g1/G1-L1-KOKORO-EXACT/'
    }
    deviceLocks = [ordered]@{
        protocol = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
        k60 = [ordered]@{
            lockId = 'G1-L1-KOKORO-EXACT-QA-K60-8e875894-20261105-0116'
            acquired = '2026-11-05 01:16 +08:00'
            released = '2026-11-05 01:29:39 +08:00'
            beforeSha256 = 'aeccfde324c4b83e87d7787e71ebc1689d07be3a6ca355b66ee79c3d18d18a3b'
        }
        pad5 = [ordered]@{
            lockId = 'G1-L1-KOKORO-EXACT-QA-PAD5-43af8627-20261105-0130'
            acquired = '2026-11-05 01:30 +08:00'
            released = '2026-11-05 01:36:27 +08:00'
            beforeSha256 = '106f710268c5c7ccc1bb11862515915017db44dc3ce4775a79b032e316f9486f'
        }
        final = Get-LockSummary
    }
    staticEvidence = [ordered]@{
        apk = [ordered]@{
            path = Rel $Apk
            bytes = (Get-Item -LiteralPath $Apk).Length
            sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
            minSdk = 26
            targetSdk = 37
            abi = 'arm64-v8a'
            permissions = $aaptPermissions
            internetPermissionAbsent = -not ($aaptPermissions -match 'android.permission.INTERNET')
            serviceCount = 0
        }
        kokoroArchive = [ordered]@{
            path = Rel $Archive
            bytes = (Get-Item -LiteralPath $Archive).Length
            sha256 = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        sherpaAar = [ordered]@{
            path = Rel $Aar
            bytes = (Get-Item -LiteralPath $Aar).Length
            sha256 = (Get-FileHash -LiteralPath $Aar -Algorithm SHA256).Hash.ToLowerInvariant()
            abis = $aarAbis
            nativeLibraryCount = $aarNativeCount
        }
        id2speaker = [ordered]@{
            sid3 = 'zf_001'
            sid58 = 'zm_009'
            sid59 = 'zm_010'
            source = 'onnx-audit.json / report.json; sid 3, 58, 59 confirmed in packaged metadata'
        }
        speakerCount = 103
        licenseAndNotice = 'PARTIAL (unchanged from implementation report)'
        narration = [ordered]@{
            textCharacters = $narration.Length
            hanCharacters = $narrationHan
            canonicalUtf8Sha256 = '3908bcd1fa6aa9e57ab544636ed975c93f881c7d599ceb045fd57b953c05bfcb'
        }
    }
    devices = [ordered]@{ k60 = $k60Data; pad5 = $padData }
    matrix = $matrix
    p0p3 = $p0p3
    uncovered = @(
        'Pad 5 first audio for zm_010 was not completed after the P1 stop; zf_001 failed before model load.',
        'Pad 5 long text, background, process recreation, zero network, cancel, and audio owner were not executed.',
        '30-minute continuous background/lock-screen availability is BLOCKED: NO_BACKGROUND_OWNER for the harness.',
        'Pause/last-stable-sentence resume is BLOCKED: NO_RESUME_STATE for the harness.',
        'Audio-focus contention and single MediaSession owner are BLOCKED: HARNESS_NOT_AUDIO_OWNER.',
        'Subjective quality, pronunciation, voice-rights acceptance, production App integration, voice-pack lifecycle, G1/Q03/Release were not signed by QA.'
    )
    maximumClaim = 'On K60 API 35 / arm64-v8a only, the current isolated APK proves zf_001 sid3 and zm_010 sid59 each generate a 45-60 second 24 kHz mono WAV, a 356-Han long-text override completes, and the executed offline/cancel paths converge. It does not prove a two-device runtime, background ownership, resume, audio ownership, production integration, voice quality, or G1 readiness.'
    nextStep = [ordered]@{
        owner = 'implementation owner (not independent QA)'
        action = 'Fix only the harness API-compatibility class and produce a new immutable APK/hash with minSdk-correct SHA-256 code.'
        firstReadOnlyAction = 'Re-read report.json P1 evidence, build info, and HarnessActivity SHA-256 path; confirm no asset drift.'
        stopCondition = 'Stop if the replacement APK still fails on API 33, assets/ABI drift, any new P0-P2 appears, or QA is asked to modify harness/production code instead of testing an immutable candidate.'
    }
    evidence = [ordered]@{
        commands = Rel (Join-Path $QaRoot 'commands.log')
        deviceSnapshots = Rel $deviceSnapshotsPath
        netstatsSummaryV2 = Rel $netSummaryPath
        qaRoot = Rel $QaRoot
        qaTool = Rel (Join-Path $QaRoot 'device-qa.ps1')
    }
    qaProbeCorrection = 'The first K60 pass used an incorrect UID parser and stopped before zero-network/long-text. The same QA owner corrected only qa/device-qa.ps1, uninstalled the harness, then reran the remaining K60 branches. The implementation and harness were not modified.'
}

$reportPath = Join-Path $QaRoot 'report.json'
$report | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $reportPath -Encoding utf8

$md = @()
$md += '# Aurorama G1 Kokoro Exact - Independent QA'
$md += ''
$md += '**INDEPENDENT QA: BLOCKED**'
$md += ''
$md += '- Branch/HEAD: `' + $report.git.branch + '` / `' + $report.git.head + '`; tracked=0, outside evidence root=0.'
$md += '- Verdict: `BLOCKED_P1_HARNESS_API_COMPAT`; P0–P3 = `0/1/0/2`.'
$md += '- Device locks released: ACTIVE=`' + $report.deviceLocks.final.activeCount + '`; final lock SHA-256 `' + $report.deviceLocks.final.finalSha256 + '`.'
$md += ''
$md += '## K60'
$md += ''
$md += '| Voice | API/ABI | Load ms | First ms | Inference ms | Audio s | RTF | Peak PSS KB | Samples/max gap |'
$md += '|---|---|---:|---:|---:|---:|---:|---:|---:|'
foreach ($voice in @('zf_001', 'zm_010')) {
    $run = $k60Data.firstAudio.$voice
    $md += '| `' + $voice + '` | 35 / arm64-v8a | ' + $run.modelLoadMillis + ' | ' + $run.firstAudioMillis + ' | ' + $run.inferenceMillis + ' | ' + ([double]$run.audioDurationMillis / 1000).ToString('0.000') + ' | ' + $run.rtf + ' | ' + $run.peakPssKb + ' | ' + $run.sampleCount + ' / ' + $run.maxObservedSampleGapSeconds + ' s |'
}
$md += ''
$md += '- Long text: 401 chars / 356 Han via existing `probe-zf` override; 116.300 s, RTF `0.765262`, peak PSS `926,968 KB`, no truncation/crash.'
$md += '- Zero network: UID `10304`; airplane=1, active default network=`none`; identity blocks and `mAppUidStatsMap` both show rx/tx delta 0.'
$md += '- Background: 181.5 s screen-asleep supplement did not complete; source/manifest have no service owner => `BLOCKED: NO_BACKGROUND_OWNER`.'
$md += '- Restart: interrupted/completed force-stop both return to `preparing`; no persistence/resume contract => `BLOCKED: NO_RESUME_STATE`.'
$md += '- Cancel: no internal cancel; forced cancel leaves PID null and no partial WAV/temp output.'
$md += '- Audio owner: dumpsys shows no harness audio/media session => `BLOCKED: HARNESS_NOT_AUDIO_OWNER`.'
$md += '- speaker58 / `zm_009`: static whitelist rejects; runtime forbidden hits 0.'
$md += ''
$md += '## Pad 5'
$md += ''
$md += '- Device: API 33 / arm64-v8a; APK install exit 0 and base SHA MATCH.'
$md += '- P1: `java.lang.NoClassDefFoundError: java/util/HexFormat` before model load, although APK declares minSdk 26.'
$md += '- `zf_001` produced no WAV/first-audio/RTF; `zm_010` was not accepted as complete after the stop.'
$md += '- All later Pad branches stopped under the new-P0–P2 rule.'
$md += ''
$md += '## Cleanup'
$md += ''
$md += '- Both harness installs uninstalled successfully; PID and package external directories absent.'
$md += '- Production packages before/after MATCH on both devices; no user data, account, server, or security settings changed.'
$md += '- Network/screen restored: K60 Wi-Fi active network 174, Pad Wi-Fi active network 186; both Awake/unlocked; ACTIVE=0.'
$md += ''
$md += '## Maximum Claim'
$md += ''
$md += $report.maximumClaim
$md += ''
$md += 'Evidence: `report.json`, `device-snapshots.json`, `commands.log`, `logcat-*`, `netstats-*`, `pss-*.csv` under this QA root.'

$reportMdPath = Join-Path $QaRoot 'report.md'
[IO.File]::WriteAllLines($reportMdPath, [string[]]$md, $Utf8NoBom)

$summaryFiles = @(
    $reportPath,
    $deviceSnapshotsPath,
    $netSummaryPath,
    $reportMdPath,
    (Join-Path $QaRoot 'commands.log'),
    (Join-Path $K60Root 'first-zf-metrics_zf.json'),
    (Join-Path $K60Root 'first-zm-metrics_zm.json'),
    (Join-Path $K60Root 'long-text-metrics_probe-zf.json'),
    (Join-Path $PadRoot 'first-zf-metrics_failure.json')
)
$sumLines = foreach ($file in $summaryFiles) {
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $(Rel $file)"
}
[IO.File]::WriteAllLines((Join-Path $QaRoot 'SHA256SUMS'), [string[]]$sumLines, $Utf8NoBom)

[ordered]@{
    report = Rel $reportPath
    reportMarkdown = Rel $reportMdPath
    deviceSnapshots = Rel $deviceSnapshotsPath
    netstatsSummary = Rel $netSummaryPath
    verdict = $report.verdict
    p0p3 = [ordered]@{ P0 = 0; P1 = 1; P2 = 0; P3 = 2 }
    activeLocks = $report.deviceLocks.final.activeCount
} | ConvertTo-Json -Depth 4
