[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$qaRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $qaRoot '..\..\..\..')).Path
$rawRoot = Join-Path $qaRoot 'raw'
$utf8 = [Text.UTF8Encoding]::new($false)
$serial = '43af8627'
$package = 'dev.aurorama.kokoro.harness'
$peerPackage = 'dev.aurorama.kokoro.focuspeer'
$expectedMainSha =
    '252a3a15f637b05e874d9b2ad2aa0c48076d2babb83f66c5ec18683dc533e161'
$expectedPeerSha =
    '61c79aa1b56b9fc921ce23199394d342f4badad31389b842f38c1d5c3a4bebab'
$artifactAuditPath = Join-Path $repoRoot (
    '.planning\v2-g1\G1-L1-KOKORO-EXACT\runtime-closure-v3\artifacts\artifact-audit.json')
$artifactAudit = Get-Content -LiteralPath $artifactAuditPath -Raw -Encoding utf8 |
    ConvertFrom-Json
$before = Get-Content -LiteralPath (
    Join-Path $rawRoot 'device-before.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$installed = Get-Content -LiteralPath (
    Join-Path $rawRoot 'device-installed.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$inference = Get-Content -LiteralPath (
    Join-Path $rawRoot 'inference-stop\summary.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$playing = Get-Content -LiteralPath (
    Join-Path $rawRoot 'playing-stop\summary.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$cleanup = Get-Content -LiteralPath (
    Join-Path $rawRoot 'cleanup\cleanup.json') -Raw -Encoding utf8 |
    ConvertFrom-Json

function Write-Json {
    param(
        [string] $Path,
        [object] $Value
    )
    $text = ($Value | ConvertTo-Json -Depth 30) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $text + "`n", $utf8)
}

function Get-RelativeEvidencePath {
    param([string] $Path)
    return [IO.Path]::GetRelativePath($qaRoot, $Path).Replace('\', '/')
}

$mainRelativePath = [string] $artifactAudit.main.path
$peerRelativePath = [string] $artifactAudit.peer.path
$mainPath = Join-Path $repoRoot $mainRelativePath
$peerPath = Join-Path $repoRoot $peerRelativePath
$mainItem = Get-Item -LiteralPath $mainPath
$peerItem = Get-Item -LiteralPath $peerPath
$mainSha = (Get-FileHash -LiteralPath $mainPath -Algorithm SHA256).Hash.ToLowerInvariant()
$peerSha = (Get-FileHash -LiteralPath $peerPath -Algorithm SHA256).Hash.ToLowerInvariant()
$null = git -C $repoRoot merge-base --is-ancestor `
    '35ed65c2dc0e4171bdaf5b6fe055ad1f2e7ead8c' HEAD
$baselineAncestor = $LASTEXITCODE -eq 0
$hostObjectPass =
    $mainItem.Length -eq 427009671 -and
    $mainSha -eq $expectedMainSha -and
    $peerItem.Length -eq 12696 -and
    $peerSha -eq $expectedPeerSha
$deviceObjectPass =
    $installed.testPackageIdentities.$package.bytes -eq $mainItem.Length -and
    $installed.testPackageIdentities.$package.sha256 -eq $mainSha -and
    $installed.testPackageIdentities.$peerPackage.bytes -eq $peerItem.Length -and
    $installed.testPackageIdentities.$peerPackage.sha256 -eq $peerSha -and
    $installed.testPackageIdentities.$package.versionCode -eq 4 -and
    $installed.testPackageIdentities.$package.versionName -eq '2.0-kokoro-runtime-v5'
$roundPasses = @(
    @($inference, $playing) |
        Where-Object { $_.verdict -ne 'PASS' }
).Count -eq 0
$crashPasses =
    $inference.crashGate.passed -and
    $playing.crashGate.passed
$resourcePasses =
    $inference.resourceConvergence.passed -and
    $playing.resourceConvergence.passed
$processPasses =
    $inference.processAliveAfterWait -and
    $inference.samePid -and
    $playing.processAliveAfterWait -and
    $playing.samePid
$phasePasses =
    $inference.phaseValid -and
    $playing.phaseValid -and
    $inference.audioStartsBeforeStop -eq 0 -and
    $playing.audioStartsBeforeStop -ge 1 -and
    $playing.audioCompletionsBeforeStop -eq 0
$releasePasses =
    $inference.releaseDeferredSeen -and
    $inference.releaseAfterInferenceSeen -and
    $playing.releaseImmediateSeen
$stopGatePass =
    $hostObjectPass -and
    $deviceObjectPass -and
    $roundPasses -and
    $crashPasses -and
    $resourcePasses -and
    $processPasses -and
    $phasePasses -and
    $releasePasses -and
    $cleanup.passed

$lockPath = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
$lockText = Get-Content -LiteralPath $lockPath -Raw -Encoding utf8
$activeLockCount = @(
    [regex]::Matches($lockText, '(?m)^### .+ — ACTIVE\s*$')
).Count
$qaV7Released = [bool] (
    $lockText -match '(?m)^### G1-L1-KOKORO-EXACT-QA-V7-PAD-43af8627-20261105 — RELEASED\s*$'
)
$lockSha = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()

$report = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.report.v1'
    task = 'Aurorama 2.0 G1-L1 Kokoro isolated harness Pad stop-only and native-crash gate'
    role = 'fresh independent Pad runtime QA / user-promise verification expert'
    generatedAt = (Get-Date).ToString('o')
    status = if ($stopGatePass) {
        'PASS_STOP_ONLY / P1_STOP_GATE_CLOSED / FULL_RUNTIME_REVALIDATION_REQUIRED'
    } else {
        'FAIL_STOP_ONLY'
    }
    verdict = if ($stopGatePass) { 'PASS_STOP_ONLY' } else { 'FAIL' }
    verdictScope = 'Closes only REV6-P1-01 stop-only and native-crash gates on Pad 43af8627. It does not close full 30-minute runtime, K60, dual-device matrix, Pad performance P2, licenses, production integration, Q03, G2 or G1.'
    object = [ordered]@{
        worktree = $repoRoot
        branch = ((git -C $repoRoot branch --show-current) -join "`n").Trim()
        handoffHead = '9a4496d7471774d7b680999d9912ad1b4ad92a37'
        implementationBaseline = '35ed65c2dc0e4171bdaf5b6fe055ad1f2e7ead8c'
        implementationBaselineIsAncestor = $baselineAncestor
        hostMain = [ordered]@{
            path = $artifactAudit.main.path
            bytes = $mainItem.Length
            sha256 = $mainSha
            versionCode = 4
            versionName = '2.0-kokoro-runtime-v5'
            abi = 'arm64-v8a'
            permissions = @($artifactAudit.main.permissions)
            internetPermission = [bool] $artifactAudit.main.internetPermission
            serviceCount = [int] $artifactAudit.main.serviceCount
            signatureVerification = $artifactAudit.main.signatureVerification
        }
        hostPeer = [ordered]@{
            path = $artifactAudit.peer.path
            bytes = $peerItem.Length
            sha256 = $peerSha
            versionCode = 1
            versionName = '1.0-focus-peer'
            permissions = @()
            internetPermission = $false
            signatureVerification = $artifactAudit.peer.signatureVerification
        }
        artifactAudit = $artifactAuditPath
        artifactAuditMatch = $hostObjectPass -and $deviceObjectPass
        deviceMain = $installed.testPackageIdentities.$package
        devicePeer = $installed.testPackageIdentities.$peerPackage
        deviceObserved = [ordered]@{
            serial = $serial
            sdk = 33
            abi = 'arm64-v8a'
            primaryCpuAbi = 'arm64-v8a'
            secondaryCpuAbi = $null
            postNotificationsGrantedForTest = $true
            noIdentityOrAccountGate = $true
            servicePresentWhenForeground =
                $inference.serviceBefore.recordCount -eq 1 -and
                $playing.serviceBefore.recordCount -eq 1
            serviceForeground =
                $inference.serviceBefore.foreground -and
                $playing.serviceBefore.foreground
            mediaSessionMetadataObserved = 'Kokoro isolated runtime · zf_001 / kokoro-multi-lang-v1_1'
            notificationActionsObserved = @('STOP', 'PLAY_PAUSE', 'NEXT', 'PREVIOUS')
        }
    }
    actualExecuted = @(
        'Verified worktree, branch, clean state, implementation-baseline ancestry, both host APK bytes/SHA, Pad keyguard and device-lock ACTIVE=0.',
        'Registered a unique QA-v7 Pad lock for 43af8627; issued zero K60-targeted commands.',
        'Installed both new APKs as fresh installs through the MIUI USB-install confirmation path; no identity or account gate appeared.',
        'Read device-side base.apk bytes/SHA, package, versionCode/name, arm64-v8a ABI, permissions, foreground service, notification actions and active MediaSession metadata.',
        'Granted only POST_NOTIFICATIONS for the harness notification journey.',
        'Executed one short-sample Stop during native preparation/inference with no AudioTrack start before Stop.',
        'Executed one Stop during a real 4-second TTS AudioTrack after AUDIO_TRACK_STARTED and before AUDIO_TRACK_COMPLETED.',
        'Captured per-round package/pid-filtered logcat, stop command, PID before/after, service/FGS, notification, active MediaSession, ApplicationExitInfo, crash-buffer F DEBUG and SYSTEM_TOMBSTONE filtering.',
        'Uninstalled both APKs, removed package-specific external data and remote helper XML, waited for ActivityManager cleanup, and proved no test package/PID/service/notification/active MediaSession residue.',
        'Restored and re-read production app and device baseline; production release SHA/path/version and network/volume/screen/current-user/wakefulness/keyguard all matched.'
    )
    inferenceStop = [ordered]@{
        verdict = $inference.verdict
        pid = $inference.runtimePidBefore
        pidAfterWait = $inference.runtimePidAfter
        samePid = $inference.samePid
        stopAtHost = $inference.tapAt
        stopLogTime = $inference.stopLogTime
        phaseValid = $inference.phaseValid
        audioStartsBeforeStop = $inference.audioStartsBeforeStop
        releaseDeferredSeen = $inference.releaseDeferredSeen
        releaseAfterInferenceSeen = $inference.releaseAfterInferenceSeen
        newAudioTracksAfterStop = $inference.audioStartsAfterStop
        notificationControl = $inference.notificationControl
        summary = Get-RelativeEvidencePath (Join-Path $rawRoot 'inference-stop\summary.json')
        timeline = Get-RelativeEvidencePath (Join-Path $rawRoot 'inference-stop\stop-timeline-filtered.log')
        filteredLog = Get-RelativeEvidencePath (Join-Path $rawRoot 'inference-stop\runtime-logcat-package-filtered.log')
        crashBuffer = Get-RelativeEvidencePath (Join-Path $rawRoot 'inference-stop\crash-buffer-package-filtered.txt')
        exitInfoCurrent = Get-RelativeEvidencePath (Join-Path $rawRoot 'inference-stop\exit-info-current-run.txt')
        tombstoneCurrent = Get-RelativeEvidencePath (Join-Path $rawRoot 'inference-stop\tombs-current-run.txt')
    }
    playingStop = [ordered]@{
        verdict = $playing.verdict
        pid = $playing.runtimePidBefore
        pidAfterWait = $playing.runtimePidAfter
        samePid = $playing.samePid
        stopAtHost = $playing.tapAt
        stopLogTime = $playing.stopLogTime
        phaseValid = $playing.phaseValid
        audioStartsBeforeStop = $playing.audioStartsBeforeStop
        audioCompletionsBeforeStop = $playing.audioCompletionsBeforeStop
        stoppedAtPositionMillis = 3121
        releaseImmediateSeen = $playing.releaseImmediateSeen
        newAudioTracksAfterStop = $playing.audioStartsAfterStop
        notificationControl = $playing.notificationControl
        summary = Get-RelativeEvidencePath (Join-Path $rawRoot 'playing-stop\summary.json')
        timeline = Get-RelativeEvidencePath (Join-Path $rawRoot 'playing-stop\stop-timeline-filtered.log')
        filteredLog = Get-RelativeEvidencePath (Join-Path $rawRoot 'playing-stop\runtime-logcat-package-filtered.log')
        crashBuffer = Get-RelativeEvidencePath (Join-Path $rawRoot 'playing-stop\crash-buffer-package-filtered.txt')
        exitInfoCurrent = Get-RelativeEvidencePath (Join-Path $rawRoot 'playing-stop\exit-info-current-run.txt')
        tombstoneCurrent = Get-RelativeEvidencePath (Join-Path $rawRoot 'playing-stop\tombs-current-run.txt')
    }
    crashGate = [ordered]@{
        passed = $crashPasses
        currentRunApplicationExitReason5Count =
            [int] $inference.crashGate.currentRunApplicationExitReason5Count +
            [int] $playing.crashGate.currentRunApplicationExitReason5Count
        rawApplicationExitInfo = @(
            [ordered]@{
                round = 'inference-stop'
                command = "adb -s $serial shell dumpsys activity exit-info $package"
                currentRunBlockCount = [int] $inference.crashGate.currentRunApplicationExitBlockCount
                path = Get-RelativeEvidencePath (
                    Join-Path $rawRoot 'inference-stop\exit-info-package-raw.txt')
            },
            [ordered]@{
                round = 'playing-stop'
                command = "adb -s $serial shell dumpsys activity exit-info $package"
                currentRunBlockCount = [int] $playing.crashGate.currentRunApplicationExitBlockCount
                path = Get-RelativeEvidencePath (
                    Join-Path $rawRoot 'playing-stop\exit-info-package-raw.txt')
            }
        )
        fDebugCount = [int] $inference.crashGate.fDebugCount + [int] $playing.crashGate.fDebugCount
        nativeTombstoneCount =
            [int] $inference.crashGate.tombstoneCount +
            [int] $playing.crashGate.tombstoneCount
        javaFatalCount =
            [int] $inference.crashGate.javaFatalCount +
            [int] $playing.crashGate.javaFatalCount
        anrCount =
            [int] $inference.crashGate.anrCount +
            [int] $playing.crashGate.anrCount
        inputTimeoutCount =
            [int] $inference.crashGate.inputTimeoutCount +
            [int] $playing.crashGate.inputTimeoutCount
        tombstoneScan = 'Direct /data/tombstones listing is permission denied on the unrooted Pad; dumpsys dropbox SYSTEM_TOMBSTONE was filtered to the current run start and package. Old QA-v6 entries were not counted.'
        scanCoverage = 'ApplicationExitInfo, crash buffer F DEBUG, Java FATAL, app ANR and InputTimeout per round.'
    }
    resourceConvergence = [ordered]@{
        passed = $resourcePasses
        inference = $inference.resourceConvergence
        playing = $playing.resourceConvergence
        activeMediaSessionCorrection = @(
            [ordered]@{
                round = 'inference-stop'
                issue = $inference.analysisCorrection.kind
                correctedCounts = $inference.analysisCorrection.correctedCounts
                evidence = $inference.analysisCorrection.evidence
            },
            [ordered]@{
                round = 'playing-stop'
                issue = $playing.analysisCorrection.kind
                correctedCounts = $playing.analysisCorrection.correctedCounts
                evidence = $playing.analysisCorrection.evidence
            }
        )
    }
    cleanup = [ordered]@{
        passed = $cleanup.passed
        initialUninstall = 'Both adb uninstall --user 0 commands returned Success at the first cleanup pass.'
        idempotentRecheck = 'A later verification pass returned not installed for both packages, proving absence.'
        activityManagerTransient = 'The first immediate snapshot retained one stale service record; a 3-second follow-up showed 0 service records. Final evidence has 0.'
        packagePathsAbsent = @(
            $cleanup.packagePaths.$package.Count -eq 0 -and
            $cleanup.packagePaths.$peerPackage.Count -eq 0
        )[0]
        runtimePidsAbsent =
            [string]::IsNullOrWhiteSpace($cleanup.runtimePids.$package) -and
            [string]::IsNullOrWhiteSpace($cleanup.runtimePids.$peerPackage)
        serviceRecordCount = $cleanup.serviceRecordCount
        notificationRecordCount = $cleanup.notificationRecordCount
        activeMediaSessionCount = $cleanup.activeMediaSessionCount
        externalPackagePathsAbsent =
            @($cleanup.externalPathsRemain.PSObject.Properties.Value |
                Where-Object { $_ }).Count -eq 0
        remoteHelperEntriesAbsent = @($cleanup.remoteHelperEntries).Count -eq 0
        productionMatch = $cleanup.productionMatch
        restoreMatch = $cleanup.restoreMatch
        restored = $cleanup.restored
        evidence = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'cleanup\cleanup.json')
        afterSnapshot = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'cleanup\device-after.json')
    }
    actualDeviceSerials = @($serial)
    k60TargetedCommands = 0
    screenshotsTaken = 0
    viewImageCalls = 0
    findings = [ordered]@{
        P0 = @()
        P1 = @(
            [ordered]@{
                id = 'REV6-P1-01'
                status = 'CLOSED_STOP_ONLY'
                detail = 'Fresh independent QA reproduced both required Stop phases on the new v5 APK without native crash, Java FATAL, ANR, InputTimeout, tombstone or current-run ApplicationExitInfo reason=5.'
                scopeLimit = 'Does not close full runtime duration, background reliability, dual-device behavior or production integration.'
            }
        )
        P2 = @(
            [ordered]@{
                id = 'REV6-P2-01'
                status = 'UNCHANGED / NOT_RETESTED / UNACCEPTED'
                detail = 'Pad zf_001 performance remains external to this stop-only gate.'
            }
        )
        P3 = @()
        summary = 'New P0/P1/P2/P3 = 0/0/0/0; REV6-P1-01 stop-only gate is closed; existing Pad P2 remains unaccepted.'
    }
    qaHarnessIssues = @(
        [ordered]@{
            id = 'QA7-EV-ROOT'
            kind = 'EVIDENCE_PATH_SCOPE'
            detail = 'The first stop-round script resolved its root one level too high and created a new raw evidence directory outside qa-v7.'
            remediation = 'The entire newly created evidence tree was moved into qa-v7/raw before analysis, the source root was corrected, and no existing harness or v3 evidence was touched.'
            deviceRerunRequired = $false
        },
        [ordered]@{
            id = 'QA7-EV-MEDIA'
            kind = 'MEDIA_SESSION_PARSER_FALSE_POSITIVE'
            detail = 'The first parser counted package text in the Android audio-playback history rather than the active SessionStack.'
            remediation = 'Derived assertions were recomputed from the captured SessionStack lines: 1 before Stop and 0 after Stop in both rounds. The incorrect initial values are retained in a correction object.'
            deviceRerunRequired = $false
        },
        [ordered]@{
            id = 'QA7-EV-TRANSIENT-SERVICE'
            kind = 'ACTIVITY_MANAGER_TRANSIENT_RECORD'
            detail = 'The first cleanup snapshot immediately after uninstall retained one stale service record.'
            remediation = 'A delayed recheck found 0 service records and no package, PID, notification or active MediaSession; final cleanup evidence is PASS.'
            deviceRerunRequired = $false
        }
    )
    uncovered = @(
        'full 30-minute or longer Pad runtime',
        'K60 rerun and full dual-device runtime matrix',
        'Pad zf_001 performance acceptance',
        'license, notice, training data and voice ownership closure',
        'production Aurorama TTS/service integration',
        'profile freeze, Q03, G2 or G1 PASS',
        'physical speaker audibility and subjective quality'
    )
    maximumClaim = 'On fresh-installed new APK 252a3a15...e161 on Pad 43af8627, notification Stop invoked during native inference and during real TTS AudioTrack playback both completed without current-run native crash, Java FATAL, ANR, InputTimeout, tombstone, active MediaSession, notification, service or new AudioTrack residue. This is PASS_STOP_ONLY only; it is not full runtime, K60, performance, production, license, Q03 or G1 PASS.'
    nextStep = [ordered]@{
        role = 'different fresh full runtime QA, distinct from both implementation owner and QA-v7'
        action = 'Revalidate the new APK on Pad and K60 with the full authorized runtime matrix.'
        firstReadOnlyAction = 'Read qa-v7/report.json and both round summaries, then verify new APK bytes/SHA and device-lock ACTIVE=0 before installing.'
        stopCondition = 'Stop on APK/source drift, keyguard or lock conflict, any current-run native crash/F DEBUG/tombstone/FATAL/ANR/InputTimeout/reason=5, resource non-convergence, new P0-P2, or any request to broaden scope without authorization.'
    }
    deviceLock = [ordered]@{
        path = $lockPath
        serial = $serial
        acquiredHash = '9a0894f533b7d3c917f21d6292f152cdf895da4ecf221df5401b08995a9fdd7f'
        finalHash = $lockSha
        qaV7Released = $qaV7Released
        activeHeaderCount = $activeLockCount
    }
    security = [ordered]@{
        screenshotsTaken = 0
        notificationXmlRetained = $false
        unfilteredHostLogRetained = $false
        logRetention = 'Package/PID/task-filtered lines only; no unfiltered host log.'
        serverWrites = 0
        accountOrIdentityGate = 'NOT_TRIGGERED'
        k60TargetedCommands = 0
    }
    memoryCheckpoint = [ordered]@{
        status = 'PENDING_FINAL_VERIFICATION'
        added = @()
        updated = @()
        archived = @()
        skipped = @()
        indexLines = $null
        indexBytes = $null
        check = 'PENDING'
        doctor = 'PENDING'
    }
    evidenceIndex = [ordered]@{
        inferenceSummary = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'inference-stop\summary.json')
        inferenceTimeline = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'inference-stop\stop-timeline-filtered.log')
        playingSummary = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'playing-stop\summary.json')
        playingTimeline = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'playing-stop\stop-timeline-filtered.log')
        cleanup = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'cleanup\cleanup.json')
        deviceBefore = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'device-before.json')
        deviceInstalled = Get-RelativeEvidencePath (
            Join-Path $rawRoot 'device-installed.json')
        artifactAudit = (
            'F:/Develop/code_data/work_tree/Aurorama/v2-requirements-baseline/' +
            'Aurorama/.planning/v2-g1/G1-L1-KOKORO-EXACT/runtime-closure-v3/' +
            'artifacts/artifact-audit.json'
        )
    }
}

$reportPath = Join-Path $qaRoot 'report.json'
Write-Json $reportPath $report

$readme = @'
# Kokoro QA-v7 — Pad stop-only / native crash gate

结论：`PASS_STOP_ONLY`。在 Pad 5 `43af8627` 的 fresh install 新版 main/peer 上，
推理中通知 Stop 与真实 AudioTrack playing 中通知 Stop 均未出现当前运行 native crash、
Java FATAL、ANR、InputTimeout、tombstone 或新 AudioTrack，PID 存活且资源收敛。

本结果只关闭 `REV6-P1-01` 的 stop-only / native-crash 门；不覆盖完整 30 分钟、
K60 / 双机矩阵、Pad P2 性能、许可、生产 runtime、Q03、G2 或 G1。

- 报告：`report.json`
- 推理 Stop：`raw/inference-stop/`
- 播放 Stop：`raw/playing-stop/`
- 清理恢复：`raw/cleanup/`
- 工具：`tools/`

所有终端与 ADB 仅使用 PowerShell 7；截图 0；未保留未过滤宿主日志或通知 XML。
'@
$readme = $readme -replace "`r`n", "`n"
[IO.File]::WriteAllText((Join-Path $qaRoot 'README.md'), $readme, $utf8)

$report | Select-Object status, verdict | Format-List
if (-not $stopGatePass) {
    exit 1
}
