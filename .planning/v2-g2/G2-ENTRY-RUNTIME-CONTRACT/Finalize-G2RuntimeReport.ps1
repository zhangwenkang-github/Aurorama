[CmdletBinding()]
param(
    [switch]$StaticSelfTest,
    [ValidateSet("Pad", "K60")]
    [string]$DeviceRole,
    [string]$EvidenceRoot,
    [string]$ObservationsPath,
    [string]$OutputPath,
    [switch]$FixtureMode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
$contractRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $contractRoot "..\..\..")
)
$contractPath = Join-Path $contractRoot "contract.json"
$candidatePath = Join-Path $contractRoot "candidate.json"
$authorizationPath = Join-Path $contractRoot "authorization-receipt.json"
$schemaPath = Join-Path $contractRoot "report.schema.json"
$manifestPath = Join-Path $contractRoot "runtime-tools.manifest.json"
$pinnedSourceCommit = "35f9d493f16cdf79f946871fc331e2ceece287d2"
$pinnedMainSha256 =
    "1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3"
$pinnedPeerSha256 =
    "d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a"
$pinnedSchemaSha256 =
    "a1c1549eb08b8c49ad0a2c4b6bb59fb8bbf641d1529f05948d1bb9e591409bae"
$gateIds = @(
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
$requiredObservationProperties = @(
    "schema",
    "ownerRole",
    "evidenceRoot",
    "device",
    "predecessor",
    "execution",
    "audioTrackEvents",
    "continuity",
    "systemSurfaces",
    "audioFocus",
    "stability",
    "uiCancel",
    "processRecovery",
    "zeroNetwork",
    "productFindings",
    "uncovered",
    "cleanupAndRestore"
)

. (Join-Path $contractRoot "G2RuntimeStatePolicy.ps1")

function Read-G2ReportJsonStrict {
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
    $jsonText = $utf8Strict.GetString($bytes)
    Assert-G2StateNoDuplicateJsonProperties `
        -JsonText $jsonText `
        -Name $Path
    return (
        $jsonText |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Get-G2ReportFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function Write-G2ReportText {
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

function Write-G2ReportJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    Write-G2ReportText `
        -Path $Path `
        -Text ($Value | ConvertTo-Json -Depth 100)
}

function Resolve-G2ReportBoundedPath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        throw "PATH_EMPTY"
    }
    if (
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath -match "^[A-Za-z]:" -or
        $RelativePath.StartsWith("\\", [System.StringComparison]::Ordinal)
    ) {
        throw "PATH_ABSOLUTE_FORBIDDEN"
    }
    $segments = @($RelativePath -split "[\\/]")
    if ($segments -contains "..") {
        throw "PATH_TRAVERSAL_FORBIDDEN"
    }
    $fullRoot = [System.IO.Path]::GetFullPath($Root).
        TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $fullRoot $RelativePath)
    )
    if (-not $fullPath.StartsWith(
        ($fullRoot + [System.IO.Path]::DirectorySeparatorChar),
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "PATH_OUTSIDE_ROOT"
    }
    Assert-G2ReportNoReparsePath -Root $fullRoot -FullPath $fullPath
    return $fullPath
}

function Assert-G2ReportNoReparsePath {
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

function Assert-G2ReportPinnedIdentity {
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

function Assert-G2ReportAuthorization {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Authorization
    )
    if (
        [string]$Authorization.schema -cne
            "aurorama.g2-device-execution-authorization.v1" -or
        [string]$Authorization.decisionId -cne "V2-D210" -or
        [string]$Authorization.contractId -cne [string]$Contract.contractId -or
        [string]$Authorization.authorizedBy -cne "USER" -or
        [bool]$Authorization.scope.runtimeQa -ne $true -or
        [bool]$Authorization.scope.deviceInstall -ne $true -or
        [bool]$Authorization.scope.pad.authorized -ne $true -or
        [string]$Authorization.scope.pad.serial -cne "43af8627" -or
        [bool]$Authorization.scope.k60.authorized -ne $true -or
        [bool]$Authorization.scope.k60.conditional -ne $true -or
        [string]$Authorization.scope.k60.serial -cne "8e875894" -or
        [string]$Authorization.scope.k60.condition -cne
            "PAD_FULL_CORE_PASS_AND_RELEASED_ACTIVE_0" -or
        [string]$Authorization.candidate.sourceCommit -cne
            $pinnedSourceCommit -or
        [string]$Authorization.candidate.mainSha256 -cne
            $pinnedMainSha256 -or
        [string]$Authorization.candidate.focusPeerSha256 -cne
            $pinnedPeerSha256
    ) {
        throw "FINALIZER_AUTHORIZATION_CONDITIONAL_SCOPE_MISMATCH"
    }
}

function Resolve-G2ReportEvidenceRoot {
    param(
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [AllowNull()][string]$RequestedEvidenceRoot,
        [AllowNull()][string]$RepositoryBase
    )
    $expectedRelative = if ($DeviceRole -ceq "Pad") {
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2"
    } else {
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-k60-v2"
    }
    if ([string]::IsNullOrWhiteSpace($RequestedEvidenceRoot)) {
        $RequestedEvidenceRoot = $expectedRelative
    }
    $normalized = $RequestedEvidenceRoot.Replace("\", "/").TrimEnd("/")
    if ($normalized -cne $expectedRelative) {
        throw "EVIDENCE_ROOT_MISMATCH"
    }
    $effectiveRepositoryRoot = if (
        [string]::IsNullOrWhiteSpace($RepositoryBase)
    ) {
        $repositoryRoot
    } else {
        [System.IO.Path]::GetFullPath($RepositoryBase)
    }
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $effectiveRepositoryRoot $expectedRelative)
    )
    Assert-G2ReportNoReparsePath `
        -Root $effectiveRepositoryRoot `
        -FullPath $fullPath
    $effectiveContractRoot = Join-Path `
        $effectiveRepositoryRoot `
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT"
    $contractPrefix =
        $effectiveContractRoot.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar
        ) +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith(
        $contractPrefix,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "PATH_OUTSIDE_ROOT"
    }
    return [pscustomobject]@{
        RelativePath = $expectedRelative
        FullPath = $fullPath
    }
}

function Assert-G2FinalizerReleasedLock {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [Parameter(Mandatory)][string]$EvidenceRoot,
        [Parameter(Mandatory)][string]$LockPath
    )
    if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) {
        throw "FINALIZER_LOCK_MISSING"
    }
    $lock = Read-G2ReportJsonStrict $LockPath
    Assert-G2StateDeviceLockShape `
        -Lock $lock `
        -Name "FINALIZER_DEVICE_LOCK"
    $serial = if ($DeviceRole -ceq "Pad") {
        [string]$Contract.devicePolicy.pad.serial
    } else {
        [string]$Contract.devicePolicy.k60.serial
    }
    $expectedRoot = if ($DeviceRole -ceq "Pad") {
        [string]$Contract.evidence.padRuntimeRoot
    } else {
        [string]$Contract.evidence.k60RuntimeRoot
    }
    if (
        $EvidenceRoot -cne $expectedRoot -or
        [string]$lock.schema -cne "aurorama.g2-device-lock.v1" -or
        [string]$lock.deviceRole -cne $DeviceRole -or
        [string]$lock.serial -cne $serial -or
        [string]$lock.attemptScope -cne $expectedRoot -or
        [string]$lock.state -cne "RELEASED_ACTIVE_0" -or
        [int]$lock.activeCount -ne 0 -or
        [int]$lock.attemptNumber -ne 1 -or
        [string]::IsNullOrWhiteSpace([string]$lock.releasedAt) -or
        [string]$lock.authoritativeLockStateAtRegistration -cne
            "RELEASED_ACTIVE_0"
    ) {
        throw "FINALIZER_LOCK_NOT_RELEASED_ACTIVE_0"
    }
    return [pscustomobject]@{
        state = "RELEASED_ACTIVE_0"
        activeCount = 0
        attemptNumber = 1
        sha256 = Get-G2ReportFileSha256 $LockPath
    }
}

function Get-G2FinalizerContext {
    param(
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [AllowNull()][string]$RequestedEvidenceRoot,
        [string]$Action = "Finalize",
        [AllowNull()][string]$ContractPathOverride,
        [AllowNull()][string]$ManifestPathOverride,
        [AllowNull()][string]$RepairAuditReceiptPathOverride
    )
    $effectiveContractPath = if ([string]::IsNullOrWhiteSpace(
        $ContractPathOverride
    )) {
        $contractPath
    } else {
        [System.IO.Path]::GetFullPath($ContractPathOverride)
    }
    $effectiveManifestPath = if ([string]::IsNullOrWhiteSpace(
        $ManifestPathOverride
    )) {
        $manifestPath
    } else {
        [System.IO.Path]::GetFullPath($ManifestPathOverride)
    }
    foreach ($required in @(
        $effectiveContractPath,
        $candidatePath,
        $authorizationPath,
        $schemaPath,
        $effectiveManifestPath
    )) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "REQUIRED_FILE_MISSING $required"
        }
    }
    $contract = Read-G2ReportJsonStrict $effectiveContractPath
    $candidate = Read-G2ReportJsonStrict $candidatePath
    $authorization = Read-G2ReportJsonStrict $authorizationPath
    $manifest = Read-G2ReportJsonStrict $effectiveManifestPath
    Assert-G2CandidateRecordShape `
        -Candidate $candidate `
        -Name "FINALIZER_CANDIDATE_RECORD"
    Assert-G2AuthorizationReceiptShape `
        -Authorization $authorization `
        -Name "FINALIZER_AUTHORIZATION_RECEIPT"
    Assert-G2ReportPinnedIdentity -Contract $contract
    if (
        $contract.schema -cne "aurorama.g2-entry-runtime-contract.v1" -or
        $contract.contractId -cne
            "G2-ENTRY-RUNTIME-CONTRACT-2026-11-06" -or
        $contract.owner.roleId -cne
            "G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06" -or
        $contract.tools.runtimeFinalizer.roleId -cne $toolOwnerRole -or
        $contract.tools.runtimeFinalizer.path -cne
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/Finalize-G2RuntimeReport.ps1"
    ) {
        throw "FINALIZER_CONTRACT_BINDING_MISMATCH"
    }
    $statePolicy = Assert-G2RuntimeToolStatePolicy `
        -Contract $contract `
        -ContractRoot $contractRoot `
        -RepositoryRoot $repositoryRoot `
        -Action $Action `
        -RepairAuditReceiptPathOverride $RepairAuditReceiptPathOverride `
        -ManifestPathOverride $effectiveManifestPath
    Assert-G2ReportAuthorization `
        -Contract $contract `
        -Authorization $authorization
    if ((Get-G2ReportFileSha256 $schemaPath) -cne
        [string]$contract.evidence.reportSchemaSha256) {
        throw "FINALIZER_SCHEMA_HASH_MISMATCH"
    }
    return [pscustomobject]@{
        Contract = $contract
        Candidate = $candidate
        Authorization = $authorization
        Manifest = $manifest
        Evidence = Resolve-G2ReportEvidenceRoot `
            -DeviceRole $DeviceRole `
            -RequestedEvidenceRoot $RequestedEvidenceRoot
        StatePolicy = $statePolicy
        RepositoryRoot = $repositoryRoot
    }
}

function Assert-G2FinalizerAttemptBinding {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)]$Observations
    )
    $contract = $Context.Contract
    $deviceRole = [string]$Observations.device.order
    if ($deviceRole -notin @("Pad", "K60")) {
        throw "FINALIZER_ATTEMPT_DEVICE_INVALID"
    }
    $expectedRoot = if ($deviceRole -ceq "Pad") {
        [string]$contract.evidence.padRuntimeRoot
    } else {
        [string]$contract.evidence.k60RuntimeRoot
    }
    $expectedOwner = if ($deviceRole -ceq "Pad") {
        "G2_PAD_QA_OWNER_2026_11_06"
    } else {
        "G2_K60_QA_OWNER_2026_11_06"
    }
    $expectedSerial = if ($deviceRole -ceq "Pad") {
        "43af8627"
    } else {
        "8e875894"
    }
    if (
        [string]$Context.Evidence.RelativePath -cne $expectedRoot -or
        [string]$Observations.evidenceRoot -cne $expectedRoot -or
        [string]$Observations.ownerRole -cne $expectedOwner -or
        [string]$Observations.device.role -cne $expectedOwner -or
        [string]$Observations.device.serial -cne $expectedSerial
    ) {
        throw "FINALIZER_ATTEMPT_SCOPE_MISMATCH"
    }
    $registrationPath = Join-Path `
        $Context.Evidence.FullPath `
        "collector-registration.json"
    $sessionPath = Join-Path `
        $Context.Evidence.FullPath `
        "collector-session.json"
    $lockPath = Join-Path $Context.Evidence.FullPath "device-lock.json"
    foreach ($requiredAttemptFile in @(
        $registrationPath,
        $sessionPath,
        $lockPath
    )) {
        if (-not (Test-Path -LiteralPath $requiredAttemptFile -PathType Leaf)) {
            throw "FINALIZER_ATTEMPT_FILE_MISSING $requiredAttemptFile"
        }
    }
    $registration = Read-G2ReportJsonStrict $registrationPath
    $session = Read-G2ReportJsonStrict $sessionPath
    Assert-G2StateRegistrationShape `
        -Registration $registration `
        -Name "FINALIZER_REGISTRATION"
    Assert-G2StateSessionShape `
        -Session $session `
        -Name "FINALIZER_SESSION"
    if (
        [string]$registration.schema -cne
            "aurorama.g2-collector-registration.v1" -or
        [string]$registration.contractId -cne [string]$contract.contractId -or
        [string]$registration.deviceRole -cne $deviceRole -or
        [string]$registration.serial -cne $expectedSerial -or
        [string]$registration.qaOwnerRole -cne $expectedOwner -or
        [string]$registration.attemptScope -cne $expectedRoot -or
        [string]$registration.attemptState -cne
            "REGISTERED_WINDOW_NOT_STARTED" -or
        [string]$registration.lockState -cne "ACTIVE_COUNT_1" -or
        [int]$registration.attemptNumber -ne 1 -or
        [int]$registration.windowSeconds -ne 600 -or
        [string]$registration.candidateMainSha256 -cne
            [string]$contract.candidate.main.sha256 -or
        [string]$registration.candidateFocusPeerSha256 -cne
            [string]$contract.candidate.focusPeer.sha256 -or
        [string]$registration.toolsDigest -cne
            [string]$contract.tools.digestSha256 -or
        [string]$registration.authoritativeLockState -cne
            "RELEASED_ACTIVE_0"
    ) {
        throw "FINALIZER_REGISTRATION_BINDING_MISMATCH"
    }
    if ($deviceRole -ceq "Pad") {
        if (
            [string]$registration.recoveryBasis.outcome -cne
                "PRE_WINDOW_TOOL_BLOCK" -or
            [bool]$registration.recoveryBasis.historicalWindowConsumed -ne
                $false -or
            [string]$registration.recoveryBasis.blockerEvidencePointer -cne
                (
                    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/" +
                    "qa-pad-v1/blocker.json"
                ) -or
            [string]$registration.recoveryBasis.blockerSha256 -notmatch
                "^[0-9a-f]{64}$"
        ) {
            throw "FINALIZER_PREWINDOW_RECOVERY_BASIS_MISMATCH"
        }
    }
    if (
        [string]$session.schema -cne
            "aurorama.g2-collector-session.v1" -or
        [string]$session.contractId -cne [string]$contract.contractId -or
        [string]$session.qaOwnerRole -cne $expectedOwner -or
        [string]$session.evidenceRoot -cne $expectedRoot -or
        [string]$session.attemptScope -cne $expectedRoot -or
        [string]$session.attemptState -cne "WINDOW_STARTED" -or
        [string]$session.deviceRole -cne $deviceRole -or
        [string]$session.serial -cne $expectedSerial -or
        [int]$session.attemptNumber -ne 1 -or
        [int]$session.windowSeconds -ne 600 -or
        [bool]$session.extended -ne $false -or
        [bool]$session.retry -ne $false -or
        [bool]$session.continuous -ne $true -or
        [bool]$session.hostLogcatProcessStopped -ne $true -or
        [string]$session.startedAt -cne
            [string]$Observations.execution.startedAt -or
        [string]$session.completedAt -cne
            [string]$Observations.execution.completedAt -or
        [string]$session.candidateMainSha256 -cne
            [string]$contract.candidate.main.sha256 -or
        [string]$session.candidateFocusPeerSha256 -cne
            [string]$contract.candidate.focusPeer.sha256 -or
        [string]$session.toolsDigest -cne
            [string]$contract.tools.digestSha256 -or
        -not [string]::IsNullOrWhiteSpace([string]$session.error)
    ) {
        throw "FINALIZER_SESSION_BINDING_MISMATCH"
    }
    $releasedLock = Assert-G2FinalizerReleasedLock `
        -Contract $contract `
        -DeviceRole $deviceRole `
        -EvidenceRoot $expectedRoot `
        -LockPath $lockPath
    if ($deviceRole -ceq "K60") {
        $predecessor = $Observations.predecessor
        $padRoot = [string]$contract.evidence.padRuntimeRoot
        $expectedPointer = "$padRoot/report.json"
        $repositoryRoot = [System.IO.Path]::GetFullPath(
            (Join-Path $contractRoot "..\..\..")
        )
        $padReportPath = Resolve-G2ReportBoundedPath `
            -Root $repositoryRoot `
            -RelativePath $expectedPointer
        $padLockPath = Resolve-G2ReportBoundedPath `
            -Root $repositoryRoot `
            -RelativePath "$padRoot/device-lock.json"
        Assert-G2StateExactFields `
            -Value $predecessor `
            -Expected @(
                "deviceOrder",
                "coreVerdict",
                "productVerdict",
                "lockStateAfterClosure",
                "evidenceRoot",
                "evidencePointer",
                "reportSha256",
                "lockSha256",
                "candidateMainSha256",
                "candidateFocusPeerSha256"
            ) `
            -Name "FINALIZER_K60_PREDECESSOR"
        $padReport = Read-G2ReportJsonStrict $padReportPath
        $padLock = Read-G2ReportJsonStrict $padLockPath
        Assert-G2StateRuntimeReportShape `
            -Report $padReport `
            -Name "FINALIZER_K60_PAD_REPORT"
        Assert-G2StateDeviceLockShape `
            -Lock $padLock `
            -Name "FINALIZER_K60_PAD_LOCK"
        if (
            $null -eq $predecessor -or
            [string]$predecessor.coreVerdict -cne "PASS" -or
            [string]$predecessor.productVerdict -cne "PASS" -or
            [string]$predecessor.evidenceRoot -cne $padRoot -or
            [string]$predecessor.evidencePointer -cne $expectedPointer -or
            [string]$predecessor.lockStateAfterClosure -cne
                "RELEASED_ACTIVE_0" -or
            [string]$predecessor.candidateMainSha256 -cne
                [string]$contract.candidate.main.sha256 -or
            [string]$predecessor.candidateFocusPeerSha256 -cne
                [string]$contract.candidate.focusPeer.sha256 -or
            [string]$predecessor.reportSha256 -cne
                (Get-G2ReportFileSha256 $padReportPath) -or
            [string]$predecessor.lockSha256 -cne
                (Get-G2ReportFileSha256 $padLockPath)
        ) {
            throw "FINALIZER_K60_PREDECESSOR_BINDING_MISMATCH"
        }
    }
    return [pscustomobject]@{
        evidenceRoot = $expectedRoot
        attemptNumber = 1
        registrationSha256 = Get-G2ReportFileSha256 $registrationPath
        sessionSha256 = Get-G2ReportFileSha256 $sessionPath
        deviceLockSha256 = [string]$releasedLock.sha256
    }
}

function Assert-G2ObservationShape {
    param([Parameter(Mandatory)]$Observations)
    foreach ($propertyName in $requiredObservationProperties) {
        $property = $Observations.PSObject.Properties[$propertyName]
        if ($null -eq $property) {
            throw "OBSERVATION_MISSING $propertyName"
        }
    }
    if (@($Observations.audioTrackEvents).Count -eq 0) {
        throw "OBSERVATION_MISSING audioTrackEvents NOT_RUN"
    }
}

function Get-G2CombinedStatus {
    param([AllowEmptyCollection()][object[]]$Values)
    $statuses = @(
        $Values | ForEach-Object {
            if ($null -eq $_) {
                "NOT_RUN"
            } else {
                [string]$_
            }
        }
    )
    if ($statuses.Count -eq 0 -or $statuses -contains "NOT_RUN") {
        return "NOT_RUN"
    }
    if ($statuses -contains "FAIL" -or $statuses -contains "BLOCKED") {
        return "FAIL"
    }
    if (@($statuses | Where-Object { $_ -cne "PASS" }).Count -gt 0) {
        return "NOT_RUN"
    }
    return "PASS"
}

function Get-G2GateResult {
    param(
        [Parameter(Mandatory)][string]$GateId,
        [Parameter(Mandatory)][string]$Status,
        [AllowEmptyCollection()][string[]]$Evidence = @(),
        [AllowNull()][string]$Observed
    )
    return [pscustomobject]@{
        gateId = $GateId
        status = $Status
        evidence = @($Evidence | Where-Object {
            -not [string]::IsNullOrWhiteSpace([string]$_)
        })
        observed = if ([string]::IsNullOrWhiteSpace($Observed)) {
            $null
        } else {
            $Observed
        }
    }
}

function Get-G2ProductGateResults {
    param([Parameter(Mandatory)]$Observations)
    $events = @($Observations.audioTrackEvents)
    $voiceStartComplete = @(
        "zf_001",
        "zm_010"
    ) | ForEach-Object {
        $voice = $_
        @($events | Where-Object kind -eq "START" |
            Where-Object voiceId -eq $voice).Count -gt 0 -and
        @($events | Where-Object kind -eq "COMPLETION" |
            Where-Object voiceId -eq $voice).Count -gt 0
    }
    $bothVoices =
        $voiceStartComplete.Count -eq 2 -and
        @($voiceStartComplete | Where-Object { -not $_ }).Count -eq 0
    $continuity = $Observations.continuity
    $continuityPass =
        [bool]$continuity.head.audioTrackStarted -and
        [bool]$continuity.head.audioTrackCompleted -and
        [bool]$continuity.middle.audioTrackStarted -and
        [bool]$continuity.middle.audioTrackCompleted -and
        [bool]$continuity.tail.audioTrackStarted -and
        [bool]$continuity.tail.audioTrackCompleted
    $gapPass = [double]$continuity.maximumAudioGapSeconds -le 15.0
    $surfaces = $Observations.systemSurfaces
    $surfaceStatuses = @(
        $surfaces.foregroundService.status,
        $surfaces.mediaSession.status,
        $surfaces.notification.status,
        $surfaces.ui.status,
        $surfaces.systemControls.status,
        $surfaces.stateEquality.status
    )
    $surfaceStatus = Get-G2CombinedStatus $surfaceStatuses
    $focusObject = $Observations.audioFocus
    $focusStatuses = @(
        $focusObject.status,
        $focusObject.focusRequest.status,
        $focusObject.focusLoss.status,
        $focusObject.focusGain.status
    )
    $focusStatus = if (
        [bool]$focusObject.singleOwner -and
        @($focusObject.ownerPids).Count -eq 1 -and
        (Get-G2CombinedStatus $focusStatuses) -ceq "PASS"
    ) {
        "PASS"
    } else {
        Get-G2CombinedStatus $focusStatuses
    }
    $stability = $Observations.stability
    $stabilityStatus = Get-G2CombinedStatus @(
        $stability.nativeCrash.status,
        $stability.javaFatal.status,
        $stability.anr.status,
        $stability.inputTimeout.status,
        $stability.applicationExitInfo.status
    )
    $cancel = $Observations.uiCancel
    $cancelPass =
        [string]$cancel.status -ceq "PASS" -and
        [bool]$cancel.cancelObserved -and
        [bool]$cancel.pidConverged -and
        [bool]$cancel.serviceConverged -and
        [bool]$cancel.notificationConverged -and
        [bool]$cancel.mediaSessionConverged -and
        [bool]$cancel.focusConverged
    $recovery = $Observations.processRecovery
    $recoveryPass =
        [bool]$recovery.processKilled -and
        [string]$recovery.recoveryMode -ceq
            "PAUSED_AT_LAST_STABLE_SENTENCE" -and
        [bool]$recovery.autoPlaybackAfterDeath -eq $false -and
        [bool]$recovery.lastStableSentenceRestored -and
        [bool]$recovery.packWhitelistVerified -and
        [string]$recovery.serviceOwnerAfterRestart.status -ceq "PASS"
    $network = $Observations.zeroNetwork
    $networkPass =
        [string]$network.status -ceq "PASS" -and
        [bool]$network.allPartitionsAccounted -and
        [long]$network.windowBytesDelta -eq 0 -and
        @($network.nonzeroAttribution).Count -eq 0
    $cleanup = $Observations.cleanupAndRestore
    $cleanupPass =
        [string]$cleanup.status -ceq "PASS" -and
        [bool]$cleanup.packagesAbsent -and
        [bool]$cleanup.pidsAbsent -and
        [bool]$cleanup.serviceRecordsAbsent -and
        [bool]$cleanup.activeNotificationRecordsAbsent -and
        [bool]$cleanup.activeMediaSessionsAbsent -and
        [bool]$cleanup.audioFocusReleased -and
        [bool]$cleanup.externalDataAbsent -and
        [bool]$cleanup.internalDataAbsent -and
        [int]$cleanup.hostLogcatOrphans -eq 0 -and
        [bool]$cleanup.networkRestored -and
        [bool]$cleanup.userSettingsRestored -and
        [bool]$cleanup.productionPackageMatch -and
        [string]$cleanup.deviceLockState -ceq
            "RELEASED_ACTIVE_0" -and
        [int]$cleanup.deviceLockActiveCount -eq 0
    $execution = $Observations.execution
    $windowPass =
        [int]$execution.windowMinutes -eq 10 -and
        [int]$execution.windowSeconds -eq 600 -and
        [int]$execution.attemptNumber -eq 1 -and
        [bool]$execution.extended -eq $false -and
        [bool]$execution.retry -eq $false -and
        [bool]$execution.continuous
    $identityPass =
        [bool]$execution.freshInstall -and
        [bool]$execution.candidateIdentityVerified
    $audioEventEvidence = @($events | ForEach-Object { $_.evidence } |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
        Select-Object -Unique)
    return @(
        (Get-G2GateResult `
            -GateId $gateIds[0] `
            -Status $(if ($identityPass) { "PASS" } else { "FAIL" }) `
            -Evidence @("collector-session.json") `
            -Observed "freshInstall=$($execution.freshInstall)")
        (Get-G2GateResult `
            -GateId $gateIds[1] `
            -Status $(if ($bothVoices) { "PASS" } else { "NOT_RUN" }) `
            -Evidence $audioEventEvidence `
            -Observed "audioTrackEvents=$($events.Count)")
        (Get-G2GateResult `
            -GateId $gateIds[2] `
            -Status $(if ($bothVoices) { "PASS" } else { "NOT_RUN" }) `
            -Evidence $audioEventEvidence `
            -Observed "zf_001_and_zm_010=$bothVoices")
        (Get-G2GateResult `
            -GateId $gateIds[3] `
            -Status $(if ($continuityPass) { "PASS" } else { "NOT_RUN" }) `
            -Evidence @(
                $continuity.head.evidence
                $continuity.middle.evidence
                $continuity.tail.evidence
            ) `
            -Observed "head/middle/tail=$continuityPass")
        (Get-G2GateResult `
            -GateId $gateIds[4] `
            -Status $(if ($gapPass) { "PASS" } else { "FAIL" }) `
            -Evidence @("raw/runtime.log") `
            -Observed "maximumAudioGapSeconds=$($continuity.maximumAudioGapSeconds)")
        (Get-G2GateResult `
            -GateId $gateIds[5] `
            -Status $surfaceStatus `
            -Evidence @(
                $surfaces.foregroundService.evidence
                $surfaces.mediaSession.evidence
                $surfaces.notification.evidence
                $surfaces.ui.evidence
                $surfaces.systemControls.evidence
                $surfaces.stateEquality.evidence
            ) `
            -Observed "surfaceStatus=$surfaceStatus")
        (Get-G2GateResult `
            -GateId $gateIds[6] `
            -Status $focusStatus `
            -Evidence @($focusObject.evidence) `
            -Observed "singleOwner=$($focusObject.singleOwner)")
        (Get-G2GateResult `
            -GateId $gateIds[7] `
            -Status $stabilityStatus `
            -Evidence @($stability.evidence) `
            -Observed "stabilityStatus=$stabilityStatus")
        (Get-G2GateResult `
            -GateId $gateIds[8] `
            -Status $(if ($cancelPass) { "PASS" } else { "FAIL" }) `
            -Evidence @($cancel.evidence) `
            -Observed "cancelPass=$cancelPass")
        (Get-G2GateResult `
            -GateId $gateIds[9] `
            -Status $(if ($recoveryPass) { "PASS" } else { "NOT_RUN" }) `
            -Evidence @($recovery.evidence) `
            -Observed "recoveryPass=$recoveryPass")
        (Get-G2GateResult `
            -GateId $gateIds[10] `
            -Status $(if ($networkPass) { "PASS" } else { "FAIL" }) `
            -Evidence @($network.evidence) `
            -Observed "windowBytesDelta=$($network.windowBytesDelta)")
        (Get-G2GateResult `
            -GateId $gateIds[11] `
            -Status $(if ($cleanupPass) { "PASS" } else { "FAIL" }) `
            -Evidence @($cleanup.evidence) `
            -Observed "cleanupPass=$cleanupPass")
        (Get-G2GateResult `
            -GateId $gateIds[12] `
            -Status $(if ($windowPass) { "PASS" } else { "FAIL" }) `
            -Evidence @("collector-session.json") `
            -Observed "attempt=1/window=600/no-retry/no-extension")
    )
}

function ConvertTo-G2CandidateReport {
    param([Parameter(Mandatory)]$Contract)
    return [pscustomobject]@{
        sourceCommit = [string]$Contract.candidate.sourceCommit
        mainPath = [string]$Contract.candidate.main.path
        mainSha256 = [string]$Contract.candidate.main.sha256
        mainBytes = [long]$Contract.candidate.main.bytes
        mainPackage = [string]$Contract.candidate.main.package
        mainVersionCode = [int]$Contract.candidate.main.versionCode
        mainVersionName = [string]$Contract.candidate.main.versionName
        mainAbi = [string]$Contract.candidate.main.abi
        mainInternetPermission = [bool]$Contract.candidate.main.internetPermission
        focusPeerPath = [string]$Contract.candidate.focusPeer.path
        focusPeerSha256 = [string]$Contract.candidate.focusPeer.sha256
        focusPeerBytes = [long]$Contract.candidate.focusPeer.bytes
    }
}

function ConvertTo-G2DeviceReport {
    param([Parameter(Mandatory)]$Device)
    return [pscustomobject]@{
        order = [string]$Device.order
        role = [string]$Device.role
        serial = [string]$Device.serial
        model = [string]$Device.model
        sdk = [int]$Device.sdk
        abi = [string]$Device.abi
        user = [int]$Device.user
        lockStateAtStart = [string]$Device.lockStateAtStart
    }
}

function ConvertTo-G2ExecutionReport {
    param([Parameter(Mandatory)]$Execution)
    return [pscustomobject]@{
        windowMinutes = [int]$Execution.windowMinutes
        windowSeconds = [int]$Execution.windowSeconds
        attemptNumber = [int]$Execution.attemptNumber
        extended = [bool]$Execution.extended
        retry = [bool]$Execution.retry
        continuous = [bool]$Execution.continuous
        startedAt = [string]$Execution.startedAt
        completedAt = [string]$Execution.completedAt
        freshInstall = [bool]$Execution.freshInstall
        candidateIdentityVerified = [bool]$Execution.candidateIdentityVerified
    }
}

function ConvertTo-G2PredecessorReport {
    param([AllowNull()]$Predecessor)
    if ($null -eq $Predecessor) {
        return $null
    }
    return [pscustomobject]@{
        deviceOrder = [string]$Predecessor.deviceOrder
        coreVerdict = [string]$Predecessor.coreVerdict
        productVerdict = [string]$Predecessor.productVerdict
        lockStateAfterClosure = [string]$Predecessor.lockStateAfterClosure
        evidenceRoot = [string]$Predecessor.evidenceRoot
        evidencePointer = [string]$Predecessor.evidencePointer
        reportSha256 = [string]$Predecessor.reportSha256
        lockSha256 = [string]$Predecessor.lockSha256
        candidateMainSha256 =
            [string]$Predecessor.candidateMainSha256
        candidateFocusPeerSha256 =
            [string]$Predecessor.candidateFocusPeerSha256
    }
}

function ConvertTo-G2AudioTrackReport {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Events)
    return @($Events | ForEach-Object {
        $item = [ordered]@{
            kind = [string]$_.kind
            timestamp = [string]$_.timestamp
            voiceId = [string]$_.voiceId
            sentenceRequestId = [string]$_.sentenceRequestId
            trackSessionId = [string]$_.trackSessionId
        }
        if ($null -ne $_.PSObject.Properties["observedPid"]) {
            $item.observedPid = [int]$_.observedPid
        }
        if ($null -ne $_.PSObject.Properties["evidence"]) {
            $item.evidence = [string]$_.evidence
        }
        [pscustomobject]$item
    })
}

function ConvertTo-G2ContinuityReport {
    param([Parameter(Mandatory)]$Continuity)
    $convertSample = {
        param($Sample)
        [pscustomobject]@{
            timestamp = [string]$Sample.timestamp
            voiceId = [string]$Sample.voiceId
            audioTrackStarted = [bool]$Sample.audioTrackStarted
            audioTrackCompleted = [bool]$Sample.audioTrackCompleted
            evidence = @($Sample.evidence)
        }
    }
    return [pscustomobject]@{
        head = (& $convertSample $Continuity.head)
        middle = (& $convertSample $Continuity.middle)
        tail = (& $convertSample $Continuity.tail)
        maximumAudioGapSeconds =
            [double]$Continuity.maximumAudioGapSeconds
    }
}

function ConvertTo-G2CheckReport {
    param([Parameter(Mandatory)]$Check)
    return [pscustomobject]@{
        status = [string]$Check.status
        evidence = @($Check.evidence)
    }
}

function ConvertTo-G2SurfacesReport {
    param([Parameter(Mandatory)]$Surfaces)
    return [pscustomobject]@{
        foregroundService =
            ConvertTo-G2CheckReport $Surfaces.foregroundService
        mediaSession = ConvertTo-G2CheckReport $Surfaces.mediaSession
        notification = ConvertTo-G2CheckReport $Surfaces.notification
        ui = ConvertTo-G2CheckReport $Surfaces.ui
        systemControls = ConvertTo-G2CheckReport $Surfaces.systemControls
        stateEquality = ConvertTo-G2CheckReport $Surfaces.stateEquality
    }
}

function ConvertTo-G2AudioFocusReport {
    param([Parameter(Mandatory)]$Focus)
    return [pscustomobject]@{
        status = [string]$Focus.status
        singleOwner = [bool]$Focus.singleOwner
        ownerPids = @($Focus.ownerPids | ForEach-Object { [int]$_ })
        focusRequest = ConvertTo-G2CheckReport $Focus.focusRequest
        focusLoss = ConvertTo-G2CheckReport $Focus.focusLoss
        focusGain = ConvertTo-G2CheckReport $Focus.focusGain
        evidence = @($Focus.evidence)
    }
}

function ConvertTo-G2StabilityReport {
    param([Parameter(Mandatory)]$Stability)
    return [pscustomobject]@{
        nativeCrash = ConvertTo-G2CheckReport $Stability.nativeCrash
        javaFatal = ConvertTo-G2CheckReport $Stability.javaFatal
        anr = ConvertTo-G2CheckReport $Stability.anr
        inputTimeout = ConvertTo-G2CheckReport $Stability.inputTimeout
        applicationExitInfo =
            ConvertTo-G2CheckReport $Stability.applicationExitInfo
        evidence = @($Stability.evidence)
    }
}

function ConvertTo-G2CleanupReport {
    param([Parameter(Mandatory)]$Cleanup)
    return [pscustomobject]@{
        status = [string]$Cleanup.status
        packagesAbsent = [bool]$Cleanup.packagesAbsent
        pidsAbsent = [bool]$Cleanup.pidsAbsent
        serviceRecordsAbsent = [bool]$Cleanup.serviceRecordsAbsent
        activeNotificationRecordsAbsent =
            [bool]$Cleanup.activeNotificationRecordsAbsent
        activeMediaSessionsAbsent =
            [bool]$Cleanup.activeMediaSessionsAbsent
        audioFocusReleased = [bool]$Cleanup.audioFocusReleased
        externalDataAbsent = [bool]$Cleanup.externalDataAbsent
        internalDataAbsent = [bool]$Cleanup.internalDataAbsent
        hostLogcatOrphans = [int]$Cleanup.hostLogcatOrphans
        networkRestored = [bool]$Cleanup.networkRestored
        userSettingsRestored = [bool]$Cleanup.userSettingsRestored
        productionPackageMatch = [bool]$Cleanup.productionPackageMatch
        deviceLockState = [string]$Cleanup.deviceLockState
        deviceLockActiveCount = [int]$Cleanup.deviceLockActiveCount
        evidence = @($Cleanup.evidence)
    }
}

function Merge-G2CleanupIntoObservations {
    param(
        [Parameter(Mandatory)]$Observations,
        [Parameter(Mandatory)]$Cleanup
    )
    if ([string]$Cleanup.schema -cne
        "aurorama.g2-cleanup-verification.v1") {
        throw "CLEANUP_RESULT_SCHEMA_MISMATCH"
    }
    if ([string]$Cleanup.status -notin @("PASS", "FAIL", "BLOCKED")) {
        throw "CLEANUP_RESULT_STATUS_INVALID"
    }
    foreach ($propertyName in @(
        "packagesAbsent",
        "pidsAbsent",
        "serviceRecordsAbsent",
        "activeNotificationRecordsAbsent",
        "activeMediaSessionsAbsent",
        "audioFocusReleased",
        "externalDataAbsent",
        "internalDataAbsent",
        "networkRestored",
        "userSettingsRestored",
        "productionPackageMatch"
    )) {
        if ($null -eq $Cleanup.PSObject.Properties[$propertyName]) {
            throw "CLEANUP_RESULT_MISSING_$propertyName"
        }
    }
    $copy = $Observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $copy.cleanupAndRestore = ConvertTo-G2CleanupReport $Cleanup
    return $copy
}

function New-G2RuntimeReportFromObservations {
    param(
        [Parameter(Mandatory)]$Observations,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Authorization,
        [Parameter(Mandatory)][string]$ContractRoot,
        [switch]$FixtureOnly
    )
    Assert-G2ObservationShape -Observations $Observations
    $gates = @(Get-G2ProductGateResults -Observations $Observations)
    if ($gates.Count -ne 13) {
        throw "PRODUCT_GATE_COUNT_INVALID actual=$($gates.Count)"
    }
    $coreNotRun = @(
        $gates | Where-Object status -eq "NOT_RUN"
    ).Count -gt 0
    $coreFail = @(
        $gates | Where-Object status -eq "FAIL"
    ).Count -gt 0
    $fixture = $FixtureOnly.IsPresent -or
        [bool]$Observations.fixtureOnly
    $uncovered = @($Observations.uncovered)
    if ($fixture) {
        $uncovered += "STATIC_FIXTURE_NOT_DEVICE_QA"
    }
    $productVerdict = if ($fixture -or $coreNotRun) {
        "NOT_ASSESSED"
    } elseif ($coreFail) {
        "FAIL"
    } else {
        "PASS"
    }
    $verdict = if ($fixture -or $coreNotRun) {
        "BLOCKED"
    } elseif ($coreFail) {
        "FAIL"
    } else {
        "PASS"
    }
    $receiptPath = [string]$Contract.authorization.authorizationReceiptPath
    $reportRepositoryRoot = [System.IO.Path]::GetFullPath(
        (Join-Path $ContractRoot "..\..\..")
    )
    $receiptFullPath = Resolve-G2ReportBoundedPath `
        -Root $reportRepositoryRoot `
        -RelativePath $receiptPath
    $productFindings = $Observations.productFindings
    $report = [pscustomobject]@{
        schema = "aurorama.g2-entry-runtime-report.v1"
        contractId = [string]$Contract.contractId
        ownerRole = [string]$Observations.ownerRole
        evidenceRoot = [string]$Observations.evidenceRoot
        candidate = ConvertTo-G2CandidateReport $Contract
        toolsDigest = [string]$Contract.tools.digestSha256
        authorization = [pscustomobject]@{
            deviceExecutionGranted = [bool]$Authorization.scope.runtimeQa
            grantReceiptPath = $receiptPath
            grantReceiptSha256 =
                Get-G2ReportFileSha256 $receiptFullPath
            contractOnlyPhase = $false
        }
        device = ConvertTo-G2DeviceReport $Observations.device
        predecessor = ConvertTo-G2PredecessorReport $Observations.predecessor
        execution = ConvertTo-G2ExecutionReport $Observations.execution
        productGates = $gates
        audioTrackEvents =
            @(ConvertTo-G2AudioTrackReport $Observations.audioTrackEvents)
        continuity = ConvertTo-G2ContinuityReport $Observations.continuity
        systemSurfaces =
            ConvertTo-G2SurfacesReport $Observations.systemSurfaces
        audioFocus =
            ConvertTo-G2AudioFocusReport $Observations.audioFocus
        stability =
            ConvertTo-G2StabilityReport $Observations.stability
        uiCancel = [pscustomobject]@{
            status = [string]$Observations.uiCancel.status
            cancelObserved = [bool]$Observations.uiCancel.cancelObserved
            pidConverged = [bool]$Observations.uiCancel.pidConverged
            serviceConverged =
                [bool]$Observations.uiCancel.serviceConverged
            notificationConverged =
                [bool]$Observations.uiCancel.notificationConverged
            mediaSessionConverged =
                [bool]$Observations.uiCancel.mediaSessionConverged
            focusConverged =
                [bool]$Observations.uiCancel.focusConverged
            evidence = @($Observations.uiCancel.evidence)
        }
        processRecovery = [pscustomobject]@{
            processKilled = [bool]$Observations.processRecovery.processKilled
            recoveryMode = [string]$Observations.processRecovery.recoveryMode
            autoPlaybackAfterDeath =
                [bool]$Observations.processRecovery.autoPlaybackAfterDeath
            lastStableSentenceRestored =
                [bool]$Observations.processRecovery.lastStableSentenceRestored
            packWhitelistVerified =
                [bool]$Observations.processRecovery.packWhitelistVerified
            serviceOwnerAfterRestart = ConvertTo-G2CheckReport `
                $Observations.processRecovery.serviceOwnerAfterRestart
            evidence = @($Observations.processRecovery.evidence)
        }
        zeroNetwork = [pscustomobject]@{
            status = [string]$Observations.zeroNetwork.status
            uid = [int]$Observations.zeroNetwork.uid
            allPartitionsAccounted =
                [bool]$Observations.zeroNetwork.allPartitionsAccounted
            windowBytesDelta =
                [long]$Observations.zeroNetwork.windowBytesDelta
            nonzeroAttribution =
                @($Observations.zeroNetwork.nonzeroAttribution)
            evidence = @($Observations.zeroNetwork.evidence)
        }
        productFindings = [pscustomobject]@{
            P0 = $productFindings.P0
            P1 = $productFindings.P1
            P2 = $productFindings.P2
            P3 = $productFindings.P3
            summary = [string]$productFindings.summary
        }
        uncovered = @($uncovered)
        cleanupAndRestore =
            ConvertTo-G2CleanupReport $Observations.cleanupAndRestore
        passWithGapsForbidden = $true
        productVerdict = $productVerdict
        verdict = $verdict
        finalizedAt = [DateTimeOffset]::Now.ToString("o")
    }
    Test-G2ReportSemantics -Report $report -Contract $Contract
    return $report
}

function Test-G2ReportSemantics {
    param(
        [Parameter(Mandatory)]$Report,
        [Parameter(Mandatory)]$Contract
    )
    if ($Report.schema -cne "aurorama.g2-entry-runtime-report.v1") {
        throw "REPORT_SCHEMA_MISMATCH"
    }
    if ($Report.contractId -cne $Contract.contractId) {
        throw "REPORT_CONTRACT_ID_MISMATCH"
    }
    $expectedEvidenceRoot = if ([string]$Report.device.order -ceq "Pad") {
        [string]$Contract.evidence.padRuntimeRoot
    } elseif ([string]$Report.device.order -ceq "K60") {
        [string]$Contract.evidence.k60RuntimeRoot
    } else {
        $null
    }
    if (
        [string]::IsNullOrWhiteSpace($expectedEvidenceRoot) -or
        [string]$Report.evidenceRoot -cne $expectedEvidenceRoot
    ) {
        throw "REPORT_EVIDENCE_SCOPE_MISMATCH"
    }
    if ($Report.passWithGapsForbidden -ne $true) {
        throw "PASS_WITH_GAPS_GATE_MISSING"
    }
    $gateIdsActual = @($Report.productGates | ForEach-Object {
        [string]$_.gateId
    })
    if (
        $gateIdsActual.Count -ne 13 -or
        @($gateIdsActual | Select-Object -Unique).Count -ne 13 -or
        (@($gateIds | Where-Object {
            $gateIdsActual -notcontains $_
        }).Count -ne 0)
    ) {
        throw "CORE_GATE_SET_MISMATCH"
    }
    $notRun = @($Report.productGates | Where-Object {
        [string]$_.status -ceq "NOT_RUN"
    })
    if (
        [string]$Report.verdict -ceq "PASS" -and
        $notRun.Count -gt 0
    ) {
        throw "CORE_NOT_RUN_FORBIDDEN"
    }
    if ([string]$Report.cleanupAndRestore.status -ceq "FAIL") {
        throw "CLEANUP_FAIL_FORBIDDEN"
    }
    if (
        [string]$Report.verdict -cne "PASS" -and
        @($Report.productGates | Where-Object {
            [string]$_.status -eq "PASS"
        }).Count -eq 0
    ) {
        return
    }
    if (
        [string]$Report.cleanupAndRestore.status -cne "PASS" -and
        [string]$Report.verdict -eq "PASS"
    ) {
        throw "CLEANUP_STATUS_NOT_PASS"
    }
    if ([string]$Report.verdict -ne "PASS") {
        return
    }
    $nonPass = @(
        $Report.productGates | Where-Object {
            [string]$_.status -cne "PASS"
        }
    )
    if ($nonPass.Count -gt 0) {
        throw "CORE_NOT_PASS"
    }
    $cleanup = $Report.cleanupAndRestore
    if (
        [string]$cleanup.status -cne "PASS" -or
        -not [bool]$cleanup.packagesAbsent -or
        -not [bool]$cleanup.pidsAbsent -or
        -not [bool]$cleanup.serviceRecordsAbsent -or
        -not [bool]$cleanup.activeNotificationRecordsAbsent -or
        -not [bool]$cleanup.activeMediaSessionsAbsent -or
        -not [bool]$cleanup.audioFocusReleased -or
        -not [bool]$cleanup.externalDataAbsent -or
        -not [bool]$cleanup.internalDataAbsent -or
        [int]$cleanup.hostLogcatOrphans -ne 0 -or
        -not [bool]$cleanup.networkRestored -or
        -not [bool]$cleanup.userSettingsRestored -or
        -not [bool]$cleanup.productionPackageMatch -or
        [string]$cleanup.deviceLockState -cne
            "RELEASED_ACTIVE_0" -or
        [int]$cleanup.deviceLockActiveCount -ne 0
    ) {
        throw "CLEANUP_NOT_PASS"
    }
    if ([string]$Report.productVerdict -cne "PASS") {
        throw "PRODUCT_VERDICT_NOT_PASS"
    }
    foreach ($findingName in @("P0", "P1", "P2", "P3")) {
        if ([string]$Report.productFindings.$findingName -ceq
            "NOT_ASSESSED") {
            throw "PASS_FINDINGS_NOT_ASSESSED_$findingName"
        }
    }
    if ($Report.candidate.mainSha256 -cne
            $Contract.candidate.main.sha256 -or
        $Report.candidate.focusPeerSha256 -cne
            $Contract.candidate.focusPeer.sha256 -or
        $Report.candidate.sourceCommit -cne
            $Contract.candidate.sourceCommit) {
        throw "REPORT_CANDIDATE_MISMATCH"
    }
    if (
        [int]$Report.execution.windowMinutes -ne 10 -or
        [int]$Report.execution.windowSeconds -ne 600 -or
        [int]$Report.execution.attemptNumber -ne 1 -or
        [bool]$Report.execution.extended -or
        [bool]$Report.execution.retry -or
        -not [bool]$Report.execution.continuous
    ) {
        throw "REPORT_WINDOW_POLICY_MISMATCH"
    }
    $windowStarted = [DateTimeOffset]::Parse(
        [string]$Report.execution.startedAt
    )
    $windowCompleted = [DateTimeOffset]::Parse(
        [string]$Report.execution.completedAt
    )
    $elapsedSeconds = (
        $windowCompleted - $windowStarted
    ).TotalSeconds
    if ([Math]::Abs($elapsedSeconds - 600.0) -gt 0.25) {
        throw (
            "REPORT_WINDOW_ELAPSED_MISMATCH " +
            "expected=600 actual=$elapsedSeconds"
        )
    }
    if (
        [string]$Report.ownerRole -cne [string]$Report.device.role
    ) {
        throw "REPORT_OWNER_DEVICE_ROLE_MISMATCH"
    }
    if ([string]$Report.device.order -ceq "Pad") {
        if (
            [string]$Report.device.role -cne
                "G2_PAD_QA_OWNER_2026_11_06" -or
            [string]$Report.device.serial -cne "43af8627" -or
            $null -ne $Report.predecessor
        ) {
            throw "REPORT_PAD_DEVICE_OR_PREDECESSOR_MISMATCH"
        }
    } elseif ([string]$Report.device.order -ceq "K60") {
        if (
            [string]$Report.device.role -cne
                "G2_K60_QA_OWNER_2026_11_06" -or
            [string]$Report.device.serial -cne "8e875894" -or
            $null -eq $Report.predecessor -or
            [string]$Report.predecessor.deviceOrder -cne "Pad" -or
            [string]$Report.predecessor.coreVerdict -cne "PASS" -or
            [string]$Report.predecessor.productVerdict -cne "PASS" -or
            [string]$Report.predecessor.lockStateAfterClosure -cne
                "RELEASED_ACTIVE_0" -or
            [string]$Report.predecessor.evidenceRoot -cne
                [string]$Contract.evidence.padRuntimeRoot -or
            [string]$Report.predecessor.evidencePointer -cne
                (
                    [string]$Contract.evidence.padRuntimeRoot +
                    "/report.json"
                ) -or
            [string]$Report.predecessor.reportSha256 -notmatch
                "^[0-9a-f]{64}$" -or
            [string]$Report.predecessor.lockSha256 -notmatch
                "^[0-9a-f]{64}$" -or
            [string]$Report.predecessor.candidateMainSha256 -cne
                [string]$Contract.candidate.main.sha256 -or
            [string]$Report.predecessor.candidateFocusPeerSha256 -cne
                [string]$Contract.candidate.focusPeer.sha256
        ) {
            throw "REPORT_K60_PREDECESSOR_REQUIRED"
        }
    } else {
        throw "REPORT_DEVICE_ORDER_INVALID"
    }
    $audioEvents = @($Report.audioTrackEvents)
    $trackGroups = @{}
    foreach ($event in $audioEvents) {
        $trackKey = [string]$event.trackSessionId
        if (-not $trackGroups.ContainsKey($trackKey)) {
            $trackGroups[$trackKey] = [System.Collections.Generic.List[object]]::new()
        }
        $trackGroups[$trackKey].Add($event)
    }
    foreach ($entry in $trackGroups.GetEnumerator()) {
        $starts = @($entry.Value | Where-Object kind -eq "START")
        $completions = @(
            $entry.Value | Where-Object kind -eq "COMPLETION"
        )
        if ($starts.Count -ne 1 -or $completions.Count -ne 1) {
            throw "AUDIO_TRACK_START_COMPLETION_PAIR_INVALID $($entry.Key)"
        }
        if (
            [string]$starts[0].voiceId -cne
                [string]$completions[0].voiceId -or
            [string]$starts[0].sentenceRequestId -cne
                [string]$completions[0].sentenceRequestId
        ) {
            throw "AUDIO_TRACK_COMPLETION_IDENTITY_MISMATCH $($entry.Key)"
        }
    }
    foreach ($voiceId in @("zf_001", "zm_010")) {
        if (
            @($audioEvents | Where-Object voiceId -eq $voiceId |
                Where-Object kind -eq "START").Count -eq 0 -or
            @($audioEvents | Where-Object voiceId -eq $voiceId |
                Where-Object kind -eq "COMPLETION").Count -eq 0
        ) {
            throw "AUDIO_TRACK_VOICE_COVERAGE_MISSING $voiceId"
        }
    }
}

function Invoke-G2FinalizerStaticSelfTest {
    if ($gateIds.Count -ne 13 -or
        @($gateIds | Select-Object -Unique).Count -ne 13) {
        throw "FINALIZER_GATE_SET_STATIC_SELF_TEST_FAILED"
    }
    if (@($requiredObservationProperties | Select-Object -Unique).Count -ne
        $requiredObservationProperties.Count) {
        throw "FINALIZER_REQUIRED_FIELD_STATIC_SELF_TEST_FAILED"
    }
    Write-Output "PASS G2_RUNTIME_FINALIZER_STATIC_SELF_TEST"
    Write-Output "DEVICE_COMMANDS=0"
}

function Invoke-G2FinalizerMain {
    if ($StaticSelfTest) {
        Invoke-G2FinalizerStaticSelfTest
        return
    }
    if ([string]::IsNullOrWhiteSpace($DeviceRole)) {
        throw "DEVICE_ROLE_REQUIRED"
    }
    $context = Get-G2FinalizerContext `
        -DeviceRole $DeviceRole `
        -RequestedEvidenceRoot $EvidenceRoot `
        -Action Finalize
    $resolvedObservationsPath = if (
        [string]::IsNullOrWhiteSpace($ObservationsPath)
    ) {
        $manualObservationsPath =
            Join-Path $context.Evidence.FullPath "observations.json"
        $automaticObservationsPath =
            Join-Path $context.Evidence.FullPath "observations.auto.json"
        if (Test-Path -LiteralPath $manualObservationsPath -PathType Leaf) {
            $manualObservationsPath
        } else {
            $automaticObservationsPath
        }
    } else {
        Resolve-G2ReportBoundedPath `
            -Root $context.Evidence.FullPath `
            -RelativePath $ObservationsPath
    }
    $resolvedOutputPath = if (
        [string]::IsNullOrWhiteSpace($OutputPath)
    ) {
        Join-Path $context.Evidence.FullPath "report.json"
    } else {
        Resolve-G2ReportBoundedPath `
            -Root $context.Evidence.FullPath `
            -RelativePath $OutputPath
    }
    if (-not (Test-Path -LiteralPath $resolvedObservationsPath -PathType Leaf)) {
        throw "OBSERVATIONS_FILE_MISSING"
    }
    $observations = Read-G2ReportJsonStrict $resolvedObservationsPath
    $cleanupResultPath = Join-Path $context.Evidence.FullPath "cleanup.json"
    if (Test-Path -LiteralPath $cleanupResultPath -PathType Leaf) {
        $cleanupResult = Read-G2ReportJsonStrict $cleanupResultPath
        if ([bool]$cleanupResult.fixtureOnly -and -not $FixtureMode) {
            throw "CLEANUP_FIXTURE_FORBIDDEN"
        }
        $observations = Merge-G2CleanupIntoObservations `
            -Observations $observations `
            -Cleanup $cleanupResult
    }
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $context.Contract `
        -Authorization $context.Authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly:$FixtureMode
    if (-not $FixtureMode) {
        [void](Assert-G2FinalizerAttemptBinding `
            -Context $context `
            -Observations $observations)
    }
    $json = $report | ConvertTo-Json -Depth 100
    if (-not ($json | Test-Json -SchemaFile $schemaPath)) {
        throw "REPORT_SCHEMA_VALIDATION_FAILED"
    }
    Write-G2ReportJson -Path $resolvedOutputPath -Value $report
    Write-Output "FINALIZED G2_RUNTIME_REPORT"
    Write-Output "VERDICT=$($report.verdict)"
    Write-Output "PRODUCT_VERDICT=$($report.productVerdict)"
    Write-Output "REPORT=$resolvedOutputPath"
}

if ($MyInvocation.InvocationName -ne ".") {
    try {
        Invoke-G2FinalizerMain
    } catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
