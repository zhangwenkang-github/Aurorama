[CmdletBinding()]
param(
    [string]$ContractPath,
    [string]$RepoRoot,
    [ValidateSet("next", "padQa")]
    [string]$Prompt = "next",
    [switch]$EmitPrompt
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8Output
$OutputEncoding = $utf8Output

$validationErrors = [System.Collections.Generic.List[string]]::new()

function Add-PreflightError {
    param([Parameter(Mandatory)][string]$Code)
    $validationErrors.Add($Code)
}

function Get-ContractProperty {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory)][string]$PropertyName
    )

    if ($null -eq $ObjectValue) {
        return $null
    }

    $propertyInfo = $ObjectValue.PSObject.Properties[$PropertyName]
    if ($null -eq $propertyInfo) {
        return $null
    }

    return $propertyInfo.Value
}

function Test-NonEmptyString {
    param([AllowNull()][object]$Value)
    return ($Value -is [string] -and -not [string]::IsNullOrWhiteSpace($Value))
}

function Test-StringArray {
    param([AllowNull()][object]$Value)

    if ($Value -isnot [System.Array]) {
        return $false
    }

    foreach ($item in $Value) {
        if (-not (Test-NonEmptyString -Value $item)) {
            return $false
        }
    }

    return $true
}

function Test-OrdinalSetEqual {
    param(
        [Parameter(Mandatory)][string[]]$Actual,
        [Parameter(Mandatory)][string[]]$Expected
    )

    $actualSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $expectedSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )

    foreach ($item in $Actual) {
        if ([string]::IsNullOrWhiteSpace($item)) {
            return $false
        }
        [void]$actualSet.Add($item)
    }
    foreach ($item in $Expected) {
        [void]$expectedSet.Add($item)
    }

    return $actualSet.SetEquals($expectedSet)
}

function Test-OrdinalStringArrayEqual {
    param(
        [AllowNull()][object]$Actual,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Expected
    )

    if ($Actual -isnot [System.Array]) {
        return $false
    }

    $actualItems = @($Actual)
    if ($actualItems.Count -ne $Expected.Count) {
        return $false
    }

    for ($itemIndex = 0; $itemIndex -lt $Expected.Count; $itemIndex++) {
        if (
            $actualItems[$itemIndex] -isnot [string] -or
            $actualItems[$itemIndex] -cne $Expected[$itemIndex]
        ) {
            return $false
        }
    }

    return $true
}

function Test-ExactObjectProperties {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory)][string[]]$ExpectedProperties
    )

    if ($null -eq $ObjectValue) {
        return $false
    }

    $actualProperties = @(
        $ObjectValue.PSObject.Properties | ForEach-Object { $_.Name }
    )
    if ($actualProperties.Count -ne $ExpectedProperties.Count) {
        return $false
    }

    return Test-OrdinalSetEqual `
        -Actual ([string[]]$actualProperties) `
        -Expected $ExpectedProperties
}

function Get-QaToolsDigestSnapshot {
    param(
        [Parameter(Mandatory)][string]$ToolsRoot
    )

    $rootPath = (Resolve-Path -LiteralPath $ToolsRoot).Path
    $files = @(
        Get-ChildItem -LiteralPath $rootPath -File -Recurse -Force |
            Where-Object { $_.Name -ne 'tools.sha256' }
    )
    $byRelativePath = @{}
    foreach ($file in $files) {
        $relativePath = [System.IO.Path]::GetRelativePath(
            $rootPath,
            $file.FullName
        ).Replace('\', '/')
        $byRelativePath[$relativePath] = $file
    }

    $relativePaths = [string[]]@($byRelativePath.Keys)
    [Array]::Sort($relativePaths, [System.StringComparer]::Ordinal)
    $manifestLines = @(
        foreach ($relativePath in $relativePaths) {
            $fileSha256 = (
                Get-FileHash `
                    -LiteralPath $byRelativePath[$relativePath].FullName `
                    -Algorithm SHA256
            ).Hash.ToLowerInvariant()
            "$fileSha256  tools/$relativePath"
        }
    )
    $manifestText = if ($manifestLines.Count -gt 0) {
        ($manifestLines -join "`n") + "`n"
    }
    else {
        ''
    }
    $manifestBytes = [System.Text.UTF8Encoding]::new($false).GetBytes(
        $manifestText
    )
    $digestSha256 = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($manifestBytes)
    ).ToLowerInvariant()

    return [pscustomobject][ordered]@{
        digestSha256 = $digestSha256
        entryCount = $relativePaths.Count
        relativePaths = $relativePaths
        manifestLines = $manifestLines
        manifestText = $manifestText
    }
}

function Read-StrictUtf8LfFile {
    param(
        [Parameter(Mandatory)][string]$AbsolutePath,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    if (-not (Test-Path -LiteralPath $AbsolutePath -PathType Leaf)) {
        Add-PreflightError -Code "$ErrorPrefix-MISSING"
        return $null
    }

    $rawBytes = [System.IO.File]::ReadAllBytes($AbsolutePath)
    if (
        $rawBytes.Length -ge 3 -and
        $rawBytes[0] -eq 0xEF -and
        $rawBytes[1] -eq 0xBB -and
        $rawBytes[2] -eq 0xBF
    ) {
        Add-PreflightError -Code "$ErrorPrefix-BOM"
    }
    if ([Array]::IndexOf($rawBytes, [byte]13) -ge 0) {
        Add-PreflightError -Code "$ErrorPrefix-CRLF"
    }

    try {
        return $utf8Strict.GetString($rawBytes)
    }
    catch {
        Add-PreflightError -Code "$ErrorPrefix-NOT-UTF8"
        return $null
    }
}

function Resolve-RepoFile {
    param(
        [Parameter(Mandatory)][string]$RelativeOrAbsolutePath,
        [Parameter(Mandatory)][string]$RepositoryRootPath,
        [Parameter(Mandatory)][string]$ErrorCode
    )

    if ([string]::IsNullOrWhiteSpace($RelativeOrAbsolutePath)) {
        Add-PreflightError -Code "$ErrorCode-EMPTY"
        return $null
    }

    try {
        if ([System.IO.Path]::IsPathRooted($RelativeOrAbsolutePath)) {
            $fullPath = [System.IO.Path]::GetFullPath($RelativeOrAbsolutePath)
        }
        else {
            $fullPath = [System.IO.Path]::GetFullPath(
                (Join-Path $RepositoryRootPath $RelativeOrAbsolutePath)
            )
        }
    }
    catch {
        Add-PreflightError -Code "$ErrorCode-INVALID"
        return $null
    }

    $repositoryPrefix = $RepositoryRootPath.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar

    if (
        -not $fullPath.Equals($RepositoryRootPath, [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $fullPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    ) {
        Add-PreflightError -Code "$ErrorCode-OUTSIDE-REPO"
        return $null
    }

    return $fullPath
}

function Get-JsonPointerValue {
    param(
        [AllowNull()][object]$RootValue,
        [Parameter(Mandatory)][string]$JsonPointer
    )

    if (-not $JsonPointer.StartsWith("#/", [System.StringComparison]::Ordinal)) {
        return $null
    }

    $currentValue = $RootValue
    foreach ($rawSegment in $JsonPointer.Substring(2).Split("/")) {
        $segment = $rawSegment.Replace("~1", "/").Replace("~0", "~")
        if ($null -eq $currentValue) {
            return $null
        }
        $propertyInfo = $currentValue.PSObject.Properties[$segment]
        if ($null -eq $propertyInfo) {
            return $null
        }
        $currentValue = $propertyInfo.Value
    }

    return $currentValue
}

function Get-PromptText {
    param(
        [AllowNull()][object]$PromptValue,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    $lines = Get-ContractProperty -ObjectValue $PromptValue -PropertyName "textLines"
    if (-not (Test-StringArray -Value $lines)) {
        Add-PreflightError -Code "$ErrorPrefix-TEXT-LINES"
        return $null
    }

    return ($lines -join "`n")
}

function Assert-Tokens {
    param(
        [AllowNull()][string]$TextValue,
        [Parameter(Mandatory)][string[]]$Tokens,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    if ($null -eq $TextValue) {
        Add-PreflightError -Code "$ErrorPrefix-TEXT-MISSING"
        return
    }

    foreach ($token in $Tokens) {
        if (-not $TextValue.Contains($token, [System.StringComparison]::Ordinal)) {
            Add-PreflightError -Code ("{0}-TOKEN-{1}" -f $ErrorPrefix, $token)
        }
    }
}

$script:branchValue = "unknown"
$script:headValue = "unknown"
$script:dirtyCount = -1
$script:roleCount = 0
$script:gateCount = 0
$script:toolsEntryCount = 0
$script:toolsDigestValue = "unknown"
$script:tasksInProgressCount = 0

try {
    if ($PSVersionTable.PSVersion.Major -lt 7) {
        Add-PreflightError -Code "POWERSHELL7_REQUIRED"
    }

    if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
        $repositoryRoot = [System.IO.Path]::GetFullPath(
            (Join-Path $PSScriptRoot "..\..")
        )
    }
    else {
        $repositoryRoot = [System.IO.Path]::GetFullPath($RepoRoot)
    }

    if (-not (Test-Path -LiteralPath $repositoryRoot -PathType Container)) {
        Add-PreflightError -Code "REPO_ROOT_MISSING"
        throw "Repository root is unavailable."
    }

    if ([string]::IsNullOrWhiteSpace($ContractPath)) {
        $contractFullPath = Join-Path $PSScriptRoot "HANDOFF_BOOTSTRAP_V2.json"
    }
    elseif ([System.IO.Path]::IsPathRooted($ContractPath)) {
        $contractFullPath = [System.IO.Path]::GetFullPath($ContractPath)
    }
    else {
        $contractFullPath = [System.IO.Path]::GetFullPath(
            (Join-Path $repositoryRoot $ContractPath)
        )
    }

    $scriptText = Read-StrictUtf8LfFile `
        -AbsolutePath $PSCommandPath `
        -ErrorPrefix "PREFLIGHT-SCRIPT"
    [void]$scriptText

    $contractText = Read-StrictUtf8LfFile `
        -AbsolutePath $contractFullPath `
        -ErrorPrefix "CONTRACT"
    if ($null -eq $contractText) {
        throw "Contract cannot be read."
    }

    try {
        $contractData = $contractText | ConvertFrom-Json
    }
    catch {
        Add-PreflightError -Code "CONTRACT-JSON-INVALID"
        throw "Contract JSON is invalid."
    }

    $requiredTopLevelNames = @(
        "contractVersion",
        "schema",
        "generatedAt",
        "inputBaseline",
        "handoffFlags",
        "evidenceRoot",
        "evidence",
        "qaRoundState",
        "acceptedArtifacts",
        "qaTools",
        "devicePolicy",
        "productGates",
        "readOrder",
        "rejectedScope",
        "roles",
        "requiredGates",
        "stopConditions",
        "firstReadOnlyActions",
        "activePrompt",
        "next_prompt_path",
        "qa_prompt_path",
        "nextPromptPath",
        "qaPromptPath",
        "prompts",
        "validation"
    )
    foreach ($name in $requiredTopLevelNames) {
        if ($null -eq $contractData.PSObject.Properties[$name]) {
            Add-PreflightError -Code "TOP-LEVEL-MISSING-$name"
        }
    }
    if ($validationErrors.Count -gt 0) {
        throw "Required top-level fields are missing."
    }

    if (
        (Get-ContractProperty $contractData "contractVersion") -cne "HANDOFF_BOOTSTRAP_V2" -or
        (Get-ContractProperty $contractData "schema") -cne "aurorama.handoff-bootstrap.v2"
    ) {
        Add-PreflightError -Code "CONTRACT-IDENTITY-INVALID"
    }

    $inputBaseline = Get-ContractProperty $contractData "inputBaseline"
    $inputCommit = Get-ContractProperty $inputBaseline "commit"
    $inputBranch = Get-ContractProperty $inputBaseline "branch"
    $inputWorktree = Get-ContractProperty $inputBaseline "worktree"
    $qaV8HandoffHead = Get-ContractProperty $inputBaseline "qaV8HandoffHead"
    if (
        -not [System.IO.Path]::IsPathFullyQualified([string]$inputWorktree) -or
        -not $inputWorktree.Equals(
            $repositoryRoot,
            [System.StringComparison]::OrdinalIgnoreCase
        )
    ) {
        Add-PreflightError -Code "WORKTREE-IDENTITY-MISMATCH"
    }
    foreach ($shaValue in @($inputCommit, $qaV8HandoffHead)) {
        if ($shaValue -notmatch "^[0-9a-f]{40}$") {
            Add-PreflightError -Code "INPUT-COMMIT-INVALID"
        }
    }

    $flags = Get-ContractProperty $contractData "handoffFlags"
    $expectedFlags = [ordered]@{
        SUBAGENT_FIRST = "YES"
        ONE_PHASE_PER_FRESH_AGENT = "YES"
        NO_REUSE_SUBAGENT = "YES"
        ROOT_INTEGRATION_ONLY = "YES"
    }
    $actualFlagNames = @(
        $flags.PSObject.Properties | ForEach-Object { $_.Name }
    )
    if (-not (Test-OrdinalSetEqual `
        -Actual $actualFlagNames `
        -Expected ([string[]]$expectedFlags.Keys))) {
        Add-PreflightError -Code "HANDOFF-FLAG-SET-INVALID"
    }
    foreach ($flagName in $expectedFlags.Keys) {
        $flagValue = Get-ContractProperty $flags $flagName
        if ($flagValue -cne $expectedFlags[$flagName]) {
            Add-PreflightError -Code "HANDOFF-FLAG-INVALID-$flagName"
        }
    }

    $devicePolicy = Get-ContractProperty $contractData "devicePolicy"
    if (
        (Get-ContractProperty $devicePolicy "DEVICE_ORDER") -cne "Pad->K60" -or
        (Get-ContractProperty $devicePolicy "DEVICE_WINDOW_MINUTES") -ne 10
    ) {
        Add-PreflightError -Code "DEVICE-POLICY-DRIFT"
    }
    if ((Get-ContractProperty $devicePolicy "oldThirtyMinuteEvidenceReusable") -ne $false) {
        Add-PreflightError -Code "OLD-30-MINUTE-EVIDENCE-REUSABLE"
    }

    $devices = @(Get-ContractProperty $devicePolicy "devices")
    $expectedDevices = @(
        [pscustomobject]@{
            order = 1
            name = "Pad"
            serial = "43af8627"
            registered = $true
        },
        [pscustomobject]@{
            order = 2
            name = "K60"
            serial = "8e875894"
            registered = $false
        }
    )
    if ($devices.Count -ne $expectedDevices.Count) {
        Add-PreflightError -Code "DEVICE-COUNT-INVALID"
    }
    else {
        for ($deviceIndex = 0; $deviceIndex -lt $expectedDevices.Count; $deviceIndex++) {
            $actualDevice = $devices[$deviceIndex]
            $expectedDevice = $expectedDevices[$deviceIndex]
            foreach ($fieldName in @("order", "name", "serial", "registered")) {
                if (
                    (Get-ContractProperty $actualDevice $fieldName) -cne
                    (Get-ContractProperty $expectedDevice $fieldName)
                ) {
                    Add-PreflightError -Code "DEVICE-ENTRY-INVALID-$deviceIndex-$fieldName"
                }
            }
        }
    }
    if ((Get-ContractProperty $devicePolicy "maximumAudioGapSeconds") -ne 15) {
        Add-PreflightError -Code "MAX-AUDIO-GAP-NOT-15"
    }

    $expectedProductGates = @(
        "fresh-install-and-device-profile-identity",
        "real-audiotrack-start-completion",
        "head-middle-tail-continuity",
        "maximum-audio-gap-lte-15s",
        "foreground-service-mediasession-notification",
        "pid-lifecycle-and-resource-convergence",
        "current-run-native-java-crash-anr-inputtimeout-gate",
        "audio-focus-and-single-audio-owner",
        "voice-profiles-zf_001-zm_010",
        "cleanup-and-capture-restore"
    )
    $actualProductGates = @(
        Get-ContractProperty $contractData "productGates"
    )
    if (-not (Test-OrdinalSetEqual `
        -Actual $actualProductGates `
        -Expected $expectedProductGates)) {
        Add-PreflightError -Code "PRODUCT-GATE-SET-INVALID"
    }

    $qaRoundState = Get-ContractProperty $contractData "qaRoundState"
    $qaRoundStateProperties = @(
        "status",
        "lastQaVerdict",
        "round1IntegrationReview",
        "round2IntegrationReview",
        "independentIntegrationReview",
        "preHandoffAudit",
        "padQaPromptDispatch",
        "productFindings",
        "coreStepsExecuted",
        "padTenMinuteCoreRun",
        "k60Registration",
        "k60CoreRun",
        "cleanup",
        "padLockActiveCount"
    )
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $qaRoundState `
        -ExpectedProperties $qaRoundStateProperties)) {
        Add-PreflightError -Code "QA-ROUND-STATE-SCHEMA-INVALID"
    }
    $expectedQaRoundState = [ordered]@{
        status = "ROUND3_INTEGRATION_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT"
        lastQaVerdict = "BLOCKED_TOOL_NONCONVERGENCE"
        round1IntegrationReview = "FAIL_1_P1_2_P2_REPAIRED_CLOSED_BY_ROUND2"
        round2IntegrationReview = "FAIL_P2_PREFLIGHT_SEMANTIC_BYPASS_REPAIRED_CLOSED_BY_ROUND3_PASS"
        independentIntegrationReview = "PASS"
        preHandoffAudit = "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
        padQaPromptDispatch = "BLOCKED"
        coreStepsExecuted = 0
        padTenMinuteCoreRun = "NOT_RUN"
        k60Registration = "NOT_REGISTERED"
        k60CoreRun = "NOT_RUN"
        cleanup = "PASS_CLEANUP_AND_RESTORE"
        padLockActiveCount = 0
    }
    foreach ($stateName in $expectedQaRoundState.Keys) {
        if (
            (Get-ContractProperty $qaRoundState $stateName) -cne
            $expectedQaRoundState[$stateName]
        ) {
            Add-PreflightError -Code "QA-ROUND-STATE-MISMATCH-$stateName"
        }
    }
    $productFindings = Get-ContractProperty $qaRoundState "productFindings"
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $productFindings `
        -ExpectedProperties @("P0", "P1", "P2", "P3"))) {
        Add-PreflightError -Code "PRODUCT-FINDINGS-SCHEMA-INVALID"
    }
    foreach ($severity in @("P0", "P1", "P2", "P3")) {
        if ((Get-ContractProperty $productFindings $severity) -cne "NOT_ASSESSED") {
            Add-PreflightError -Code "PRODUCT-FINDINGS-NOT-ASSESSED-$severity"
        }
    }

    $expectedRoleIds = @(
        "ROOT_INTEGRATION",
        "HANDOFF_PROTOCOL_OWNER",
        "IMPLEMENTATION_OWNER",
        "TOOL_REPAIR_OWNER",
        "DOC_PROTOCOL_OWNER",
        "INTEGRATION_REVIEW_OWNER",
        "ROUND2_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER",
        "PRE_HANDOFF_AUDIT_OWNER",
        "PAD_QA_OWNER",
        "K60_QA_OWNER"
    )
    $roleObjects = @(Get-ContractProperty $contractData "roles")
    $script:roleCount = $roleObjects.Count
    $actualRoleIds = @(
        $roleObjects | ForEach-Object {
            [string](Get-ContractProperty $_ "id")
        }
    )
    if (-not (Test-OrdinalSetEqual `
        -Actual $actualRoleIds `
        -Expected $expectedRoleIds)) {
        Add-PreflightError -Code "ROLE-TABLE-INCOMPLETE"
    }
    if ($actualRoleIds.Count -ne $expectedRoleIds.Count) {
        Add-PreflightError -Code "ROLE-DUPLICATE-OR-EXTRA"
    }

    $requiredRoleProperties = @(
        "id",
        "kind",
        "identity",
        "freshness",
        "phaseLimit",
        "reuse",
        "allowedActions",
        "writeScope",
        "deviceAccess",
        "deliverable",
        "stopAfter"
    )
    foreach ($roleObject in $roleObjects) {
        $roleId = [string](Get-ContractProperty $roleObject "id")
        foreach ($propertyName in $requiredRoleProperties) {
            if ($null -eq $roleObject.PSObject.Properties[$propertyName]) {
                Add-PreflightError -Code "ROLE-PROPERTY-MISSING-$roleId-$propertyName"
            }
        }
        if (-not (Test-StringArray -Value (Get-ContractProperty $roleObject "allowedActions"))) {
            Add-PreflightError -Code "ROLE-ALLOWED-INVALID-$roleId"
        }
        $writeScopeValue = $roleObject.PSObject.Properties["writeScope"].Value
        if ($writeScopeValue -isnot [System.Array]) {
            Add-PreflightError -Code "ROLE-WRITE-SCOPE-INVALID-$roleId"
        }
        if ($roleId -eq "ROOT_INTEGRATION") {
            if (
                (Get-ContractProperty $roleObject "freshness") -cne "THREAD_BOUND" -or
                (Get-ContractProperty $roleObject "phaseLimit") -cne "ROOT_INTEGRATION_ONLY" -or
                (Get-ContractProperty $roleObject "deviceAccess") -cne "FORBIDDEN"
            ) {
                Add-PreflightError -Code "ROOT-ROLE-BOUNDARY-INVALID"
            }
        }
        elseif (
            (Get-ContractProperty $roleObject "freshness") -cne "FRESH_PER_PHASE" -or
            (Get-ContractProperty $roleObject "phaseLimit") -cne "ONE" -or
            (Get-ContractProperty $roleObject "reuse") -cne "FORBIDDEN"
        ) {
            Add-PreflightError -Code "FRESH-ROLE-BOUNDARY-INVALID-$roleId"
        }
    }

    $activePromptName = [string](Get-ContractProperty $contractData "activePrompt")
    $allowedPromptNames = @("toolRepair", "padQa")
    if ($activePromptName -notin $allowedPromptNames) {
        Add-PreflightError -Code "ACTIVE-PROMPT-UNKNOWN"
    }

    $canonicalPreflightPath = [System.IO.Path]::GetFullPath($PSCommandPath)
    $quotedCanonicalPreflightPath = "'" + $canonicalPreflightPath.Replace("'", "''") + "'"
    $expectedPromptCommands = @{
        toolRepair = [string[]]@(
            "pwsh",
            "-NoProfile",
            "-File",
            $canonicalPreflightPath,
            "-EmitPrompt"
        )
        padQa = [string[]]@(
            "pwsh",
            "-NoProfile",
            "-File",
            $canonicalPreflightPath,
            "-Prompt",
            "padQa",
            "-EmitPrompt"
        )
    }
    $expectedPromptActionTexts = @{
        toolRepair = "pwsh -NoProfile -File $quotedCanonicalPreflightPath -EmitPrompt"
        padQa = "pwsh -NoProfile -File $quotedCanonicalPreflightPath -Prompt padQa -EmitPrompt"
    }

    $promptsObject = Get-ContractProperty $contractData "prompts"
    foreach ($promptName in $allowedPromptNames) {
        $promptObject = Get-ContractProperty $promptsObject $promptName
        if ($null -eq $promptObject) {
            Add-PreflightError -Code "PROMPT-MISSING-$promptName"
            continue
        }
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $promptObject `
            -ExpectedProperties @(
                "roleId",
                "status",
                "dispatchable",
                "actionClass",
                "command",
                "firstAction",
                "requiredTokens",
                "textLines"
            ))) {
            Add-PreflightError -Code "PROMPT-SCHEMA-INVALID-$promptName"
        }
        $promptStatus = Get-ContractProperty $promptObject "status"
        $allowedPromptStatuses = @(
            "ACTIVE",
            "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
        )
        if ($promptStatus -notin $allowedPromptStatuses) {
            Add-PreflightError -Code "PROMPT-STATUS-UNKNOWN-$promptName"
        }
        $promptRoleId = [string](Get-ContractProperty $promptObject "roleId")
        $expectedPromptRoleId = if ($promptName -eq "toolRepair") {
            "PRE_HANDOFF_AUDIT_OWNER"
        }
        else {
            "PAD_QA_OWNER"
        }
        if ($promptRoleId -cne $expectedPromptRoleId) {
            Add-PreflightError -Code "PROMPT-ROLE-MISMATCH-$promptName"
        }
        $promptDispatchable = Get-ContractProperty $promptObject "dispatchable"
        $expectedPromptDispatchable = $promptName -eq "toolRepair"
        if ($promptDispatchable -ne $expectedPromptDispatchable) {
            Add-PreflightError -Code "PROMPT-DISPATCH-BOUNDARY-INVALID-$promptName"
        }
        $expectedPromptStatus = if ($promptName -eq "toolRepair") {
            "ACTIVE"
        }
        else {
            "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
        }
        if ($promptStatus -cne $expectedPromptStatus) {
            Add-PreflightError -Code "PROMPT-STATUS-MISMATCH-$promptName"
        }
        if (
            (Get-ContractProperty $promptObject "actionClass") -cne
            "READ_ONLY_PREFLIGHT"
        ) {
            Add-PreflightError -Code "PROMPT-ACTION-CLASS-INVALID-$promptName"
        }
        if (-not (Test-OrdinalStringArrayEqual `
            -Actual (Get-ContractProperty $promptObject "command") `
            -Expected $expectedPromptCommands[$promptName])) {
            Add-PreflightError -Code "PROMPT-COMMAND-INVALID-$promptName"
        }
        if (
            (Get-ContractProperty $promptObject "firstAction") -cne
            $expectedPromptActionTexts[$promptName]
        ) {
            Add-PreflightError -Code "PROMPT-FIRST-ACTION-TEXT-INVALID-$promptName"
        }
        $promptText = Get-PromptText `
            -PromptValue $promptObject `
            -ErrorPrefix "PROMPT-$promptName"
        if ($null -ne $promptText) {
            $expectedActionLead = (
                "第一条只读动作必须实际执行：" +
                $expectedPromptActionTexts[$promptName]
            )
            $promptLines = @(Get-ContractProperty $promptObject "textLines")
            if (
                $promptLines.Count -lt 3 -or
                $promptLines[2] -isnot [string] -or
                -not $promptLines[2].StartsWith(
                    $expectedActionLead,
                    [System.StringComparison]::Ordinal
                )
            ) {
                Add-PreflightError -Code "PROMPT-FIRST-ACTION-LINE-INVALID-$promptName"
            }
            elseif (
                $promptLines[2].Length -le $expectedActionLead.Length -or
                $promptLines[2][$expectedActionLead.Length] -cne [char]"。"
            ) {
                Add-PreflightError -Code "PROMPT-FIRST-ACTION-TRAILING-ARGUMENT-$promptName"
            }
            $pwshCommandCount = [regex]::Matches(
                $promptText,
                "(?i)(?<![A-Za-z0-9_])pwsh(?=\s)"
            ).Count
            if ($pwshCommandCount -ne 1) {
                Add-PreflightError -Code "PROMPT-COMMAND-COUNT-NOT-ONE-$promptName"
            }
        }
        $requiredPromptTokens = @(
            Get-ContractProperty $promptObject "requiredTokens"
        )
        if (-not (Test-StringArray -Value $requiredPromptTokens)) {
            Add-PreflightError -Code "PROMPT-TOKENS-INVALID-$promptName"
        }
        else {
            Assert-Tokens `
                -TextValue $promptText `
                -Tokens $requiredPromptTokens `
                -ErrorPrefix "PROMPT-$promptName"
        }
    }
    $mandatoryPromptTokens = @(
        "SUBAGENT_FIRST=YES",
        "ONE_PHASE_PER_FRESH_AGENT=YES",
        "NO_REUSE_SUBAGENT=YES",
        "ROOT_INTEGRATION_ONLY=YES",
        "DEVICE_WINDOW_MINUTES=10",
        "DEVICE_ORDER=Pad->K60",
        "READ_ONLY_PREFLIGHT",
        "BLOCKED"
    )
    foreach ($promptName in $allowedPromptNames) {
        $promptObject = Get-ContractProperty $promptsObject $promptName
        $promptText = Get-PromptText `
            -PromptValue $promptObject `
            -ErrorPrefix "MANDATORY-PROMPT-$promptName"
        Assert-Tokens `
            -TextValue $promptText `
            -Tokens $mandatoryPromptTokens `
            -ErrorPrefix "MANDATORY-PROMPT-$promptName"
    }
    $padQaPromptText = Get-PromptText `
        -PromptValue (Get-ContractProperty $promptsObject "padQa") `
        -ErrorPrefix "MANDATORY-PAD-QA-PROMPT"
    Assert-Tokens `
        -TextValue $padQaPromptText `
        -Tokens @(
            "qa-v9",
            "43af8627",
            "8e875894",
            "AudioTrack",
            "15 秒",
            "ApplicationExitInfo",
            "cleanup",
            "ACTIVE=0",
            "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4"
        ) `
        -ErrorPrefix "MANDATORY-PAD-QA-PROMPT"
    $toolRepairPrompt = Get-ContractProperty $promptsObject "toolRepair"
    if ((Get-ContractProperty $toolRepairPrompt "status") -cne "ACTIVE") {
        Add-PreflightError -Code "TOOL-REPAIR-PROMPT-NOT-ACTIVE"
    }
    $padQaPrompt = Get-ContractProperty $promptsObject "padQa"
    if (
        (Get-ContractProperty $padQaPrompt "status") -cne
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    ) {
        Add-PreflightError -Code "PAD-QA-PROMPT-DISPATCH-BOUNDARY-INVALID"
    }

    $selectedPromptName = if ($Prompt -eq "next") {
        $activePromptName
    }
    else {
        $Prompt
    }
    if ($selectedPromptName -cne $activePromptName) {
        Add-PreflightError -Code "PROMPT-NOT-ACTIVE"
    }

    $nextPromptPath = [string](Get-ContractProperty $contractData "nextPromptPath")
    $qaPromptPath = [string](Get-ContractProperty $contractData "qaPromptPath")
    $snakeNextPromptPath = [string](Get-ContractProperty $contractData "next_prompt_path")
    $snakeQaPromptPath = [string](Get-ContractProperty $contractData "qa_prompt_path")
    if ($snakeNextPromptPath -cne $nextPromptPath) {
        Add-PreflightError -Code "NEXT-PROMPT-PATH-MISMATCH"
    }
    if ($snakeQaPromptPath -cne $qaPromptPath) {
        Add-PreflightError -Code "QA-PROMPT-PATH-MISMATCH"
    }
    foreach ($promptPointerPair in @(
        @{
            Name = "NEXT"
            Pointer = $nextPromptPath
            ExpectedSuffix = "/prompts/$activePromptName/textLines"
        },
        @{
            Name = "QA"
            Pointer = $qaPromptPath
            ExpectedSuffix = "/prompts/padQa/textLines"
        }
    )) {
        $pointerValue = $promptPointerPair.Pointer
        if ($pointerValue -notmatch "^([^#]+)#(/.*)$") {
            Add-PreflightError -Code "PROMPT-POINTER-INVALID-$($promptPointerPair.Name)"
            continue
        }
        $pointerBase = $Matches[1]
        $pointerFragment = $Matches[2]
        if (-not $pointerFragment.EndsWith(
            $promptPointerPair.ExpectedSuffix,
            [System.StringComparison]::Ordinal
        )) {
            Add-PreflightError -Code "PROMPT-POINTER-SUFFIX-INVALID-$($promptPointerPair.Name)"
        }
        $pointerFile = Resolve-RepoFile `
            -RelativeOrAbsolutePath $pointerBase `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "PROMPT-POINTER-FILE-$($promptPointerPair.Name)"
        if ($null -ne $pointerFile -and -not (Test-Path -LiteralPath $pointerFile -PathType Leaf)) {
            Add-PreflightError -Code "PROMPT-POINTER-FILE-MISSING-$($promptPointerPair.Name)"
        }
        $pointerValueFromContract = Get-JsonPointerValue `
            -RootValue $contractData `
            -JsonPointer ("#" + $pointerFragment)
        if (-not (Test-StringArray -Value $pointerValueFromContract)) {
            Add-PreflightError -Code "PROMPT-POINTER-TARGET-INVALID-$($promptPointerPair.Name)"
        }
    }

    $firstReadOnlyActions = Get-ContractProperty $contractData "firstReadOnlyActions"
    foreach ($promptName in @("toolRepair", "padQa")) {
        $actionList = @(Get-ContractProperty $firstReadOnlyActions $promptName)
        if (-not (Test-StringArray -Value $actionList) -or $actionList.Count -lt 2) {
            Add-PreflightError -Code "FIRST-ACTION-INVALID-$promptName"
            continue
        }
        if ($actionList[0] -cne $expectedPromptActionTexts[$promptName]) {
            Add-PreflightError -Code "FIRST-ACTION-NOT-CANONICAL-$promptName"
        }
        if (
            $promptName -eq "toolRepair" -and
            -not $actionList[1].Contains(
                "PRE_HANDOFF_AUDIT_OWNER",
                [System.StringComparison]::Ordinal
            )
        ) {
            Add-PreflightError -Code "FIRST-ACTION-NEXT-ROLE-INVALID"
        }
    }

    $readOrder = @(Get-ContractProperty $contractData "readOrder")
    if ($readOrder.Count -eq 0) {
        Add-PreflightError -Code "READ-ORDER-EMPTY"
    }
    $seenReadOrderPaths = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $expectedReadOrderNumber = 1
    foreach ($readEntry in $readOrder) {
        if ((Get-ContractProperty $readEntry "order") -ne $expectedReadOrderNumber) {
            Add-PreflightError -Code "READ-ORDER-SEQUENCE-INVALID"
        }
        $expectedReadOrderNumber++
        $readPath = [string](Get-ContractProperty $readEntry "path")
        $readSection = Get-ContractProperty $readEntry "section"
        if (
            -not (Test-NonEmptyString $readSection) -or
            -not $seenReadOrderPaths.Add($readPath)
        ) {
            Add-PreflightError -Code "READ-ORDER-ENTRY-INVALID"
        }
        $readFullPath = Resolve-RepoFile `
            -RelativeOrAbsolutePath $readPath `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "READ-ORDER-PATH"
        if ($null -ne $readFullPath -and -not (Test-Path -LiteralPath $readFullPath -PathType Leaf)) {
            Add-PreflightError -Code "READ-ORDER-PATH-MISSING"
        }
    }

    $rejectedScope = Get-ContractProperty $contractData "rejectedScope"
    foreach ($scopeName in @("readDenied", "executionDenied", "evidenceReuseDenied")) {
        if (-not (Test-StringArray -Value (Get-ContractProperty $rejectedScope $scopeName))) {
            Add-PreflightError -Code "REJECTED-SCOPE-INVALID-$scopeName"
        }
    }

    $requiredGateContainer = Get-ContractProperty $contractData "requiredGates"
    $expectedRequiredGateIds = @(
        "PREFLIGHT_PASS",
        "TOOL_NONCONVERGENCE_REPAIRED",
        "CONTRACT_DIGEST_REFRESHED",
        "INDEPENDENT_INTEGRATION_REVIEW_PASS",
        "PRE_HANDOFF_AUDIT_PASS",
        "QA_DISPATCHABLE"
    )
    $expectedGateOwners = @(
        "ROOT_INTEGRATION",
        "TOOL_REPAIR_OWNER",
        "DOC_PROTOCOL_OWNER",
        "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER",
        "PRE_HANDOFF_AUDIT_OWNER",
        "PAD_QA_OWNER"
    )
    $expectedGateRequirements = @{
        PREFLIGHT_PASS = [string[]]@()
        TOOL_NONCONVERGENCE_REPAIRED = [string[]]@("PREFLIGHT_PASS")
        CONTRACT_DIGEST_REFRESHED = [string[]]@(
            "TOOL_NONCONVERGENCE_REPAIRED"
        )
        INDEPENDENT_INTEGRATION_REVIEW_PASS = [string[]]@(
            "CONTRACT_DIGEST_REFRESHED"
        )
        PRE_HANDOFF_AUDIT_PASS = [string[]]@(
            "INDEPENDENT_INTEGRATION_REVIEW_PASS"
        )
        QA_DISPATCHABLE = [string[]]@("PRE_HANDOFF_AUDIT_PASS")
    }
    $expectedGateStatuses = @(
        "PASS",
        "PASS",
        "PASS",
        "PASS",
        "BLOCKED",
        "BLOCKED"
    )
    $allowedGateStatuses = @("PASS", "NOT_RUN", "BLOCKED")

    $gateContainersValid = (
        (Test-ExactObjectProperties `
            -ObjectValue $requiredGateContainer `
            -ExpectedProperties @("lifecycleVersion", "lifecycle")) -and
        (Get-ContractProperty $requiredGateContainer "lifecycleVersion") -ceq
        "AURORAMA_HANDOFF_GATE_ORDER_V1"
    )
    if (-not $gateContainersValid) {
        Add-PreflightError -Code "REQUIRED-GATE-CONTAINER-INVALID"
    }

    $gateLifecycleValue = Get-ContractProperty $requiredGateContainer "lifecycle"
    if ($gateLifecycleValue -isnot [System.Array]) {
        Add-PreflightError -Code "REQUIRED-GATE-LIFECYCLE-INVALID"
        $gateObjects = @()
    }
    else {
        $gateObjects = @($gateLifecycleValue)
    }
    $script:gateCount = $gateObjects.Count
    $actualRequiredGateIds = @(
        $gateObjects | ForEach-Object {
            [string](Get-ContractProperty $_ "id")
        }
    )
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual $actualRequiredGateIds `
        -Expected $expectedRequiredGateIds)) {
        Add-PreflightError -Code "REQUIRED-GATE-LIFECYCLE-ORDER-INVALID"
    }
    if ($actualRequiredGateIds.Count -ne $expectedRequiredGateIds.Count) {
        Add-PreflightError -Code "REQUIRED-GATE-COUNT-INVALID"
    }
    $uniqueActualGateIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($actualGateId in $actualRequiredGateIds) {
        if (-not $uniqueActualGateIds.Add($actualGateId)) {
            Add-PreflightError -Code "REQUIRED-GATE-DUPLICATE"
        }
    }

    $priorGateIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $gateStatusById = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::Ordinal
    )
    for ($gateIndex = 0; $gateIndex -lt $gateObjects.Count; $gateIndex++) {
        $gateObject = $gateObjects[$gateIndex]
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $gateObject `
            -ExpectedProperties @("id", "owner", "requiredBefore", "status"))) {
            Add-PreflightError -Code "REQUIRED-GATE-SCHEMA-INVALID-$gateIndex"
        }

        $gateId = [string](Get-ContractProperty $gateObject "id")
        $gateOwner = [string](Get-ContractProperty $gateObject "owner")
        $gateStatus = [string](Get-ContractProperty $gateObject "status")
        $gateRequirementsProperty = $gateObject.PSObject.Properties["requiredBefore"]
        $gateRequirementsValue = [string[]]@()
        if ($null -ne $gateRequirementsProperty) {
            $gateRequirementsValue = [string[]]$gateRequirementsProperty.Value
        }

        if ($gateIndex -lt $expectedRequiredGateIds.Count) {
            if ($gateId -cne $expectedRequiredGateIds[$gateIndex]) {
                Add-PreflightError -Code "REQUIRED-GATE-ID-ORDER-INVALID-$gateIndex"
            }
            if ($gateOwner -cne $expectedGateOwners[$gateIndex]) {
                Add-PreflightError -Code "REQUIRED-GATE-OWNER-INVALID-$gateId"
            }
            if (-not (Test-OrdinalStringArrayEqual `
                -Actual $gateRequirementsValue `
                -Expected ([string[]]($expectedGateRequirements[$gateId])))) {
                Add-PreflightError -Code "REQUIRED-GATE-REQUIRED-BEFORE-INVALID-$gateId"
            }
            if ($gateStatus -cne $expectedGateStatuses[$gateIndex]) {
                Add-PreflightError -Code "REQUIRED-GATE-STATUS-INVALID-$gateId"
            }
        }

        if ($gateStatus -cnotin $allowedGateStatuses) {
            Add-PreflightError -Code "REQUIRED-GATE-STATUS-UNKNOWN-$gateId"
        }
        if ($gateRequirementsValue -is [System.Array]) {
            foreach ($prerequisiteId in @($gateRequirementsValue)) {
                if (-not $priorGateIds.Contains([string]$prerequisiteId)) {
                    Add-PreflightError -Code "REQUIRED-GATE-DEPENDENCY-NOT-PRIOR-$gateId"
                    continue
                }
                if (
                    $gateStatus -ceq "PASS" -and
                    -not $gateStatusById.ContainsKey([string]$prerequisiteId)
                ) {
                    Add-PreflightError -Code "REQUIRED-GATE-PASS-DEPENDENCY-MISSING-$gateId"
                }
                elseif (
                    $gateStatus -ceq "PASS" -and
                    $gateStatusById[[string]$prerequisiteId] -cne "PASS"
                ) {
                    Add-PreflightError -Code "REQUIRED-GATE-PASS-BEFORE-PREREQUISITE-$gateId"
                }
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($gateId)) {
            [void]$priorGateIds.Add($gateId)
            $gateStatusById[$gateId] = $gateStatus
        }
    }
    $preHandoffAuditStateValue = [string](
        Get-ContractProperty $qaRoundState "preHandoffAudit"
    )
    if (
        $preHandoffAuditStateValue -ceq "PASS" -or
        (
            $gateStatusById.ContainsKey("PRE_HANDOFF_AUDIT_PASS") -and
            $gateStatusById["PRE_HANDOFF_AUDIT_PASS"] -ceq "PASS"
        )
    ) {
        Add-PreflightError -Code "AUDIT-PASS-WITHOUT-ALLOWED-AUDIT-PHASE"
    }
    if (
        $gateStatusById.ContainsKey("QA_DISPATCHABLE") -and
        $gateStatusById["QA_DISPATCHABLE"] -ceq "PASS" -and
        (
            -not $gateStatusById.ContainsKey("PRE_HANDOFF_AUDIT_PASS") -or
            $gateStatusById["PRE_HANDOFF_AUDIT_PASS"] -cne "PASS"
        )
    ) {
        Add-PreflightError -Code "QA-DISPATCHABLE-BEFORE-AUDIT-PASS"
    }

    $stopConditionValues = @(
        Get-ContractProperty $contractData "stopConditions"
    )
    $minimumStopConditions = @(
        "PREFLIGHT_FAIL_CLOSED",
        "DEVICE_ORDER_WINDOW_SERIAL_OR_LOCK_DRIFT",
        "SHARED_WRITE_OVERLAP_OR_SAME_AGENT_REUSE",
        "TEN_MINUTE_WINDOW_EXTENSION_REQUEST",
        "K60_ATTEMPT_BEFORE_PAD_CORE_PASS",
        "OLD_EVIDENCE_OR_INSTALL_CLEANUP_USED_AS_PRODUCT_PASS"
    )
    foreach ($stopCondition in $minimumStopConditions) {
        if ($stopCondition -cnotin $stopConditionValues) {
            Add-PreflightError -Code "STOP-CONDITION-MISSING-$stopCondition"
        }
    }

    $evidence = Get-ContractProperty $contractData "evidence"
    $evidenceRootValue = [string](Get-ContractProperty $contractData "evidenceRoot")
    if ($evidenceRootValue -cne [string](Get-ContractProperty $evidence "currentRoot")) {
        Add-PreflightError -Code "EVIDENCE-ROOT-MISMATCH"
    }
    $currentEvidenceRoot = Resolve-RepoFile `
        -RelativeOrAbsolutePath ([string](Get-ContractProperty $evidence "currentRoot")) `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "CURRENT-EVIDENCE-ROOT"
    if ($null -ne $currentEvidenceRoot -and -not (Test-Path -LiteralPath $currentEvidenceRoot -PathType Container)) {
        Add-PreflightError -Code "CURRENT-EVIDENCE-ROOT-MISSING"
    }
    $nextPadRoot = Resolve-RepoFile `
        -RelativeOrAbsolutePath ([string](Get-ContractProperty $evidence "nextPadRoot")) `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "NEXT-PAD-EVIDENCE-ROOT"
    if ($null -ne $nextPadRoot -and (Test-Path -LiteralPath $nextPadRoot)) {
        $nextPadChildren = @(Get-ChildItem -LiteralPath $nextPadRoot -Force)
        if ($nextPadChildren.Count -gt 0) {
            Add-PreflightError -Code "NEXT-PAD-EVIDENCE-ROOT-REUSED"
        }
    }
    $deviceLockPath = [string](Get-ContractProperty $evidence "deviceLockPath")
    if (-not (Test-Path -LiteralPath $deviceLockPath -PathType Leaf)) {
        Add-PreflightError -Code "DEVICE-LOCK-MISSING"
    }

    $acceptedArtifacts = Get-ContractProperty $contractData "acceptedArtifacts"
    $artifactFacts = [ordered]@{}
    foreach ($artifactName in @("main", "peer")) {
        $artifact = Get-ContractProperty $acceptedArtifacts $artifactName
        $artifactPath = [string](Get-ContractProperty $artifact "path")
        $artifactSha = [string](Get-ContractProperty $artifact "sha256")
        $artifactBytes = Get-ContractProperty $artifact "bytes"
        if ($artifactSha -notmatch "^[0-9a-f]{64}$") {
            Add-PreflightError -Code "ARTIFACT-SHA-INVALID-$artifactName"
        }
        $artifactFullPath = Resolve-RepoFile `
            -RelativeOrAbsolutePath $artifactPath `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "ARTIFACT-$artifactName"
        if ($null -eq $artifactFullPath -or -not (Test-Path -LiteralPath $artifactFullPath -PathType Leaf)) {
            Add-PreflightError -Code "ARTIFACT-MISSING-$artifactName"
            continue
        }
        $artifactFile = Get-Item -LiteralPath $artifactFullPath
        if ($artifactFile.Length -ne [long]$artifactBytes) {
            Add-PreflightError -Code "ARTIFACT-SIZE-MISMATCH-$artifactName"
        }
        $actualArtifactSha = (Get-FileHash -LiteralPath $artifactFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualArtifactSha -cne $artifactSha) {
            Add-PreflightError -Code "ARTIFACT-SHA-MISMATCH-$artifactName"
        }
        $artifactFacts[$artifactName] = [ordered]@{
            bytes = $artifactFile.Length
            sha256 = $actualArtifactSha
        }
    }

    $qaTools = Get-ContractProperty $contractData "qaTools"
    $qaToolsRootPath = [string](Get-ContractProperty $qaTools "root")
    $legacyToolsManifestPath = [string](Get-ContractProperty $qaTools "manifestPath")
    $canonicalManifestLines = @(
        Get-ContractProperty $qaTools "canonicalManifestLines"
    )
    $expectedToolsDigest = [string](Get-ContractProperty $qaTools "digestSha256")
    $expectedToolsEntryCount = Get-ContractProperty $qaTools "entryCount"
    $expectedToolsManifestBytes = Get-ContractProperty $qaTools "manifestBytes"
    $expectedCanonicalManifestSha256 = [string](
        Get-ContractProperty $qaTools "canonicalManifestSha256"
    )
    $expectedToolsSortOrder = [string](Get-ContractProperty $qaTools "sortOrder")
    $expectedToolsSelfExcluded = Get-ContractProperty $qaTools "selfExcluded"
    $legacyManifestSha256 = [string](
        Get-ContractProperty $qaTools "legacyManifestSha256"
    )
    $legacyManifestState = [string](
        Get-ContractProperty $qaTools "legacyManifestState"
    )
    if ($expectedToolsDigest -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "TOOLS-DIGEST-INVALID"
    }
    if ($expectedToolsEntryCount -notmatch "^\d+$" -or [int]$expectedToolsEntryCount -lt 1) {
        Add-PreflightError -Code "TOOLS-ENTRY-COUNT-INVALID"
    }
    elseif ([int]$expectedToolsEntryCount -ne 31) {
        Add-PreflightError -Code "TOOLS-ENTRY-COUNT-NOT-31"
    }
    if (
        $expectedToolsManifestBytes -notmatch "^\d+$" -or
        [int]$expectedToolsManifestBytes -lt 1
    ) {
        Add-PreflightError -Code "TOOLS-MANIFEST-BYTES-INVALID"
    }
    elseif ([int]$expectedToolsManifestBytes -ne 2984) {
        Add-PreflightError -Code "TOOLS-MANIFEST-BYTES-NOT-2984"
    }
    if ($expectedCanonicalManifestSha256 -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-SHA-INVALID"
    }
    elseif ($expectedCanonicalManifestSha256 -cne $expectedToolsDigest) {
        Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-SHA-DIGEST-MISMATCH"
    }
    if ($expectedToolsSortOrder -cne "relative path ordinal") {
        Add-PreflightError -Code "TOOLS-SORT-ORDER-INVALID"
    }
    if ($expectedToolsSelfExcluded -ne $true) {
        Add-PreflightError -Code "TOOLS-SELF-EXCLUSION-INVALID"
    }
    if (-not (Test-StringArray -Value $canonicalManifestLines)) {
        Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-INVALID"
    }
    if ($legacyManifestSha256 -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-SHA-INVALID"
    }
    elseif (
        $legacyManifestSha256 -cne
        "d8e98a90b250bafb1bb48d62f315041fd0f67653d8d81c4650aca9fb617d0bfb"
    ) {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-SHA-DRIFT"
    }
    if (
        $legacyManifestState -cne
        "STALE_21_ENTRY_PRE_REPAIR_SNAPSHOT_HISTORY_ONLY"
    ) {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-STATE-INVALID"
    }
    $supersededContractDigest = [string](
        Get-ContractProperty $qaTools "supersededContractDigestSha256"
    )
    $supersededContractDigestState = [string](
        Get-ContractProperty $qaTools "supersededContractDigestState"
    )
    if ($supersededContractDigest -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "SUPERSEDED-CONTRACT-DIGEST-INVALID"
    }
    elseif (
        $supersededContractDigest -cne
        "a7e5def25343bb96c15b76398b421115de60f40824698d67a682f3c4b7908901"
    ) {
        Add-PreflightError -Code "SUPERSEDED-CONTRACT-DIGEST-DRIFT"
    }
    if (
        $supersededContractDigestState -cne
        "HISTORICAL_ROUND1_DIGEST_NOT_CURRENT_AUTHORITY"
    ) {
        Add-PreflightError -Code "SUPERSEDED-CONTRACT-DIGEST-STATE-INVALID"
    }
    $repairEvidence = Get-ContractProperty $qaTools "repairEvidence"
    if (
        (Get-ContractProperty $repairEvidence "round1ReviewVerdict") -cne "FAIL" -or
        (Get-ContractProperty $repairEvidence "round1Findings") -cne "1_P1_2_P2" -or
        (Get-ContractProperty $repairEvidence "staticSelfTest") -cne "25/25" -or
        (Get-ContractProperty $repairEvidence "testsBefore") -ne 21 -or
        (Get-ContractProperty $repairEvidence "testsAfter") -ne 25 -or
        (Get-ContractProperty $repairEvidence "regressionsAdded") -ne 4 -or
        (Get-ContractProperty $repairEvidence "independentRound2Review") -cne
        "FAIL_1_P2_REPAIRED" -or
        (Get-ContractProperty $repairEvidence "independentRound3Review") -cne "PASS"
    ) {
        Add-PreflightError -Code "REPAIR-EVIDENCE-SUMMARY-INVALID"
    }
    $closedRound1Findings = @(Get-ContractProperty $repairEvidence "findings")
    if ($closedRound1Findings.Count -ne 3) {
        Add-PreflightError -Code "ROUND1-FINDINGS-NOT-CLOSED"
    }
    else {
        foreach ($closedFinding in $closedRound1Findings) {
            if (
                (Get-ContractProperty $closedFinding "status") -cne
                "CLOSED_BY_ROUND2_REVIEW"
            ) {
                Add-PreflightError -Code "ROUND1-FINDING-STATUS-INVALID"
            }
        }
    }
    $round2Findings = @(Get-ContractProperty $repairEvidence "round2Findings")
    if (
        $round2Findings.Count -ne 1 -or
        (Get-ContractProperty $round2Findings[0] "id") -cne "ROUND2-P2-01" -or
        (Get-ContractProperty $round2Findings[0] "severity") -cne "P2" -or
        (Get-ContractProperty $round2Findings[0] "status") -cne
        "CLOSED_BY_ROUND3_REVIEW_PASS"
    ) {
        Add-PreflightError -Code "ROUND2-P2-EVIDENCE-INVALID"
    }
    $round3ReviewEvidence = Get-ContractProperty `
        $repairEvidence `
        "round3ReviewEvidence"
    if (
        (Get-ContractProperty $round3ReviewEvidence "verdict") -cne "PASS" -or
        (Get-ContractProperty $round3ReviewEvidence "owner") -cne
        "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER" -or
        (Get-ContractProperty $round3ReviewEvidence "gitBoundary") -cne "PASS" -or
        (Get-ContractProperty $round3ReviewEvidence "toolsEntryCount") -ne 31 -or
        (Get-ContractProperty $round3ReviewEvidence "toolsManifestBytes") -ne 2984 -or
        (Get-ContractProperty $round3ReviewEvidence "toolsDigestSha256") -cne
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4" -or
        (Get-ContractProperty $round3ReviewEvidence "runTests") -cne "25/25" -or
        (Get-ContractProperty $round3ReviewEvidence "selfTest") -cne
        "AST_31_UTF8_31_PASS" -or
        (Get-ContractProperty $round3ReviewEvidence "contractTests") -cne "16/16" -or
        (Get-ContractProperty $round3ReviewEvidence "forgedContractCases") -cne
        "15_EXIT_2" -or
        (Get-ContractProperty $round3ReviewEvidence "qaV9DryRun") -cne
        "NO_TOOLS_MANIFEST_HASH_OR_MTIME_CHANGE" -or
        (Get-ContractProperty $round3ReviewEvidence "productP0P3") -cne
        "NOT_ASSESSED" -or
        (Get-ContractProperty $round3ReviewEvidence "k60") -cne "NOT_RUN"
    ) {
        Add-PreflightError -Code "ROUND3-REVIEW-EVIDENCE-INVALID"
    }
    if (
        (Get-ContractProperty $qaTools "inputDigestFrozen") -ne $true -or
        (Get-ContractProperty $qaTools "refreshRequiredAfterAnyToolChange") -ne $true -or
        (Get-ContractProperty $qaTools "executeQaWithUnrefreshedDigest") -cne "FORBIDDEN"
    ) {
        Add-PreflightError -Code "TOOLS-DIGEST-FREEZE-POLICY-INVALID"
    }

    $qaToolsRootFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $qaToolsRootPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "QA-TOOLS-ROOT"
    if ($null -eq $qaToolsRootFullPath -or -not (Test-Path -LiteralPath $qaToolsRootFullPath -PathType Container)) {
        Add-PreflightError -Code "QA-TOOLS-ROOT-MISSING"
    }

    $legacyToolsManifestFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $legacyToolsManifestPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "LEGACY-TOOLS-MANIFEST"
    if (
        $null -eq $legacyToolsManifestFullPath -or
        -not (Test-Path -LiteralPath $legacyToolsManifestFullPath -PathType Leaf)
    ) {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-MISSING"
    }
    else {
        $actualCanonicalManifestSha256 = (
            Get-FileHash `
                -LiteralPath $legacyToolsManifestFullPath `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        if ($actualCanonicalManifestSha256 -cne $expectedCanonicalManifestSha256) {
            Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-SHA-MISMATCH"
        }
        $actualCanonicalManifestBytes = (
            Get-Item -LiteralPath $legacyToolsManifestFullPath
        ).Length
        if ($actualCanonicalManifestBytes -ne [int]$expectedToolsManifestBytes) {
            Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-BYTES-MISMATCH"
        }
    }

    if (
        $null -ne $qaToolsRootFullPath -and
        (Test-Path -LiteralPath $qaToolsRootFullPath -PathType Container) -and
        (Test-StringArray -Value $canonicalManifestLines)
    ) {
        $manifestRelativePaths = [System.Collections.Generic.List[string]]::new()
        $manifestPathSet = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        $manifestShaByPath = @{}
        $toolsRootPrefix = $qaToolsRootFullPath.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        ) + [System.IO.Path]::DirectorySeparatorChar

        foreach ($manifestLine in $canonicalManifestLines) {
            if ($manifestLine -notmatch "^([0-9a-f]{64})  tools/([^/\\]+(?:/[^/\\]+)*)$") {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-LINE-INVALID"
                continue
            }
            $manifestSha = $Matches[1]
            $manifestRelativePath = $Matches[2]
            $pathSegments = @($manifestRelativePath -split "/")
            if ($pathSegments -contains "." -or $pathSegments -contains "..") {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-PATH-INVALID"
                continue
            }
            if (-not $manifestPathSet.Add($manifestRelativePath)) {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-DUPLICATE"
                continue
            }
            $manifestRelativePaths.Add($manifestRelativePath)
            $manifestShaByPath[$manifestRelativePath] = $manifestSha

            $manifestToolFullPath = [System.IO.Path]::GetFullPath(
                (Join-Path $qaToolsRootFullPath $manifestRelativePath)
            )
            if (
                -not $manifestToolFullPath.StartsWith(
                    $toolsRootPrefix,
                    [System.StringComparison]::OrdinalIgnoreCase
                )
            ) {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-PATH-ESCAPE"
                continue
            }
            if (-not (Test-Path -LiteralPath $manifestToolFullPath -PathType Leaf)) {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-FILE-MISSING"
                continue
            }
            $actualManifestToolSha = (
                Get-FileHash `
                    -LiteralPath $manifestToolFullPath `
                    -Algorithm SHA256
            ).Hash.ToLowerInvariant()
            if ($actualManifestToolSha -cne $manifestSha) {
                Add-PreflightError -Code "TOOLS-FILE-SHA-MISMATCH-$manifestRelativePath"
            }
        }

        $actualRelativePaths = [string[]]$manifestRelativePaths.ToArray()
        $ordinalRelativePaths = [string[]]@($actualRelativePaths)
        [Array]::Sort($ordinalRelativePaths, [System.StringComparer]::Ordinal)
        if (
            ($actualRelativePaths -join "`n") -cne
            ($ordinalRelativePaths -join "`n")
        ) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-NOT-ORDINAL"
        }
        if ($actualRelativePaths.Count -ne [int]$expectedToolsEntryCount) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-ENTRY-COUNT-MISMATCH"
        }

        $canonicalManifestText = ($canonicalManifestLines -join "`n") + "`n"
        $canonicalManifestBytes = [System.Text.UTF8Encoding]::new(
            $false
        ).GetBytes($canonicalManifestText)
        if ($canonicalManifestBytes.Length -ne [int]$expectedToolsManifestBytes) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-BYTES-MISMATCH"
        }
        $canonicalManifestDigest = [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData(
                $canonicalManifestBytes
            )
        ).ToLowerInvariant()
        if ($canonicalManifestDigest -cne $expectedToolsDigest) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-DIGEST-MISMATCH"
        }

        $actualToolsSnapshot = Get-QaToolsDigestSnapshot `
            -ToolsRoot $qaToolsRootFullPath
        $script:toolsDigestValue = $actualToolsSnapshot.digestSha256
        $script:toolsEntryCount = $actualToolsSnapshot.entryCount
        if ($actualToolsSnapshot.digestSha256 -cne $expectedToolsDigest) {
            Add-PreflightError -Code "TOOLS-DIGEST-MISMATCH"
        }
        if ($actualToolsSnapshot.entryCount -ne [int]$expectedToolsEntryCount) {
            Add-PreflightError -Code "TOOLS-DIRECTORY-ENTRY-COUNT-MISMATCH"
        }
        if (-not (Test-OrdinalSetEqual `
            -Actual ([string[]]$actualToolsSnapshot.manifestLines) `
            -Expected ([string[]]$canonicalManifestLines))) {
            Add-PreflightError -Code "TOOLS-DIRECTORY-MANIFEST-MISMATCH"
        }
    }

    $reportPath = [string](Get-ContractProperty $evidence "currentReportPath")
    $reportFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $reportPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "QA-REPORT"
    if ($null -ne $reportFullPath -and (Test-Path -LiteralPath $reportFullPath -PathType Leaf)) {
        $reportText = Read-StrictUtf8LfFile `
            -AbsolutePath $reportFullPath `
            -ErrorPrefix "QA-REPORT"
        try {
            $reportData = $reportText | ConvertFrom-Json
            if ((Get-ContractProperty $reportData "status") -cne "BLOCKED_TOOL_NONCONVERGENCE") {
                Add-PreflightError -Code "QA-REPORT-STATUS-MISMATCH"
            }
            if (
                (Get-ContractProperty (Get-ContractProperty (Get-ContractProperty $reportData "findings") "product") "P0") -cne
                "NOT_ASSESSED"
            ) {
                Add-PreflightError -Code "QA-REPORT-PRODUCT-MISMATCH"
            }
            $reportObject = Get-ContractProperty $reportData "object"
            $reportMain = Get-ContractProperty $reportObject "hostMain"
            $reportPeer = Get-ContractProperty $reportObject "hostPeer"
            foreach ($artifactName in @("main", "peer")) {
                $contractArtifact = Get-ContractProperty $acceptedArtifacts $artifactName
                $reportArtifact = if ($artifactName -eq "main") { $reportMain } else { $reportPeer }
                if (
                    (Get-ContractProperty $reportArtifact "sha256") -cne
                    (Get-ContractProperty $contractArtifact "sha256") -or
                    (Get-ContractProperty $reportArtifact "bytes") -ne
                    (Get-ContractProperty $contractArtifact "bytes")
                ) {
                    Add-PreflightError -Code "QA-REPORT-ARTIFACT-MISMATCH-$artifactName"
                }
            }
            $reportTools = Get-ContractProperty $reportObject "tools"
            if ((Get-ContractProperty $reportTools "aggregateSha256") -cne $legacyManifestSha256) {
                Add-PreflightError -Code "QA-REPORT-HISTORICAL-TOOLS-MISMATCH"
            }
            $reportDevice = Get-ContractProperty $reportObject "device"
            if ((Get-ContractProperty $reportDevice "serial") -cne "43af8627") {
                Add-PreflightError -Code "QA-REPORT-PAD-SERIAL-MISMATCH"
            }
            $reportCleanup = Get-ContractProperty $reportData "cleanup"
            if ((Get-ContractProperty $reportCleanup "status") -cne "PASS_CLEANUP_AND_RESTORE") {
                Add-PreflightError -Code "QA-REPORT-CLEANUP-MISMATCH"
            }
            $reportDeviceLock = Get-ContractProperty $reportData "deviceLock"
            if ((Get-ContractProperty $reportDeviceLock "activeCountAfterClosure") -ne 0) {
                Add-PreflightError -Code "QA-REPORT-DEVICE-LOCK-ACTIVE"
            }
        }
        catch {
            Add-PreflightError -Code "QA-REPORT-JSON-INVALID"
        }
    }

    $validation = Get-ContractProperty $contractData "validation"
    $activePromptExpectedRoleId = [string](
        Get-ContractProperty $validation "activePromptExpectedRoleId"
    )
    if (
        $activePromptExpectedRoleId -cne
        "PRE_HANDOFF_AUDIT_OWNER" -or
        (Get-ContractProperty $toolRepairPrompt "roleId") -cne
        $activePromptExpectedRoleId
    ) {
        Add-PreflightError -Code "ACTIVE-PROMPT-EXPECTED-ROLE-INVALID"
    }
    $qaPromptRequiredStatus = [string](
        Get-ContractProperty $validation "qaPromptRequiredStatus"
    )
    if (
        $qaPromptRequiredStatus -cne
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT" -or
        (Get-ContractProperty $padQaPrompt "status") -cne
        $qaPromptRequiredStatus
    ) {
        Add-PreflightError -Code "QA-PROMPT-REQUIRED-STATUS-INVALID"
    }
    $activeDocumentPath = [string](Get-ContractProperty $validation "activeDocument")
    $activeDocumentFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $activeDocumentPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "ACTIVE-DOCUMENT"
    if ($null -ne $activeDocumentFullPath) {
        $activeDocumentText = Read-StrictUtf8LfFile `
            -AbsolutePath $activeDocumentFullPath `
            -ErrorPrefix "ACTIVE-DOCUMENT"
        $activeDocumentLines = @($activeDocumentText -split "`n")
        $activeHeadingPrefix = [string](Get-ContractProperty $validation "activeHeadingPrefix")
        $activeIndexes = @(
            for ($lineIndex = 0; $lineIndex -lt $activeDocumentLines.Count; $lineIndex++) {
                if ($activeDocumentLines[$lineIndex].StartsWith(
                    $activeHeadingPrefix,
                    [System.StringComparison]::Ordinal
                )) {
                    $lineIndex
                }
            }
        )
        if ($activeIndexes.Count -ne 1) {
            Add-PreflightError -Code "ACTIVE-SECTION-COUNT-NOT-ONE"
        }
        else {
            $activeStart = $activeIndexes[0]
            $activeEnd = $activeDocumentLines.Count
            for ($lineIndex = $activeStart + 1; $lineIndex -lt $activeDocumentLines.Count; $lineIndex++) {
                if ($activeDocumentLines[$lineIndex].StartsWith(
                    "### HISTORY",
                    [System.StringComparison]::Ordinal
                )) {
                    $activeEnd = $lineIndex
                    break
                }
            }
            $activeSectionText = (
                $activeDocumentLines[$activeStart..($activeEnd - 1)] -join "`n"
            )
            $activeRequiredTokens = @(
                Get-ContractProperty $validation "activeRequiredTokens"
            )
            Assert-Tokens `
                -TextValue $activeSectionText `
                -Tokens $activeRequiredTokens `
                -ErrorPrefix "ACTIVE-SECTION"
        }
    }

    $tasksDocumentPath = [string](Get-ContractProperty $validation "tasksDocument")
    $tasksInProgressHeading = [string](
        Get-ContractProperty $validation "tasksInProgressHeading"
    )
    $tasksInProgressExpectedCount = Get-ContractProperty `
        $validation `
        "tasksInProgressExpectedCount"
    if (
        $tasksInProgressExpectedCount -notmatch "^\d+$" -or
        [int]$tasksInProgressExpectedCount -lt 1
    ) {
        Add-PreflightError -Code "TASKS-IN-PROGRESS-EXPECTED-COUNT-INVALID"
    }
    $tasksDocumentFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $tasksDocumentPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "TASKS-DOCUMENT"
    if ($null -ne $tasksDocumentFullPath) {
        $tasksDocumentText = Read-StrictUtf8LfFile `
            -AbsolutePath $tasksDocumentFullPath `
            -ErrorPrefix "TASKS-DOCUMENT"
        $tasksDocumentLines = @($tasksDocumentText -split "`n")
        $tasksHeadingIndexes = @(
            for ($lineIndex = 0; $lineIndex -lt $tasksDocumentLines.Count; $lineIndex++) {
                if (
                    $tasksDocumentLines[$lineIndex] -ceq
                    $tasksInProgressHeading
                ) {
                    $lineIndex
                }
            }
        )
        if ($tasksHeadingIndexes.Count -ne 1) {
            Add-PreflightError -Code "TASKS-IN-PROGRESS-HEADING-COUNT-NOT-ONE"
        }
        else {
            $tasksStart = $tasksHeadingIndexes[0] + 1
            $tasksEnd = $tasksDocumentLines.Count
            for ($lineIndex = $tasksStart; $lineIndex -lt $tasksDocumentLines.Count; $lineIndex++) {
                if ($tasksDocumentLines[$lineIndex].StartsWith(
                    "## ",
                    [System.StringComparison]::Ordinal
                )) {
                    $tasksEnd = $lineIndex
                    break
                }
            }
            $inProgressRows = @(
                for ($lineIndex = $tasksStart; $lineIndex -lt $tasksEnd; $lineIndex++) {
                    if ($tasksDocumentLines[$lineIndex] -match '^\| `V2-[^`]+`') {
                        $tasksDocumentLines[$lineIndex]
                    }
                }
            )
            $script:tasksInProgressCount = $inProgressRows.Count
            if ($inProgressRows.Count -ne [int]$tasksInProgressExpectedCount) {
                Add-PreflightError -Code "TASKS-IN-PROGRESS-COUNT-MISMATCH"
            }
        }
    }

    $requiredTextAnchors = @(
        Get-ContractProperty $validation "requiredTextAnchors"
    )
    foreach ($anchor in $requiredTextAnchors) {
        $anchorPath = [string](Get-ContractProperty $anchor "path")
        $anchorFullPath = Resolve-RepoFile `
            -RelativeOrAbsolutePath $anchorPath `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "ANCHOR"
        if ($null -eq $anchorFullPath) {
            continue
        }
        $anchorText = Read-StrictUtf8LfFile `
            -AbsolutePath $anchorFullPath `
            -ErrorPrefix "ANCHOR-$anchorPath"
        $anchorTokens = @(Get-ContractProperty $anchor "tokens")
        Assert-Tokens `
            -TextValue $anchorText `
            -Tokens $anchorTokens `
            -ErrorPrefix "ANCHOR-$anchorPath"
        $forbiddenTokens = @(Get-ContractProperty $anchor "forbiddenTokens")
        foreach ($forbiddenToken in $forbiddenTokens) {
            if (
                (Test-NonEmptyString $forbiddenToken) -and
                $anchorText.Contains($forbiddenToken, [System.StringComparison]::Ordinal)
            ) {
                Add-PreflightError -Code "STALE-TOKEN-PRESENT-$anchorPath"
            }
        }
    }

    if ($null -ne $inputCommit -and $null -ne $inputBranch) {
        $branchOutput = & git -C $repositoryRoot rev-parse --abbrev-ref HEAD 2>$null
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "GIT-BRANCH-READ-FAILED"
        }
        else {
            $script:branchValue = ([string]$branchOutput).Trim()
            if ($script:branchValue -cne $inputBranch) {
                Add-PreflightError -Code "GIT-BRANCH-MISMATCH"
            }
        }

        $headOutput = & git -C $repositoryRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "GIT-HEAD-READ-FAILED"
        }
        else {
            $script:headValue = ([string]$headOutput).Trim()
        }

        & git -C $repositoryRoot merge-base --is-ancestor $inputCommit $script:headValue 2>$null
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "INPUT-BASELINE-NOT-ANCESTOR"
        }

        $statusOutput = @(& git -C $repositoryRoot status --porcelain 2>$null)
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "GIT-STATUS-READ-FAILED"
        }
        else {
            $script:dirtyCount = $statusOutput.Count
        }
    }
}
catch {
    Add-PreflightError -Code ("PREFLIGHT-EXCEPTION-" + $_.Exception.GetType().Name)
}

if ($validationErrors.Count -gt 0) {
    $errorCodes = @(
        $validationErrors |
            Select-Object -Unique |
            Select-Object -First 8
    ) -join ","
    Write-Output (
        "FAIL_CLOSED HANDOFF_BOOTSTRAP_V2 errors={0} codes={1}" -f
        $validationErrors.Count,
        $errorCodes
    )
    exit 2
}

Write-Output (
    "PASS HANDOFF_BOOTSTRAP_V2 branch={0} head={1} dirty={2} input={3} roles={4} tasks={5} gates={6} order=Pad->K60 window=10 apk=MATCH tools=MATCH manifest=MATCH({7}/{7}) digest={8} docs=MATCH lock=RELEASED" -f
    $script:branchValue,
    $script:headValue,
    $script:dirtyCount,
    $inputCommit.Substring(0, 12),
    $script:roleCount,
    $script:tasksInProgressCount,
    $script:gateCount,
    $script:toolsEntryCount,
    $script:toolsDigestValue
)

if ($EmitPrompt) {
    $selectedPromptObject = Get-ContractProperty $promptsObject $selectedPromptName
    $selectedPromptText = Get-PromptText `
        -PromptValue $selectedPromptObject `
        -ErrorPrefix "EMIT-$selectedPromptName"
    $selectedPromptPath = if ($selectedPromptName -eq "padQa") {
        $qaPromptPath
    }
    else {
        $nextPromptPath
    }
    Write-Output "NEXT_PROMPT_PATH=$selectedPromptPath"
    Write-Output "NEXT_PROMPT_BEGIN"
    Write-Output $selectedPromptText
    Write-Output "NEXT_PROMPT_END"
}
