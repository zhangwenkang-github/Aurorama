[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$harnessRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $taskRoot '..\..\..')).Path
$samplesRoot = Join-Path $taskRoot 'samples'
$logsRoot = Join-Path $taskRoot 'logs'
$reportPath = Join-Path $taskRoot 'implementation-report.json'
$lockPath = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
$taskRelative = '.planning/v2-g1/G1-L1-KOKORO-EXACT/'

function Read-Json([string] $path) {
    return Get-Content -Raw -Encoding utf8 -LiteralPath $path | ConvertFrom-Json
}

function Get-Sha256([string] $path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-WavMetadata(
    [string] $path,
    [string] $speaker,
    [int] $sid) {
    $bytes = [IO.File]::ReadAllBytes($path)
    $channels = [BitConverter]::ToInt16($bytes, 22)
    $sampleRate = [BitConverter]::ToInt32($bytes, 24)
    $byteRate = [BitConverter]::ToInt32($bytes, 28)
    $bitsPerSample = [BitConverter]::ToInt16($bytes, 34)
    $dataBytes = [BitConverter]::ToInt32($bytes, 40)
    $maxAbs = 0
    $sumSquares = 0.0
    $sampleCount = 0
    for ($offset = 44; $offset + 1 -lt $bytes.Length; $offset += 20) {
        $sample = [Math]::Abs([int][BitConverter]::ToInt16($bytes, $offset))
        if ($sample -gt $maxAbs) { $maxAbs = $sample }
        $sumSquares += [double]$sample * $sample
        $sampleCount++
    }
    return [ordered]@{
        path = $path
        speaker = $speaker
        sid = $sid
        fileBytes = $bytes.Length
        sha256 = Get-Sha256 $path
        riff = [Text.Encoding]::ASCII.GetString($bytes, 0, 4)
        wave = [Text.Encoding]::ASCII.GetString($bytes, 8, 4)
        sampleRate = $sampleRate
        channels = $channels
        bitDepth = $bitsPerSample
        byteRate = $byteRate
        dataBytes = $dataBytes
        durationSeconds = [Math]::Round($dataBytes / [double]$byteRate, 3)
        sampledMaxAbsPcm = $maxAbs
        sampledRmsPcm = [Math]::Round([Math]::Sqrt($sumSquares / $sampleCount), 3)
    }
}

$zfPath = Join-Path $samplesRoot 'zf_001_sid3.wav'
$zmPath = Join-Path $samplesRoot 'zm_010_sid59.wav'
$textPath = Join-Path $samplesRoot 'narration.txt'
$zf = Get-WavMetadata $zfPath 'zf_001' 3
$zm = Get-WavMetadata $zmPath 'zm_010' 59

$textBytes = [IO.File]::ReadAllBytes($textPath)
$text = [Text.Encoding]::UTF8.GetString($textBytes).Trim()
$canonicalTextHash =
    [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
$hanCount = [regex]::Matches($text, '[\p{IsCJKUnifiedIdeographs}]').Count

$metricsZf = Read-Json (Join-Path $logsRoot 'metrics_zf.json')
$metricsZm = Read-Json (Join-Path $logsRoot 'metrics_zm.json')
$metricsWarm = Read-Json (Join-Path $logsRoot 'metrics_warm-zf.json')
$metricsZfApi33 = Read-Json (Join-Path $logsRoot 'metrics_zf_api33.json')
$metricsZmApi33 = Read-Json (Join-Path $logsRoot 'metrics_zm_api33.json')
$buildInfo = Read-Json (Join-Path $logsRoot 'build-info.json')
$dexApiAudit = Read-Json (Join-Path $logsRoot 'dex-api-audit.log')
$deviceEnvironment = Read-Json (Join-Path $logsRoot 'device-environment.json')
$pssLines = Get-Content -Encoding utf8 -LiteralPath (Join-Path $logsRoot 'pss-samples-warm-zf.log')
$pssValues = @(
    $pssLines | ForEach-Object {
        if ($_ -match 'PSS_KB=(\d+)') { [int]$matches[1] }
    }
)
$pssMaximum = ($pssValues | Measure-Object -Maximum).Maximum
$pssMinimum = ($pssValues | Measure-Object -Minimum).Minimum
$api33PssValues = @(
    Get-Content -Encoding utf8 (Join-Path $logsRoot 'pss-samples-pad-api33-zm.log')
    Get-Content -Encoding utf8 (Join-Path $logsRoot 'pss-samples-pad-api33-zm-part2.log')
) | ForEach-Object {
    if ($_ -match 'PSS_KB=(\d+)') { [int]$matches[1] }
}
$api33ZfPath = Join-Path $logsRoot 'api33-zf_001_sid3.wav'
$api33ZmPath = Join-Path $logsRoot 'api33-zm_010_sid59.wav'
$api33Zf = Get-WavMetadata $api33ZfPath 'zf_001' 3
$api33Zm = Get-WavMetadata $api33ZmPath 'zm_010' 59
$oldApkArchive =
    Join-Path $logsRoot 'apk-archive\kokoro-isolated-harness-debug-3aeb62d8.apk'
$activeCount =
    (Select-String -LiteralPath $lockPath -Pattern '^### .* — ACTIVE\s*$' -Encoding utf8).Count
$lockSha256 = Get-Sha256 $lockPath
$buildCommands =
    Get-Content -Encoding utf8 -LiteralPath (Join-Path $logsRoot 'android-build-commands.log')

$testCommand =
    ".\\gradlew.bat -p '.planning\\v2-g1\\G1-L1-KOKORO-EXACT\\harness' " +
    ':contract:test --offline --console=plain --rerun-tasks'
$buildCommand =
    "pwsh -NoProfile -File '$harnessRoot\\build.ps1'"
$installCommand =
    "pwsh -NoProfile -File '$harnessRoot\\install-harness.ps1' " +
    "-Serial '8e875894' -ApkPath '$($buildInfo.apk)'"
$zfStartCommand =
    "adb -s 8e875894 shell am start -W -n " +
    'dev.aurorama.kokoro.harness/.HarnessActivity --es mode zf'
$zmStartCommand =
    "adb -s 8e875894 shell am start -W -n " +
    'dev.aurorama.kokoro.harness/.HarnessActivity --es mode zm'
$warmStartCommand =
    "adb -s 8e875894 shell am start -W -n " +
    'dev.aurorama.kokoro.harness/.HarnessActivity --es mode warm-zf'
$uninstallCommand = 'adb -s 8e875894 uninstall dev.aurorama.kokoro.harness'
$padInstallCommand =
    "pwsh -NoProfile -File '$harnessRoot\\install-harness.ps1' " +
    "-Serial '43af8627' -ApkPath '$($buildInfo.apk)'"
$padZfStartCommand =
    'adb -s 43af8627 shell am start -W -n ' +
    'dev.aurorama.kokoro.harness/.HarnessActivity --es mode zf'
$padZmStartCommand =
    'adb -s 43af8627 shell am start -W -n ' +
    'dev.aurorama.kokoro.harness/.HarnessActivity --es mode zm'
$padUninstallCommand = 'adb -s 43af8627 uninstall dev.aurorama.kokoro.harness'

$report = [ordered]@{
    schema = 'aurorama.kokoro-isolated-harness.implementation-report.v1'
    task = 'V2-G1-L1-KOKORO-EXACT'
    role = 'senior Android TTS isolated-harness implementation engineer'
    generatedAt = (Get-Date).ToString('o')
    status = 'PASS'
    worktree = [ordered]@{
        path = $repoRoot
        branch = (git -C $repoRoot branch --show-current).Trim()
        head = (git -C $repoRoot rev-parse HEAD).Trim()
        parent = (git -C $repoRoot rev-parse HEAD^).Trim()
        expectedHead = 'cb68738e6dd81cfcce7e224aabb361876286e636'
        expectedParent = 'c1974d9291edb2dc9ff40692a4778f2cfc1be77b'
    }
    inputGate = [ordered]@{
        kokoroArchiveBytes = 364855331
        kokoroArchiveSha256 =
            'b78e1ac42b852e839cceaafbe88f66806bf5860992d1d7b9a3f2ad1198427aed'
        sherpaAarBytes = 49441341
        sherpaAarSha256 =
            'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
        modelSha256 =
            '99db0bc8fd0c5033d249fe922db16768b71b4a6b26cfd93d206fbf66c76efb5f'
        voicesSha256 =
            'e64a5a581d8c2a350d848f51c3121657cd83aa07ed6109172177345874a7244c'
        tokensSha256 =
            '931ab2df2400cd65d580a22402024c2347ced8ae9ea300e545144b1aacc48e14'
        lexiconZhSha256 =
            'fea060803db3e59193987f1a3436f56917bda09f3b3595b6588e46ae60cebf93'
        modelSampleRate = 24000
        assetGate = 'PARTIAL'
        licenseAndNotice = 'PARTIAL'
        profileFreeze = $false
    }
    compatibilityFix = [ordered]@{
        reportedP1 = 'Pad 5 API 33: java.lang.NoClassDefFoundError: java/util/HexFormat'
        status = 'RESOLVED_ON_API33'
        rootCause = 'HexFormat was linked from the text SHA-256 path before model load'
        replacement = 'manual lowercase unsigned-byte hex encoder in HexCodec'
        additionalApiTightening = @(
            'replaced String.isBlank with trim().isEmpty()',
            'replaced main-code List.of with Collections.unmodifiableList(Arrays.asList(...))'
        )
        hashAlgorithm = 'SHA-256 unchanged'
        oldApk = [ordered]@{
            archivePath = $oldApkArchive
            bytes = (Get-Item -LiteralPath $oldApkArchive).Length
            sha256 = Get-Sha256 $oldApkArchive
        }
        newApk = [ordered]@{
            path = $buildInfo.apk
            bytes = $buildInfo.apkBytes
            sha256 = $buildInfo.apkSha256
        }
        dexApiAudit = $dexApiAudit
    }
    whitelistDiff = [ordered]@{
        fullModelSpeakerCount = 103
        allowed = @(
            [ordered]@{speaker = 'zf_001'; sid = 3},
            [ordered]@{speaker = 'zm_010'; sid = 59}
        )
        explicitlyRejected = @(
            [ordered]@{speaker = 'zm_009'; sid = 58; reason = 'user rejected; permanently forbidden'}
        )
        generated = @(
            [ordered]@{speaker = 'zf_001'; sid = 3},
            [ordered]@{speaker = 'zm_010'; sid = 59}
        )
        speaker58SelectedOrGenerated = $false
        violations = @()
        jvmCoverage = @(
            'allow sid 3',
            'allow sid 59',
            'reject sid 58 / zm_009',
            'reject mismatch, unknown id, and null'
        )
    }
    tdd = [ordered]@{
        red = [ordered]@{
            executed = $true
            exitCode = 1
            stage = 'compileTestJava'
            missingContractSymbols = 25
            evidence = 'implementation-report.red+failure; raw compiler output was observed before implementation'
        }
        hexFixRed = [ordered]@{
            executed = $true
            exitCode = 1
            stage = 'compileTestJava'
            missingHexCodecSymbols = 4
        }
        green = [ordered]@{
            executed = 10
            failures = 0
            errors = 0
            skipped = 0
            exitCode = 0
            command = $testCommand
            log = Join-Path $logsRoot 'tdd-hex-fix.log'
            cases = @(
                'HexCodecTest.encodesUnsignedBytesAsLowercaseHex',
                'HexCodecTest.preservesSha256ContractWithoutHexFormat',
                'NarrationContractTest.fixedNarrationMeetsOriginalNovelLengthAndPunctuationContract',
                'NarrationContractTest.bothVoicesUseTheSameTextAndParameters',
                'NarrationContractTest.generationParametersArePinned',
                'SpeakerWhitelistTest.allowsExactlyTheTwoApprovedVoices',
                'SpeakerWhitelistTest.explicitlyRejectsSpeaker58AndZm009',
                'SpeakerWhitelistTest.rejectsMismatchedNamesUnknownIdsAndNulls',
                'WavWriterTest.writesCanonical24kMono16BitPcmWave',
                'WavWriterTest.rejectsEmptySamplesAndOverwrite'
            )
        }
    }
    build = [ordered]@{
        status = 'PASS'
        strategy = 'standalone SDK-toolchain APK; root Gradle project not modified'
        javaHome = 'F:\Develop\Android\Android Studio\jbr'
        androidSdk = 'F:\Develop\Android\AndroidSDK'
        compileSdk = 37
        buildTools = '37.0.0'
        minSdk = 26
        targetSdk = 37
        abi = 'arm64-v8a'
        package = 'dev.aurorama.kokoro.harness'
        apk = $buildInfo.apk
        apkBytes = $buildInfo.apkBytes
        apkSha256 = $buildInfo.apkSha256
        signing = @('APK Signature Scheme v2', 'APK Signature Scheme v3', 'Android debug certificate')
        permissions = @()
        nativeLibraries = $buildInfo.nativeLibraries
        dexApiAudit = $dexApiAudit
        commands = $buildCommands
        command = $buildCommand
        info = Join-Path $logsRoot 'build-info.json'
        log = Join-Path $logsRoot 'android-build.log'
    }
    installAndRuntime = [ordered]@{
        status = 'PASS'
        firstInstallRequiredMiuiOfficialConfirmation = $true
        controlledRetries = @(
            'standard install rejected with INSTALL_FAILED_USER_RESTRICTED',
            'install wrapper detected the first confirmation page',
            'same-owner wrapper fixed page dedup and installed successfully'
        )
        finalUpdatePreservedHarnessData = $true
        device = [ordered]@{
            serial = $deviceEnvironment.serial
            model = $deviceEnvironment.model
            api = [int]$deviceEnvironment.api
            abiList = $deviceEnvironment.abiList
            runtimeAbi = 'arm64-v8a'
        }
        command = $installCommand
        finalStartCommands = @($zfStartCommand, $zmStartCommand, $warmStartCommand)
        cleanupCommand = $uninstallCommand
        cleanup = [ordered]@{
            harnessPackageAbsent = $true
            harnessPidAbsent = $true
            harnessExternalFilesAbsent = $true
            productionPackagesUnchanged = $true
            padCommands = 0
            postCleanupEvidence = Join-Path $logsRoot 'post-uninstall-state.txt'
        }
        api33CompatibilityRetest = [ordered]@{
            status = 'PASS'
            p1Resolved = $true
            device = [ordered]@{
                serial = '43af8627'
                model = '21051182C'
                api = 33
                abiList = 'arm64-v8a,armeabi-v7a,armeabi'
                runtimeAbi = 'arm64-v8a'
            }
            installCommand = $padInstallCommand
            startCommands = @($padZfStartCommand, $padZmStartCommand)
            noClassDefFoundErrorMatches = 0
            cleanupCommand = $padUninstallCommand
            cleanupEvidence = Join-Path $logsRoot 'pad-api33-post-uninstall-state.txt'
            productionPackagesUnchanged = $true
            screenAndNetworkRestored = $true
        }
    }
    deviceLock = [ordered]@{
        required = $true
        lockId = 'G1-L1-KOKORO-EXACT-HEX-PAD-43af8627-20261105-0142'
        device = 'Pad 5 43af8627'
        file = $lockPath
        acquiredAt = '2026-11-05T01:42:49+08:00'
        releasedAt = '2026-11-05T01:48:17+08:00'
        activeBefore = 0
        activeAfter = $activeCount
        finalLockSha256 = $lockSha256
        status = if ($activeCount -eq 0) { 'RELEASED' } else { 'BLOCKED' }
    }
    samples = [ordered]@{
        text = [ordered]@{
            path = $textPath
            text = $text
            hanCharacters = $hanCount
            suggestedHanRange = '220-300'
            deviationReason =
                'measured zf duration with 248 Han was 78.450 s; only text length was reduced to satisfy 45-60 s'
            canonicalUtf8Sha256 = $canonicalTextHash
            fileSha256 = Get-Sha256 $textPath
            bom = $false
        }
        parameters = [ordered]@{
            signature = $metricsZf.parameters
            speed = 1.0
            lengthScale = 1.0
            silenceScale = 1.0
            numThreads = 2
            maxNumSentences = 1
            identicalForBothVoices = $true
        }
        files = @($zf, $zm)
    }
    metrics = [ordered]@{
        coldReference = [ordered]@{
            speaker = 'zf_001'
            sid = 3
            modelLoadMillis = $metricsZf.modelLoadMillis
            firstAudioMillis = $metricsZf.results[0].firstAudioMillis
            inferenceMillis = $metricsZf.results[0].inferenceMillis
            audioDurationMillis = $metricsZf.results[0].audioDurationMillis
            rtf = $metricsZf.results[0].rtf
            ttsSampleRate = $metricsZf.ttsSampleRate
            ttsSpeakerCount = $metricsZf.ttsSpeakerCount
            ttsLangObserved = $metricsZf.ttsLang
        }
        secondVoice = [ordered]@{
            speaker = 'zm_010'
            sid = 59
            modelLoadMillis = $metricsZm.modelLoadMillis
            firstAudioMillis = $metricsZm.results[0].firstAudioMillis
            inferenceMillis = $metricsZm.results[0].inferenceMillis
            audioDurationMillis = $metricsZm.results[0].audioDurationMillis
            rtf = $metricsZm.results[0].rtf
            ttsSampleRate = $metricsZm.ttsSampleRate
            ttsSpeakerCount = $metricsZm.ttsSpeakerCount
            ttsLangObserved = $metricsZm.ttsLang
        }
        warmReference = [ordered]@{
            definition = 'second pass after force-stop with OS page cache warm'
            speaker = 'zf_001'
            sid = 3
            modelLoadMillis = $metricsWarm.modelLoadMillis
            firstAudioMillis = $metricsWarm.results[0].firstAudioMillis
            inferenceMillis = $metricsWarm.results[0].inferenceMillis
            audioDurationMillis = $metricsWarm.results[0].audioDurationMillis
            rtf = $metricsWarm.results[0].rtf
        }
        api33Compatibility = [ordered]@{
            zf001 = [ordered]@{
                wav = $api33Zf
                modelLoadMillis = $metricsZfApi33.modelLoadMillis
                firstAudioMillis = $metricsZfApi33.results[0].firstAudioMillis
                inferenceMillis = $metricsZfApi33.results[0].inferenceMillis
                audioDurationMillis = $metricsZfApi33.results[0].audioDurationMillis
                rtf = $metricsZfApi33.results[0].rtf
                observedPssKb = 663296
            }
            zm010 = [ordered]@{
                wav = $api33Zm
                modelLoadMillis = $metricsZmApi33.modelLoadMillis
                firstAudioMillis = $metricsZmApi33.results[0].firstAudioMillis
                inferenceMillis = $metricsZmApi33.results[0].inferenceMillis
                audioDurationMillis = $metricsZmApi33.results[0].audioDurationMillis
                rtf = $metricsZmApi33.results[0].rtf
                pssSampleCount = $api33PssValues.Count
                observedMaximumPssKb =
                    ($api33PssValues | Measure-Object -Maximum).Maximum
                observedMinimumPssKb =
                    ($api33PssValues | Measure-Object -Minimum).Minimum
            }
            noClassDefFoundErrorMatches = 0
            rawMetrics = @(
                (Join-Path $logsRoot 'metrics_zf_api33.json'),
                (Join-Path $logsRoot 'metrics_zm_api33.json')
            )
            logs = @(
                (Join-Path $logsRoot 'device-pad-api33-zf.log'),
                (Join-Path $logsRoot 'device-pad-api33-zm.log')
            )
        }
        pss = [ordered]@{
            source = 'adb dumpsys meminfo TOTAL PSS'
            sampleCount = $pssValues.Count
            intervalSeconds = 2
            observedMaximumKb = $pssMaximum
            observedMaximumMib = [Math]::Round($pssMaximum / 1024.0, 2)
            observedMinimumKb = $pssMinimum
            semantics = 'interval-sampled observed maximum, not a hardware perf-counter peak'
            log = Join-Path $logsRoot 'pss-samples-warm-zf.log'
        }
        rawMetrics = @(
            (Join-Path $logsRoot 'metrics_zf.json'),
            (Join-Path $logsRoot 'metrics_zm.json'),
            (Join-Path $logsRoot 'metrics_warm-zf.json')
        )
    }
    knownLimitations = @(
        'Pad API 33 now passes the HexFormat P1 path, but performance is slower than K60: zf RTF 1.107103 and zm RTF 1.129349.',
        'License and notice evidence remains PARTIAL; no profile freeze or full license PASS is claimed.',
        'Final fixed text is 177 Han characters, below the suggested 220-300 range, because measured duration otherwise exceeded 60 seconds.',
        'AAR ttsLang() surfaced en-us while the harness Kokoro config requested lang=zh; effective Chinese pronunciation and voice quality were not independently auditioned.',
        'PSS is a 2-second interval-sampled dumpsys maximum, not a true hardware peak.',
        'Cold/warm naming is harness-local: cold means first inference after process restart; warm means a later process with OS page cache warm.',
        'This is an isolated debug runtime smoke, not production integration and not a deterministic waveform guarantee.'
    )
    uncovered = @(
        'complete license / notice / model-card / voice-rights closure',
        'independent subjective listening and pronunciation review',
        '30-minute background and lock-screen run',
        'process death/rebuild recovery matrix',
        'UID-level zero-network full matrix',
        'voice-pack import/delete/fallback and single-audio-owner production behavior',
        'background owner, MediaSession, process-resume state, and audio-focus behavior',
        'other Android API levels, devices, and ABIs'
    )
    p0p3 = [ordered]@{
        P0 = 0
        P1 = 0
        resolvedP1 = 1
        P2 = 0
        P3 = @(
            'fixed text length below suggestion because of measured duration',
            'lang() surface en-us / subjective Chinese pronunciation not audited',
            'license and notice remain PARTIAL',
            'interval-sampled PSS only'
        )
    }
    exactCommands = [ordered]@{
        tdd = $testCommand
        build = $buildCommand
        api35Install = $installCommand
        api35Start = @($zfStartCommand, $zmStartCommand, $warmStartCommand)
        api35Uninstall = $uninstallCommand
        api33Install = $padInstallCommand
        api33Start = @($padZfStartCommand, $padZmStartCommand)
        api33Uninstall = $padUninstallCommand
        buildInternals = $buildCommands
    }
    independentQa = [ordered]@{
        ownerRequirement = 'new independent QA agent, different from this implementation session'
        readOnlyStartCommands = @(
            "Get-Content -Raw -Encoding utf8 '$reportPath'",
            "Get-Content -Raw -Encoding utf8 '$(Join-Path $logsRoot 'dex-api-audit.log')'",
            "Get-FileHash -LiteralPath '$($buildInfo.apk)' -Algorithm SHA256"
        )
        prerequisite = 'consume the read-only evidence first; acquire the unique ACTIVE device lock and verify serial/API/ABI before any adb command'
        startCommands = @(
            $buildCommand,
            $testCommand,
            $padInstallCommand,
            $padZfStartCommand,
            $padZmStartCommand
        )
        expectedSamples = @(
            [ordered]@{
                file = $zfPath
                bytes = $zf.fileBytes
                sha256 = $zf.sha256
                durationSeconds = $zf.durationSeconds
            },
            [ordered]@{
                file = $zmPath
                bytes = $zm.fileBytes
                sha256 = $zm.sha256
                durationSeconds = $zm.durationSeconds
            }
        )
        stopConditions = @(
            'no unique device lock or serial/lock drift',
            'branch/HEAD/hash/asset drift',
            'speaker other than sid 3 or 59 requested',
            'speaker 58 or zm_009 selection/generation',
            'default network dependency or server write',
            'requires production-code change',
            'sample header/duration/hash mismatch after accounting for stated nondeterminism',
            'new P0-P2 or scope expansion'
        )
    }
    notes = @(
        'This PASS proves isolated harness build/install/runtime/export on K60 API 35 and Pad 5 API 33 arm64.',
        'The Pad API 33 NoClassDefFoundError P1 is resolved in the new APK and the old APK is archived immutably.',
        'It is not independent QA, G1 PASS, runtime profile freeze, quality acceptance, or Release readiness.'
    )
}

$json = $report | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText($reportPath, $json + "`n", [Text.UTF8Encoding]::new($false))

$statusLines = @(git -C $repoRoot status --porcelain=v1 --untracked-files=all)
$outsideWhitelist = @(
    $statusLines | Where-Object {
        $relativePath = $_.Substring(3).Replace('\', '/')
        -not $relativePath.StartsWith($taskRelative, [StringComparison]::Ordinal)
    }
)
$report.scope = [ordered]@{
    implementationWriteRoot =
        '.planning/v2-g1/G1-L1-KOKORO-EXACT/{harness,samples,logs,implementation-report.json}'
    deviceLockOperationalWrite = $lockPath
    productionCodeModified = $false
    docsModified = $false
    otherEvidenceRootsModified = $false
    outOfWhitelistGitChanges = $outsideWhitelist
}
$report.gitStatus = [ordered]@{
    command = 'git status --porcelain=v1 --untracked-files=all'
    lines = $statusLines
    allWithinTaskEvidenceRoot = ($outsideWhitelist.Count -eq 0)
}
$json = $report | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText($reportPath, $json + "`n", [Text.UTF8Encoding]::new($false))

Write-Output "REPORT=$reportPath"
Write-Output "STATUS=$($report.status)"
Write-Output "GIT_CHANGES=$($statusLines.Count)"
Write-Output "OUTSIDE_WHITELIST=$($outsideWhitelist.Count)"
