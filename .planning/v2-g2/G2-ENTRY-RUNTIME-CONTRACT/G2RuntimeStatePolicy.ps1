Set-StrictMode -Version Latest

$script:G2StatePreAuditStatus = "R2_WIP_REPAIR_R3_IN_PROGRESS"
$script:G2StateQaReadyStatus =
    "PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA"
$script:G2StateQaNextRole = "G2_PAD_QA_OWNER_2026_11_06"
$script:G2StatePolicySchema = "aurorama.g2-tool-state-policy.v1"
$script:G2StateReceiptSchema = "aurorama.g2-repair-audit-receipt.v1"
$script:G2StateCurrentDeviceAccess = "STATIC_SELF_TEST_ONLY_THIS_PHASE"
$script:G2StateQaReadyDeviceAccess = "PAD_FIRST_K60_GATED_AFTER_AUDIT"
$script:G2StateQaPromptSchema = "aurorama.g2-pad-qa-dispatch.v1"
$script:G2StateQaPromptActionClass = "READ_ONLY_PREFLIGHT"
$script:G2StateQaFirstReadOnlyAction =
    "pwsh -NoProfile -File tools/v2-handoff/Invoke-HandoffPreflight.ps1 -EmitPrompt"
$script:G2StateQaWorktree =
    "F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama"
$script:G2StateQaBranch = "codex/v2-integration"
$script:G2StateQaReadOrder = @(
    "AGENTS.md",
    "docs/PROJECT_PLAN.md",
    "docs/V2_PLAN.md",
    "docs/TASKS.md",
    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/CHECKPOINT.md",
    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/README.md",
    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/contract.json"
)
$script:G2StateQaWriteScope =
    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2/**"
$script:G2StateMinimumAuditedCommit =
    "6b04eb300256fc8384e5407cee24907e8343f949"
$script:G2StateCurrentReceiptPath =
    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/repair-audit-receipt.json"
$script:G2StateReceiptSchemaPath =
    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/repair-audit-receipt.schema.json"
$script:G2StateSettlementAuditBaseline = [pscustomobject][ordered]@{
    schema = "aurorama.g2-settlement-audit-baseline.v1"
    toolFilesExact = 4
    testsPassedExact = 116
    testsFailedExact = 0
    dynamicNegativeExact = 188
    currentTransitionsExact = 64
    staticSelfTestsExact = 3
    handoffPassedExact = 108
    handoffFailedExact = 0
    handoffDynamicNegativeExact = 0
    handoffTempCleanupPassedExact = $true
    boundaryViolationsExact = 0
}
$script:G2StateStaticActions = @("Validate", "StaticSelfTest")
$script:G2StateRuntimeActions = @(
    "Register",
    "RunWindow",
    "Reconcile",
    "Finalize",
    "CaptureBefore",
    "Verify"
)
$script:G2StateToolPaths = @(
    "Finalize-G2RuntimeReport.ps1",
    "G2RuntimeStatePolicy.ps1",
    "Invoke-G2RuntimeCollector.ps1",
    "Test-G2CleanupRestore.ps1"
)
$script:G2StateExpectedManifestEntries = @(
    [pscustomobject]@{
        path = "Invoke-G2RuntimeCollector.ps1"
        kind = "tool"
    },
    [pscustomobject]@{
        path = "Finalize-G2RuntimeReport.ps1"
        kind = "tool"
    },
    [pscustomobject]@{
        path = "Test-G2CleanupRestore.ps1"
        kind = "tool"
    },
    [pscustomobject]@{
        path = "G2RuntimeStatePolicy.ps1"
        kind = "tool"
    },
    [pscustomobject]@{
        path = "tests/Test-G2RuntimeTools.ps1"
        kind = "test"
    },
    [pscustomobject]@{
        path = "repair-audit-receipt.schema.json"
        kind = "schema"
    },
    [pscustomobject]@{
        path = "tests/fixtures/netstats-before.txt"
        kind = "fixture"
    },
    [pscustomobject]@{
        path = "tests/fixtures/netstats-after.txt"
        kind = "fixture"
    },
    [pscustomobject]@{
        path = "tests/fixtures/runtime-log-happy.log"
        kind = "fixture"
    },
    [pscustomobject]@{
        path = "tests/fixtures/observations-complete.json"
        kind = "fixture"
    },
    [pscustomobject]@{
        path = "tests/fixtures/cleanup-complete.json"
        kind = "fixture"
    }
)
$script:G2StateRequiredProductGateIds = @(
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
$script:G2StateUtf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$script:G2StateUtf8Output = [System.Text.UTF8Encoding]::new($false)

function Assert-G2StateJsonElementNoDuplicateProperties {
    param(
        [Parameter(Mandatory)]
        [System.Text.Json.JsonElement]$Element,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Path
    )
    if (
        $Element.ValueKind -eq
        [System.Text.Json.JsonValueKind]::Object
    ) {
        $names = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        foreach ($property in $Element.EnumerateObject()) {
            $propertyName = [string]$property.Name
            if (-not $names.Add($propertyName)) {
                throw (
                    "JSON_DUPLICATE_PROPERTY $Name " +
                    "path=$Path property=$propertyName"
                )
            }
            Assert-G2StateJsonElementNoDuplicateProperties `
                -Element $property.Value `
                -Name $Name `
                -Path ($Path + "." + $propertyName)
        }
        return
    }
    if (
        $Element.ValueKind -eq
        [System.Text.Json.JsonValueKind]::Array
    ) {
        $index = 0
        foreach ($item in $Element.EnumerateArray()) {
            Assert-G2StateJsonElementNoDuplicateProperties `
                -Element $item `
                -Name $Name `
                -Path ($Path + "[" + $index + "]")
            $index++
        }
    }
}

function Assert-G2StateNoDuplicateJsonProperties {
    param(
        [Parameter(Mandatory)][string]$JsonText,
        [Parameter(Mandatory)][string]$Name
    )
    $document = [System.Text.Json.JsonDocument]::Parse($JsonText)
    try {
        Assert-G2StateJsonElementNoDuplicateProperties `
            -Element $document.RootElement `
            -Name $Name `
            -Path '$'
    } finally {
        $document.Dispose()
    }
}

function Read-G2StateJsonStrict {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "STATE_FILE_MISSING $Path"
    }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if (
        $bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF
    ) {
        throw "STATE_JSON_BOM_FORBIDDEN $Path"
    }
    if ([Array]::IndexOf($bytes, [byte]13) -ge 0) {
        throw "STATE_JSON_CR_FORBIDDEN $Path"
    }
    $jsonText = $G2StateUtf8Strict.GetString($bytes)
    Assert-G2StateNoDuplicateJsonProperties `
        -JsonText $jsonText `
        -Name $Path
    return (
        $jsonText |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Get-G2StateFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "STATE_FILE_MISSING $Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function Get-G2StateOrdinalSortedStrings {
    param([AllowNull()][string[]]$Value)
    [string[]]$copy = @($Value)
    [Array]::Sort($copy, [System.StringComparer]::Ordinal)
    return $copy
}

function Test-G2StateOrdinalSequenceEquals {
    param(
        [AllowNull()][object[]]$Left,
        [AllowNull()][object[]]$Right
    )
    $leftValues = @($Left)
    $rightValues = @($Right)
    if ($leftValues.Count -ne $rightValues.Count) {
        return $false
    }
    for ($index = 0; $index -lt $leftValues.Count; $index++) {
        if (
            [string]$leftValues[$index] -cne
                [string]$rightValues[$index]
        ) {
            return $false
        }
    }
    return $true
}

function Test-G2StateOrdinalStringSetEquals {
    param(
        [AllowNull()][string[]]$Left,
        [AllowNull()][string[]]$Right
    )
    $leftSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $rightSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($value in @($Left)) {
        [void]$leftSet.Add([string]$value)
    }
    foreach ($value in @($Right)) {
        [void]$rightSet.Add([string]$value)
    }
    return $leftSet.SetEquals($rightSet)
}

function Assert-G2StateExactFields {
    param(
        [AllowNull()]$Value,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Name
    )
    if ($null -eq $Value -or $Value -isnot [pscustomobject]) {
        throw "STATE_OBJECT_REQUIRED $Name"
    }
    $actual = @($Value.PSObject.Properties.Name)
    $expectedSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $actualSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($fieldName in @($Expected)) {
        if (-not $expectedSet.Add([string]$fieldName)) {
            throw "STATE_EXPECTED_FIELD_DUPLICATE $Name $fieldName"
        }
    }
    foreach ($fieldName in $actual) {
        if (-not $actualSet.Add([string]$fieldName)) {
            throw "STATE_FIELD_DUPLICATE $Name $fieldName"
        }
    }
    if (
        $actualSet.Count -ne $expectedSet.Count -or
        -not $expectedSet.SetEquals($actualSet)
    ) {
        $expectedDisplay = @(
            Get-G2StateOrdinalSortedStrings -Value @($Expected)
        )
        $actualDisplay = @(
            Get-G2StateOrdinalSortedStrings -Value $actual
        )
        throw (
            "STATE_FIELD_SET_MISMATCH $Name " +
            "expected=$($expectedDisplay -join ',') " +
            "actual=$($actualDisplay -join ',')"
        )
    }
}

function Assert-G2StateExactObjectArrayFields {
    param(
        [AllowNull()]$Values,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Name
    )
    if ($null -eq $Values) {
        throw "STATE_OBJECT_ARRAY_REQUIRED $Name"
    }
    $index = 0
    foreach ($value in @($Values)) {
        Assert-G2StateExactFields `
            -Value $value `
            -Expected $Expected `
            -Name ("$Name[$index]")
        $index++
    }
}

function Assert-G2ContractStateShape {
    param([Parameter(Mandatory)]$Contract)
    Assert-G2StateExactFields `
        -Value $Contract `
        -Expected @(
            "schema",
            "contractId",
            "version",
            "status",
            "createdAt",
            "owner",
            "toolStatePolicy",
            "validation",
            "evidence",
            "candidate",
            "attemptRecovery",
            "tools",
            "authorization",
            "devicePolicy",
            "executionPolicy",
            "productGates",
            "sequence",
            "reportModel",
            "stopConditions",
            "reusePolicy",
            "stateLocks",
            "nonGoals",
            "sourcePointers"
        ) `
        -Name "CONTRACT_ROOT"
    Assert-G2StateExactFields `
        -Value $Contract.owner `
        -Expected @(
            "roleId",
            "kind",
            "freshness",
            "reuse",
            "qaAuthority",
            "deviceAccess",
            "writeScope",
            "retiresAfterContractReport"
        ) `
        -Name "CONTRACT_OWNER"
    Assert-G2StateExactFields `
        -Value $Contract.toolStatePolicy `
        -Expected @(
            "schema",
            "preAuditStatus",
            "qaReadyStatus",
            "qaReadyActivePrompt",
            "currentToolDeviceAccess",
            "qaReadyToolDeviceAccess",
            "repairAuditReceiptPath",
            "repairAuditReceiptSchemaPath",
            "repairAuditReceiptSchemaSha256",
            "minimumAuditedCommit",
            "requiredNextRole"
        ) `
        -Name "CONTRACT_TOOL_STATE_POLICY"
    Assert-G2StateExactFields `
        -Value $Contract.validation `
        -Expected @("repairAuditExpectations") `
        -Name "CONTRACT_VALIDATION"
    Assert-G2StateExactFields `
        -Value $Contract.evidence `
        -Expected @(
            "root",
            "contractPath",
            "candidatePath",
            "reportSchemaPath",
            "reportSchemaSha256",
            "padRuntimeRoot",
            "k60RuntimeRoot"
        ) `
        -Name "CONTRACT_EVIDENCE"
    Assert-G2StateExactFields `
        -Value $Contract.candidate `
        -Expected @(
            "sourceCommit",
            "sourceInputsDigestSha256",
            "buildMode",
            "qaExecuted",
            "deviceTouched",
            "networkUsed",
            "main",
            "focusPeer"
        ) `
        -Name "CONTRACT_CANDIDATE"
    foreach ($artifactName in @("main", "focusPeer")) {
        $artifactFields = if ($artifactName -ceq "main") {
            @(
                "path",
                "bytes",
                "sha256",
                "package",
                "versionCode",
                "versionName",
                "abi",
                "internetPermission",
                "signerCertificateSha256"
            )
        } else {
            @(
                "path",
                "bytes",
                "sha256",
                "package",
                "versionCode",
                "versionName",
                "internetPermission",
                "signerCertificateSha256"
            )
        }
        Assert-G2StateExactFields `
            -Value $Contract.candidate.$artifactName `
            -Expected $artifactFields `
            -Name "CONTRACT_CANDIDATE_$artifactName"
    }
    Assert-G2StateExactFields `
        -Value $Contract.attemptRecovery `
        -Expected @(
            "schema",
            "historicalPreWindowBlock",
            "activeAttempt",
            "authoritativeLock"
        ) `
        -Name "CONTRACT_ATTEMPT_RECOVERY"
    Assert-G2StateExactFields `
        -Value $Contract.attemptRecovery.historicalPreWindowBlock `
        -Expected @(
            "deviceRole",
            "root",
            "blockerPath",
            "outcome",
            "requiredStatus",
            "requiredAttemptWindow",
            "requiredFreshInstall",
            "requiredCoreRuntimeMatrix",
            "requiredWindowConsumed",
            "internalLockAuthoritative",
            "evidenceFiles"
        ) `
        -Name "CONTRACT_HISTORICAL_PREWINDOW_BLOCK"
    Assert-G2StateExactObjectArrayFields `
        -Values @(
            $Contract.attemptRecovery.historicalPreWindowBlock.evidenceFiles
        ) `
        -Expected @("path", "sha256") `
        -Name "CONTRACT_HISTORICAL_EVIDENCE_FILE"
    Assert-G2StateExactFields `
        -Value $Contract.attemptRecovery.activeAttempt `
        -Expected @(
            "attemptNumber",
            "windowSeconds",
            "padRoot",
            "k60Root"
        ) `
        -Name "CONTRACT_ACTIVE_ATTEMPT"
    Assert-G2StateExactFields `
        -Value $Contract.attemptRecovery.authoritativeLock `
        -Expected @(
            "handoffContractPath",
            "deviceLockPath",
            "requiredStateAtRegistration",
            "internalAttemptLockAuthoritative"
        ) `
        -Name "CONTRACT_AUTHORITATIVE_LOCK"
    Assert-G2StateExactFields `
        -Value $Contract.tools `
        -Expected @(
            "candidateBuilder",
            "contractValidator",
            "statePolicy",
            "runtimeCollector",
            "runtimeFinalizer",
            "cleanupVerifier",
            "runtimeManifest",
            "digestSha256"
        ) `
        -Name "CONTRACT_TOOLS"
    foreach ($toolName in @(
        "candidateBuilder",
        "contractValidator",
        "statePolicy",
        "runtimeManifest"
    )) {
        Assert-G2StateExactFields `
            -Value $Contract.tools.$toolName `
            -Expected @(
                "roleId",
                "path",
                "sha256",
                "status",
                "deviceAccess",
                "networkAccess"
            ) `
            -Name "CONTRACT_TOOL_$toolName"
    }
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        Assert-G2StateExactFields `
            -Value $Contract.tools.$toolName `
            -Expected @(
                "roleId",
                "path",
                "sha256",
                "status",
                "deviceAccess",
                "networkAccess",
                "mustBeNew"
            ) `
            -Name "CONTRACT_TOOL_$toolName"
    }
    Assert-G2StateExactFields `
        -Value $Contract.authorization `
        -Expected @(
            "entryDecision",
            "deviceAuthorizationDecision",
            "contractOnly",
            "contractPackageReady",
            "qaDispatchable",
            "activePrompt",
            "generatedExecutableQaPrompt",
            "qaDispatch",
            "deviceExecution",
            "requiredDeviceAuthorizationReceipt",
            "authorizationReceiptPath",
            "padExecutionAuthorized",
            "k60ExecutionConditional",
            "k60Condition"
        ) `
        -Name "CONTRACT_AUTHORIZATION"
    Assert-G2StateExactFields `
        -Value $Contract.devicePolicy `
        -Expected @(
            "deviceOrder",
            "deviceWindowMinutes",
            "windowSeconds",
            "attemptsPerDevice",
            "windowExtensionAllowed",
            "retryAllowed",
            "k60Locked",
            "pad",
            "k60"
        ) `
        -Name "CONTRACT_DEVICE_POLICY"
    Assert-G2StateExactFields `
        -Value $Contract.devicePolicy.pad `
        -Expected @(
            "roleId",
            "serial",
            "model",
            "sdk",
            "currentUser",
            "freshness",
            "deviceAccess",
            "writeScope"
        ) `
        -Name "CONTRACT_PAD_POLICY"
    Assert-G2StateExactFields `
        -Value $Contract.devicePolicy.k60 `
        -Expected @(
            "roleId",
            "serial",
            "currentStatus",
            "freshness",
            "deviceAccess",
            "writeScope"
        ) `
        -Name "CONTRACT_K60_POLICY"
    Assert-G2StateExactFields `
        -Value $Contract.executionPolicy `
        -Expected @(
            "padRequiresFullCorePassBeforeK60",
            "padLockMustBeReleasedBeforeK60",
            "sameCandidateRequiredForBothDevices",
            "candidateDriftFailsClosed",
            "windowExtensionAllowed",
            "retryAllowed",
            "passWithGapsAllowedForCore",
            "singleContinuousWindowPerDevice",
            "audioGapLimitSeconds",
            "requiredVoices",
            "zeroNetworkMustCoverEveryUidPartition"
        ) `
        -Name "CONTRACT_EXECUTION_POLICY"
    Assert-G2StateExactObjectArrayFields `
        -Values $Contract.productGates `
        -Expected @("id", "requirement") `
        -Name "CONTRACT_PRODUCT_GATE"
    Assert-G2StateExactObjectArrayFields `
        -Values $Contract.sequence `
        -Expected @("order", "id", "deviceAction", "exitCondition") `
        -Name "CONTRACT_SEQUENCE_ITEM"
    Assert-G2StateExactFields `
        -Value $Contract.reportModel `
        -Expected @(
            "schemaPath",
            "schemaId",
            "oneReportPerDevice",
            "resultValues",
            "productP0P3Required",
            "uncoveredRequired",
            "executedEvidenceRequired",
            "passWithGapsForbidden"
        ) `
        -Name "CONTRACT_REPORT_MODEL"
    Assert-G2StateExactFields `
        -Value $Contract.reusePolicy `
        -Expected @(
            "qaV9OwnerReusable",
            "qaV9ToolsReusable",
            "qaV9EvidenceReusable",
            "qaV9PromptReusable",
            "qaV9HistoricalReferenceOnly",
            "oldThirtyMinuteEvidenceReusable",
            "oldStopOnlyEvidenceReusable",
            "installOrCleanupAloneCanProveRuntime"
        ) `
        -Name "CONTRACT_REUSE_POLICY"
    Assert-G2StateExactFields `
        -Value $Contract.stateLocks `
        -Expected @(
            "q03",
            "exactProfileVoice",
            "padP2",
            "g1",
            "runtime",
            "l2L3",
            "g2Implementation",
            "release",
            "serverWrite",
            "qaV9Status",
            "productP0P3"
        ) `
        -Name "CONTRACT_STATE_LOCKS"
}

function Assert-G2StateNoReparsePath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$FullPath
    )
    $relative = [System.IO.Path]::GetRelativePath($Root, $FullPath)
    $current = $Root
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
            throw "STATE_PATH_REPARSE_POINT_FORBIDDEN $current"
        }
    }
}

function Resolve-G2StateBoundedPath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        throw "STATE_PATH_EMPTY"
    }
    if (
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath -match "^[A-Za-z]:" -or
        $RelativePath.StartsWith("\\", [System.StringComparison]::Ordinal)
    ) {
        throw "STATE_PATH_ABSOLUTE_FORBIDDEN $RelativePath"
    }
    $segments = @($RelativePath -split "[\\/]")
    if ($segments -contains "..") {
        throw "STATE_PATH_TRAVERSAL_FORBIDDEN $RelativePath"
    }
    if ($segments -contains "." -or $segments -contains "") {
        throw "STATE_PATH_SEGMENT_FORBIDDEN $RelativePath"
    }
    $fullRoot = [System.IO.Path]::GetFullPath($Root).
        TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        )
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $fullRoot $RelativePath)
    )
    $prefix =
        $fullRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith(
        $prefix,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "STATE_PATH_OUTSIDE_ROOT $RelativePath"
    }
    Assert-G2StateNoReparsePath -Root $fullRoot -FullPath $fullPath
    return $fullPath
}

function Get-G2RuntimeAuthoritativeLockSnapshot {
    param([Parameter(Mandatory)][string]$DeviceLockPath)
    if (-not (Test-Path -LiteralPath $DeviceLockPath -PathType Leaf)) {
        throw "AUTHORITATIVE_DEVICE_LOCK_MISSING"
    }
    $bytes = [System.IO.File]::ReadAllBytes($DeviceLockPath)
    try {
        $text = $G2StateUtf8Strict.GetString($bytes)
    } catch {
        throw "AUTHORITATIVE_DEVICE_LOCK_NOT_UTF8"
    }
    $lines = @($text.Replace("`r", "").Split("`n"))
    $currentStart = -1
    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex] -ceq "## 当前占用") {
            $currentStart = $lineIndex + 1
            break
        }
    }
    if ($currentStart -lt 0) {
        throw "AUTHORITATIVE_DEVICE_LOCK_SECTION_MISSING"
    }
    $currentEnd = $lines.Count
    for ($lineIndex = $currentStart; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex].StartsWith(
            "## ",
            [System.StringComparison]::Ordinal
        )) {
            $currentEnd = $lineIndex
            break
        }
    }
    $activeCount = 0
    for ($lineIndex = $currentStart; $lineIndex -lt $currentEnd; $lineIndex++) {
        if ($lines[$lineIndex] -match '^### .*— ACTIVE(?:\s|$)') {
            $activeCount++
        }
    }
    return [pscustomobject]@{
        state = if ($activeCount -eq 0) {
            "RELEASED_ACTIVE_0"
        } else {
            "ACTIVE_COUNT_$activeCount"
        }
        activeCount = $activeCount
        sha256 = [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData($bytes)
        ).ToLowerInvariant()
    }
}

function Assert-G2StateToolManifest {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$ContractRoot,
        [Parameter(Mandatory)][string]$ManifestPath
    )
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        throw "RUNTIME_TOOL_MANIFEST_MISSING"
    }
    if (
        (Get-G2StateFileSha256 $ManifestPath) -cne
            [string]$Contract.tools.runtimeManifest.sha256
    ) {
        throw "RUNTIME_TOOL_MANIFEST_SHA_MISMATCH"
    }
    $manifest = Read-G2StateJsonStrict $ManifestPath
    Assert-G2StateExactFields `
        -Value $manifest `
        -Expected @(
            "schema",
            "contractId",
            "ownerRole",
            "status",
            "attemptRecoveryStatus",
            "createdAt",
            "freshness",
            "reuse",
            "qaV9ReuseCount",
            "deviceCommandsExecutedInSelfTest",
            "adbCallsInSelfTest",
            "networkCallsInSelfTest",
            "gradleCallsInSelfTest",
            "fixtureModeCannotProducePass",
            "devicePolicy",
            "capabilities",
            "files",
            "toolsDigestSha256"
        ) `
        -Name "RUNTIME_TOOL_MANIFEST_ROOT"
    Assert-G2StateExactFields `
        -Value $manifest.devicePolicy `
        -Expected @(
            "allowedSerials",
            "padFirst",
            "k60RequiresPadFullPassAndReleasedLock",
            "attemptsPerDevice",
            "windowSeconds",
            "extensionAllowed",
            "retryAllowed"
        ) `
        -Name "RUNTIME_TOOL_MANIFEST_DEVICE_POLICY"
    Assert-G2StateExactFields `
        -Value $manifest.capabilities `
        -Expected @(
            "runtimeCollector",
            "runtimeFinalizer",
            "cleanupVerifier"
        ) `
        -Name "RUNTIME_TOOL_MANIFEST_CAPABILITIES"
    if (
        [string]$manifest.schema -cne "aurorama.g2-runtime-tools.v1" -or
        [string]$manifest.contractId -cne [string]$Contract.contractId -or
        [string]$manifest.ownerRole -cne
            "G2_RUNTIME_TOOL_OWNER_2026_11_06" -or
        [int]$manifest.qaV9ReuseCount -ne 0
    ) {
        throw "RUNTIME_TOOL_MANIFEST_IDENTITY_MISMATCH"
    }
    $entries = @($manifest.files)
    $expectedEntries = @($script:G2StateExpectedManifestEntries)
    if (
        $entries.Count -ne 11 -or
        $expectedEntries.Count -ne 11
    ) {
        throw (
            "RUNTIME_TOOL_MANIFEST_CARDINALITY_MISMATCH " +
            "expected=11 actual=$($entries.Count)"
        )
    }
    $entryPaths = @($entries | ForEach-Object {
        $pathValue = [string]$_.path
        if ([string]::IsNullOrWhiteSpace($pathValue)) {
            throw "RUNTIME_TOOL_MANIFEST_PATH_EMPTY"
        }
        $pathValue
    })
    $entryPathSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($entryPath in $entryPaths) {
        if (-not $entryPathSet.Add($entryPath)) {
            throw "RUNTIME_TOOL_MANIFEST_PATH_DUPLICATE $entryPath"
        }
    }
    foreach ($expectedEntry in $expectedEntries) {
        $matching = @(
            $entries |
                Where-Object {
                    [string]$_.path -ceq
                        [string]$expectedEntry.path
                }
        )
        if ($matching.Count -ne 1) {
            throw (
                "RUNTIME_TOOL_MANIFEST_ENTRY_SET_MISMATCH " +
                [string]$expectedEntry.path
            )
        }
        if (
            [string]$matching[0].kind -cne
                [string]$expectedEntry.kind
        ) {
            throw (
                "RUNTIME_TOOL_MANIFEST_KIND_MISMATCH " +
                [string]$expectedEntry.path
            )
        }
    }
    foreach ($entry in $entries) {
        Assert-G2StateExactFields `
            -Value $entry `
            -Expected @("path", "sha256", "kind") `
            -Name "RUNTIME_TOOL_MANIFEST_ENTRY"
        $entryPath = Resolve-G2StateBoundedPath `
            -Root $ContractRoot `
            -RelativePath ([string]$entry.path)
        if (
            (Get-G2StateFileSha256 $entryPath) -cne
                [string]$entry.sha256
        ) {
            throw "RUNTIME_TOOL_MANIFEST_FILE_SHA_MISMATCH $($entry.path)"
        }
    }
    $unorderedToolPaths = @(
        $entries |
            Where-Object { [string]$_.kind -ceq "tool" } |
            ForEach-Object { [string]$_.path }
    )
    $actualToolPaths = @(
        Get-G2StateOrdinalSortedStrings -Value $unorderedToolPaths
    )
    if (
        $actualToolPaths.Count -ne $G2StateToolPaths.Count -or
        -not (Test-G2StateOrdinalStringSetEquals `
            -Left $G2StateToolPaths `
            -Right $actualToolPaths)
    ) {
        throw (
            "RUNTIME_TOOL_KIND_SET_MISMATCH " +
            "actual=$($actualToolPaths -join ',')"
        )
    }
    $toolEntries = @($actualToolPaths | ForEach-Object {
        $toolPath = $_
        @(
            $entries |
                Where-Object { [string]$_.path -ceq $toolPath }
        )[0]
    })
    $digestBuilder = [System.Text.StringBuilder]::new()
    foreach ($entry in $toolEntries) {
        $entryPath = Resolve-G2StateBoundedPath `
            -Root $ContractRoot `
            -RelativePath ([string]$entry.path)
        [void]$digestBuilder.Append(
            (Get-G2StateFileSha256 $entryPath) +
                "  " +
                [string]$entry.path +
                "`n"
        )
    }
    $digest = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            $G2StateUtf8Output.GetBytes($digestBuilder.ToString())
        )
    ).ToLowerInvariant()
    if (
        [string]$manifest.toolsDigestSha256 -cne $digest -or
        [string]$Contract.tools.digestSha256 -cne $digest
    ) {
        throw "RUNTIME_TOOLS_DIGEST_MISMATCH"
    }
    return [pscustomobject]@{
        Manifest = $manifest
        ManifestFiles = $entries
        ToolFiles = $actualToolPaths
        ToolsDigest = $digest
    }
}

function Assert-G2StateRegistrationShape {
    param(
        [AllowNull()]$Registration,
        [Parameter(Mandatory)][string]$Name
    )
    Assert-G2StateExactFields `
        -Value $Registration `
        -Expected @(
            "schema",
            "contractId",
            "toolOwnerRole",
            "qaOwnerRole",
            "deviceRole",
            "serial",
            "attemptNumber",
            "windowSeconds",
            "attemptScope",
            "attemptState",
            "registeredAt",
            "candidateMainSha256",
            "candidateFocusPeerSha256",
            "sourceCommit",
            "toolsDigest",
            "predecessor",
            "recoveryBasis",
            "authoritativeLockState",
            "authoritativeLockSha256",
            "lockState"
        ) `
        -Name $Name
    if ($null -ne $Registration.recoveryBasis) {
        Assert-G2StateExactFields `
            -Value $Registration.recoveryBasis `
            -Expected @(
                "outcome",
                "historicalWindowConsumed",
                "blockerEvidencePointer",
                "blockerSha256",
                "historicalDeviceLockEvidencePointer",
                "historicalDeviceLockSha256",
                "authoritativeLockState",
                "authoritativeLockSha256"
            ) `
            -Name "${Name}_RECOVERY_BASIS"
    }
}

function Assert-G2StateSessionShape {
    param(
        [AllowNull()]$Session,
        [Parameter(Mandatory)][string]$Name
    )
    Assert-G2StateExactFields `
        -Value $Session `
        -Expected @(
            "schema",
            "contractId",
            "toolOwnerRole",
            "qaOwnerRole",
            "evidenceRoot",
            "attemptScope",
            "attemptState",
            "toolsDigest",
            "candidateMainSha256",
            "candidateFocusPeerSha256",
            "sourceCommit",
            "deviceRole",
            "serial",
            "attemptNumber",
            "windowSeconds",
            "extended",
            "retry",
            "continuous",
            "startedAt",
            "completedAt",
            "uid",
            "device",
            "commandCount",
            "commands",
            "snapshots",
            "hostLogcatProcessId",
            "hostLogcatProcessStopped",
            "error"
        ) `
        -Name $Name
    Assert-G2StateExactFields `
        -Value $Session.device `
        -Expected @("serial", "model", "sdk", "abi", "user") `
        -Name "${Name}_DEVICE"
}

function Assert-G2StateDeviceLockShape {
    param(
        [AllowNull()]$Lock,
        [Parameter(Mandatory)][string]$Name
    )
    Assert-G2StateExactFields `
        -Value $Lock `
        -Expected @(
            "schema",
            "deviceRole",
            "serial",
            "attemptScope",
            "state",
            "activeCount",
            "attemptNumber",
            "authoritativeLockStateAtRegistration",
            "historicalBlockerSha256",
            "registeredAt",
            "releasedAt"
        ) `
        -Name $Name
}

function Assert-G2StateRuntimeReportShape {
    param(
        [AllowNull()]$Report,
        [Parameter(Mandatory)][string]$Name
    )
    Assert-G2StateExactFields `
        -Value $Report `
        -Expected @(
            "schema",
            "contractId",
            "ownerRole",
            "evidenceRoot",
            "candidate",
            "toolsDigest",
            "authorization",
            "device",
            "predecessor",
            "execution",
            "productGates",
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
            "cleanupAndRestore",
            "passWithGapsForbidden",
            "productVerdict",
            "verdict",
            "finalizedAt"
        ) `
        -Name $Name
    Assert-G2StateExactFields `
        -Value $Report.candidate `
        -Expected @(
            "sourceCommit",
            "mainPath",
            "mainSha256",
            "mainBytes",
            "mainPackage",
            "mainVersionCode",
            "mainVersionName",
            "mainAbi",
            "mainInternetPermission",
            "focusPeerPath",
            "focusPeerSha256",
            "focusPeerBytes"
        ) `
        -Name "${Name}_CANDIDATE"
    Assert-G2StateExactFields `
        -Value $Report.authorization `
        -Expected @(
            "deviceExecutionGranted",
            "grantReceiptPath",
            "grantReceiptSha256",
            "contractOnlyPhase"
        ) `
        -Name "${Name}_AUTHORIZATION"
    Assert-G2StateExactFields `
        -Value $Report.device `
        -Expected @(
            "order",
            "role",
            "serial",
            "model",
            "sdk",
            "abi",
            "user",
            "lockStateAtStart"
        ) `
        -Name "${Name}_DEVICE"
    Assert-G2StateExactFields `
        -Value $Report.execution `
        -Expected @(
            "windowMinutes",
            "windowSeconds",
            "attemptNumber",
            "extended",
            "retry",
            "continuous",
            "startedAt",
            "completedAt",
            "freshInstall",
            "candidateIdentityVerified"
        ) `
        -Name "${Name}_EXECUTION"
    Assert-G2StateExactFields `
        -Value $Report.continuity `
        -Expected @(
            "head",
            "middle",
            "tail",
            "maximumAudioGapSeconds"
        ) `
        -Name "${Name}_CONTINUITY"
    Assert-G2StateExactFields `
        -Value $Report.systemSurfaces `
        -Expected @(
            "foregroundService",
            "mediaSession",
            "notification",
            "ui",
            "systemControls",
            "stateEquality"
        ) `
        -Name "${Name}_SYSTEM_SURFACES"
    Assert-G2StateExactFields `
        -Value $Report.audioFocus `
        -Expected @(
            "status",
            "singleOwner",
            "ownerPids",
            "focusRequest",
            "focusLoss",
            "focusGain",
            "evidence"
        ) `
        -Name "${Name}_AUDIO_FOCUS"
    Assert-G2StateExactFields `
        -Value $Report.stability `
        -Expected @(
            "nativeCrash",
            "javaFatal",
            "anr",
            "inputTimeout",
            "applicationExitInfo",
            "evidence"
        ) `
        -Name "${Name}_STABILITY"
    Assert-G2StateExactFields `
        -Value $Report.uiCancel `
        -Expected @(
            "status",
            "cancelObserved",
            "pidConverged",
            "serviceConverged",
            "notificationConverged",
            "mediaSessionConverged",
            "focusConverged",
            "evidence"
        ) `
        -Name "${Name}_UI_CANCEL"
    Assert-G2StateExactFields `
        -Value $Report.processRecovery `
        -Expected @(
            "processKilled",
            "recoveryMode",
            "autoPlaybackAfterDeath",
            "lastStableSentenceRestored",
            "packWhitelistVerified",
            "serviceOwnerAfterRestart",
            "evidence"
        ) `
        -Name "${Name}_PROCESS_RECOVERY"
    Assert-G2StateExactFields `
        -Value $Report.zeroNetwork `
        -Expected @(
            "status",
            "uid",
            "allPartitionsAccounted",
            "windowBytesDelta",
            "nonzeroAttribution",
            "evidence"
        ) `
        -Name "${Name}_ZERO_NETWORK"
    Assert-G2StateExactFields `
        -Value $Report.productFindings `
        -Expected @("P0", "P1", "P2", "P3", "summary") `
        -Name "${Name}_PRODUCT_FINDINGS"
    Assert-G2StateExactFields `
        -Value $Report.cleanupAndRestore `
        -Expected @(
            "status",
            "packagesAbsent",
            "pidsAbsent",
            "serviceRecordsAbsent",
            "activeNotificationRecordsAbsent",
            "activeMediaSessionsAbsent",
            "audioFocusReleased",
            "externalDataAbsent",
            "internalDataAbsent",
            "hostLogcatOrphans",
            "networkRestored",
            "userSettingsRestored",
            "productionPackageMatch",
            "deviceLockState",
            "deviceLockActiveCount",
            "evidence"
        ) `
        -Name "${Name}_CLEANUP"
}

function Assert-G2AuthorizationReceiptShape {
    param(
        [AllowNull()]$Authorization,
        [Parameter(Mandatory)][string]$Name
    )
    Assert-G2StateExactFields `
        -Value $Authorization `
        -Expected @(
            "schema",
            "receiptId",
            "decisionId",
            "contractId",
            "authorizedAt",
            "authorizedBy",
            "sourceQuote",
            "scope",
            "candidate",
            "executionPolicy"
        ) `
        -Name $Name
    Assert-G2StateExactFields `
        -Value $Authorization.scope `
        -Expected @(
            "runtimeQa",
            "deviceInstall",
            "newRuntimeToolCreation",
            "pad",
            "k60",
            "g2Implementation",
            "release",
            "serverWrite",
            "q03Close",
            "profileVoiceFreeze",
            "padP2Acceptance",
            "l2L3Unlock"
        ) `
        -Name "${Name}_SCOPE"
    Assert-G2StateExactFields `
        -Value $Authorization.scope.pad `
        -Expected @("authorized", "serial", "order") `
        -Name "${Name}_PAD"
    Assert-G2StateExactFields `
        -Value $Authorization.scope.k60 `
        -Expected @(
            "authorized",
            "conditional",
            "serial",
            "order",
            "condition"
        ) `
        -Name "${Name}_K60"
    Assert-G2StateExactFields `
        -Value $Authorization.candidate `
        -Expected @(
            "sourceCommit",
            "mainSha256",
            "mainBytes",
            "mainVersionCode",
            "mainVersionName",
            "focusPeerSha256",
            "focusPeerBytes"
        ) `
        -Name "${Name}_CANDIDATE"
    Assert-G2StateExactFields `
        -Value $Authorization.executionPolicy `
        -Expected @(
            "deviceOrder",
            "deviceWindowMinutes",
            "attemptsPerInstrumentedPhase",
            "windowExtensionAllowed",
            "retryAllowed",
            "sameCandidateRequired",
            "freshOwnerPerDevice",
            "k60LockedUntilPadClosure",
            "passWithGapsForbiddenForCore"
        ) `
        -Name "${Name}_EXECUTION_POLICY"
}

function Assert-G2CandidateRecordShape {
    param(
        [AllowNull()]$Candidate,
        [Parameter(Mandatory)][string]$Name
    )
    Assert-G2StateExactFields `
        -Value $Candidate `
        -Expected @(
            "schema",
            "ownerRole",
            "builtAt",
            "sourceCommit",
            "sourceInputsDigestSha256",
            "buildMode",
            "qaExecuted",
            "deviceTouched",
            "networkUsed",
            "main",
            "focusPeer",
            "runtimeInputs",
            "worktreeStatusAtBuild"
        ) `
        -Name $Name
    Assert-G2StateExactFields `
        -Value $Candidate.main `
        -Expected @(
            "path",
            "bytes",
            "sha256",
            "package",
            "versionCode",
            "versionName",
            "abi",
            "internetPermission",
            "permissions",
            "signerCertificateSha256"
        ) `
        -Name "${Name}_MAIN"
    Assert-G2StateExactFields `
        -Value $Candidate.focusPeer `
        -Expected @(
            "path",
            "bytes",
            "sha256",
            "package",
            "versionCode",
            "versionName",
            "internetPermission",
            "permissions",
            "signerCertificateSha256"
        ) `
        -Name "${Name}_FOCUS_PEER"
    Assert-G2StateExactFields `
        -Value $Candidate.runtimeInputs `
        -Expected @(
            "modelOnnxSha256",
            "voicesSha256",
            "tokensSha256",
            "lexiconSha256",
            "sherpaAarSha256"
        ) `
        -Name "${Name}_RUNTIME_INPUTS"
}

function Assert-G2StateCandidateFiles {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$RepositoryRoot
    )
    foreach ($artifact in @(
        $Contract.candidate.main,
        $Contract.candidate.focusPeer
    )) {
        $path = Resolve-G2StateBoundedPath `
            -Root $RepositoryRoot `
            -RelativePath ([string]$artifact.path)
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "CANDIDATE_ARTIFACT_MISSING $($artifact.path)"
        }
        if ((Get-Item -LiteralPath $path).Length -ne [long]$artifact.bytes) {
            throw "CANDIDATE_BYTES_MISMATCH $($artifact.path)"
        }
        if ((Get-G2StateFileSha256 $path) -cne
            [string]$artifact.sha256) {
            throw "CANDIDATE_SHA256_MISMATCH $($artifact.path)"
        }
    }
}

function Get-G2ValidatedPadPredecessor {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$PadRoot
    )
    $expectedRoot = [string]$Contract.evidence.padRuntimeRoot
    $repositoryBase = [System.IO.Path]::GetFullPath($RepositoryRoot)
    $expectedPadRoot = Resolve-G2StateBoundedPath `
        -Root $repositoryBase `
        -RelativePath $expectedRoot
    $actualPadRoot = [System.IO.Path]::GetFullPath($PadRoot)
    if ($actualPadRoot -cne $expectedPadRoot) {
        throw "K60_PREDECESSOR_FIXED_PAD_ROOT_MISMATCH"
    }
    $reportPath = Join-Path $actualPadRoot "report.json"
    $lockPath = Join-Path $actualPadRoot "device-lock.json"
    if (
        -not (Test-Path -LiteralPath $reportPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $lockPath -PathType Leaf)
    ) {
        throw "K60_PREDECESSOR_REPORT_OR_LOCK_MISSING"
    }
    $padReport = Read-G2StateJsonStrict $reportPath
    $padLock = Read-G2StateJsonStrict $lockPath
    Assert-G2StateRuntimeReportShape `
        -Report $padReport `
        -Name "K60_PREDECESSOR_PAD_REPORT"
    Assert-G2StateDeviceLockShape `
        -Lock $padLock `
        -Name "K60_PREDECESSOR_PAD_LOCK"
    $gateIds = @($padReport.productGates | ForEach-Object {
        [string]$_.gateId
    })
    $nonPassGates = @(
        $padReport.productGates |
            Where-Object { [string]$_.status -cne "PASS" }
    )
    $historicalBlocker = @(
        $Contract.attemptRecovery.historicalPreWindowBlock.evidenceFiles |
            Where-Object { [string]$_.path -ceq "blocker.json" }
    )
    if ($historicalBlocker.Count -ne 1) {
        throw "K60_PREDECESSOR_HISTORICAL_BLOCKER_PIN_MISSING"
    }
    $cleanup = $padReport.cleanupAndRestore
    if (
        [string]$padReport.schema -cne
            "aurorama.g2-entry-runtime-report.v1" -or
        [string]$padReport.contractId -cne [string]$Contract.contractId -or
        [string]$padReport.ownerRole -cne
            "G2_PAD_QA_OWNER_2026_11_06" -or
        [string]$padReport.evidenceRoot -cne $expectedRoot -or
        [string]$padReport.device.order -cne "Pad" -or
        [string]$padReport.device.role -cne
            "G2_PAD_QA_OWNER_2026_11_06" -or
        [string]$padReport.device.serial -cne "43af8627" -or
        [int]$padReport.execution.attemptNumber -ne 1 -or
        [int]$padReport.execution.windowSeconds -ne 600 -or
        [bool]$padReport.execution.extended -ne $false -or
        [bool]$padReport.execution.retry -ne $false -or
        [bool]$padReport.execution.continuous -ne $true -or
        [bool]$padReport.passWithGapsForbidden -ne $true -or
        $null -ne $padReport.predecessor -or
        [string]$padReport.verdict -cne "PASS" -or
        [string]$padReport.productVerdict -cne "PASS" -or
        $gateIds.Count -ne 13 -or
        @($gateIds | Select-Object -Unique).Count -ne 13 -or
        @($script:G2StateRequiredProductGateIds | Where-Object {
            $gateIds -notcontains $_
        }).Count -gt 0 -or
        $nonPassGates.Count -gt 0 -or
        [string]$cleanup.status -cne "PASS" -or
        [bool]$cleanup.packagesAbsent -ne $true -or
        [bool]$cleanup.pidsAbsent -ne $true -or
        [bool]$cleanup.serviceRecordsAbsent -ne $true -or
        [bool]$cleanup.activeNotificationRecordsAbsent -ne $true -or
        [bool]$cleanup.activeMediaSessionsAbsent -ne $true -or
        [bool]$cleanup.audioFocusReleased -ne $true -or
        [bool]$cleanup.externalDataAbsent -ne $true -or
        [bool]$cleanup.internalDataAbsent -ne $true -or
        [int]$cleanup.hostLogcatOrphans -ne 0 -or
        [bool]$cleanup.networkRestored -ne $true -or
        [bool]$cleanup.userSettingsRestored -ne $true -or
        [bool]$cleanup.productionPackageMatch -ne $true -or
        [string]$cleanup.deviceLockState -cne
            "RELEASED_ACTIVE_0" -or
        [int]$cleanup.deviceLockActiveCount -ne 0 -or
        [string]$padReport.candidate.mainSha256 -cne
            [string]$Contract.candidate.main.sha256 -or
        [string]$padReport.candidate.focusPeerSha256 -cne
            [string]$Contract.candidate.focusPeer.sha256 -or
        [string]$padReport.candidate.sourceCommit -cne
            [string]$Contract.candidate.sourceCommit -or
        [string]$padLock.schema -cne "aurorama.g2-device-lock.v1" -or
        [string]$padLock.deviceRole -cne "Pad" -or
        [string]$padLock.serial -cne "43af8627" -or
        [string]$padLock.attemptScope -cne $expectedRoot -or
        [string]$padLock.state -cne "RELEASED_ACTIVE_0" -or
        [int]$padLock.activeCount -ne 0 -or
        [int]$padLock.attemptNumber -ne 1 -or
        [string]::IsNullOrWhiteSpace([string]$padLock.releasedAt) -or
        [string]$padLock.authoritativeLockStateAtRegistration -cne
            "RELEASED_ACTIVE_0" -or
        [string]$padLock.historicalBlockerSha256 -cne
            [string]$historicalBlocker[0].sha256
    ) {
        throw "K60_PREDECESSOR_PAD_NOT_FULL_PASS_OR_LOCK_NOT_RELEASED"
    }
    return [pscustomobject]@{
        deviceOrder = "Pad"
        coreVerdict = "PASS"
        productVerdict = "PASS"
        lockStateAfterClosure = "RELEASED_ACTIVE_0"
        evidenceRoot = $expectedRoot
        candidateMainSha256 =
            [string]$Contract.candidate.main.sha256
        candidateFocusPeerSha256 =
            [string]$Contract.candidate.focusPeer.sha256
        evidencePointer = "$expectedRoot/report.json"
        reportSha256 = Get-G2StateFileSha256 $reportPath
        lockSha256 = Get-G2StateFileSha256 $lockPath
    }
}

function Assert-G2PadPredecessorBinding {
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual
    )
    $fields = @(
        "deviceOrder",
        "coreVerdict",
        "productVerdict",
        "lockStateAfterClosure",
        "evidenceRoot",
        "candidateMainSha256",
        "candidateFocusPeerSha256",
        "evidencePointer",
        "reportSha256",
        "lockSha256"
    )
    Assert-G2StateExactFields `
        -Value $Actual `
        -Expected $fields `
        -Name "PAD_PREDECESSOR"
    foreach ($field in $fields) {
        if (
            [string]$Actual.$field -cne [string]$Expected.$field
        ) {
            throw "PAD_PREDECESSOR_BINDING_MISMATCH $field"
        }
    }
}

function Assert-G2StateCommitBinding {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$AuditedCommit,
        [Parameter(Mandatory)][string]$Parent,
        [Parameter(Mandatory)][string]$CandidateSourceCommit,
        [Parameter(Mandatory)][string]$MinimumAuditedCommit
    )
    if (
        $AuditedCommit -cnotmatch "^[0-9a-f]{40}$" -or
        $Parent -cnotmatch "^[0-9a-f]{40}$" -or
        $CandidateSourceCommit -cnotmatch "^[0-9a-f]{40}$" -or
        $MinimumAuditedCommit -cnotmatch "^[0-9a-f]{40}$"
    ) {
        throw "AUDIT_COMMIT_IDENTITY_INVALID"
    }
    if ($AuditedCommit -ceq $MinimumAuditedCommit) {
        throw "AUDITED_COMMIT_STRICT_DESCENDANT_REQUIRED"
    }
    & git -C $RepositoryRoot merge-base --is-ancestor `
        $MinimumAuditedCommit $AuditedCommit 2>&1 |
        Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "AUDITED_COMMIT_BASELINE_MISMATCH"
    }
    & git -C $RepositoryRoot merge-base --is-ancestor `
        $CandidateSourceCommit $AuditedCommit 2>&1 |
        Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "CANDIDATE_SOURCE_NOT_AUDITED_ANCESTOR"
    }
    $actualParent = @(
        & git -C $RepositoryRoot rev-parse `
            "$AuditedCommit^1" 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "AUDITED_COMMIT_PARENT_MISSING"
    }
    $parentValue = (
        ($actualParent | ForEach-Object { [string]$_ }) -join "`n"
    ).Trim()
    if ($parentValue -cne $Parent) {
        throw "AUDITED_COMMIT_PARENT_MISMATCH"
    }
}

function Assert-G2StateBoolean {
    param(
        [AllowNull()]$Value,
        [Parameter(Mandatory)][bool]$Expected,
        [Parameter(Mandatory)][string]$Name
    )
    if ($Value -isnot [bool] -or [bool]$Value -ne $Expected) {
        throw "STATE_BOOLEAN_MISMATCH $Name"
    }
}

function Assert-G2StateInteger {
    param(
        [AllowNull()]$Value,
        [Parameter(Mandatory)][long]$Expected,
        [Parameter(Mandatory)][string]$Name
    )
    if ($Value -isnot [int] -and $Value -isnot [long]) {
        throw "STATE_INTEGER_REQUIRED $Name"
    }
    if ([long]$Value -ne $Expected) {
        throw (
            "STATE_INTEGER_MISMATCH $Name " +
            "expected=$Expected actual=$Value"
        )
    }
}

function Assert-G2StateNonNegativeInteger {
    param(
        [AllowNull()]$Value,
        [Parameter(Mandatory)][string]$Name
    )
    if ($Value -isnot [int] -and $Value -isnot [long]) {
        throw "STATE_INTEGER_REQUIRED $Name"
    }
    if ([long]$Value -lt 0) {
        throw "STATE_INTEGER_NEGATIVE $Name"
    }
    return [long]$Value
}

function Get-G2SettlementAuditBaseline {
    return [pscustomobject][ordered]@{
        schema =
            [string]$script:G2StateSettlementAuditBaseline.schema
        toolFilesExact =
            [long]$script:G2StateSettlementAuditBaseline.toolFilesExact
        testsPassedExact =
            [long]$script:G2StateSettlementAuditBaseline.
                testsPassedExact
        testsFailedExact =
            [long]$script:G2StateSettlementAuditBaseline.testsFailedExact
        dynamicNegativeExact =
            [long]$script:G2StateSettlementAuditBaseline.
                dynamicNegativeExact
        currentTransitionsExact =
            [long]$script:G2StateSettlementAuditBaseline.
                currentTransitionsExact
        staticSelfTestsExact =
            [long]$script:G2StateSettlementAuditBaseline.
                staticSelfTestsExact
        handoffPassedExact =
            [long]$script:G2StateSettlementAuditBaseline.
                handoffPassedExact
        handoffFailedExact =
            [long]$script:G2StateSettlementAuditBaseline.
                handoffFailedExact
        handoffDynamicNegativeExact =
            [long]$script:G2StateSettlementAuditBaseline.
                handoffDynamicNegativeExact
        handoffTempCleanupPassedExact =
            [bool]$script:G2StateSettlementAuditBaseline.
                handoffTempCleanupPassedExact
        boundaryViolationsExact =
            [long]$script:G2StateSettlementAuditBaseline.
                boundaryViolationsExact
    }
}

function Assert-G2SettlementAuditBaseline {
    param(
        [Parameter(Mandatory)][ValidateSet("CONTRACT", "RECEIPT")]
        [string]$Layer,
        [AllowNull()]$ToolFiles,
        [AllowNull()]$TestsPassed,
        [AllowNull()]$TestsFailed,
        [AllowNull()]$DynamicNegative,
        [AllowNull()]$CurrentTransitions,
        [AllowNull()]$StaticSelfTests,
        [AllowNull()]$HandoffPassed,
        [AllowNull()]$HandoffFailed,
        [AllowNull()]$HandoffDynamicNegative,
        [AllowNull()]$HandoffTempCleanupPassed,
        [AllowNull()]$BoundaryViolations
    )
    $baseline = Get-G2SettlementAuditBaseline
    Assert-G2StateInteger `
        -Value $ToolFiles `
        -Expected $baseline.toolFilesExact `
        -Name "${Layer}_TOOL_FILES_EXACT"
    Assert-G2StateInteger `
        -Value $TestsPassed `
        -Expected $baseline.testsPassedExact `
        -Name "${Layer}_TESTS_PASSED_EXACT"
    Assert-G2StateInteger `
        -Value $TestsFailed `
        -Expected $baseline.testsFailedExact `
        -Name "${Layer}_TESTS_FAILED_EXACT"
    Assert-G2StateInteger `
        -Value $DynamicNegative `
        -Expected $baseline.dynamicNegativeExact `
        -Name "${Layer}_DYNAMIC_NEGATIVE_EXACT"
    Assert-G2StateInteger `
        -Value $CurrentTransitions `
        -Expected $baseline.currentTransitionsExact `
        -Name "${Layer}_CURRENT_TRANSITIONS_EXACT"
    Assert-G2StateInteger `
        -Value $StaticSelfTests `
        -Expected $baseline.staticSelfTestsExact `
        -Name "${Layer}_STATIC_SELF_TESTS_EXACT"
    Assert-G2StateInteger `
        -Value $HandoffPassed `
        -Expected $baseline.handoffPassedExact `
        -Name "${Layer}_HANDOFF_PASSED_EXACT"
    Assert-G2StateInteger `
        -Value $HandoffFailed `
        -Expected $baseline.handoffFailedExact `
        -Name "${Layer}_HANDOFF_FAILED_EXACT"
    Assert-G2StateInteger `
        -Value $HandoffDynamicNegative `
        -Expected $baseline.handoffDynamicNegativeExact `
        -Name "${Layer}_HANDOFF_DYNAMIC_NEGATIVE_EXACT"
    Assert-G2StateBoolean `
        -Value $HandoffTempCleanupPassed `
        -Expected $baseline.handoffTempCleanupPassedExact `
        -Name "${Layer}_HANDOFF_TEMP_CLEANUP_EXACT"
    Assert-G2StateInteger `
        -Value $BoundaryViolations `
        -Expected $baseline.boundaryViolationsExact `
        -Name "${Layer}_BOUNDARY_VIOLATIONS_EXACT"
    return $baseline
}

function Assert-G2PreAuditRepairAuditExpectations {
    param([Parameter(Mandatory)]$Contract)
    if (
        $null -eq $Contract.PSObject.Properties["validation"] -or
        $null -eq
            $Contract.validation.PSObject.Properties[
                "repairAuditExpectations"
            ]
    ) {
        throw "PRE_AUDIT_REPAIR_AUDIT_EXPECTATIONS_MISSING"
    }
    $expectations = $Contract.validation.repairAuditExpectations
    Assert-G2StateExactFields `
        -Value $expectations `
        -Expected @(
            "status",
            "source",
            "preflightCounts",
            "g2ToolsCounts",
            "handoffCounts",
            "boundaryViolations"
        ) `
        -Name "PRE_AUDIT_REPAIR_AUDIT_EXPECTATIONS"
    if (
        [string]$expectations.status -cne
            "PENDING_SETTLEMENT_FINAL_AUDIT"
    ) {
        throw (
            "PRE_AUDIT_REPAIR_AUDIT_EXPECTATIONS_PENDING_REQUIRED " +
            "actual=$($expectations.status)"
        )
    }
    if (
        $null -ne $expectations.source -or
        $null -ne $expectations.preflightCounts -or
        $null -ne $expectations.g2ToolsCounts -or
        $null -ne $expectations.handoffCounts -or
        $null -ne $expectations.boundaryViolations
    ) {
        throw "PRE_AUDIT_REPAIR_AUDIT_EXPECTATIONS_MUST_BE_NULL"
    }
}

function Assert-G2RepairAuditExpectationsDefinition {
    param([Parameter(Mandatory)]$Contract)
    if (
        $null -eq $Contract.PSObject.Properties["validation"] -or
        $Contract.validation -isnot [pscustomobject] -or
        $null -eq
            $Contract.validation.PSObject.Properties[
                "repairAuditExpectations"
            ]
    ) {
        throw "REPAIR_AUDIT_EXPECTATIONS_MISSING"
    }
    $expectations = $Contract.validation.repairAuditExpectations
    Assert-G2StateExactFields `
        -Value $expectations `
        -Expected @(
            "status",
            "source",
            "preflightCounts",
            "g2ToolsCounts",
            "handoffCounts",
            "boundaryViolations"
        ) `
        -Name "REPAIR_AUDIT_EXPECTATIONS"
    $status = [string]$expectations.status
    if ($status -ceq "PENDING_SETTLEMENT_FINAL_AUDIT") {
        if (
            $null -ne $expectations.source -or
            $null -ne $expectations.preflightCounts -or
            $null -ne $expectations.g2ToolsCounts -or
            $null -ne $expectations.handoffCounts -or
            $null -ne $expectations.boundaryViolations
        ) {
            throw "REPAIR_AUDIT_PENDING_EXPECTATIONS_MUST_BE_NULL"
        }
        return $expectations
    }
    if ($status -cne "SETTLED_FROM_FINAL_AUDIT") {
        throw "REPAIR_AUDIT_EXPECTATIONS_STATUS_INVALID"
    }
    Assert-G2StateExactFields `
        -Value $expectations.source `
        -Expected @(
            "auditedCommit",
            "g2ToolsCommand",
            "handoffCommand",
            "staticSelfTestCommand"
        ) `
        -Name "REPAIR_AUDIT_EXPECTATIONS_SOURCE"
    Assert-G2StateExactFields `
        -Value $expectations.preflightCounts `
        -Expected @(
            "inputFiles",
            "roles",
            "tasks",
            "gates",
            "currentTransitions"
        ) `
        -Name "REPAIR_AUDIT_EXPECTED_PREFLIGHT_COUNTS"
    Assert-G2StateExactFields `
        -Value $expectations.g2ToolsCounts `
        -Expected @(
            "files",
            "testsPassed",
            "testsFailed",
            "dynamicNegative",
            "staticSelfTests"
        ) `
        -Name "REPAIR_AUDIT_EXPECTED_G2_TOOLS_COUNTS"
    Assert-G2StateExactFields `
        -Value $expectations.handoffCounts `
        -Expected @(
            "passed",
            "failed",
            "dynamicNegative",
            "tempCleanupPassed"
        ) `
        -Name "REPAIR_AUDIT_EXPECTED_HANDOFF_COUNTS"
    if (
        [string]$expectations.source.auditedCommit -cnotmatch
            "^[0-9a-f]{40}$" -or
        -not ([string]$expectations.source.g2ToolsCommand).Contains(
            "tests/Test-G2RuntimeTools.ps1",
            [System.StringComparison]::Ordinal
        ) -or
        -not ([string]$expectations.source.handoffCommand).Contains(
            "tools/v2-handoff/Test-HandoffPreflight.ps1",
            [System.StringComparison]::Ordinal
        ) -or
        -not ([string]$expectations.source.staticSelfTestCommand).Contains(
            "StaticSelfTest",
            [System.StringComparison]::Ordinal
        )
    ) {
        throw "REPAIR_AUDIT_EXPECTATIONS_SOURCE_INVALID"
    }
    $inputFiles = Assert-G2StateNonNegativeInteger `
        -Value $expectations.preflightCounts.inputFiles `
        -Name "EXPECTED_INPUT_FILES"
    $roles = Assert-G2StateNonNegativeInteger `
        -Value $expectations.preflightCounts.roles `
        -Name "EXPECTED_ROLES"
    $tasks = Assert-G2StateNonNegativeInteger `
        -Value $expectations.preflightCounts.tasks `
        -Name "EXPECTED_TASKS"
    $gates = Assert-G2StateNonNegativeInteger `
        -Value $expectations.preflightCounts.gates `
        -Name "EXPECTED_GATES"
    [void](Assert-G2StateInteger `
        -Value $expectations.preflightCounts.currentTransitions `
        -Expected (
            [long]$script:G2StateSettlementAuditBaseline.currentTransitionsExact
        ) `
        -Name "EXPECTED_CURRENT_TRANSITIONS")
    $currentTransitions =
        [long]$expectations.preflightCounts.currentTransitions
    $files = Assert-G2StateNonNegativeInteger `
        -Value $expectations.g2ToolsCounts.files `
        -Name "EXPECTED_G2_TOOL_FILES"
    $testsPassed = Assert-G2StateNonNegativeInteger `
        -Value $expectations.g2ToolsCounts.testsPassed `
        -Name "EXPECTED_G2_TEST_PASSED"
    $testsFailed = Assert-G2StateNonNegativeInteger `
        -Value $expectations.g2ToolsCounts.testsFailed `
        -Name "EXPECTED_G2_TEST_FAILED"
    $dynamicNegative = Assert-G2StateNonNegativeInteger `
        -Value $expectations.g2ToolsCounts.dynamicNegative `
        -Name "EXPECTED_G2_DYNAMIC_NEGATIVE"
    $staticSelfTests = Assert-G2StateNonNegativeInteger `
        -Value $expectations.g2ToolsCounts.staticSelfTests `
        -Name "EXPECTED_STATIC_SELF_TESTS"
    $handoffPassed = Assert-G2StateNonNegativeInteger `
        -Value $expectations.handoffCounts.passed `
        -Name "EXPECTED_HANDOFF_PASSED"
    $handoffFailed = Assert-G2StateNonNegativeInteger `
        -Value $expectations.handoffCounts.failed `
        -Name "EXPECTED_HANDOFF_FAILED"
    $handoffDynamicNegative = Assert-G2StateNonNegativeInteger `
        -Value $expectations.handoffCounts.dynamicNegative `
        -Name "EXPECTED_HANDOFF_DYNAMIC_NEGATIVE"
    if (
        $testsPassed -lt 1 -or
        $testsFailed -ne 0 -or
        $staticSelfTests -ne 3 -or
        $handoffPassed -lt 1 -or
        $handoffFailed -ne 0
    ) {
        throw "REPAIR_AUDIT_EXPECTATIONS_VALUE_INVALID"
    }
    Assert-G2StateBoolean `
        -Value $expectations.handoffCounts.tempCleanupPassed `
        -Expected $true `
        -Name "EXPECTED_TEMP_CLEANUP_PASSED"
    $expectedBoundaryViolations = Assert-G2StateNonNegativeInteger `
        -Value $expectations.boundaryViolations `
        -Name "EXPECTED_BOUNDARY_VIOLATIONS"
    if ($expectedBoundaryViolations -ne 0) {
        throw "REPAIR_AUDIT_EXPECTED_BOUNDARY_VIOLATIONS_MISMATCH"
    }
    [void](Assert-G2SettlementAuditBaseline `
        -Layer CONTRACT `
        -ToolFiles $files `
        -TestsPassed $testsPassed `
        -TestsFailed $testsFailed `
        -DynamicNegative $dynamicNegative `
        -CurrentTransitions $currentTransitions `
        -StaticSelfTests $staticSelfTests `
        -HandoffPassed $handoffPassed `
        -HandoffFailed $handoffFailed `
        -HandoffDynamicNegative $handoffDynamicNegative `
        -HandoffTempCleanupPassed `
            $expectations.handoffCounts.tempCleanupPassed `
        -BoundaryViolations $expectedBoundaryViolations)
    return [pscustomobject]@{
        Source = $expectations.source
        InputFiles = $inputFiles
        Roles = $roles
        Tasks = $tasks
        Gates = $gates
        CurrentTransitions = $currentTransitions
        Files = $files
        TestsPassed = $testsPassed
        TestsFailed = $testsFailed
        DynamicNegative = $dynamicNegative
        StaticSelfTests = $staticSelfTests
        HandoffPassed = $handoffPassed
        HandoffFailed = $handoffFailed
        HandoffDynamicNegative = $handoffDynamicNegative
        BoundaryViolations = $expectedBoundaryViolations
    }
}

function Assert-G2RepairAuditReceiptMatchesExpectations {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Receipt
    )
    $expectations =
        Assert-G2RepairAuditExpectationsDefinition -Contract $Contract
    if (
        [string]$Contract.validation.repairAuditExpectations.status -cne
            "SETTLED_FROM_FINAL_AUDIT"
    ) {
        throw "REPAIR_AUDIT_EXPECTATIONS_NOT_SETTLED"
    }
    if (
        [string]$expectations.Source.auditedCommit -cne
            [string]$Receipt.auditedCommit
    ) {
        throw "REPAIR_AUDIT_EXPECTATIONS_AUDITED_COMMIT_MISMATCH"
    }
    Assert-G2StateInteger `
        -Value $Receipt.preflightCounts.inputFiles `
        -Expected $expectations.InputFiles `
        -Name "RECEIPT_INPUT_FILES"
    Assert-G2StateInteger `
        -Value $Receipt.preflightCounts.roles `
        -Expected $expectations.Roles `
        -Name "RECEIPT_ROLES"
    Assert-G2StateInteger `
        -Value $Receipt.preflightCounts.tasks `
        -Expected $expectations.Tasks `
        -Name "RECEIPT_TASKS"
    Assert-G2StateInteger `
        -Value $Receipt.preflightCounts.gates `
        -Expected $expectations.Gates `
        -Name "RECEIPT_GATES"
    Assert-G2StateInteger `
        -Value $Receipt.preflightCounts.currentTransitions `
        -Expected $expectations.CurrentTransitions `
        -Name "RECEIPT_CURRENT_TRANSITIONS"
    Assert-G2StateInteger `
        -Value $Receipt.g2ToolsCounts.files `
        -Expected $expectations.Files `
        -Name "RECEIPT_G2_TOOL_FILES"
    Assert-G2StateInteger `
        -Value $Receipt.g2ToolsCounts.testsPassed `
        -Expected $expectations.TestsPassed `
        -Name "RECEIPT_G2_TESTS_PASSED"
    Assert-G2StateInteger `
        -Value $Receipt.g2ToolsCounts.testsFailed `
        -Expected $expectations.TestsFailed `
        -Name "RECEIPT_G2_TESTS_FAILED"
    Assert-G2StateInteger `
        -Value $Receipt.g2ToolsCounts.dynamicNegative `
        -Expected $expectations.DynamicNegative `
        -Name "RECEIPT_G2_DYNAMIC_NEGATIVE"
    Assert-G2StateInteger `
        -Value $Receipt.g2ToolsCounts.staticSelfTests `
        -Expected $expectations.StaticSelfTests `
        -Name "RECEIPT_STATIC_SELF_TESTS"
    Assert-G2StateInteger `
        -Value $Receipt.handoffCounts.passed `
        -Expected $expectations.HandoffPassed `
        -Name "RECEIPT_HANDOFF_PASSED"
    Assert-G2StateInteger `
        -Value $Receipt.handoffCounts.failed `
        -Expected $expectations.HandoffFailed `
        -Name "RECEIPT_HANDOFF_FAILED"
    Assert-G2StateInteger `
        -Value $Receipt.handoffCounts.dynamicNegative `
        -Expected $expectations.HandoffDynamicNegative `
        -Name "RECEIPT_HANDOFF_DYNAMIC_NEGATIVE"
    Assert-G2StateBoolean `
        -Value $Receipt.handoffCounts.tempCleanupPassed `
        -Expected $true `
        -Name "RECEIPT_TEMP_CLEANUP_PASSED"
    Assert-G2StateInteger `
        -Value $Receipt.boundaryViolations `
        -Expected $expectations.BoundaryViolations `
        -Name "RECEIPT_BOUNDARY_VIOLATIONS"
    [void](Assert-G2SettlementAuditBaseline `
        -Layer RECEIPT `
        -ToolFiles $Receipt.g2ToolsCounts.files `
        -TestsPassed $Receipt.g2ToolsCounts.testsPassed `
        -TestsFailed $Receipt.g2ToolsCounts.testsFailed `
        -DynamicNegative $Receipt.g2ToolsCounts.dynamicNegative `
        -CurrentTransitions $Receipt.preflightCounts.currentTransitions `
        -StaticSelfTests $Receipt.g2ToolsCounts.staticSelfTests `
        -HandoffPassed $Receipt.handoffCounts.passed `
        -HandoffFailed $Receipt.handoffCounts.failed `
        -HandoffDynamicNegative $Receipt.handoffCounts.dynamicNegative `
        -HandoffTempCleanupPassed `
            $Receipt.handoffCounts.tempCleanupPassed `
        -BoundaryViolations $Receipt.boundaryViolations)
    return $expectations
}

function Assert-G2QaDispatchDescriptor {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Descriptor,
        [Parameter(Mandatory)][string]$AuditedCommit
    )
    Assert-G2StateExactFields `
        -Value $Descriptor `
        -Expected @(
            "schema",
            "ownerRole",
            "actionClass",
            "firstReadOnlyAction",
            "worktree",
            "branch",
            "head",
            "headExpectation",
            "readOrder",
            "writeScope",
            "device",
            "window",
            "productGateIds",
            "nonGoals",
            "stopConditions",
            "promptText",
            "promptSha256"
        ) `
        -Name "QA_DISPATCH"
    Assert-G2StateExactFields `
        -Value $Descriptor.device `
        -Expected @(
            "order",
            "first",
            "serial",
            "roleId",
            "k60"
        ) `
        -Name "QA_DISPATCH_DEVICE"
    Assert-G2StateExactFields `
        -Value $Descriptor.window `
        -Expected @(
            "minutes",
            "seconds",
            "attempts",
            "continuous",
            "extensionAllowed",
            "retryAllowed"
        ) `
        -Name "QA_DISPATCH_WINDOW"
    if (
        [string]$Descriptor.schema -cne $G2StateQaPromptSchema -or
        [string]$Descriptor.ownerRole -cne $G2StateQaNextRole -or
        [string]$Descriptor.actionClass -cne
            $G2StateQaPromptActionClass -or
        [string]$Descriptor.firstReadOnlyAction -cne
            $G2StateQaFirstReadOnlyAction -or
        [string]$Descriptor.worktree -cne $G2StateQaWorktree -or
        [string]$Descriptor.branch -cne $G2StateQaBranch -or
        [string]$Descriptor.head -cnotmatch "^[0-9a-f]{40}$" -or
        [string]$Descriptor.head -cne $AuditedCommit -or
        [string]$Descriptor.headExpectation -cne
            "EXACT_AUDITED_COMMIT_CLEAN"
    ) {
        throw "QA_DISPATCH_IDENTITY_OR_HEAD_MISMATCH"
    }
    $readOrder = @(
        $Descriptor.readOrder |
            ForEach-Object { [string]$_ }
    )
    if (
        $readOrder.Count -ne $G2StateQaReadOrder.Count -or
        -not (Test-G2StateOrdinalSequenceEquals `
            -Left $readOrder `
            -Right $G2StateQaReadOrder)
    ) {
        throw "QA_DISPATCH_READ_ORDER_MISMATCH"
    }
    $writeScope = @(
        $Descriptor.writeScope |
            ForEach-Object { [string]$_ }
    )
    if (
        $writeScope.Count -ne 1 -or
        $writeScope[0] -cne $G2StateQaWriteScope
    ) {
        throw "QA_DISPATCH_WRITE_SCOPE_MISMATCH"
    }
    if (
        [string]$Descriptor.device.order -cne "Pad->K60" -or
        [string]$Descriptor.device.first -cne "Pad" -or
        [string]$Descriptor.device.serial -cne "43af8627" -or
        [string]$Descriptor.device.roleId -cne $G2StateQaNextRole -or
        [string]$Descriptor.device.k60 -cne
            "FORBIDDEN_UNTIL_PAD_FULL_CORE_PASS_AND_RELEASED_ACTIVE_0"
    ) {
        throw "QA_DISPATCH_DEVICE_MISMATCH"
    }
    Assert-G2StateInteger `
        -Value $Descriptor.window.minutes `
        -Expected 10 `
        -Name "QA_DISPATCH_WINDOW_MINUTES"
    Assert-G2StateInteger `
        -Value $Descriptor.window.seconds `
        -Expected 600 `
        -Name "QA_DISPATCH_WINDOW_SECONDS"
    Assert-G2StateInteger `
        -Value $Descriptor.window.attempts `
        -Expected 1 `
        -Name "QA_DISPATCH_WINDOW_ATTEMPTS"
    Assert-G2StateBoolean `
        -Value $Descriptor.window.continuous `
        -Expected $true `
        -Name "QA_DISPATCH_WINDOW_CONTINUOUS"
    Assert-G2StateBoolean `
        -Value $Descriptor.window.extensionAllowed `
        -Expected $false `
        -Name "QA_DISPATCH_WINDOW_EXTENSION"
    Assert-G2StateBoolean `
        -Value $Descriptor.window.retryAllowed `
        -Expected $false `
        -Name "QA_DISPATCH_WINDOW_RETRY"
    $productGateIds = @(
        $Descriptor.productGateIds |
            ForEach-Object { [string]$_ }
    )
    $expectedGateIds = @(
        $Contract.productGates |
            ForEach-Object { [string]$_.id }
    )
    if (
        $productGateIds.Count -ne 13 -or
        @($productGateIds | Select-Object -Unique).Count -ne 13 -or
        $expectedGateIds.Count -ne 13 -or
        -not (Test-G2StateOrdinalStringSetEquals `
            -Left $productGateIds `
            -Right $G2StateRequiredProductGateIds) -or
        -not (Test-G2StateOrdinalStringSetEquals `
            -Left $expectedGateIds `
            -Right $G2StateRequiredProductGateIds)
    ) {
        throw "QA_DISPATCH_PRODUCT_GATES_MISMATCH"
    }
    $nonGoals = @(
        $Descriptor.nonGoals |
            ForEach-Object { [string]$_ }
    )
    $contractNonGoals = @(
        $Contract.nonGoals |
            ForEach-Object { [string]$_ }
    )
    if (
        $nonGoals.Count -lt 1 -or
        -not (Test-G2StateOrdinalSequenceEquals `
            -Left $nonGoals `
            -Right $contractNonGoals)
    ) {
        throw "QA_DISPATCH_NON_GOALS_MISMATCH"
    }
    $stopConditions = @(
        $Descriptor.stopConditions |
            ForEach-Object { [string]$_ }
    )
    $contractStopConditions = @(
        $Contract.stopConditions |
            ForEach-Object { [string]$_ }
    )
    if (
        $stopConditions.Count -lt 1 -or
        -not (Test-G2StateOrdinalSequenceEquals `
            -Left $stopConditions `
            -Right $contractStopConditions)
    ) {
        throw "QA_DISPATCH_STOP_CONDITIONS_MISMATCH"
    }
    $promptText = [string]$Descriptor.promptText
    if (
        [string]::IsNullOrWhiteSpace($promptText) -or
        $promptText.Contains("`r", [System.StringComparison]::Ordinal) -or
        -not $promptText.Contains(
            $G2StateQaNextRole,
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            $G2StateQaFirstReadOnlyAction,
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            $G2StateQaWorktree,
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            $G2StateQaBranch,
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            $AuditedCommit,
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            $G2StateQaWriteScope,
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            "K60",
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            "10 minutes",
            [System.StringComparison]::Ordinal
        ) -or
        -not $promptText.Contains(
            "retry",
            [System.StringComparison]::OrdinalIgnoreCase
        )
    ) {
        throw "QA_DISPATCH_PROMPT_TEXT_INVALID"
    }
    foreach ($requiredText in @(
        $readOrder +
        $productGateIds +
        $nonGoals +
        $stopConditions
    )) {
        if (-not $promptText.Contains(
            [string]$requiredText,
            [System.StringComparison]::Ordinal
        )) {
            throw "QA_DISPATCH_PROMPT_TEXT_MISSING $requiredText"
        }
    }
    $actualPromptSha256 = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            $G2StateUtf8Output.GetBytes($promptText)
        )
    ).ToLowerInvariant()
    if (
        [string]$Descriptor.promptSha256 -cnotmatch "^[0-9a-f]{64}$" -or
        [string]$Descriptor.promptSha256 -cne $actualPromptSha256
    ) {
        throw "QA_DISPATCH_PROMPT_HASH_MISMATCH"
    }
    return [pscustomobject]@{
        OwnerRole = [string]$Descriptor.ownerRole
        PromptSha256 = [string]$Descriptor.promptSha256
        Head = [string]$Descriptor.head
    }
}

function Assert-G2RepairAuditReceipt {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Receipt,
        [Parameter(Mandatory)][string]$ReceiptPath,
        [Parameter(Mandatory)][string]$ReceiptSchemaPath,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)]$ManifestState,
        [Parameter(Mandatory)]$LockSnapshot
    )
    $rootFields = @(
        "schema",
        "version",
        "auditorRole",
        "auditedCommit",
        "parent",
        "auditedAt",
        "verdict",
        "scope",
        "preflightCounts",
        "g2ToolsCounts",
        "handoffCounts",
        "toolsDigest",
        "candidate",
        "qaPromptSha256",
        "qaPadV1",
        "externalLock",
        "boundaryViolations",
        "nextRole"
    )
    Assert-G2StateExactFields `
        -Value $Receipt `
        -Expected $rootFields `
        -Name "REPAIR_AUDIT_RECEIPT"
    Assert-G2StateExactFields `
        -Value $Receipt.scope `
        -Expected @(
            "contractId",
            "contractRoot",
            "mode",
            "qaExecuted",
            "deviceTouched",
            "networkUsed"
        ) `
        -Name "REPAIR_AUDIT_RECEIPT_SCOPE"
    Assert-G2StateExactFields `
        -Value $Receipt.preflightCounts `
        -Expected @(
            "inputFiles",
            "roles",
            "tasks",
            "gates",
            "currentTransitions"
        ) `
        -Name "REPAIR_AUDIT_PREFLIGHT_COUNTS"
    Assert-G2StateExactFields `
        -Value $Receipt.g2ToolsCounts `
        -Expected @(
            "files",
            "testsPassed",
            "testsFailed",
            "dynamicNegative",
            "staticSelfTests"
        ) `
        -Name "REPAIR_AUDIT_G2_TOOLS_COUNTS"
    Assert-G2StateExactFields `
        -Value $Receipt.handoffCounts `
        -Expected @(
            "passed",
            "failed",
            "dynamicNegative",
            "tempCleanupPassed"
        ) `
        -Name "REPAIR_AUDIT_HANDOFF_COUNTS"
    Assert-G2StateExactFields `
        -Value $Receipt.candidate `
        -Expected @("mainSha256", "focusPeerSha256") `
        -Name "REPAIR_AUDIT_CANDIDATE"
    Assert-G2StateExactFields `
        -Value $Receipt.externalLock `
        -Expected @("state", "sha256") `
        -Name "REPAIR_AUDIT_EXTERNAL_LOCK"
    if (
        [string]$Receipt.schema -cne $G2StateReceiptSchema -or
        [string]$Receipt.version -cne "1.0.0" -or
        [string]$Receipt.auditorRole -cnotmatch
            "^G2_[A-Z0-9_]*AUDIT_OWNER_2026_11_06$" -or
        [string]$Receipt.verdict -cne "PASS" -or
        [string]$Receipt.nextRole -cne $G2StateQaNextRole -or
        [string]$Receipt.qaPromptSha256 -cnotmatch "^[0-9a-f]{64}$" -or
        [int]$Receipt.boundaryViolations -ne 0
    ) {
        throw "REPAIR_AUDIT_RECEIPT_IDENTITY_MISMATCH"
    }
    $auditedAtValue = [DateTimeOffset]::MinValue
    $auditedAtText = [string]$Receipt.auditedAt
    if (
        $auditedAtText -cnotmatch
            "^(?:[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2}))$" -or
        -not [DateTimeOffset]::TryParse(
            $auditedAtText,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$auditedAtValue
        )
    ) {
        throw "REPAIR_AUDIT_TIMESTAMP_INVALID"
    }
    if (
        [string]$Receipt.scope.contractId -cne [string]$Contract.contractId -or
        [string]$Receipt.scope.contractRoot -cne
            [string]$Contract.evidence.root -or
        [string]$Receipt.scope.mode -cne
            "AUDIT_PASS_PENDING_FRESH_PAD_QA"
    ) {
        throw "REPAIR_AUDIT_SCOPE_MISMATCH"
    }
    Assert-G2StateBoolean `
        -Value $Receipt.scope.qaExecuted `
        -Expected $false `
        -Name "SCOPE_QA_EXECUTED"
    Assert-G2StateBoolean `
        -Value $Receipt.scope.deviceTouched `
        -Expected $false `
        -Name "SCOPE_DEVICE_TOUCHED"
    Assert-G2StateBoolean `
        -Value $Receipt.scope.networkUsed `
        -Expected $false `
        -Name "SCOPE_NETWORK_USED"
    $expectations = Assert-G2RepairAuditReceiptMatchesExpectations `
        -Contract $Contract `
        -Receipt $Receipt
    if ($expectations.Files -ne $ManifestState.ToolFiles.Count) {
        throw "REPAIR_AUDIT_EXPECTED_TOOL_FILE_COUNT_MISMATCH"
    }
    if (
        [string]$Receipt.toolsDigest -cne
            [string]$ManifestState.ToolsDigest -or
        [string]$Receipt.candidate.mainSha256 -cne
            [string]$Contract.candidate.main.sha256 -or
        [string]$Receipt.candidate.focusPeerSha256 -cne
            [string]$Contract.candidate.focusPeer.sha256
    ) {
        throw "REPAIR_AUDIT_TOOL_OR_CANDIDATE_MISMATCH"
    }
    $qaDispatchState = Assert-G2QaDispatchDescriptor `
        -Contract $Contract `
        -Descriptor $Contract.authorization.qaDispatch `
        -AuditedCommit ([string]$Receipt.auditedCommit)
    if (
        [string]$Receipt.qaPromptSha256 -cne
            [string]$qaDispatchState.PromptSha256
    ) {
        throw "REPAIR_AUDIT_PROMPT_HASH_MISMATCH"
    }
    $expectedEvidence = @(
        $Contract.attemptRecovery.historicalPreWindowBlock.evidenceFiles
    )
    $receiptEvidence = @($Receipt.qaPadV1)
    if (
        $receiptEvidence.Count -ne 5 -or
        $expectedEvidence.Count -ne 5
    ) {
        throw "REPAIR_AUDIT_QA_PAD_V1_COUNT_MISMATCH"
    }
    $historicalRoot = [string](
        $Contract.attemptRecovery.historicalPreWindowBlock.root
    )
    for ($index = 0; $index -lt 5; $index++) {
        Assert-G2StateExactFields `
            -Value $receiptEvidence[$index] `
            -Expected @("path", "sha256") `
            -Name "REPAIR_AUDIT_QA_PAD_V1_$index"
        $expectedPath = [string]$expectedEvidence[$index].path
        $expectedSha = [string]$expectedEvidence[$index].sha256
        if (
            [string]$receiptEvidence[$index].path -cne $expectedPath -or
            [string]$receiptEvidence[$index].sha256 -cne $expectedSha
        ) {
            throw "REPAIR_AUDIT_QA_PAD_V1_RECEIPT_MISMATCH $expectedPath"
        }
        $actualPath = Resolve-G2StateBoundedPath `
            -Root $RepositoryRoot `
            -RelativePath ($historicalRoot + "/" + $expectedPath)
        if (
            (Get-G2StateFileSha256 $actualPath) -cne $expectedSha
        ) {
            throw "REPAIR_AUDIT_QA_PAD_V1_FILE_SHA_MISMATCH $expectedPath"
        }
    }
    if (
        [string]$Receipt.externalLock.state -cne
            [string]$LockSnapshot.state -or
        [string]$Receipt.externalLock.sha256 -cne
            [string]$LockSnapshot.sha256 -or
        [string]$LockSnapshot.state -cne "RELEASED_ACTIVE_0"
    ) {
        throw "REPAIR_AUDIT_EXTERNAL_LOCK_MISMATCH"
    }
    Assert-G2StateCommitBinding `
        -RepositoryRoot $RepositoryRoot `
        -AuditedCommit ([string]$Receipt.auditedCommit) `
        -Parent ([string]$Receipt.parent) `
        -CandidateSourceCommit ([string]$Contract.candidate.sourceCommit) `
        -MinimumAuditedCommit (
            [string]$Contract.toolStatePolicy.minimumAuditedCommit
        )

    $jsonSchemaValid = $false
    try {
        $jsonText = [System.IO.File]::ReadAllText(
            $ReceiptPath,
            $G2StateUtf8Strict
        )
        $jsonSchemaValid = $jsonText |
            Test-Json -SchemaFile $ReceiptSchemaPath -ErrorAction Stop
    } catch {
        throw "REPAIR_AUDIT_RECEIPT_SCHEMA_MISMATCH"
    }
    if (-not $jsonSchemaValid) {
        throw "REPAIR_AUDIT_RECEIPT_SCHEMA_MISMATCH"
    }
    return [pscustomobject]@{
        Verdict = "PASS"
        AuditedCommit = [string]$Receipt.auditedCommit
        Parent = [string]$Receipt.parent
        ToolsDigest = [string]$Receipt.toolsDigest
        QaPromptSha256 = [string]$qaDispatchState.PromptSha256
        ExternalLock = $LockSnapshot
    }
}

function Assert-G2ToolStateDefinition {
    param([Parameter(Mandatory)]$Contract)
    Assert-G2ContractStateShape -Contract $Contract
    [void](Assert-G2RepairAuditExpectationsDefinition -Contract $Contract)
    Assert-G2StateExactFields `
        -Value $Contract.toolStatePolicy `
        -Expected @(
            "schema",
            "preAuditStatus",
            "qaReadyStatus",
            "qaReadyActivePrompt",
            "currentToolDeviceAccess",
            "qaReadyToolDeviceAccess",
            "repairAuditReceiptPath",
            "repairAuditReceiptSchemaPath",
            "repairAuditReceiptSchemaSha256",
            "minimumAuditedCommit",
            "requiredNextRole"
        ) `
        -Name "TOOL_STATE_POLICY"
    if (
        [string]$Contract.toolStatePolicy.schema -cne
            $G2StatePolicySchema -or
        [string]$Contract.toolStatePolicy.preAuditStatus -cne
            $G2StatePreAuditStatus -or
        [string]$Contract.toolStatePolicy.qaReadyStatus -cne
            $G2StateQaReadyStatus -or
        [string]$Contract.toolStatePolicy.qaReadyActivePrompt -cne
            $G2StateQaNextRole -or
        [string]$Contract.toolStatePolicy.currentToolDeviceAccess -cne
            $G2StateCurrentDeviceAccess -or
        [string]$Contract.toolStatePolicy.qaReadyToolDeviceAccess -cne
            $G2StateQaReadyDeviceAccess -or
        [string]$Contract.toolStatePolicy.repairAuditReceiptPath -cne
            $G2StateCurrentReceiptPath -or
        [string]$Contract.toolStatePolicy.repairAuditReceiptSchemaPath -cne
            $G2StateReceiptSchemaPath -or
        [string]$Contract.toolStatePolicy.requiredNextRole -cne
            $G2StateQaNextRole -or
        [string]$Contract.toolStatePolicy.minimumAuditedCommit -cne
            $G2StateMinimumAuditedCommit
    ) {
        throw "TOOL_STATE_POLICY_DEFINITION_MISMATCH"
    }
}

function Assert-G2RuntimeToolStatePolicy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$ContractRoot,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$Action,
        [AllowNull()][string]$RepairAuditReceiptPathOverride,
        [AllowNull()][string]$ManifestPathOverride
    )
    Assert-G2ToolStateDefinition -Contract $Contract
    $manifestPath = if ([string]::IsNullOrWhiteSpace(
        $ManifestPathOverride
    )) {
        Join-Path $ContractRoot "runtime-tools.manifest.json"
    } else {
        [System.IO.Path]::GetFullPath($ManifestPathOverride)
    }
    $manifestState = Assert-G2StateToolManifest `
        -Contract $Contract `
        -ContractRoot $ContractRoot `
        -ManifestPath $manifestPath
    Assert-G2StateCandidateFiles `
        -Contract $Contract `
        -RepositoryRoot $RepositoryRoot

    $receiptSchemaPath = Resolve-G2StateBoundedPath `
        -Root $RepositoryRoot `
        -RelativePath (
            [string]$Contract.toolStatePolicy.repairAuditReceiptSchemaPath
        )
    if (
        (Get-G2StateFileSha256 $receiptSchemaPath) -cne
            [string]$Contract.toolStatePolicy.
                repairAuditReceiptSchemaSha256
    ) {
        throw "REPAIR_AUDIT_RECEIPT_SCHEMA_SHA_MISMATCH"
    }
    $statePolicyPath = Resolve-G2StateBoundedPath `
        -Root $RepositoryRoot `
        -RelativePath ([string]$Contract.tools.statePolicy.path)
    if (
        (Get-G2StateFileSha256 $statePolicyPath) -cne
            [string]$Contract.tools.statePolicy.sha256
    ) {
        throw "RUNTIME_STATE_POLICY_SHA_MISMATCH"
    }

    $allowedActions = @(
        $G2StateStaticActions + $G2StateRuntimeActions
    )
    if ($allowedActions -cnotcontains $Action) {
        throw "TOOL_ACTION_UNKNOWN action=$Action"
    }
    $isStaticAction = $G2StateStaticActions -ccontains $Action
    $defaultReceiptPath = Resolve-G2StateBoundedPath `
        -Root $RepositoryRoot `
        -RelativePath (
            [string]$Contract.toolStatePolicy.repairAuditReceiptPath
        )
    $receiptPath = if ([string]::IsNullOrWhiteSpace(
        $RepairAuditReceiptPathOverride
    )) {
        $defaultReceiptPath
    } else {
        [System.IO.Path]::GetFullPath($RepairAuditReceiptPathOverride)
    }

    switch -CaseSensitive ([string]$Contract.status) {
        $G2StatePreAuditStatus {
            Assert-G2PreAuditRepairAuditExpectations -Contract $Contract
            foreach ($toolName in @(
                "runtimeCollector",
                "runtimeFinalizer",
                "cleanupVerifier"
            )) {
                if (
                    [string]$Contract.tools.$toolName.status -cne
                        $G2StatePreAuditStatus -or
                    [string]$Contract.tools.$toolName.deviceAccess -cne
                        $G2StateCurrentDeviceAccess
                ) {
                    throw "PRE_AUDIT_TOOL_STATUS_MISMATCH $toolName"
                }
            }
            if (
                [string]$Contract.tools.statePolicy.status -cne
                    $G2StatePreAuditStatus -or
                [string]$Contract.tools.runtimeManifest.status -cne
                    $G2StatePreAuditStatus -or
                [string]$manifestState.Manifest.status -cne
                    $G2StatePreAuditStatus
            ) {
                throw "PRE_AUDIT_MANIFEST_STATUS_MISMATCH"
            }
            if (
                [bool]$Contract.authorization.qaDispatchable -ne $false -or
                [string]$Contract.authorization.activePrompt -cne "none" -or
                [bool]$Contract.authorization.contractPackageReady -ne $false -or
                [bool]$Contract.authorization.generatedExecutableQaPrompt `
                    -ne $false -or
                $null -ne $Contract.authorization.qaDispatch
            ) {
                throw "PRE_AUDIT_AUTHORIZATION_STATE_MISMATCH"
            }
            if (Test-Path -LiteralPath $receiptPath -PathType Leaf) {
                throw "PRE_AUDIT_REPAIR_AUDIT_RECEIPT_FORBIDDEN"
            }
            if (-not $isStaticAction) {
                throw "PRE_AUDIT_ACTION_FORBIDDEN action=$Action"
            }
            return [pscustomobject]@{
                Status = $G2StatePreAuditStatus
                Mode = "PRE_AUDIT_STATIC_ONLY"
                QaDispatchable = $false
                ActivePrompt = "none"
                QaPromptSha256 = $null
                AllowedActions = @($G2StateStaticActions)
                Action = $Action
                ToolsDigest = [string]$manifestState.ToolsDigest
                ManifestFiles = @($manifestState.ToolFiles)
                Receipt = $null
            }
        }
        $G2StateQaReadyStatus {
            foreach ($toolName in @(
                "runtimeCollector",
                "runtimeFinalizer",
                "cleanupVerifier"
            )) {
                if (
                    [string]$Contract.tools.$toolName.status -cne
                        $G2StateQaReadyStatus -or
                    [string]$Contract.tools.$toolName.deviceAccess -cne
                        $G2StateQaReadyDeviceAccess
                ) {
                    throw "QA_READY_TOOL_STATUS_MISMATCH $toolName"
                }
            }
            if (
                [string]$Contract.tools.statePolicy.status -cne
                    $G2StateQaReadyStatus -or
                [string]$Contract.tools.runtimeManifest.status -cne
                    $G2StateQaReadyStatus -or
                [string]$manifestState.Manifest.status -cne
                    $G2StateQaReadyStatus
            ) {
                throw "QA_READY_MANIFEST_STATUS_MISMATCH"
            }
            if (
                [bool]$Contract.authorization.qaDispatchable -ne $true -or
                [string]$Contract.authorization.activePrompt -cne
                    $G2StateQaNextRole -or
                [bool]$Contract.authorization.contractPackageReady -ne $true -or
                [bool]$Contract.authorization.generatedExecutableQaPrompt `
                    -ne $true
            ) {
                throw "QA_READY_AUTHORIZATION_STATE_MISMATCH"
            }
            if ($null -eq $Contract.authorization.qaDispatch) {
                throw "QA_READY_QA_DISPATCH_MISSING"
            }
            if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) {
                throw "QA_READY_REPAIR_AUDIT_RECEIPT_MISSING"
            }
            $receipt = Read-G2StateJsonStrict $receiptPath
            $handoffPath = Resolve-G2StateBoundedPath `
                -Root $RepositoryRoot `
                -RelativePath (
                    [string]$Contract.attemptRecovery.authoritativeLock.
                        handoffContractPath
                )
            $handoff = Read-G2StateJsonStrict $handoffPath
            if (
                [string]$handoff.contractVersion -cne
                    "HANDOFF_BOOTSTRAP_V2" -or
                [string]$handoff.evidence.deviceLockPath -cne
                    [string]$Contract.attemptRecovery.authoritativeLock.
                        deviceLockPath
            ) {
                throw "AUTHORITATIVE_LOCK_SOURCE_MISMATCH"
            }
            $lockSnapshot = Get-G2RuntimeAuthoritativeLockSnapshot `
                -DeviceLockPath (
                    [string]$Contract.attemptRecovery.authoritativeLock.
                        deviceLockPath
                )
            $receiptState = Assert-G2RepairAuditReceipt `
                -Contract $Contract `
                -Receipt $receipt `
                -ReceiptPath $receiptPath `
                -ReceiptSchemaPath $receiptSchemaPath `
                -RepositoryRoot $RepositoryRoot `
                -ManifestState $manifestState `
                -LockSnapshot $lockSnapshot
            return [pscustomobject]@{
                Status = $G2StateQaReadyStatus
                Mode = "AUDIT_PASS_PENDING_FRESH_PAD_QA"
                QaDispatchable = $true
                ActivePrompt = $G2StateQaNextRole
                QaPromptSha256 = [string]$receiptState.QaPromptSha256
                AllowedActions = @(
                    $G2StateStaticActions + $G2StateRuntimeActions
                )
                Action = $Action
                ToolsDigest = [string]$manifestState.ToolsDigest
                ManifestFiles = @($manifestState.ToolFiles)
                Receipt = $receiptState
            }
        }
        default {
            throw "TOOL_STATE_STATUS_UNKNOWN status=$($Contract.status)"
        }
    }
}
