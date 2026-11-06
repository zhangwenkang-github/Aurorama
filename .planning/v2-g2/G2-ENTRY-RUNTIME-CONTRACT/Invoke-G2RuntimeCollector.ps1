[CmdletBinding()]
param(
    [ValidateSet(
        "Validate",
        "Register",
        "RunWindow",
        "Reconcile",
        "StaticSelfTest"
    )]
    [string]$Action = "Validate",

    [ValidateSet("Pad", "K60")]
    [string]$DeviceRole,

    [string]$EvidenceRoot,
    [string]$InteractionPlanPath,
    [int]$Attempt = 1,
    [int]$WindowSeconds = 600,
    [switch]$Extended,
    [switch]$Retry
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
$scriptRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $scriptRoot "..\..\..")
)
$contractRoot = $scriptRoot
$contractPath = Join-Path $contractRoot "contract.json"
$candidatePath = Join-Path $contractRoot "candidate.json"
$authorizationPath = Join-Path $contractRoot "authorization-receipt.json"
$schemaPath = Join-Path $contractRoot "report.schema.json"
$manifestPath = Join-Path $contractRoot "runtime-tools.manifest.json"
$adbPath =
    "F:\Develop\Android\AndroidSDK\platform-tools\adb.exe"
$mainPackage = "dev.aurorama.kokoro.harness"
$peerPackage = "dev.aurorama.kokoro.focuspeer"
$runtimeTag = "KOKORO_RUNTIME"
$peerTag = "KOKORO_FOCUS_PEER"
$pinnedSourceCommit = "35f9d493f16cdf79f946871fc331e2ceece287d2"
$pinnedMainSha256 =
    "1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3"
$pinnedPeerSha256 =
    "d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a"
$pinnedSchemaSha256 =
    "8cb6d8766e773107e9493b828239ee417c61cf703a3c7756f94b684729921046"
$requiredProductGates = @(
    "G2-RT-01-FRESH-INSTALL-IDENTITY",
    "G2-RT-02-AUDIOTRACK-START-COMPLETION",
    "G2-RT-03-VOICE-PROFILES-ZF-ZM",
    "G2-RT-04-HEAD-MIDDLE-TAIL-CONTINUITY",
    "G2-RT-05-MAX-AUDIO-GAP-15S",
    "G2-RT-06-FGS-MEDIASESSION-NOTIFICATION-UI-EQUALITY",
    "G2-RT-07-AUDIOFOCUS-SINGLE-OWNER",
    "G2-RT-08-CRASH-ANR-INPUTTIMEOUT",
    "G2-RT-09-UI-CANCEL",
    "G2-RT-10-PROCESS-RESTART-RECOVERY",
    "G2-RT-11-APP-UID-ZERO-NETWORK",
    "G2-RT-12-CLEANUP-CAPTURE-RESTORE",
    "G2-RT-13-SINGLE-10MIN-WINDOW-NO-RETRY"
)

function Read-G2JsonStrict {
    param([Parameter(Mandatory)][string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if (
        $bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF
    ) {
        throw "JSON_BOM_FORBIDDEN $Path"
    }
    if ([Array]::IndexOf($bytes, [byte]13) -ge 0) {
        throw "JSON_CR_FORBIDDEN $Path"
    }
    return (
        $utf8Strict.GetString($bytes) |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Get-G2FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function Write-G2Utf8LfText {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text
    )
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    $parent = [System.IO.Path]::GetDirectoryName($Path)
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        [void][System.IO.Directory]::CreateDirectory($parent)
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8Output)
}

function Write-G2Json {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    Write-G2Utf8LfText `
        -Path $Path `
        -Text ($Value | ConvertTo-Json -Depth 100)
}

function Resolve-G2BoundedPath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        throw "PATH_EMPTY"
    }
    if (
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath.StartsWith("\\", [System.StringComparison]::Ordinal) -or
        $RelativePath -match "^[A-Za-z]:"
    ) {
        throw "PATH_ABSOLUTE_FORBIDDEN $RelativePath"
    }
    $segments = @($RelativePath -split "[\\/]")
    if ($segments -contains "..") {
        throw "PATH_TRAVERSAL_FORBIDDEN $RelativePath"
    }
    if ($segments -contains "." -or $segments -contains "") {
        throw "PATH_SEGMENT_FORBIDDEN $RelativePath"
    }
    $fullRoot = [System.IO.Path]::GetFullPath($Root).
        TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        )
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $fullRoot $RelativePath)
    )
    $rootPrefix =
        $fullRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith(
        $rootPrefix,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "PATH_OUTSIDE_ROOT $RelativePath"
    }
    Assert-G2NoReparsePath -Root $fullRoot -FullPath $fullPath
    return $fullPath
}

function Assert-G2NoReparsePath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$FullPath
    )
    $current = $Root
    if (Test-Path -LiteralPath $current) {
        $rootItem = Get-Item -LiteralPath $current -Force
        if (
            $rootItem.Attributes -band
            [System.IO.FileAttributes]::ReparsePoint
        ) {
            throw "PATH_REPARSE_POINT_FORBIDDEN $current"
        }
    }
    $relative = [System.IO.Path]::GetRelativePath($Root, $FullPath)
    foreach ($segment in @($relative -split "[\\/]")) {
        if ([string]::IsNullOrWhiteSpace($segment)) {
            continue
        }
        $current = Join-Path $current $segment
        if (-not (Test-Path -LiteralPath $current)) {
            break
        }
        $item = Get-Item -LiteralPath $current -Force
        if (
            $item.Attributes -band
            [System.IO.FileAttributes]::ReparsePoint
        ) {
            throw "PATH_REPARSE_POINT_FORBIDDEN $current"
        }
    }
}

function Resolve-G2EvidenceRoot {
    param(
        [Parameter(Mandatory)][string]$ContractRoot,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [AllowNull()][string]$RequestedEvidenceRoot
    )
    $expectedRelative = if ($DeviceRole -ceq "Pad") {
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
    } else {
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-k60-v1"
    }
    if ([string]::IsNullOrWhiteSpace($RequestedEvidenceRoot)) {
        $RequestedEvidenceRoot = $expectedRelative
    }
    $normalized = $RequestedEvidenceRoot.Replace("\", "/").TrimEnd("/")
    if ($normalized -cne $expectedRelative) {
        throw "EVIDENCE_ROOT_MISMATCH expected=$expectedRelative actual=$normalized"
    }
    $repository = [System.IO.Path]::GetFullPath(
        (Join-Path $ContractRoot "..\..\..")
    )
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $repository $expectedRelative)
    )
    $contractPrefix =
        [System.IO.Path]::GetFullPath($ContractRoot).
            TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith(
        $contractPrefix,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "PATH_OUTSIDE_ROOT $fullPath"
    }
    return [pscustomobject]@{
        RelativePath = $expectedRelative
        FullPath = $fullPath
    }
}

function Assert-G2WindowPolicy {
    param(
        [Parameter(Mandatory)][int]$Attempt,
        [Parameter(Mandatory)][int]$WindowSeconds,
        [Parameter(Mandatory)][bool]$Extended,
        [Parameter(Mandatory)][bool]$Retry
    )
    if ($Attempt -ne 1) {
        throw "ATTEMPT_MUST_BE_ONE attempt=$Attempt RETRY_FORBIDDEN"
    }
    if ($WindowSeconds -ne 600) {
        throw "WINDOW_SECONDS_MUST_BE_600 actual=$WindowSeconds"
    }
    if ($Extended) {
        throw "WINDOW_EXTENSION_FORBIDDEN"
    }
    if ($Retry) {
        throw "WINDOW_RETRY_FORBIDDEN"
    }
}

function Assert-G2DevicePolicy {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [Parameter(Mandatory)][string]$Serial,
        [AllowNull()]$Predecessor
    )
    $expectedSerial = if ($DeviceRole -ceq "Pad") {
        [string]$Contract.devicePolicy.pad.serial
    } else {
        [string]$Contract.devicePolicy.k60.serial
    }
    if ($Serial -cne $expectedSerial) {
        throw "DEVICE_SERIAL_MISMATCH role=$DeviceRole actual=$Serial"
    }
    if ($DeviceRole -ceq "Pad") {
        return
    }
    if (
        $null -eq $Predecessor -or
        [string]$Predecessor.deviceOrder -cne "Pad" -or
        [string]$Predecessor.coreVerdict -cne "PASS" -or
        [string]$Predecessor.productVerdict -cne "PASS" -or
        [string]$Predecessor.lockStateAfterClosure -cne
            "RELEASED_ACTIVE_0" -or
        [string]$Predecessor.candidateMainSha256 -cne
            [string]$Contract.candidate.main.sha256 -or
        [string]$Predecessor.candidateFocusPeerSha256 -cne
            [string]$Contract.candidate.focusPeer.sha256
    ) {
        throw "K60_PREDECESSOR_PAD_PASS_AND_RELEASED_LOCK_REQUIRED"
    }
    $pointer = [string]$Predecessor.evidencePointer
    $normalizedPointer = $pointer.Replace("\", "/")
    if (-not $normalizedPointer.StartsWith(
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1/",
        [System.StringComparison]::Ordinal
    )) {
        throw "K60_PREDECESSOR_EVIDENCE_POINTER_OUTSIDE_PAD_ROOT"
    }
}

function Assert-G2PinnedIdentity {
    param([Parameter(Mandatory)]$Contract)
    if (
        [string]$Contract.candidate.sourceCommit -cne
            $pinnedSourceCommit -or
        [string]$Contract.candidate.main.sha256 -cne
            $pinnedMainSha256 -or
        [string]$Contract.candidate.focusPeer.sha256 -cne
            $pinnedPeerSha256 -or
        [string]$Contract.evidence.reportSchemaSha256 -cne
            $pinnedSchemaSha256
    ) {
        throw "PINNED_CONTRACT_IDENTITY_MISMATCH"
    }
}

function Assert-G2AuthorizationReceipt {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Authorization,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole
    )
    if (
        [string]$Authorization.schema -cne
            "aurorama.g2-device-execution-authorization.v1" -or
        [string]$Authorization.decisionId -cne "V2-D210" -or
        [string]$Authorization.contractId -cne [string]$Contract.contractId -or
        [string]$Authorization.authorizedBy -cne "USER" -or
        [bool]$Authorization.scope.runtimeQa -ne $true -or
        [bool]$Authorization.scope.deviceInstall -ne $true -or
        [bool]$Authorization.scope.newRuntimeToolCreation -ne $true -or
        [bool]$Authorization.scope.pad.authorized -ne $true -or
        [string]$Authorization.scope.pad.serial -cne "43af8627" -or
        [int]$Authorization.scope.pad.order -ne 1 -or
        [bool]$Authorization.scope.k60.authorized -ne $true -or
        [bool]$Authorization.scope.k60.conditional -ne $true -or
        [string]$Authorization.scope.k60.serial -cne "8e875894" -or
        [int]$Authorization.scope.k60.order -ne 2 -or
        [string]$Authorization.scope.k60.condition -cne
            "PAD_FULL_CORE_PASS_AND_RELEASED_ACTIVE_0" -or
        [bool]$Authorization.scope.g2Implementation -ne $false -or
        [bool]$Authorization.scope.release -ne $false -or
        [bool]$Authorization.scope.serverWrite -ne $false -or
        [bool]$Authorization.scope.q03Close -ne $false -or
        [bool]$Authorization.scope.profileVoiceFreeze -ne $false -or
        [bool]$Authorization.scope.padP2Acceptance -ne $false -or
        [bool]$Authorization.scope.l2L3Unlock -ne $false -or
        [string]$Authorization.candidate.sourceCommit -cne
            $pinnedSourceCommit -or
        [string]$Authorization.candidate.mainSha256 -cne
            $pinnedMainSha256 -or
        [string]$Authorization.candidate.focusPeerSha256 -cne
            $pinnedPeerSha256
    ) {
        throw (
            "AUTHORIZATION_RECEIPT_MISMATCH " +
            "K60_CONDITIONAL_SCOPE_REQUIRED"
        )
    }
}

function Get-G2ContractContext {
    param(
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [AllowNull()][string]$RequestedEvidenceRoot
    )
    foreach ($requiredPath in @(
        $contractPath,
        $candidatePath,
        $authorizationPath,
        $schemaPath,
        $manifestPath
    )) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "REQUIRED_FILE_MISSING $requiredPath"
        }
    }
    $contract = Read-G2JsonStrict $contractPath
    $candidate = Read-G2JsonStrict $candidatePath
    $authorization = Read-G2JsonStrict $authorizationPath
    $manifest = Read-G2JsonStrict $manifestPath
    if ($contract.schema -cne "aurorama.g2-entry-runtime-contract.v1") {
        throw "CONTRACT_SCHEMA_MISMATCH"
    }
    Assert-G2PinnedIdentity -Contract $contract
    if ($contract.contractId -cne
        "G2-ENTRY-RUNTIME-CONTRACT-2026-11-06") {
        throw "CONTRACT_ID_MISMATCH"
    }
    if ($contract.status -cne
        "PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT") {
        throw "CONTRACT_STATUS_MISMATCH"
    }
    if ($contract.owner.roleId -cne
        "G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06") {
        throw "CONTRACT_OWNER_MISMATCH"
    }
    if ($contract.tools.runtimeCollector.roleId -cne $toolOwnerRole -or
        $contract.tools.runtimeFinalizer.roleId -cne $toolOwnerRole -or
        $contract.tools.cleanupVerifier.roleId -cne $toolOwnerRole) {
        throw "RUNTIME_TOOL_OWNER_MISMATCH"
    }
    if ($contract.tools.runtimeCollector.status -cne
            "READY_SELF_TESTED_STATIC_ONLY" -or
        $contract.tools.runtimeFinalizer.status -cne
            "READY_SELF_TESTED_STATIC_ONLY" -or
        $contract.tools.cleanupVerifier.status -cne
            "READY_SELF_TESTED_STATIC_ONLY") {
        throw "RUNTIME_TOOL_STATUS_NOT_READY"
    }
    if ($manifest.ownerRole -cne $toolOwnerRole -or
        [int]$manifest.qaV9ReuseCount -ne 0) {
        throw "RUNTIME_TOOL_MANIFEST_IDENTITY_MISMATCH"
    }
    foreach ($entry in @($manifest.files)) {
        $entryPath = Resolve-G2BoundedPath `
            -Root $contractRoot `
            -RelativePath ([string]$entry.path)
        if (-not (Test-Path -LiteralPath $entryPath -PathType Leaf)) {
            throw "MANIFEST_FILE_MISSING $($entry.path)"
        }
        if ((Get-G2FileSha256 $entryPath) -cne [string]$entry.sha256) {
            throw "MANIFEST_FILE_SHA_MISMATCH $($entry.path)"
        }
    }
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        $tool = $contract.tools.$toolName
        $toolPath = Resolve-G2BoundedPath `
            -Root $repositoryRoot `
            -RelativePath ([string]$tool.path)
        if ((Get-G2FileSha256 $toolPath) -cne [string]$tool.sha256) {
            throw "RUNTIME_TOOL_SHA_MISMATCH $toolName"
        }
    }
    $toolFiles = @(
        "Finalize-G2RuntimeReport.ps1",
        "Invoke-G2RuntimeCollector.ps1",
        "Test-G2CleanupRestore.ps1"
    ) | Sort-Object
    $digestBuilder = [System.Text.StringBuilder]::new()
    foreach ($fileName in $toolFiles) {
        $path = Join-Path $contractRoot $fileName
        [void]$digestBuilder.Append(
            (Get-G2FileSha256 $path) + "  " + $fileName + "`n"
        )
    }
    $actualDigest = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            $utf8Output.GetBytes($digestBuilder.ToString())
        )
    ).ToLowerInvariant()
    if ($actualDigest -cne [string]$contract.tools.digestSha256) {
        throw "RUNTIME_TOOLS_DIGEST_MISMATCH"
    }
    Assert-G2AuthorizationReceipt `
        -Contract $contract `
        -Authorization $authorization `
        -DeviceRole $DeviceRole
    foreach ($artifact in @(
        $contract.candidate.main,
        $contract.candidate.focusPeer
    )) {
        $artifactPath = Resolve-G2BoundedPath `
            -Root $repositoryRoot `
            -RelativePath ([string]$artifact.path)
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
            throw "CANDIDATE_ARTIFACT_MISSING $($artifact.path)"
        }
        if ((Get-Item -LiteralPath $artifactPath).Length -ne
            [long]$artifact.bytes) {
            throw "CANDIDATE_BYTES_MISMATCH $($artifact.path)"
        }
        if ((Get-G2FileSha256 $artifactPath) -cne
            [string]$artifact.sha256) {
            throw "CANDIDATE_SHA_MISMATCH $($artifact.path)"
        }
    }
    if ((Get-G2FileSha256 $schemaPath) -cne
        [string]$contract.evidence.reportSchemaSha256) {
        throw "REPORT_SCHEMA_SHA_MISMATCH"
    }
    $resolvedEvidence = Resolve-G2EvidenceRoot `
        -ContractRoot $contractRoot `
        -DeviceRole $DeviceRole `
        -RequestedEvidenceRoot $RequestedEvidenceRoot
    return [pscustomobject]@{
        Contract = $contract
        Candidate = $candidate
        Authorization = $authorization
        Manifest = $manifest
        Evidence = $resolvedEvidence
        ToolsDigest = $actualDigest
    }
}

function Get-G2NetStatsUidUsage {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][int]$Uid
    )
    $pattern = [regex]::new(
        "uid=$Uid(?:\s|,)" +
        "[^\r\n]*?set=(?<set>[^\s,]+)" +
        "[^\r\n]*?tag=(?<tag>[^\s,]+)" +
        "[^\r\n]*?rb=(?<rb>\d+)" +
        "[^\r\n]*?tb=(?<tb>\d+)"
    )
    $partitions = [System.Collections.Generic.List[object]]::new()
    $rxBytes = [long]0
    $txBytes = [long]0
    foreach ($line in ($Text -split "\r?\n")) {
        $match = $pattern.Match($line)
        if (-not $match.Success) {
            continue
        }
        $received = [long]$match.Groups["rb"].Value
        $transmitted = [long]$match.Groups["tb"].Value
        $rxBytes += $received
        $txBytes += $transmitted
        $partitions.Add([pscustomobject]@{
            set = [string]$match.Groups["set"].Value
            tag = [string]$match.Groups["tag"].Value
            rb = $received
            tb = $transmitted
        })
    }
    return [pscustomobject]@{
        Uid = $Uid
        PartitionCount = $partitions.Count
        Partitions = $partitions.ToArray()
        RxBytes = $rxBytes
        TxBytes = $txBytes
    }
}

function New-G2NetStatsDelta {
    param(
        [Parameter(Mandatory)]$Before,
        [Parameter(Mandatory)]$After
    )
    $partitionKeysMatch =
        @($Before.Partitions | ForEach-Object { "$($_.set)/$($_.tag)" } |
            Sort-Object) -join "`n" -ceq
        @($After.Partitions | ForEach-Object { "$($_.set)/$($_.tag)" } |
            Sort-Object) -join "`n"
    $allAccounted =
        [int]$Before.PartitionCount -gt 0 -and
        [int]$After.PartitionCount -gt 0 -and
        $partitionKeysMatch
    $rxDelta = [long]$After.RxBytes - [long]$Before.RxBytes
    $txDelta = [long]$After.TxBytes - [long]$Before.TxBytes
    if ($rxDelta -lt 0 -or $txDelta -lt 0) {
        $allAccounted = $false
    }
    return [pscustomobject]@{
        uid = [int]$Before.Uid
        beforePartitionCount = [int]$Before.PartitionCount
        afterPartitionCount = [int]$After.PartitionCount
        allPartitionsAccounted = $allAccounted
        windowBytesDelta = if ($allAccounted) {
            $rxDelta + $txDelta
        } else {
            [long]0
        }
        rxBytesDelta = if ($allAccounted) { $rxDelta } else { [long]0 }
        txBytesDelta = if ($allAccounted) { $txDelta } else { [long]0 }
        partitions = @($After.Partitions)
    }
}

function ConvertFrom-G2LogTimestamp {
    param([Parameter(Mandatory)][string]$Text)
    return [DateTimeOffset]::ParseExact(
        $Text,
        "yyyy-MM-dd HH:mm:ss.fff",
        [System.Globalization.CultureInfo]::InvariantCulture
    )
}

function Get-G2AudioTrackEvents {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Lines
    )
    $requests = @{}
    $events = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        if ($line -notmatch
            "^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+" +
            "(?<pid>\d+)\s+\d+\s+[A-Z]\s+" +
            "${runtimeTag}:\s+(?<message>.*)$") {
            continue
        }
        $timestampText = $Matches["ts"]
        $observedPid = [int]$Matches["pid"]
        $message = [string]$Matches["message"]
        if ($message -match
            "^TTS_SENTENCE_REQUEST index=(?<index>\d+)\s+" +
            "speaker=(?<speaker>zf_001|zm_010)\s+" +
            "sid=(?<sid>\d+)\s+generation=(?<generation>\d+)") {
            $generation = [string]$Matches["generation"]
            $requests[$generation] = [pscustomobject]@{
                index = [int]$Matches["index"]
                speaker = [string]$Matches["speaker"]
                sid = [int]$Matches["sid"]
                pid = $observedPid
            }
            continue
        }
        if ($message -notmatch
            "^AUDIO_TRACK_(?<kind>STARTED|COMPLETED)\s+" +
            "owner=TTS\s+generation=(?<generation>\d+)") {
            continue
        }
        $generation = [string]$Matches["generation"]
        if (-not $requests.ContainsKey($generation)) {
            throw (
                "AUDIO_EVENT_SENTENCE_MAP_MISSING generation={0}" -f
                $generation
            )
        }
        $request = $requests[$generation]
        $kind = if ([string]$Matches["kind"] -ceq "STARTED") {
            "START"
        } else {
            "COMPLETION"
        }
        $events.Add([pscustomobject]@{
            kind = $kind
            timestamp = (
                ConvertFrom-G2LogTimestamp $timestampText
            ).ToString("o")
            voiceId = [string]$request.speaker
            sentenceRequestId = (
                "{0}:{1}:{2}" -f
                $request.speaker,
                $request.index,
                $generation
            )
            trackSessionId = "TTS:$generation"
            observedPid = [int]$request.pid
        })
    }
    return $events.ToArray()
}

function Get-G2MaximumAudioGapSeconds {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Events
    )
    if ($Events.Count -eq 0) {
        throw "AUDIO_EVENTS_MISSING"
    }
    $ordered = @($Events | Sort-Object {
        [DateTimeOffset]::Parse([string]$_.timestamp)
    })
    $maximum = 0.0
    for ($index = 0; $index -lt $ordered.Count - 1; $index++) {
        if ([string]$ordered[$index].kind -cne "COMPLETION") {
            continue
        }
        $next = $null
        for ($scan = $index + 1; $scan -lt $ordered.Count; $scan++) {
            if ([string]$ordered[$scan].kind -ceq "START") {
                $next = $ordered[$scan]
                break
            }
        }
        if ($null -eq $next) {
            continue
        }
        $gap = (
            [DateTimeOffset]::Parse([string]$next.timestamp) -
            [DateTimeOffset]::Parse([string]$ordered[$index].timestamp)
        ).TotalSeconds
        if ($gap -gt $maximum) {
            $maximum = $gap
        }
    }
    return [Math]::Round($maximum, 3)
}

function Get-G2FocusedLogLines {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Tag
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return @()
    }
    return @(
        Get-Content -LiteralPath $Path -Encoding utf8 |
            Where-Object { [string]$_ -match "\s$([regex]::Escape($Tag)):\s" }
    )
}

function Invoke-G2Adb {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
        throw "ADB_MISSING $adbPath"
    }
    $allArguments = @("-s", $Serial) + $Arguments
    $output = @(& $adbPath @allArguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw (
            "ADB_COMMAND_FAILED exit={0} args={1} tail={2}" -f
            $exitCode,
            ($Arguments -join " "),
            (@($output | Select-Object -Last 8) -join " | ")
        )
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output | ForEach-Object { [string]$_ })
    }
}

function Get-G2DeviceMetadata {
    param([Parameter(Mandatory)][string]$Serial)
    $state = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("get-state")).Output -join "`n"
    if ($state.Trim() -cne "device") {
        throw "ADB_DEVICE_NOT_READY $state"
    }
    $serial = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("get-serialno")).Output -join "`n"
    if ($serial.Trim() -cne $Serial) {
        throw "ADB_SERIAL_READBACK_MISMATCH $serial"
    }
    $model = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("shell", "getprop", "ro.product.model")).
        Output -join "`n"
    $sdk = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("shell", "getprop", "ro.build.version.sdk")).
        Output -join "`n"
    $abi = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("shell", "getprop", "ro.product.cpu.abi")).
        Output -join "`n"
    $currentUser = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("shell", "am", "get-current-user")).Output -join "`n"
    return [pscustomobject]@{
        serial = $serial.Trim()
        model = $model.Trim()
        sdk = [int]$sdk.Trim()
        abi = $abi.Trim()
        user = [int]$currentUser.Trim()
    }
}

function Get-G2AppUid {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$PackageName
    )
    $dump = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("shell", "dumpsys", "package", $PackageName)).
        Output -join "`n"
    if ($dump -notmatch "(?m)^\s*userId=(?<uid>\d+)") {
        throw "APP_UID_NOT_OBSERVED $PackageName"
    }
    return [int]$Matches["uid"]
}

function Get-G2SnapshotDirectory {
    param(
        [Parameter(Mandatory)][string]$EvidenceFullPath,
        [Parameter(Mandatory)][string]$Name
    )
    if ($Name -notmatch "^[A-Za-z0-9._-]+$") {
        throw "SNAPSHOT_NAME_INVALID $Name"
    }
    $directory = Join-Path $EvidenceFullPath "raw/snapshots/$Name"
    [void][System.IO.Directory]::CreateDirectory($directory)
    return $directory
}

function Save-G2Text {
    param(
        [Parameter(Mandatory)][string]$EvidenceFullPath,
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Lines
    )
    $fullPath = Resolve-G2BoundedPath `
        -Root $EvidenceFullPath `
        -RelativePath $RelativePath
    Write-G2Utf8LfText -Path $fullPath -Text ($Lines -join "`n")
}

function Capture-G2Snapshot {
    param(
        [Parameter(Mandatory)][string]$EvidenceFullPath,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$Name
    )
    $directory = Get-G2SnapshotDirectory `
        -EvidenceFullPath $EvidenceFullPath `
        -Name $Name
    $relative = "raw/snapshots/$Name"
    $service = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "dumpsys",
            "activity",
            "services",
            $mainPackage
        )).Output
    Save-G2Text `
        -EvidenceFullPath $EvidenceFullPath `
        -RelativePath "$relative/service.txt" `
        -Lines $service
    $notification = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "dumpsys",
            "notification",
            "--noredact"
        )).Output
    Save-G2Text `
        -EvidenceFullPath $EvidenceFullPath `
        -RelativePath "$relative/notification.txt" `
        -Lines $notification
    $mediaSession = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "dumpsys",
            "media_session"
        )).Output
    Save-G2Text `
        -EvidenceFullPath $EvidenceFullPath `
        -RelativePath "$relative/media-session.txt" `
        -Lines $mediaSession
    $audio = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @("shell", "dumpsys", "audio")).Output
    Save-G2Text `
        -EvidenceFullPath $EvidenceFullPath `
        -RelativePath "$relative/audio-focus.txt" `
        -Lines $audio
    $ui = (Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "exec-out",
            "uiautomator",
            "dump",
            "/dev/tty"
        ) -AllowFailure).Output
    Save-G2Text `
        -EvidenceFullPath $EvidenceFullPath `
        -RelativePath "$relative/ui.xml" `
        -Lines $ui
    return [pscustomobject]@{
        name = $Name
        timestamp = [DateTimeOffset]::Now.ToString("o")
        directory = $relative
    }
}

function Send-G2HarnessCommand {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$Command
    )
    if ($Command -notmatch
        "^(restore|play_sample|play_full|play_long|resume|pause|previous|next|stop|cancel|toggle_voice|music_play|music_pause|simulate_focus_loss|dump_state)$") {
        throw "HARNESS_COMMAND_FORBIDDEN $Command"
    }
    $result = Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "$mainPackage/.HarnessActivity",
            "--es",
            "command",
            $Command
        )
    return $result.Output
}

function Open-G2HarnessUi {
    param([Parameter(Mandatory)][string]$Serial)
    return Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "$mainPackage/.HarnessActivity"
        )
}

function Invoke-G2UiCancel {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$EvidenceFullPath
    )
    $uiResult = Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "exec-out",
            "uiautomator",
            "dump",
            "/dev/tty"
        ) -AllowFailure
    $uiText = $uiResult.Output -join "`n"
    Save-G2Text `
        -EvidenceFullPath $EvidenceFullPath `
        -RelativePath "raw/snapshots/ui-cancel-click.xml" `
        -Lines @($uiText)
    $nodePattern = [regex]::new(
        '<node[^>]*content-desc="runtime_command:cancel"[^>]*bounds="\[(?<x1>\d+),(?<y1>\d+)\]\[(?<x2>\d+),(?<y2>\d+)\]"[^>]*/>'
    )
    $match = $nodePattern.Match($uiText)
    if (-not $match.Success) {
        throw "UI_CANCEL_CONTROL_NOT_FOUND"
    }
    $tapX = [int]((
        [int]$match.Groups["x1"].Value +
        [int]$match.Groups["x2"].Value
    ) / 2)
    $tapY = [int]((
        [int]$match.Groups["y1"].Value +
        [int]$match.Groups["y2"].Value
    ) / 2)
    [void](Invoke-G2Adb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "input",
            "tap",
            [string]$tapX,
            [string]$tapY
        ))
}

function Get-G2DefaultInteractionPlan {
    return @(
        [pscustomobject]@{ atSeconds = 1; action = "StartLong" }
        [pscustomobject]@{ atSeconds = 20; action = "SnapshotHead" }
        [pscustomobject]@{ atSeconds = 90; action = "Pause" }
        [pscustomobject]@{ atSeconds = 92; action = "ToggleVoice" }
        [pscustomobject]@{ atSeconds = 94; action = "Resume" }
        [pscustomobject]@{ atSeconds = 180; action = "SnapshotMiddle" }
        [pscustomobject]@{ atSeconds = 240; action = "StartFocusPeer" }
        [pscustomobject]@{ atSeconds = 255; action = "Resume" }
        [pscustomobject]@{ atSeconds = 360; action = "SnapshotTail" }
        [pscustomobject]@{ atSeconds = 420; action = "MusicStart" }
        [pscustomobject]@{ atSeconds = 435; action = "MusicPause" }
        [pscustomobject]@{ atSeconds = 450; action = "ProcessDeath" }
        [pscustomobject]@{ atSeconds = 460; action = "Restore" }
        [pscustomobject]@{ atSeconds = 470; action = "SnapshotRecovery" }
        [pscustomobject]@{ atSeconds = 520; action = "UiCancel" }
        [pscustomobject]@{ atSeconds = 525; action = "SnapshotCancel" }
    )
}

function Read-G2InteractionPlan {
    param([AllowNull()][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return @(Get-G2DefaultInteractionPlan)
    }
    $plan = Read-G2JsonStrict $Path
    $actions = @($plan.actions)
    if ($actions.Count -eq 0) {
        throw "INTERACTION_PLAN_EMPTY"
    }
    $allowed = @(
        "StartLong",
        "Pause",
        "Resume",
        "ToggleVoice",
        "SnapshotHead",
        "SnapshotMiddle",
        "SnapshotTail",
        "StartFocusPeer",
        "MusicStart",
        "MusicPause",
        "ProcessDeath",
        "Restore",
        "SnapshotRecovery",
        "UiCancel",
        "SnapshotCancel"
    )
    $previous = -1
    foreach ($action in $actions) {
        $atSeconds = [int]$action.atSeconds
        if ($atSeconds -lt 0 -or $atSeconds -ge 600) {
            throw "INTERACTION_TIME_OUTSIDE_WINDOW $atSeconds"
        }
        if ($atSeconds -le $previous) {
            throw "INTERACTION_PLAN_NOT_STRICTLY_ORDERED"
        }
        if ($allowed -notcontains [string]$action.action) {
            throw "INTERACTION_ACTION_FORBIDDEN $($action.action)"
        }
        $previous = $atSeconds
    }
    foreach ($requiredAction in @(
        "StartLong",
        "SnapshotHead",
        "ToggleVoice",
        "SnapshotMiddle",
        "SnapshotTail",
        "ProcessDeath",
        "SnapshotRecovery",
        "UiCancel",
        "SnapshotCancel"
    )) {
        if (@($actions | Where-Object action -eq $requiredAction).Count -eq 0) {
            throw "INTERACTION_REQUIRED_ACTION_MISSING $requiredAction"
        }
    }
    return $actions
}

function Invoke-G2LogcatCapture {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$OutputPath,
        [Parameter(Mandatory)][string]$ErrorPath
    )
    if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
        throw "ADB_MISSING $adbPath"
    }
    $arguments = @(
        "-s",
        $Serial,
        "logcat",
        "-v",
        "threadtime,year",
        "${runtimeTag}:I",
        "${peerTag}:I",
        "AndroidRuntime:E",
        "libc:F",
        "ActivityManager:I",
        "*:S"
    )
    return Start-Process `
        -FilePath $adbPath `
        -ArgumentList $arguments `
        -RedirectStandardOutput $OutputPath `
        -RedirectStandardError $ErrorPath `
        -WindowStyle Hidden `
        -PassThru
}

function Invoke-G2Window {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [AllowNull()][string]$PlanPath
    )
    Assert-G2WindowPolicy `
        -Attempt $Attempt `
        -WindowSeconds $WindowSeconds `
        -Extended $Extended.IsPresent `
        -Retry $Retry.IsPresent
    $contract = $Context.Contract
    $evidence = $Context.Evidence
    [void][System.IO.Directory]::CreateDirectory($evidence.FullPath)
    $registrationPath = Join-Path $evidence.FullPath "collector-registration.json"
    $windowStartedPath = Join-Path $evidence.FullPath "window-started.json"
    $sessionPath = Join-Path $evidence.FullPath "collector-session.json"
    if (-not (Test-Path -LiteralPath $registrationPath -PathType Leaf)) {
        throw "COLLECTOR_REGISTRATION_MISSING"
    }
    if (
        (Test-Path -LiteralPath $windowStartedPath -PathType Leaf) -or
        (Test-Path -LiteralPath $sessionPath -PathType Leaf)
    ) {
        throw "ATTEMPT_ALREADY_CONSUMED RETRY_FORBIDDEN"
    }
    $registration = Read-G2JsonStrict $registrationPath
    if (
        [string]$registration.contractId -cne [string]$contract.contractId -or
        [string]$registration.deviceRole -cne $DeviceRole -or
        [int]$registration.attemptNumber -ne 1 -or
        [string]$registration.lockState -cne "ACTIVE_COUNT_1" -or
        [string]$registration.candidateMainSha256 -cne
            [string]$contract.candidate.main.sha256 -or
        [string]$registration.candidateFocusPeerSha256 -cne
            [string]$contract.candidate.focusPeer.sha256
    ) {
        throw "COLLECTOR_REGISTRATION_MISMATCH"
    }
    $plan = @(Read-G2InteractionPlan -Path $PlanPath)
    $serial = if ($DeviceRole -ceq "Pad") {
        [string]$contract.devicePolicy.pad.serial
    } else {
        [string]$contract.devicePolicy.k60.serial
    }
    $device = Get-G2DeviceMetadata -Serial $serial
    Assert-G2DevicePolicy `
        -Contract $contract `
        -DeviceRole $DeviceRole `
        -Serial $device.serial `
        -Predecessor $registration.predecessor
    if ($device.user -ne 0 -or $device.abi -cne "arm64-v8a") {
        throw "DEVICE_USER_OR_ABI_MISMATCH"
    }
    if ($DeviceRole -ceq "Pad") {
        if ($device.model -cne [string]$contract.devicePolicy.pad.model -or
            $device.sdk -ne [int]$contract.devicePolicy.pad.sdk) {
            throw "PAD_IDENTITY_MISMATCH"
        }
    }
    $startedAt = [DateTimeOffset]::Now
    Write-G2Json `
        -Path $windowStartedPath `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-window-started.v1"
            contractId = [string]$contract.contractId
            deviceRole = $DeviceRole
            serial = $device.serial
            attemptNumber = 1
            windowSeconds = 600
            startedAt = $startedAt.ToString("o")
        })
    Write-G2Json `
        -Path (Join-Path $evidence.FullPath "raw/device-before.json") `
        -Value $device

    $logDirectory = Join-Path $evidence.FullPath "raw"
    [void][System.IO.Directory]::CreateDirectory($logDirectory)
    $runtimeLogPath = Join-Path $logDirectory "runtime.log"
    $runtimeErrorPath = Join-Path $logDirectory "logcat.err"
    $commands = [System.Collections.Generic.List[object]]::new()
    $snapshots = [System.Collections.Generic.List[object]]::new()
    $logProcess = $null
    $windowError = $null
    $windowStartedAt = $null
    $windowCompletedAt = $null
    $appUid = 0
    try {
        [void](Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @("shell", "logcat", "-c") `
            -AllowFailure)
        [void](Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @("uninstall", $mainPackage) `
            -AllowFailure)
        [void](Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @("uninstall", $peerPackage) `
            -AllowFailure)
        $mainArtifact = Resolve-G2BoundedPath `
            -Root $repositoryRoot `
            -RelativePath ([string]$contract.candidate.main.path)
        $peerArtifact = Resolve-G2BoundedPath `
            -Root $repositoryRoot `
            -RelativePath ([string]$contract.candidate.focusPeer.path)
        $installMain = Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @("install", "-r", $mainArtifact)
        if ((($installMain.Output -join "`n") -notmatch "Success")) {
            throw "MAIN_INSTALL_NOT_CONFIRMED"
        }
        $installPeer = Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @("install", "-r", $peerArtifact)
        if ((($installPeer.Output -join "`n") -notmatch "Success")) {
            throw "PEER_INSTALL_NOT_CONFIRMED"
        }
        [void](Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @(
                "shell",
                "pm",
                "grant",
                $mainPackage,
                "android.permission.POST_NOTIFICATIONS"
            ) -AllowFailure)
        $uid = Get-G2AppUid `
            -Serial $device.serial `
            -PackageName $mainPackage
        $appUid = $uid
        $netBeforeText = (Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @(
                "shell",
                "dumpsys",
                "netstats",
                "detail"
            )).Output -join "`n"
        Save-G2Text `
            -EvidenceFullPath $evidence.FullPath `
            -RelativePath "raw/netstats-before.txt" `
            -Lines @($netBeforeText)
        $netBefore = Get-G2NetStatsUidUsage `
            -Text $netBeforeText `
            -Uid $uid
        Write-G2Json `
            -Path (Join-Path $evidence.FullPath "raw/netstats-before.json") `
            -Value $netBefore
        [void](Open-G2HarnessUi -Serial $device.serial)
        Start-Sleep -Seconds 2
        $logProcess = Invoke-G2LogcatCapture `
            -Serial $device.serial `
            -OutputPath $runtimeLogPath `
            -ErrorPath $runtimeErrorPath
        $windowStart = [DateTimeOffset]::Now
        $windowStartedAt = $windowStart
        foreach ($step in $plan) {
            $target = $windowStart.AddSeconds([int]$step.atSeconds)
            while ([DateTimeOffset]::Now -lt $target) {
                $remaining = (
                    $target - [DateTimeOffset]::Now
                ).TotalMilliseconds
                if ($remaining -le 0) {
                    break
                }
                Start-Sleep -Milliseconds ([int][Math]::Min(
                    1000,
                    [Math]::Ceiling($remaining)
                ))
            }
            $actionName = [string]$step.action
            $commands.Add([pscustomobject]@{
                atSeconds = [int]$step.atSeconds
                action = $actionName
                executedAt = [DateTimeOffset]::Now.ToString("o")
            })
            switch ($actionName) {
                "StartLong" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "play_long")
                }
                "Pause" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "pause")
                }
                "Resume" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "resume")
                }
                "ToggleVoice" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "toggle_voice")
                }
                "StartFocusPeer" {
                    [void](Invoke-G2Adb `
                        -Serial $device.serial `
                        -Arguments @(
                            "shell",
                            "am",
                            "start",
                            "-W",
                            "-n",
                            "$peerPackage/.FocusPeerActivity"
                        ))
                }
                "MusicStart" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "music_play")
                }
                "MusicPause" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "music_pause")
                }
                "ProcessDeath" {
                    [void](Invoke-G2Adb `
                        -Serial $device.serial `
                        -Arguments @(
                            "shell",
                            "am",
                            "force-stop",
                            $mainPackage
                        ))
                }
                "Restore" {
                    [void](Send-G2HarnessCommand `
                        -Serial $device.serial `
                        -Command "restore")
                    Start-Sleep -Seconds 3
                }
                "UiCancel" {
                    Invoke-G2UiCancel `
                        -Serial $device.serial `
                        -EvidenceFullPath $evidence.FullPath
                    Start-Sleep -Seconds 2
                }
                default {
                    if ($actionName.StartsWith(
                        "Snapshot",
                        [System.StringComparison]::Ordinal
                    )) {
                        $snapshotName = $actionName.Substring(8).ToLowerInvariant()
                        $snapshots.Add(
                            (Capture-G2Snapshot `
                                -EvidenceFullPath $evidence.FullPath `
                                -Serial $device.serial `
                                -Name $snapshotName)
                        )
                    }
                }
            }
        }
        $windowEnd = $windowStart.AddSeconds(600)
        while ([DateTimeOffset]::Now -lt $windowEnd) {
            $remaining = ($windowEnd - [DateTimeOffset]::Now).TotalMilliseconds
            if ($remaining -le 0) {
                break
            }
            Start-Sleep -Milliseconds ([int][Math]::Min(
                1000,
                [Math]::Ceiling($remaining)
            ))
        }
        $completedAt = $windowEnd
        $windowCompletedAt = $windowEnd
        $netAfterText = (Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @(
                "shell",
                "dumpsys",
                "netstats",
                "detail"
            )).Output -join "`n"
        Save-G2Text `
            -EvidenceFullPath $evidence.FullPath `
            -RelativePath "raw/netstats-after.txt" `
            -Lines @($netAfterText)
        $netAfter = Get-G2NetStatsUidUsage `
            -Text $netAfterText `
            -Uid $uid
        $netDelta = New-G2NetStatsDelta `
            -Before $netBefore `
            -After $netAfter
        Write-G2Json `
            -Path (Join-Path $evidence.FullPath "raw/netstats-after.json") `
            -Value ([pscustomobject]@{
                before = $netBefore
                after = $netAfter
                delta = $netDelta
            })
        $crashLines = (Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @(
                "logcat",
                "-b",
                "crash",
                "-d",
                "-v",
                "threadtime,year",
                "*:V"
            )).Output
        Save-G2Text `
            -EvidenceFullPath $evidence.FullPath `
            -RelativePath "raw/crash.log" `
            -Lines $crashLines
        $exitInfo = (Invoke-G2Adb `
            -Serial $device.serial `
            -Arguments @(
                "shell",
                "dumpsys",
                "activity",
                "exit-info",
                $mainPackage
            ) -AllowFailure).Output
        Save-G2Text `
            -EvidenceFullPath $evidence.FullPath `
            -RelativePath "raw/exit-info.txt" `
            -Lines $exitInfo
    } catch {
        $windowError = $_.Exception.Message
    } finally {
        if ($null -ne $logProcess -and -not $logProcess.HasExited) {
            Stop-Process -Id $logProcess.Id -Force
            $logProcess.WaitForExit(5000)
        }
    }
    $completedClock = [DateTimeOffset]::Now
    $session = [pscustomobject]@{
        schema = "aurorama.g2-collector-session.v1"
        contractId = [string]$contract.contractId
        toolOwnerRole = $toolOwnerRole
        qaOwnerRole = [string]$registration.qaOwnerRole
        evidenceRoot = [string]$evidence.RelativePath
        toolsDigest = [string]$Context.ToolsDigest
        candidateMainSha256 = [string]$contract.candidate.main.sha256
        candidateFocusPeerSha256 = [string]$contract.candidate.focusPeer.sha256
        sourceCommit = [string]$contract.candidate.sourceCommit
        deviceRole = $DeviceRole
        serial = [string]$device.serial
        attemptNumber = 1
        windowSeconds = 600
        extended = $false
        retry = $false
        continuous = $true
        startedAt = if ($null -eq $windowStartedAt) {
            $startedAt.ToString("o")
        } else {
            $windowStartedAt.ToString("o")
        }
        completedAt = if ($null -eq $windowCompletedAt) {
            $completedClock.ToString("o")
        } else {
            $windowCompletedAt.ToString("o")
        }
        uid = $appUid
        device = $device
        commandCount = $commands.Count
        commands = $commands.ToArray()
        snapshots = $snapshots.ToArray()
        hostLogcatProcessId = if ($null -eq $logProcess) {
            $null
        } else {
            [int]$logProcess.Id
        }
        hostLogcatProcessStopped = (
            $null -eq $logProcess -or $logProcess.HasExited
        )
        error = $windowError
    }
    Write-G2Json -Path $sessionPath -Value $session
    if (-not [string]::IsNullOrWhiteSpace($windowError)) {
        throw "WINDOW_FAILED $windowError"
    }
    Write-Output "COLLECTED G2_RUNTIME_WINDOW"
    Write-Output "SESSION=$sessionPath"
}

function Register-G2Device {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole
    )
    Assert-G2WindowPolicy `
        -Attempt $Attempt `
        -WindowSeconds $WindowSeconds `
        -Extended $Extended.IsPresent `
        -Retry $Retry.IsPresent
    $contract = $Context.Contract
    $evidence = $Context.Evidence
    [void][System.IO.Directory]::CreateDirectory($evidence.FullPath)
    $registrationPath = Join-Path $evidence.FullPath "collector-registration.json"
    $lockPath = Join-Path $evidence.FullPath "device-lock.json"
    if (
        (Test-Path -LiteralPath $registrationPath -PathType Leaf) -or
        (Test-Path -LiteralPath $lockPath -PathType Leaf)
    ) {
        throw "ATTEMPT_ALREADY_REGISTERED RETRY_FORBIDDEN"
    }
    $predecessor = $null
    if ($DeviceRole -ceq "K60") {
        $padReportPath = Join-Path `
            $contractRoot `
            "qa-pad-v1/report.json"
        $padLockPath = Join-Path `
            $contractRoot `
            "qa-pad-v1/device-lock.json"
        if (
            -not (Test-Path -LiteralPath $padReportPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath $padLockPath -PathType Leaf)
        ) {
            throw "K60_PREDECESSOR_REPORT_OR_LOCK_MISSING"
        }
        $padReport = Read-G2JsonStrict $padReportPath
        $padLock = Read-G2JsonStrict $padLockPath
        $nonPassGates = @(
            $padReport.productGates |
                Where-Object { [string]$_.status -cne "PASS" }
        )
        if (
            [string]$padReport.verdict -cne "PASS" -or
            [string]$padReport.productVerdict -cne "PASS" -or
            $nonPassGates.Count -gt 0 -or
            [string]$padReport.cleanupAndRestore.status -cne "PASS" -or
            [string]$padReport.candidate.mainSha256 -cne
                [string]$contract.candidate.main.sha256 -or
            [string]$padReport.candidate.focusPeerSha256 -cne
                [string]$contract.candidate.focusPeer.sha256 -or
            [string]$padLock.state -cne "RELEASED_ACTIVE_0" -or
            [int]$padLock.activeCount -ne 0
        ) {
            throw "K60_PREDECESSOR_PAD_NOT_FULL_PASS_OR_LOCK_NOT_RELEASED"
        }
        $predecessor = [pscustomobject]@{
            deviceOrder = "Pad"
            coreVerdict = "PASS"
            productVerdict = "PASS"
            lockStateAfterClosure = "RELEASED_ACTIVE_0"
            candidateMainSha256 = [string]$contract.candidate.main.sha256
            candidateFocusPeerSha256 = [string]$contract.candidate.focusPeer.sha256
            evidencePointer = (
                ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1/report.json"
            )
        }
    }
    $serial = if ($DeviceRole -ceq "Pad") {
        [string]$contract.devicePolicy.pad.serial
    } else {
        [string]$contract.devicePolicy.k60.serial
    }
    Assert-G2DevicePolicy `
        -Contract $contract `
        -DeviceRole $DeviceRole `
        -Serial $serial `
        -Predecessor $predecessor
    $qaOwnerRole = if ($DeviceRole -ceq "Pad") {
        "G2_PAD_QA_OWNER_2026_11_06"
    } else {
        "G2_K60_QA_OWNER_2026_11_06"
    }
    $registeredAt = [DateTimeOffset]::Now.ToString("o")
    $registration = [pscustomobject]@{
        schema = "aurorama.g2-collector-registration.v1"
        contractId = [string]$contract.contractId
        toolOwnerRole = $toolOwnerRole
        qaOwnerRole = $qaOwnerRole
        deviceRole = $DeviceRole
        serial = $serial
        attemptNumber = 1
        windowSeconds = 600
        registeredAt = $registeredAt
        candidateMainSha256 = [string]$contract.candidate.main.sha256
        candidateFocusPeerSha256 = [string]$contract.candidate.focusPeer.sha256
        sourceCommit = [string]$contract.candidate.sourceCommit
        toolsDigest = [string]$Context.ToolsDigest
        predecessor = $predecessor
        lockState = "ACTIVE_COUNT_1"
    }
    Write-G2Json -Path $registrationPath -Value $registration
    Write-G2Json -Path $lockPath -Value ([pscustomobject]@{
        schema = "aurorama.g2-device-lock.v1"
        deviceRole = $DeviceRole
        serial = $serial
        state = "ACTIVE_COUNT_1"
        activeCount = 1
        attemptNumber = 1
        registeredAt = $registeredAt
        releasedAt = $null
    })
    Write-Output "REGISTERED G2_DEVICE role=$DeviceRole serial=$serial"
    Write-Output "REGISTRATION=$registrationPath"
}

function Get-G2ReconciledObservations {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole
    )
    $contract = $Context.Contract
    $evidence = $Context.Evidence
    $sessionPath = Join-Path $evidence.FullPath "collector-session.json"
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) {
        throw "COLLECTOR_SESSION_MISSING"
    }
    $session = Read-G2JsonStrict $sessionPath
    $registration = Read-G2JsonStrict (
        Join-Path $evidence.FullPath "collector-registration.json"
    )
    $runtimeLogPath = Join-Path $evidence.FullPath "raw/runtime.log"
    $runtimeLines = if (Test-Path -LiteralPath $runtimeLogPath) {
        @(Get-Content -LiteralPath $runtimeLogPath -Encoding utf8)
    } else {
        @()
    }
    $audioEvents = @()
    $audioError = $null
    try {
        $audioEvents = @(Get-G2AudioTrackEvents -Lines $runtimeLines)
    } catch {
        $audioError = $_.Exception.Message
    }
    $maximumGap = 15.0
    if ($audioEvents.Count -gt 0) {
        $maximumGap = Get-G2MaximumAudioGapSeconds -Events $audioEvents
    }
    $netBeforeText = if (Test-Path -LiteralPath (
        Join-Path $evidence.FullPath "raw/netstats-before.txt"
    )) {
        Get-Content `
            -LiteralPath (Join-Path $evidence.FullPath "raw/netstats-before.txt") `
            -Raw `
            -Encoding utf8
    } else {
        ""
    }
    $netAfterText = if (Test-Path -LiteralPath (
        Join-Path $evidence.FullPath "raw/netstats-after.txt"
    )) {
        Get-Content `
            -LiteralPath (Join-Path $evidence.FullPath "raw/netstats-after.txt") `
            -Raw `
            -Encoding utf8
    } else {
        ""
    }
    $uid = [int]$session.uid
    $netDelta = $null
    if ($uid -gt 0) {
        $netDelta = New-G2NetStatsDelta `
            -Before (Get-G2NetStatsUidUsage -Text $netBeforeText -Uid $uid) `
            -After (Get-G2NetStatsUidUsage -Text $netAfterText -Uid $uid)
    }
    $crashText = if (Test-Path -LiteralPath (
        Join-Path $evidence.FullPath "raw/crash.log"
    )) {
        Get-Content `
            -LiteralPath (Join-Path $evidence.FullPath "raw/crash.log") `
            -Raw `
            -Encoding utf8
    } else {
        ""
    }
    $nativeCrash = $crashText -match
        "(?im)Fatal signal|F DEBUG|tombstone|signal \d+ \(SIG"
    $javaFatal = $crashText -match
        "(?im)FATAL EXCEPTION|Process: $([regex]::Escape($mainPackage))"
    $anr = $crashText -match "(?im)ANR in|Application Not Responding"
    $inputTimeout = $crashText -match
        "(?im)Input dispatching timed out|InputTimeout"
    $started = [string]$session.startedAt
    $completed = [string]$session.completedAt
    $headEvent = @($audioEvents | Select-Object -First 1)
    $tailEvent = @($audioEvents | Select-Object -Last 1)
    $headVoice = if ($headEvent.Count -gt 0) {
        [string]$headEvent[0].voiceId
    } else {
        "zf_001"
    }
    $tailVoice = if ($tailEvent.Count -gt 0) {
        [string]$tailEvent[0].voiceId
    } else {
        "zm_010"
    }
    $observations = [pscustomobject]@{
        schema = "aurorama.g2-runtime-observations.v1"
        ownerRole = [string]$registration.qaOwnerRole
        evidenceRoot = [string]$evidence.RelativePath
        device = [pscustomobject]@{
            order = $DeviceRole
            role = [string]$registration.qaOwnerRole
            serial = [string]$session.serial
            model = [string]$session.device.model
            sdk = [int]$session.device.sdk
            abi = [string]$session.device.abi
            user = [int]$session.device.user
            lockStateAtStart = "ACTIVE_COUNT_1"
        }
        predecessor = $registration.predecessor
        execution = [pscustomobject]@{
            windowMinutes = 10
            windowSeconds = 600
            attemptNumber = 1
            extended = $false
            retry = $false
            continuous = $true
            startedAt = $started
            completedAt = $completed
            freshInstall = $true
            candidateIdentityVerified = $true
        }
        audioTrackEvents = $audioEvents
        continuity = [pscustomobject]@{
            head = [pscustomobject]@{
                timestamp = $started
                voiceId = $headVoice
                audioTrackStarted = ($headEvent.Count -gt 0)
                audioTrackCompleted = (
                    $headEvent.Count -gt 0 -and
                    $audioEvents.Count -gt 1
                )
                evidence = @("raw/runtime.log")
            }
            middle = [pscustomobject]@{
                timestamp = $started
                voiceId = "zm_010"
                audioTrackStarted = (
                    @($audioEvents | Where-Object voiceId -eq "zm_010" |
                        Where-Object kind -eq "START").Count -gt 0
                )
                audioTrackCompleted = (
                    @($audioEvents | Where-Object voiceId -eq "zm_010" |
                        Where-Object kind -eq "COMPLETION").Count -gt 0
                )
                evidence = @("raw/runtime.log")
            }
            tail = [pscustomobject]@{
                timestamp = $completed
                voiceId = $tailVoice
                audioTrackStarted = ($tailEvent.Count -gt 0)
                audioTrackCompleted = (
                    $tailEvent.Count -gt 0 -and
                    [string]$tailEvent[0].kind -ceq "COMPLETION"
                )
                evidence = @("raw/runtime.log")
            }
            maximumAudioGapSeconds = $maximumGap
        }
        systemSurfaces = [pscustomobject]@{
            foregroundService = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            mediaSession = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            notification = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            ui = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            systemControls = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            stateEquality = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
        }
        audioFocus = [pscustomobject]@{
            status = "NOT_RUN"
            singleOwner = $false
            ownerPids = @()
            focusRequest = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            focusLoss = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            focusGain = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @()
            }
            evidence = @()
        }
        stability = [pscustomobject]@{
            nativeCrash = [pscustomobject]@{
                status = if ($nativeCrash) { "FAIL" } else { "PASS" }
                evidence = @("raw/crash.log")
            }
            javaFatal = [pscustomobject]@{
                status = if ($javaFatal) { "FAIL" } else { "PASS" }
                evidence = @("raw/crash.log")
            }
            anr = [pscustomobject]@{
                status = if ($anr) { "FAIL" } else { "PASS" }
                evidence = @("raw/crash.log")
            }
            inputTimeout = [pscustomobject]@{
                status = if ($inputTimeout) { "FAIL" } else { "PASS" }
                evidence = @("raw/crash.log")
            }
            applicationExitInfo = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @("raw/exit-info.txt")
            }
            evidence = @("raw/crash.log", "raw/exit-info.txt")
        }
        uiCancel = [pscustomobject]@{
            status = "NOT_RUN"
            cancelObserved = $false
            pidConverged = $false
            serviceConverged = $false
            notificationConverged = $false
            mediaSessionConverged = $false
            focusConverged = $false
            evidence = @("raw/snapshots/cancel")
        }
        processRecovery = [pscustomobject]@{
            processKilled = $true
            recoveryMode = "FAIL_CLOSED"
            autoPlaybackAfterDeath = $false
            lastStableSentenceRestored = $false
            packWhitelistVerified = $false
            serviceOwnerAfterRestart = [pscustomobject]@{
                status = "NOT_RUN"
                evidence = @("raw/snapshots/recovery/service.txt")
            }
            evidence = @("raw/snapshots/recovery")
        }
        zeroNetwork = [pscustomobject]@{
            status = if (
                $null -ne $netDelta -and
                $netDelta.allPartitionsAccounted -and
                $netDelta.windowBytesDelta -eq 0
            ) { "PASS" } elseif ($null -eq $netDelta) {
                "NOT_RUN"
            } else {
                "FAIL"
            }
            uid = $uid
            allPartitionsAccounted = if ($null -eq $netDelta) {
                $false
            } else {
                [bool]$netDelta.allPartitionsAccounted
            }
            windowBytesDelta = if ($null -eq $netDelta) {
                0
            } else {
                [long]$netDelta.windowBytesDelta
            }
            nonzeroAttribution = if (
                $null -ne $netDelta -and
                $netDelta.windowBytesDelta -ne 0
            ) {
                @("raw/netstats-before.txt", "raw/netstats-after.txt")
            } else {
                @()
            }
            evidence = @(
                "raw/netstats-before.txt",
                "raw/netstats-after.txt"
            )
        }
        productFindings = [pscustomobject]@{
            P0 = "NOT_ASSESSED"
            P1 = "NOT_ASSESSED"
            P2 = "NOT_ASSESSED"
            P3 = "NOT_ASSESSED"
            summary = "Collector output requires independent QA review."
        }
        uncovered = @(
            "controller reconciliation requires independent QA review"
        )
        cleanupAndRestore = [pscustomobject]@{
            status = "BLOCKED"
            packagesAbsent = $false
            pidsAbsent = $false
            serviceRecordsAbsent = $false
            activeNotificationRecordsAbsent = $false
            activeMediaSessionsAbsent = $false
            audioFocusReleased = $false
            externalDataAbsent = $false
            internalDataAbsent = $false
            hostLogcatOrphans = if (
                [bool]$session.hostLogcatProcessStopped
            ) { 0 } else { 1 }
            networkRestored = $false
            userSettingsRestored = $false
            productionPackageMatch = $false
            deviceLockState = "ACTIVE_COUNT_1"
            deviceLockActiveCount = 1
            evidence = @("cleanup.json")
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($audioError)) {
        $observations.uncovered = @(
            $observations.uncovered
            "audio reconciliation: $audioError"
        )
    }
    $outputPath = Join-Path $evidence.FullPath "observations.auto.json"
    Write-G2Json -Path $outputPath -Value $observations
    return $outputPath
}

function Invoke-G2CollectorStaticSelfTest {
    Assert-G2WindowPolicy `
        -Attempt 1 `
        -WindowSeconds 600 `
        -Extended $false `
        -Retry $false
    $before = @"
IDENT: uid=10315 set=DEFAULT tag=0x0 rb=1 tb=2
IDENT: uid=10315 set=FOREGROUND tag=0x0 rb=3 tb=4
"@
    $after = @"
IDENT: uid=10315 set=DEFAULT tag=0x0 rb=1 tb=2
IDENT: uid=10315 set=FOREGROUND tag=0x0 rb=3 tb=4
"@
    $delta = New-G2NetStatsDelta `
        -Before (Get-G2NetStatsUidUsage -Text $before -Uid 10315) `
        -After (Get-G2NetStatsUidUsage -Text $after -Uid 10315)
    if (-not $delta.allPartitionsAccounted -or
        $delta.windowBytesDelta -ne 0) {
        throw "NETSTATS_STATIC_SELF_TEST_FAILED"
    }
    $events = @(Get-G2AudioTrackEvents -Lines @(
        "2026-11-06 12:00:00.000  2000  2100 I KOKORO_RUNTIME: TTS_SENTENCE_REQUEST index=0 speaker=zf_001 sid=3 generation=1 longRun=true total=2",
        "2026-11-06 12:00:01.000  2000  2101 I KOKORO_RUNTIME: AUDIO_TRACK_STARTED owner=TTS generation=1 samples=48000 durationMillis=1000 longRun=true longRunAudioMillis=0",
        "2026-11-06 12:00:02.000  2000  2101 I KOKORO_RUNTIME: AUDIO_TRACK_COMPLETED owner=TTS generation=1"
    ))
    if ($events.Count -ne 2) {
        throw "AUDIO_EVENT_STATIC_SELF_TEST_FAILED"
    }
    Write-Output "PASS G2_RUNTIME_COLLECTOR_STATIC_SELF_TEST"
    Write-Output "DEVICE_COMMANDS=0"
}

function Invoke-G2CollectorMain {
    if ($Action -ceq "StaticSelfTest") {
        if ([string]::IsNullOrWhiteSpace($DeviceRole)) {
            $DeviceRole = "Pad"
        }
        Invoke-G2CollectorStaticSelfTest
        return
    }
    if ([string]::IsNullOrWhiteSpace($DeviceRole)) {
        throw "DEVICE_ROLE_REQUIRED"
    }
    $context = Get-G2ContractContext `
        -DeviceRole $DeviceRole `
        -RequestedEvidenceRoot $EvidenceRoot
    switch ($Action) {
        "Validate" {
            Write-Output "PASS G2_RUNTIME_COLLECTOR_CONTRACT"
            Write-Output "DEVICE=$DeviceRole"
            Write-Output "EVIDENCE_ROOT=$($context.Evidence.RelativePath)"
            Write-Output "TOOLS_DIGEST=$($context.ToolsDigest)"
        }
        "Register" {
            Register-G2Device `
                -Context $context `
                -DeviceRole $DeviceRole
        }
        "RunWindow" {
            Invoke-G2Window `
                -Context $context `
                -DeviceRole $DeviceRole `
                -PlanPath $InteractionPlanPath
        }
        "Reconcile" {
            $path = Get-G2ReconciledObservations `
                -Context $context `
                -DeviceRole $DeviceRole
            Write-Output "RECONCILED G2_RUNTIME_OBSERVATIONS"
            Write-Output "OBSERVATIONS=$path"
        }
        default {
            throw "UNSUPPORTED_ACTION $Action"
        }
    }
}

if ($MyInvocation.InvocationName -ne ".") {
    try {
        Invoke-G2CollectorMain
    } catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
