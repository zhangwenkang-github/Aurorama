[CmdletBinding()]
param(
    [switch]$StaticSelfTest,

    [ValidateSet("CaptureBefore", "Verify")]
    [string]$Action = "Verify",

    [ValidateSet("Pad", "K60")]
    [string]$DeviceRole,

    [string]$EvidenceRoot
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
$adbPath = "F:\Develop\Android\AndroidSDK\platform-tools\adb.exe"
$pinnedSourceCommit = "35f9d493f16cdf79f946871fc331e2ceece287d2"
$pinnedMainSha256 =
    "1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3"
$pinnedPeerSha256 =
    "d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a"
$pinnedSchemaSha256 =
    "a1c1549eb08b8c49ad0a2c4b6bb59fb8bbf641d1529f05948d1bb9e591409bae"
$mainPackage = "dev.aurorama.kokoro.harness"
$peerPackage = "dev.aurorama.kokoro.focuspeer"
$capturedSettings = @(
    @("system", "screen_brightness"),
    @("system", "screen_brightness_mode"),
    @("system", "accelerometer_rotation"),
    @("system", "user_rotation"),
    @("global", "stay_on_while_plugged_in"),
    @("system", "stay_on_while_plugged_in"),
    @("global", "airplane_mode_on"),
    @("global", "wifi_on"),
    @("global", "mobile_data")
)
$requiredBooleanChecks = @(
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
)

. (Join-Path $contractRoot "G2RuntimeStatePolicy.ps1")

function Read-G2CleanupJsonStrict {
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

function Get-G2CleanupFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function New-G2CleanupDirectory {
    param([Parameter(Mandatory)][string]$Path)
    [void][System.IO.Directory]::CreateDirectory($Path)
}

function Write-G2CleanupText {
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
        New-G2CleanupDirectory -Path $parent
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8Output)
}

function Write-G2CleanupJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    Write-G2CleanupText `
        -Path $Path `
        -Text ($Value | ConvertTo-Json -Depth 100)
}

function Resolve-G2CleanupBoundedPath {
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
    Assert-G2CleanupNoReparsePath -Root $fullRoot -FullPath $fullPath
    return $fullPath
}

function Assert-G2CleanupNoReparsePath {
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

function Assert-G2CleanupPinnedIdentity {
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

function Assert-G2CleanupSchemaPin {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$SchemaPath
    )
    Assert-G2CleanupPinnedIdentity -Contract $Contract
    if (-not (Test-Path -LiteralPath $SchemaPath -PathType Leaf)) {
        throw "CLEANUP_SCHEMA_MISSING"
    }
    $actualSchemaSha256 = Get-G2CleanupFileSha256 $SchemaPath
    if (
        $actualSchemaSha256 -cne $pinnedSchemaSha256 -or
        $actualSchemaSha256 -cne
            [string]$Contract.evidence.reportSchemaSha256
    ) {
        throw (
            "CLEANUP_SCHEMA_FILE_SHA_MISMATCH " +
            "expected=$pinnedSchemaSha256 actual=$actualSchemaSha256"
        )
    }
}

function Assert-G2CleanupAuthorization {
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
        throw "CLEANUP_AUTHORIZATION_K60_SCOPE_MISMATCH"
    }
}

function Resolve-G2CleanupEvidenceRoot {
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
    Assert-G2CleanupNoReparsePath `
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

function Resolve-G2CleanupDevicePreflight {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [Parameter(Mandatory)][ValidateSet("CaptureBefore", "Verify")]
        [string]$Action
    )
    if ($DeviceRole -cne "K60") {
        return $null
    }
    if (
        [string]$Context.StatePolicy.Mode -cne
            "AUDIT_PASS_PENDING_FRESH_PAD_QA"
    ) {
        throw "K60_CLEANUP_REQUIRES_AUDIT_READY_STATE"
    }
    if (
        $null -eq $Context.PSObject.Properties["RepositoryRoot"] -or
        [string]::IsNullOrWhiteSpace([string]$Context.RepositoryRoot)
    ) {
        throw "K60_CLEANUP_REPOSITORY_CONTEXT_REQUIRED"
    }
    $repositoryBase = [System.IO.Path]::GetFullPath(
        [string]$Context.RepositoryRoot
    )
    $padRootRelative = [string]$Context.Contract.evidence.padRuntimeRoot
    $padRoot = Resolve-G2StateBoundedPath `
        -Root $repositoryBase `
        -RelativePath $padRootRelative
    $actualPredecessor = Get-G2ValidatedPadPredecessor `
        -Contract $Context.Contract `
        -RepositoryRoot $repositoryBase `
        -PadRoot $padRoot

    $registrationRelative = (
        [string]$Context.Contract.evidence.k60RuntimeRoot +
        "/collector-registration.json"
    )
    $registrationPath = Resolve-G2StateBoundedPath `
        -Root $repositoryBase `
        -RelativePath $registrationRelative
    if (-not (Test-Path -LiteralPath $registrationPath -PathType Leaf)) {
        throw "K60_CLEANUP_REGISTRATION_MISSING"
    }
    $registration = Read-G2StateJsonStrict $registrationPath
    Assert-G2StateRegistrationShape `
        -Registration $registration `
        -Name "K60_CLEANUP_REGISTRATION"
    if (
        [string]$registration.schema -cne
            "aurorama.g2-collector-registration.v1" -or
        [string]$registration.contractId -cne
            [string]$Context.Contract.contractId -or
        [string]$registration.toolOwnerRole -cne $toolOwnerRole -or
        [string]$registration.qaOwnerRole -cne
            "G2_K60_QA_OWNER_2026_11_06" -or
        [string]$registration.deviceRole -cne "K60" -or
        [string]$registration.serial -cne "8e875894" -or
        [int]$registration.attemptNumber -ne 1 -or
        [int]$registration.windowSeconds -ne 600 -or
        [string]$registration.attemptScope -cne
            [string]$Context.Contract.evidence.k60RuntimeRoot -or
        [string]$registration.attemptState -cne
            "REGISTERED_WINDOW_NOT_STARTED" -or
        [string]$registration.candidateMainSha256 -cne
            [string]$Context.Contract.candidate.main.sha256 -or
        [string]$registration.candidateFocusPeerSha256 -cne
            [string]$Context.Contract.candidate.focusPeer.sha256 -or
        [string]$registration.sourceCommit -cne
            [string]$Context.Contract.candidate.sourceCommit -or
        [string]$registration.toolsDigest -cne
            [string]$Context.Contract.tools.digestSha256 -or
        $null -ne $registration.recoveryBasis -or
        [string]$registration.authoritativeLockState -cne
            "RELEASED_ACTIVE_0" -or
        [string]$registration.lockState -cne "ACTIVE_COUNT_1"
    ) {
        throw "K60_CLEANUP_REGISTRATION_BINDING_MISMATCH"
    }
    Assert-G2PadPredecessorBinding `
        -Expected $registration.predecessor `
        -Actual $actualPredecessor
    return $actualPredecessor
}

function Get-G2CleanupContext {
    param(
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole,
        [AllowNull()][string]$RequestedEvidenceRoot,
        [string]$Action = "Verify",
        [AllowNull()][string]$ContractPathOverride,
        [AllowNull()][string]$ManifestPathOverride,
        [AllowNull()][string]$RepairAuditReceiptPathOverride,
        [AllowNull()][string]$RepositoryBaseOverride
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
        $authorizationPath,
        $schemaPath,
        $effectiveManifestPath
    )) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "REQUIRED_FILE_MISSING $required"
        }
    }
    $contract = Read-G2CleanupJsonStrict $effectiveContractPath
    $authorization = Read-G2CleanupJsonStrict $authorizationPath
    $manifest = Read-G2CleanupJsonStrict $effectiveManifestPath
    Assert-G2CandidateRecordShape `
        -Candidate (Read-G2CleanupJsonStrict $candidatePath) `
        -Name "CLEANUP_CANDIDATE_RECORD"
    Assert-G2AuthorizationReceiptShape `
        -Authorization $authorization `
        -Name "CLEANUP_AUTHORIZATION_RECEIPT"
    Assert-G2CleanupSchemaPin `
        -Contract $contract `
        -SchemaPath $schemaPath
    if (
        $contract.schema -cne "aurorama.g2-entry-runtime-contract.v1" -or
        $contract.contractId -cne
            "G2-ENTRY-RUNTIME-CONTRACT-2026-11-06" -or
        $contract.tools.cleanupVerifier.roleId -cne $toolOwnerRole -or
        $contract.tools.cleanupVerifier.path -cne
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/Test-G2CleanupRestore.ps1"
    ) {
        throw "CLEANUP_CONTRACT_BINDING_MISMATCH"
    }
    $statePolicy = Assert-G2RuntimeToolStatePolicy `
        -Contract $contract `
        -ContractRoot $contractRoot `
        -RepositoryRoot $repositoryRoot `
        -Action $Action `
        -RepairAuditReceiptPathOverride $RepairAuditReceiptPathOverride `
        -ManifestPathOverride $effectiveManifestPath
    Assert-G2CleanupAuthorization `
        -Contract $contract `
        -Authorization $authorization
    $effectiveRepositoryRoot = if (
        [string]::IsNullOrWhiteSpace($RepositoryBaseOverride)
    ) {
        $repositoryRoot
    } else {
        [System.IO.Path]::GetFullPath($RepositoryBaseOverride)
    }
    $resolvedEvidence = Resolve-G2CleanupEvidenceRoot `
        -DeviceRole $DeviceRole `
        -RequestedEvidenceRoot $RequestedEvidenceRoot `
        -RepositoryBase $effectiveRepositoryRoot
    $context = [pscustomobject]@{
        Contract = $contract
        Authorization = $authorization
        Evidence = $resolvedEvidence
        RepositoryRoot = $effectiveRepositoryRoot
        StatePolicy = $statePolicy
        Predecessor = $null
    }
    if ($DeviceRole -ceq "K60") {
        $context.Predecessor = Resolve-G2CleanupDevicePreflight `
            -Context $context `
            -DeviceRole $DeviceRole `
            -Action $Action
    }
    return $context
}

function Test-G2ActiveMediaSessionAbsent {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Dump)
    $stackMatch = [regex]::Match(
        $Dump,
        "(?ims)Sessions Stack\s*-\s*have\s+(?<count>\d+)\s+sessions:" +
        "(?<body>.*?)(?=\r?\n\s*\r?\n|Audio playback|\z)"
    )
    if (-not $stackMatch.Success) {
        return $false
    }
    if ([int]$stackMatch.Groups["count"].Value -eq 0) {
        return $true
    }
    $body = [string]$stackMatch.Groups["body"].Value
    return -not $body.Contains(
        $mainPackage,
        [System.StringComparison]::Ordinal
    )
}

function Test-G2ActiveNotificationAbsent {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Dump,
        [Parameter(Mandatory)][string]$PackageName
    )
    $pattern = [regex]::new(
        "NotificationRecord\([^\r\n]*pkg=$([regex]::Escape($PackageName))(?:\s|\))"
    )
    return -not $pattern.IsMatch($Dump)
}

function Select-G2CleanupCandidateText {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][string[]]$Packages
    )
    foreach ($packageName in $Packages) {
        if ($packageName -notmatch
            "^(dev\.aurorama\.kokoro\.harness|dev\.aurorama\.kokoro\.focuspeer)$") {
            throw "CLEANUP_PACKAGE_FILTER_FORBIDDEN $packageName"
        }
    }
    return @(
        $Text -split "\r?\n" |
            Where-Object {
                $line = [string]$_
                @($Packages | Where-Object {
                    $line.Contains(
                        $_,
                        [System.StringComparison]::Ordinal
                    )
                }).Count -gt 0
            }
    ) -join "`n"
}

function Get-G2CurrentAudioFocusStack {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Dump)
    $captured = [System.Collections.Generic.List[string]]::new()
    $inStack = $false
    foreach ($line in @($Dump -split "\r?\n")) {
        if (-not $inStack) {
            if ($line -match "^\s*Audio Focus stack entries\b") {
                $inStack = $true
                $captured.Add($line)
            }
            continue
        }
        if ($line -match "^\S") {
            break
        }
        $captured.Add($line)
    }
    return ($captured -join "`n").TrimEnd()
}

function Test-G2CurrentAudioFocusPresent {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Dump,
        [Parameter(Mandatory)][string]$PackageName
    )
    $stack = Get-G2CurrentAudioFocusStack -Dump $Dump
    return (
        -not [string]::IsNullOrWhiteSpace($stack) -and
        $stack.Contains($PackageName, [System.StringComparison]::Ordinal)
    )
}

function Test-G2CleanupPathAbsent {
    param(
        [Parameter(Mandatory)][int]$ExitCode,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Output,
        [Parameter(Mandatory)][string]$Path
    )
    if ($ExitCode -eq 0) {
        return $false
    }
    $escapedPath = [regex]::Escape($Path)
    return (
        $Output -match (
            "(?im)^\s*(?:ls|/system/bin/ls):\s+" +
            $escapedPath +
            ":\s+No such file or directory\s*$"
        )
    )
}

function Get-G2HostLogcatProcessInventory {
    try {
        return @(
            Get-CimInstance `
                -ClassName Win32_Process `
                -Filter "Name = 'adb.exe'" `
                -ErrorAction Stop |
                ForEach-Object {
                    [pscustomobject]@{
                        ProcessId = [int]$_.ProcessId
                        CommandLine = [string]$_.CommandLine
                    }
                }
        )
    } catch {
        throw "HOST_LOGCAT_INVENTORY_FAILED $($_.Exception.Message)"
    }
}

function Get-G2HostLogcatOrphanCount {
    param(
        [AllowNull()]$Session,
        [Parameter(Mandatory)][AllowEmptyCollection()]$Processes,
        [Parameter(Mandatory)][string]$Serial
    )
    if ($null -eq $Session) {
        return 0
    }
    $pidProperty = $Session.PSObject.Properties["hostLogcatProcessId"]
    if ($null -eq $pidProperty) {
        throw "COLLECTOR_SESSION_LOGCAT_PID_MISSING"
    }
    $sessionProcessId = 0
    if (-not [int]::TryParse([string]$pidProperty.Value, [ref]$sessionProcessId)) {
        throw "COLLECTOR_SESSION_LOGCAT_PID_INVALID"
    }
    if ($sessionProcessId -le 0) {
        return 0
    }
    $serialPattern = [regex]::Escape($Serial)
    foreach ($process in @($Processes)) {
        if ($null -eq $process) {
            continue
        }
        $processIdProperty = $process.PSObject.Properties["ProcessId"]
        $commandLineProperty = $process.PSObject.Properties["CommandLine"]
        if ($null -eq $processIdProperty -or $null -eq $commandLineProperty) {
            continue
        }
        if ([int]$processIdProperty.Value -ne $sessionProcessId) {
            continue
        }
        $commandLine = [string]$commandLineProperty.Value
        if (
            $commandLine -match "(?i)(?:^|\s)logcat(?:\s|$)" -and
            $commandLine -match (
                "(?i)(?:^|\s)-s\s+`"?${serialPattern}`"?(?:\s|$)"
            )
        ) {
            return 1
        }
    }
    return 0
}

function Get-G2CleanupDeviceLockOccupancy {
    param(
        [Parameter(Mandatory)][string]$Path,
        [AllowNull()][string]$DeviceRole,
        [AllowNull()][string]$Serial,
        [AllowNull()][string]$AttemptScope
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            state = "RELEASED_ACTIVE_0"
            activeCount = 0
            attemptNumber = 1
            registeredAt = $null
            authoritativeLockStateAtRegistration = $null
            historicalBlockerSha256 = $null
        }
    }
    $lock = Read-G2CleanupJsonStrict $Path
    Assert-G2StateDeviceLockShape `
        -Lock $lock `
        -Name "CLEANUP_DEVICE_LOCK"
    $state = [string]$lock.state
    $activeCount = [int]$lock.activeCount
    $validState = (
        ($state -ceq "ACTIVE_COUNT_1" -and $activeCount -eq 1) -or
        ($state -ceq "RELEASED_ACTIVE_0" -and $activeCount -eq 0)
    )
    if (
        [string]$lock.schema -cne "aurorama.g2-device-lock.v1" -or
        -not $validState -or
        (-not [string]::IsNullOrWhiteSpace($DeviceRole) -and
            [string]$lock.deviceRole -cne $DeviceRole) -or
        (-not [string]::IsNullOrWhiteSpace($Serial) -and
            [string]$lock.serial -cne $Serial) -or
        (-not [string]::IsNullOrWhiteSpace($AttemptScope) -and
            [string]$lock.attemptScope -cne $AttemptScope)
    ) {
        throw "CLEANUP_DEVICE_LOCK_INVALID"
    }
    return [pscustomobject]@{
        state = $state
        activeCount = $activeCount
        attemptNumber = [int]$lock.attemptNumber
        registeredAt = [string]$lock.registeredAt
        authoritativeLockStateAtRegistration = if (
            $null -eq $lock.PSObject.Properties[
                "authoritativeLockStateAtRegistration"
            ]
        ) {
            $null
        } else {
            [string]$lock.authoritativeLockStateAtRegistration
        }
        historicalBlockerSha256 = if (
            $null -eq $lock.PSObject.Properties["historicalBlockerSha256"]
        ) {
            $null
        } else {
            [string]$lock.historicalBlockerSha256
        }
    }
}

function Complete-G2CleanupDeviceLock {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][bool]$AllOtherChecksPassed,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")][string]$DeviceRole,
        [Parameter(Mandatory)][string]$Serial,
        [AllowNull()][string]$AttemptScope
    )
    if (-not $AllOtherChecksPassed) {
        return Get-G2CleanupDeviceLockOccupancy `
            -Path $Path `
            -DeviceRole $DeviceRole `
            -Serial $Serial `
            -AttemptScope $AttemptScope
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "CLEANUP_ACTIVE_ATTEMPT_LOCK_MISSING"
    }
    $current = Get-G2CleanupDeviceLockOccupancy `
        -Path $Path `
        -DeviceRole $DeviceRole `
        -Serial $Serial `
        -AttemptScope $AttemptScope
    $attemptNumber = $current.attemptNumber
    Write-G2CleanupJson -Path $Path -Value ([pscustomobject]@{
        schema = "aurorama.g2-device-lock.v1"
        deviceRole = $DeviceRole
        serial = $Serial
        attemptScope = $AttemptScope
        state = "RELEASED_ACTIVE_0"
        activeCount = 0
        attemptNumber = $attemptNumber
        registeredAt = $current.registeredAt
        authoritativeLockStateAtRegistration =
            $current.authoritativeLockStateAtRegistration
        historicalBlockerSha256 = $current.historicalBlockerSha256
        releasedAt = [DateTimeOffset]::Now.ToString("o")
    })
    $occupancy = Get-G2CleanupDeviceLockOccupancy `
        -Path $Path `
        -DeviceRole $DeviceRole `
        -Serial $Serial `
        -AttemptScope $AttemptScope
    if (
        $occupancy.state -cne "RELEASED_ACTIVE_0" -or
        $occupancy.activeCount -ne 0
    ) {
        throw "CLEANUP_DEVICE_LOCK_RELEASE_NOT_OBSERVED"
    }
    return $occupancy
}

function New-G2CleanupResult {
    param([Parameter(Mandatory)]$InputObject)
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($propertyName in $requiredBooleanChecks) {
        $property = $InputObject.PSObject.Properties[$propertyName]
        if ($null -eq $property -or -not [bool]$property.Value) {
            $failures.Add($propertyName)
        }
    }
    if (
        $null -eq $InputObject.PSObject.Properties["hostLogcatOrphans"] -or
        [int]$InputObject.hostLogcatOrphans -ne 0
    ) {
        $failures.Add("hostLogcatOrphans")
    }
    if (
        $null -eq $InputObject.PSObject.Properties["deviceLockState"] -or
        [string]$InputObject.deviceLockState -cne "RELEASED_ACTIVE_0"
    ) {
        $failures.Add("deviceLockState")
    }
    if (
        $null -eq $InputObject.PSObject.Properties["deviceLockActiveCount"] -or
        [int]$InputObject.deviceLockActiveCount -ne 0
    ) {
        $failures.Add("deviceLockActiveCount")
    }
    $evidence = if (
        $null -eq $InputObject.PSObject.Properties["evidence"]
    ) {
        @()
    } else {
        @($InputObject.evidence)
    }
    return [pscustomobject]@{
        schema = "aurorama.g2-cleanup-verification.v1"
        fixtureOnly = if (
            $null -eq $InputObject.PSObject.Properties["fixtureOnly"]
        ) {
            $false
        } else {
            [bool]$InputObject.fixtureOnly
        }
        status = if ($failures.Count -eq 0) { "PASS" } else { "FAIL" }
        packagesAbsent = [bool]$InputObject.packagesAbsent
        pidsAbsent = [bool]$InputObject.pidsAbsent
        serviceRecordsAbsent = [bool]$InputObject.serviceRecordsAbsent
        activeNotificationRecordsAbsent =
            [bool]$InputObject.activeNotificationRecordsAbsent
        activeMediaSessionsAbsent =
            [bool]$InputObject.activeMediaSessionsAbsent
        audioFocusReleased = [bool]$InputObject.audioFocusReleased
        externalDataAbsent = [bool]$InputObject.externalDataAbsent
        internalDataAbsent = [bool]$InputObject.internalDataAbsent
        hostLogcatOrphans = [int]$InputObject.hostLogcatOrphans
        networkRestored = [bool]$InputObject.networkRestored
        userSettingsRestored = [bool]$InputObject.userSettingsRestored
        productionPackageMatch = [bool]$InputObject.productionPackageMatch
        deviceLockState = [string]$InputObject.deviceLockState
        deviceLockActiveCount = [int]$InputObject.deviceLockActiveCount
        evidence = $evidence
        failures = $failures.ToArray()
    }
}

function Invoke-G2CleanupAdb {
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
            "ADB_COMMAND_FAILED exit={0} args={1}" -f
            $exitCode,
            ($Arguments -join " ")
        )
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output | ForEach-Object { [string]$_ })
    }
}

function Get-G2CleanupSettingValue {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$Namespace,
        [Parameter(Mandatory)][string]$Key
    )
    if ($Namespace -notin @("system", "global", "secure")) {
        throw "SETTINGS_NAMESPACE_FORBIDDEN $Namespace"
    }
    if ($Key -notmatch "^[a-z0-9_]+$") {
        throw "SETTINGS_KEY_FORBIDDEN $Key"
    }
    $result = Invoke-G2CleanupAdb `
        -Serial $Serial `
        -Arguments @("shell", "settings", "get", $Namespace, $Key) `
        -AllowFailure
    $value = ($result.Output -join "`n").Trim()
    return [pscustomobject]@{
        namespace = $Namespace
        key = $Key
        value = if ($value -ceq "null") { $null } else { $value }
        exitCode = $result.ExitCode
    }
}

function Get-G2PackageSet {
    param([Parameter(Mandatory)][string]$Serial)
    $result = Invoke-G2CleanupAdb `
        -Serial $Serial `
        -Arguments @("shell", "pm", "list", "packages")
    return @(
        $result.Output |
            ForEach-Object {
                if ([string]$_ -match "^package:(?<name>\S+)$") {
                    [string]$Matches["name"]
                }
            } |
            Sort-Object -Unique
    )
}

function Get-G2CleanupSnapshot {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)]$Contract
    )
    $packages = @(Get-G2PackageSet -Serial $Serial)
    $pidResult = Invoke-G2CleanupAdb `
        -Serial $Serial `
        -Arguments @("shell", "pidof", $mainPackage) `
        -AllowFailure
    $peerPidResult = Invoke-G2CleanupAdb `
        -Serial $Serial `
        -Arguments @("shell", "pidof", $peerPackage) `
        -AllowFailure
    $service = (
        Invoke-G2CleanupAdb `
            -Serial $Serial `
            -Arguments @(
                "shell",
                "dumpsys",
                "activity",
                "services",
                $mainPackage
            ) -AllowFailure
    ).Output -join "`n"
    $notification = (
        Invoke-G2CleanupAdb `
            -Serial $Serial `
            -Arguments @(
                "shell",
                "dumpsys",
                "notification",
                "--noredact"
            ) -AllowFailure
    ).Output -join "`n"
    $mediaSession = (
        Invoke-G2CleanupAdb `
            -Serial $Serial `
            -Arguments @("shell", "dumpsys", "media_session") `
            -AllowFailure
    ).Output -join "`n"
    $audio = (
        Invoke-G2CleanupAdb `
            -Serial $Serial `
            -Arguments @("shell", "dumpsys", "audio") `
            -AllowFailure
    ).Output -join "`n"
    $candidatePackages = @($mainPackage, $peerPackage)
    $filteredService = Select-G2CleanupCandidateText `
        -Text $service `
        -Packages $candidatePackages
    $filteredNotification = Select-G2CleanupCandidateText `
        -Text $notification `
        -Packages $candidatePackages
    $filteredMediaSession = Select-G2CleanupCandidateText `
        -Text $mediaSession `
        -Packages $candidatePackages
    $currentAudioFocusStack = Get-G2CurrentAudioFocusStack -Dump $audio
    $filteredAudio = Select-G2CleanupCandidateText `
        -Text $currentAudioFocusStack `
        -Packages $candidatePackages
    $mediaStackMatch = [regex]::Match(
        $mediaSession,
        "Sessions Stack\s*-\s*have\s+(?<count>\d+)\s+sessions:"
    )
    $mediaStackCount = if ($mediaStackMatch.Success) {
        [int]$mediaStackMatch.Groups["count"].Value
    } else {
        0
    }
    $safeMediaSessionDump =
        "Sessions Stack - have $mediaStackCount sessions:`n" +
        $filteredMediaSession
    $settings = @(
        foreach ($setting in $capturedSettings) {
            Get-G2CleanupSettingValue `
                -Serial $Serial `
                -Namespace ([string]$setting[0]) `
                -Key ([string]$setting[1])
        }
    )
    $externalDataPath = "/sdcard/Android/data/$mainPackage"
    $externalResult = Invoke-G2CleanupAdb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "ls",
            "-d",
            $externalDataPath
        ) -AllowFailure
    $internalDataPath = "/data/data/$mainPackage"
    $internalResult = Invoke-G2CleanupAdb `
        -Serial $Serial `
        -Arguments @(
            "shell",
            "ls",
            "-d",
            $internalDataPath
        ) -AllowFailure
    return [pscustomobject]@{
        schema = "aurorama.g2-cleanup-snapshot.v1"
        capturedAt = [DateTimeOffset]::Now.ToString("o")
        serial = $Serial
        packages = $packages
        mainPids = @(
            (($pidResult.Output -join " ").Trim() -split "\s+") |
                Where-Object { $_ -match "^\d+$" }
        )
        peerPids = @(
            (($peerPidResult.Output -join " ").Trim() -split "\s+") |
                Where-Object { $_ -match "^\d+$" }
        )
        serviceDump = $filteredService
        notificationDump = $filteredNotification
        mediaSessionDump = $safeMediaSessionDump
        audioDump = $filteredAudio
        audioFocusStackDetected = (
            -not [string]::IsNullOrWhiteSpace($currentAudioFocusStack)
        )
        settings = $settings
        externalDataPath = $externalDataPath
        externalDataListing = $externalResult.Output
        externalDataExitCode = $externalResult.ExitCode
        internalDataPath = $internalDataPath
        internalDataListing = $internalResult.Output
        internalDataExitCode = $internalResult.ExitCode
        candidateMainPackage = $mainPackage
        candidatePeerPackage = $peerPackage
    }
}

function Capture-G2CleanupBefore {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole
    )
    $serial = if ($DeviceRole -ceq "Pad") {
        [string]$Context.Contract.devicePolicy.pad.serial
    } else {
        [string]$Context.Contract.devicePolicy.k60.serial
    }
    $snapshot = Get-G2CleanupSnapshot `
        -Serial $serial `
        -Contract $Context.Contract
    New-G2CleanupDirectory -Path $Context.Evidence.FullPath
    $path = Join-Path $Context.Evidence.FullPath "cleanup-before.json"
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        throw "CLEANUP_BEFORE_ALREADY_EXISTS"
    }
    Write-G2CleanupJson -Path $path -Value $snapshot
    Write-Output "CAPTURED G2_CLEANUP_BEFORE"
    Write-Output "SNAPSHOT=$path"
}

function Get-G2CandidateResiduals {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Packages,
        [Parameter(Mandatory)][AllowEmptyCollection()][int[]]$MainPids,
        [Parameter(Mandatory)][AllowEmptyCollection()][int[]]$PeerPids,
        [Parameter(Mandatory)][AllowEmptyString()][string]$ServiceDump,
        [Parameter(Mandatory)][AllowEmptyString()][string]$NotificationDump,
        [Parameter(Mandatory)][AllowEmptyString()][string]$MediaSessionDump
    )
    return [pscustomobject]@{
        packagesAbsent = -not (
            $Packages -contains $mainPackage -or
            $Packages -contains $peerPackage
        )
        pidsAbsent = (
            $MainPids.Count -eq 0 -and $PeerPids.Count -eq 0
        )
        serviceRecordsAbsent = -not (
            $ServiceDump.Contains(
                $mainPackage,
                [System.StringComparison]::Ordinal
            ) -or
            $ServiceDump.Contains(
                $peerPackage,
                [System.StringComparison]::Ordinal
            )
        )
        activeNotificationRecordsAbsent = (
            (Test-G2ActiveNotificationAbsent `
                -Dump $NotificationDump `
                -PackageName $mainPackage) -and
            (Test-G2ActiveNotificationAbsent `
                -Dump $NotificationDump `
                -PackageName $peerPackage)
        )
        activeMediaSessionsAbsent =
            Test-G2ActiveMediaSessionAbsent -Dump $MediaSessionDump
    }
}

function Remove-G2CandidateResiduals {
    param([Parameter(Mandatory)][string]$Serial)
    foreach ($packageName in @($peerPackage, $mainPackage)) {
        [void](Invoke-G2CleanupAdb `
            -Serial $Serial `
            -Arguments @("shell", "am", "force-stop", $packageName) `
            -AllowFailure)
        [void](Invoke-G2CleanupAdb `
            -Serial $Serial `
            -Arguments @("uninstall", $packageName) `
            -AllowFailure)
    }
}

function Compare-G2SettingSnapshots {
    param(
        [Parameter(Mandatory)]$Before,
        [Parameter(Mandatory)]$After
    )
    $beforeMap = @{}
    foreach ($setting in @($Before)) {
        $beforeMap["$($setting.namespace)/$($setting.key)"] = $setting.value
    }
    foreach ($setting in @($After)) {
        $key = "$($setting.namespace)/$($setting.key)"
        if (-not $beforeMap.ContainsKey($key)) {
            return $false
        }
        if ([string]$beforeMap[$key] -cne [string]$setting.value) {
            return $false
        }
    }
    return $beforeMap.Count -eq @($After).Count
}

function Invoke-G2CleanupVerification {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][ValidateSet("Pad", "K60")]$DeviceRole
    )
    $beforePath = Join-Path $Context.Evidence.FullPath "cleanup-before.json"
    if (-not (Test-Path -LiteralPath $beforePath -PathType Leaf)) {
        throw "CLEANUP_BEFORE_SNAPSHOT_MISSING"
    }
    $before = Read-G2CleanupJsonStrict $beforePath
    $serial = if ($DeviceRole -ceq "Pad") {
        [string]$Context.Contract.devicePolicy.pad.serial
    } else {
        [string]$Context.Contract.devicePolicy.k60.serial
    }
    Remove-G2CandidateResiduals -Serial $serial
    $after = Get-G2CleanupSnapshot `
        -Serial $serial `
        -Contract $Context.Contract
    $residuals = Get-G2CandidateResiduals `
        -Packages @($after.packages) `
        -MainPids @($after.mainPids) `
        -PeerPids @($after.peerPids) `
        -ServiceDump ([string]$after.serviceDump) `
        -NotificationDump ([string]$after.notificationDump) `
        -MediaSessionDump ([string]$after.mediaSessionDump)
    $sessionPath = Join-Path $Context.Evidence.FullPath "collector-session.json"
    $session = $null
    if (Test-Path -LiteralPath $sessionPath -PathType Leaf) {
        $session = Read-G2CleanupJsonStrict $sessionPath
        Assert-G2StateSessionShape `
            -Session $session `
            -Name "CLEANUP_SESSION"
    }
    $hostProcesses = if ($null -eq $session) {
        @()
    } else {
        @(Get-G2HostLogcatProcessInventory)
    }
    $hostOrphans = Get-G2HostLogcatOrphanCount `
        -Session $session `
        -Processes $hostProcesses `
        -Serial $serial
    $audioFocusReleased = (
        [bool]$after.audioFocusStackDetected -and
        -not [string]$after.audioDump.Contains(
            $mainPackage,
            [System.StringComparison]::Ordinal
        ) -and
        -not [string]$after.audioDump.Contains(
            $peerPackage,
            [System.StringComparison]::Ordinal
        ) -and
        $residuals.pidsAbsent
    )
    $productionBefore = @(
        @($before.packages) |
            Where-Object {
                $_ -cne $mainPackage -and $_ -cne $peerPackage
            } |
            Sort-Object -Unique
    )
    $productionAfter = @(
        @($after.packages) |
            Where-Object {
                $_ -cne $mainPackage -and $_ -cne $peerPackage
            } |
            Sort-Object -Unique
    )
    $productionPackageMatch =
        ($productionBefore -join "`n") -ceq
        ($productionAfter -join "`n")
    $networkRestored = Compare-G2SettingSnapshots `
        -Before @($before.settings | Where-Object {
            $_.key -in @("airplane_mode_on", "wifi_on", "mobile_data")
        }) `
        -After @($after.settings | Where-Object {
            $_.key -in @("airplane_mode_on", "wifi_on", "mobile_data")
        })
    $userSettingsRestored = Compare-G2SettingSnapshots `
        -Before @($before.settings) `
        -After @($after.settings)
    $externalDataAbsent = Test-G2CleanupPathAbsent `
        -ExitCode ([int]$after.externalDataExitCode) `
        -Output ([string](@($after.externalDataListing) -join "`n")) `
        -Path ([string]$after.externalDataPath)
    $internalDataAbsent = Test-G2CleanupPathAbsent `
        -ExitCode ([int]$after.internalDataExitCode) `
        -Output ([string](@($after.internalDataListing) -join "`n")) `
        -Path ([string]$after.internalDataPath)
    $allOtherChecksPassed = (
        [bool]$residuals.packagesAbsent -and
        [bool]$residuals.pidsAbsent -and
        [bool]$residuals.serviceRecordsAbsent -and
        [bool]$residuals.activeNotificationRecordsAbsent -and
        [bool]$residuals.activeMediaSessionsAbsent -and
        $audioFocusReleased -and
        $externalDataAbsent -and
        $internalDataAbsent -and
        $hostOrphans -eq 0 -and
        $networkRestored -and
        $userSettingsRestored -and
        $productionPackageMatch
    )
    $lockPath = Join-Path $Context.Evidence.FullPath "device-lock.json"
    $lockOccupancy = Complete-G2CleanupDeviceLock `
        -Path $lockPath `
        -AllOtherChecksPassed $allOtherChecksPassed `
        -DeviceRole $DeviceRole `
        -Serial $serial `
        -AttemptScope ([string]$Context.Evidence.RelativePath)
    $failClosed = [pscustomobject]@{
        packagesAbsent = [bool]$residuals.packagesAbsent
        pidsAbsent = [bool]$residuals.pidsAbsent
        serviceRecordsAbsent = [bool]$residuals.serviceRecordsAbsent
        activeNotificationRecordsAbsent =
            [bool]$residuals.activeNotificationRecordsAbsent
        activeMediaSessionsAbsent =
            [bool]$residuals.activeMediaSessionsAbsent
        audioFocusReleased = $audioFocusReleased
        externalDataAbsent = $externalDataAbsent
        internalDataAbsent = $internalDataAbsent
        hostLogcatOrphans = $hostOrphans
        networkRestored = $networkRestored
        userSettingsRestored = $userSettingsRestored
        productionPackageMatch = $productionPackageMatch
        deviceLockState = [string]$lockOccupancy.state
        deviceLockActiveCount = [int]$lockOccupancy.activeCount
        evidence = @(
            "cleanup-before.json",
            "cleanup-after.json"
        )
    }
    $cleanupAfterPath = Join-Path $Context.Evidence.FullPath "cleanup-after.json"
    Write-G2CleanupJson -Path $cleanupAfterPath -Value $after
    $result = New-G2CleanupResult -InputObject $failClosed
    $lockReleased = $result.status -eq "PASS"
    $resultPath = Join-Path $Context.Evidence.FullPath "cleanup.json"
    Write-G2CleanupJson -Path $resultPath -Value $result
    Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
        schema = "aurorama.g2-device-lock.v1"
        deviceRole = $DeviceRole
        serial = $serial
        attemptScope = [string]$Context.Evidence.RelativePath
        state = [string]$result.deviceLockState
        activeCount = [int]$result.deviceLockActiveCount
        attemptNumber = [int]$lockOccupancy.attemptNumber
        authoritativeLockStateAtRegistration =
            $lockOccupancy.authoritativeLockStateAtRegistration
        historicalBlockerSha256 = $lockOccupancy.historicalBlockerSha256
        releasedAt = if ($lockReleased) {
            [DateTimeOffset]::Now.ToString("o")
        } else {
            $null
        }
    })
    $externalLock = Get-G2CleanupDeviceLockOccupancy `
        -Path $lockPath `
        -DeviceRole $DeviceRole `
        -Serial $serial `
        -AttemptScope ([string]$Context.Evidence.RelativePath)
    if (
        $externalLock.state -cne [string]$result.deviceLockState -or
        $externalLock.activeCount -ne [int]$result.deviceLockActiveCount
    ) {
        throw "CLEANUP_DEVICE_LOCK_EXTERNAL_STATE_MISMATCH"
    }
    Write-Output "VERIFIED G2_CLEANUP_RESTORE"
    Write-Output "CLEANUP_STATUS=$($result.status)"
    Write-Output "FAILURES=$(@($result.failures).Count)"
    Write-Output "RESULT=$resultPath"
    if ($result.status -ne "PASS") {
        throw (
            "CLEANUP_RESIDUALS " +
            (@($result.failures) -join ",")
        )
    }
}

function Invoke-G2CleanupStaticSelfTest {
    $resolvedCandidatePath = Resolve-Path `
        -LiteralPath $candidatePath `
        -ErrorAction Stop
    if (-not (Test-Path `
        -LiteralPath $resolvedCandidatePath.ProviderPath `
        -PathType Leaf)) {
        throw "CLEANUP_STATIC_CANDIDATE_PATH_INVALID"
    }
    $fixture = [pscustomobject]@{
        packagesAbsent = $true
        pidsAbsent = $true
        serviceRecordsAbsent = $true
        activeNotificationRecordsAbsent = $true
        activeMediaSessionsAbsent = $true
        audioFocusReleased = $true
        externalDataAbsent = $true
        internalDataAbsent = $true
        hostLogcatOrphans = 0
        networkRestored = $true
        userSettingsRestored = $true
        productionPackageMatch = $true
        deviceLockState = "RELEASED_ACTIVE_0"
        deviceLockActiveCount = 0
        evidence = @("static-self-test")
    }
    $result = New-G2CleanupResult -InputObject $fixture
    if ($result.status -cne "PASS") {
        throw "CLEANUP_STATIC_SELF_TEST_FAILED"
    }
    $historyOnly = @"
Sessions Stack - have 0 sessions:

Audio playback (lastly played comes first)
  $mainPackage
"@
    if (-not (Test-G2ActiveMediaSessionAbsent -Dump $historyOnly)) {
        throw "CLEANUP_MEDIA_SESSION_HISTORY_STATIC_SELF_TEST_FAILED"
    }
    Write-Output "PASS G2_CLEANUP_RESTORE_STATIC_SELF_TEST"
    Write-Output "DEVICE_COMMANDS=0"
}

function Invoke-G2CleanupMain {
    if ($StaticSelfTest) {
        if ([string]::IsNullOrWhiteSpace($DeviceRole)) {
            $DeviceRole = "Pad"
        }
        Invoke-G2CleanupStaticSelfTest
        return
    }
    if ([string]::IsNullOrWhiteSpace($DeviceRole)) {
        throw "DEVICE_ROLE_REQUIRED"
    }
    $context = Get-G2CleanupContext `
        -DeviceRole $DeviceRole `
        -RequestedEvidenceRoot $EvidenceRoot `
        -Action $Action
    switch ($Action) {
        "CaptureBefore" {
            Capture-G2CleanupBefore `
                -Context $context `
                -DeviceRole $DeviceRole
        }
        "Verify" {
            Invoke-G2CleanupVerification `
                -Context $context `
                -DeviceRole $DeviceRole
        }
        default {
            throw "UNSUPPORTED_ACTION $Action"
        }
    }
}

if ($MyInvocation.InvocationName -ne ".") {
    try {
        Invoke-G2CleanupMain
    } catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
