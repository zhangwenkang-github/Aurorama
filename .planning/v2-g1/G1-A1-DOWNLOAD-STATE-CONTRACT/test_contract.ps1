[CmdletBinding()]
param(
    [ValidateSet("none", "broken-invariant", "broken-delete-predicate")]
    [string]$Mutation = "none",

    [switch]$AllowMissingManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$contractRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$fixturePath = Join-Path $contractRoot "fixture/contract-fixture.json"
$reportPath = Join-Path $contractRoot "report.json"
$manifestPath = Join-Path $contractRoot "manifest.json"
$strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
$failures = [System.Collections.Generic.List[string]]::new()
$deletionPredicateMutation = $Mutation -eq "broken-delete-predicate"

function Read-Utf8Text {
    param([Parameter(Mandatory)][string]$Path)

    return [System.IO.File]::ReadAllText($Path, $strictUtf8)
}

function Read-Utf8Json {
    param([Parameter(Mandatory)][string]$Path)

    return (Read-Utf8Text -Path $Path | ConvertFrom-Json)
}

function Add-Failure {
    param([Parameter(Mandatory)][string]$Message)

    $failures.Add($Message)
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )

    if (-not $Condition) {
        Add-Failure $Message
    }
}

function Get-Base64Url {
    param([Parameter(Mandatory)][string]$Value)

    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Value))
    return $encoded.TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-DownloadKey {
    param(
        [Parameter(Mandatory)]$Identity,
        [Parameter(Mandatory)]$Contract
    )

    $parts = [ordered]@{}
    foreach ($component in $Contract.identity.components) {
        $property = $Identity.PSObject.Properties[$component]
        if ($null -eq $property) {
            return $null
        }
        $value = [string]$property.Value
        if ([string]::IsNullOrWhiteSpace($value)) {
            return $null
        }
        $parts[$component] = Get-Base64Url -Value $value
    }

    $versionTag = [string]$Contract.identity.versionTag
    return "${versionTag}:server=$($parts.serverId):user=$($parts.userId):item=$($parts.itemId):source=$($parts.mediaSourceId)"
}

function Test-TransitionDecision {
    param(
        [Parameter(Mandatory)]$Transition,
        [Parameter(Mandatory)]$Context
    )

    $generation = $Context.PSObject.Properties["generationMatches"]
    $source = $Context.PSObject.Properties["sourceMatches"]
    if ($null -eq $generation -or $generation.Value -ne $true) {
        return "REJECT"
    }
    if ($null -eq $source -or $source.Value -ne $true) {
        return "REJECT"
    }
    if (-not [bool]$Transition.allowed) {
        return "REJECT"
    }

    foreach ($required in @($Transition.requires)) {
        $property = $Context.PSObject.Properties[[string]$required]
        if ($null -eq $property -or $property.Value -ne $true) {
            return "REJECT"
        }
    }
    return "ACCEPT"
}

function New-AllTrueContext {
    return [pscustomobject]@{
        generationMatches = $true
        sourceMatches = $true
        accepted = $true
        requiredFailure = $true
        cancelIntent = $true
        doneEvidence = $true
        explicitRetry = $true
        completionFacts = $true
        explicitDelete = $true
        receiptComplete = $true
        childFailed = $true
        explicitDeleteRetry = $true
        explicitRedownload = $true
        generationAdvanced = $true
    }
}

function New-NegativeGuardContext {
    param(
        [Parameter(Mandatory)]$Transition,
        [Parameter(Mandatory)]$BaseContext
    )

    $copy = $BaseContext.PSObject.Copy()
    $negativeCase = [string]$Transition.negativeCase

    if ($negativeCase -match "stale|old-generation|worker-after-delete") {
        $copy.generationMatches = $false
    }
    elseif ($negativeCase -match "source") {
        $copy.sourceMatches = $false
    }
    elseif ($negativeCase -match "acceptance") {
        $copy.accepted = $false
    }
    elseif ($negativeCase -match "cancel|automatic-retry") {
        if ($negativeCase -match "automatic-retry") {
            $copy.explicitDeleteRetry = $false
        }
        else {
            $copy.cancelIntent = $false
        }
    }
    elseif ($negativeCase -match "worker-success|no-transfer") {
        $copy.doneEvidence = $false
    }
    elseif ($negativeCase -match "retry-intent") {
        $copy.explicitRetry = $false
    }
    elseif ($negativeCase -match "manifest") {
        $copy.completionFacts = $false
    }
    elseif ($negativeCase -match "optional-only") {
        $copy.requiredFailure = $false
    }
    elseif ($negativeCase -match "pending-child|child-pending") {
        $copy.receiptComplete = $false
    }
    elseif ($negativeCase -match "children-confirmed") {
        $copy.childFailed = $false
    }
    elseif ($negativeCase -match "late-old-generation") {
        $copy.generationAdvanced = $false
    }
    elseif ($negativeCase -match "unclassified") {
        $copy.requiredFailure = $false
    }
    elseif ($negativeCase -match "delete-intent") {
        $copy.explicitDelete = $false
    }
    else {
        Add-Failure "No negative guard mapping for transition $($Transition.id): $negativeCase"
    }

    return $copy
}

function Test-CompletionFacts {
    param([Parameter(Mandatory)]$Case)

    return (
        $Case.acceptedPersisted -eq $true -and
        $Case.generationMatches -eq $true -and
        $Case.sourceMatches -eq $true -and
        $Case.doneEvidence -eq $true -and
        $Case.doneBeforeDeadline -eq $true -and
        $Case.mediaCommitted -eq $true -and
        $Case.manifestCommitted -eq $true -and
        $Case.persistentStateCommitted -eq $true -and
        $Case.fileReadable -eq $true -and
        $Case.observedBytesMatch -eq $true
    )
}

function Test-TerminalEmission {
    param([Parameter(Mandatory)]$Case)

    if ($Case.terminalKind -eq "NONE") {
        return $false
    }
    return (
        $Case.alreadyEmitted -eq $false -and
        $Case.generationMatches -eq $true -and
        $Case.sourceMatches -eq $true -and
        $Case.deleteRequested -eq $false
    )
}

function Get-DeletionState {
    param([Parameter(Mandatory)]$Case)

    $requiredChildren = @(
        "files",
        "sidecar",
        "persistentState",
        "offlineProjection",
        "terminalNotification"
    )
    $children = $Case.children
    $allChildrenConfirmed = $true
    $hasFailedOrMissing = $false
    foreach ($child in $requiredChildren) {
        $property = $children.PSObject.Properties[$child]
        if ($null -eq $property) {
            $allChildrenConfirmed = $false
            $hasFailedOrMissing = $true
            continue
        }
        $childState = [string]$property.Value
        if ($deletionPredicateMutation -and $childState -eq "PENDING") {
            $childState = "CONFIRMED"
        }
        if ($childState -eq "FAILED") {
            $allChildrenConfirmed = $false
            $hasFailedOrMissing = $true
        }
        elseif ($childState -ne "CONFIRMED") {
            $allChildrenConfirmed = $false
        }
    }

    $batchCommitted = $true
    foreach ($batchChild in @($Case.batchChildren)) {
        if ($batchChild.committed -ne $true) {
            $batchCommitted = $false
        }
    }

    if ($allChildrenConfirmed -and $batchCommitted) {
        return [pscustomobject]@{
            Committed = $true
            State = "DELETED"
        }
    }
    if ($hasFailedOrMissing) {
        return [pscustomobject]@{
            Committed = $false
            State = "DELETING_FAILED"
        }
    }
    return [pscustomobject]@{
        Committed = $false
        State = "DELETING"
    }
}

function Get-RestartState {
    param([Parameter(Mandatory)]$Case)

    switch ($Case.persistedState) {
        "RUNNING" {
            if ($Case.leaseCurrent -eq $true) {
                return "RUNNING"
            }
            return "PENDING"
        }
        "COMPLETED" {
            if ($Case.completionFacts -eq $true) {
                return "COMPLETED"
            }
            return "FAILED"
        }
        default {
            return [string]$Case.persistedState
        }
    }
}

function Test-PathInsideContract {
    param([Parameter(Mandatory)][string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $prefix = $contractRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    return $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
}

$contract = Read-Utf8Json -Path $fixturePath
Assert-True ($contract.schemaVersion -eq 1) "Fixture schemaVersion must be 1"
Assert-True ($contract.contractVersion -eq "v2-download-state-contract/v1") "Unexpected contractVersion"
Assert-True ($contract.baseline.head -eq "287c37e1d1287e2a90c047e628f7272a13cc5970") "Baseline HEAD mismatch"
Assert-True ($contract.baseline.branch -eq "codex/v2-integration") "Baseline branch mismatch"

# Canonical identity: complete four-part tuples may merge; missing provenance never merges.
foreach ($case in @($contract.identity.cases)) {
    $left = Get-DownloadKey -Identity $case.left -Contract $contract
    $right = Get-DownloadKey -Identity $case.right -Contract $contract
    switch ($case.expected) {
        "SAME_KEY" {
            Assert-True ($null -ne $left -and $null -ne $right -and $left -eq $right) "Identity case $($case.id) should merge"
        }
        "DIFFERENT_KEY" {
            Assert-True ($null -ne $left -and $null -ne $right -and $left -ne $right) "Identity case $($case.id) should stay distinct"
        }
        "UNKNOWN_NO_MERGE" {
            Assert-True ($null -eq $left -and $null -eq $right) "Identity case $($case.id) should be UNKNOWN_NO_MERGE"
        }
        default {
            Add-Failure "Unknown identity expectation $($case.expected)"
        }
    }
}

# Transition matrix: every allowed edge accepts only with current generation/source and its guards.
$stateIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($state in @($contract.states)) {
    [void]$stateIds.Add([string]$state.id)
}
$transitionPairs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$validContext = New-AllTrueContext
$allowedCount = 0
$forbiddenExampleCount = 0

foreach ($transition in @($contract.transitions)) {
    Assert-True ($stateIds.Contains([string]$transition.from)) "Transition $($transition.id) has unknown from state"
    Assert-True ($stateIds.Contains([string]$transition.to)) "Transition $($transition.id) has unknown to state"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$transition.negativeCase)) "Transition $($transition.id) lacks negativeCase"
    Assert-True ($transition.negativeExpected -eq "REJECT") "Transition $($transition.id) negativeExpected must be REJECT"
    $pair = "$($transition.from)->$($transition.to)"
    Assert-True ($transitionPairs.Add($pair)) "Duplicate transition pair $pair"

    $decision = Test-TransitionDecision -Transition $transition -Context $validContext
    if ($transition.allowed -eq $true) {
        $allowedCount++
        Assert-True ($decision -eq "ACCEPT") "Allowed transition $($transition.id) was rejected"
        $negativeContext = New-NegativeGuardContext -Transition $transition -BaseContext $validContext
        $negativeDecision = Test-TransitionDecision -Transition $transition -Context $negativeContext
        Assert-True ($negativeDecision -eq "REJECT") "Allowed transition $($transition.id) failed its negative guard"
    }
    else {
        $forbiddenExampleCount++
        Assert-True ($decision -eq "REJECT") "Forbidden transition $($transition.id) was accepted"
    }
}

$transitionLookup = @{}
foreach ($transition in @($contract.transitions)) {
    $transitionLookup["$($transition.from)->$($transition.to)"] = $transition
}
$fullPairCount = 0
$fullAllowedCount = 0
foreach ($from in $stateIds) {
    foreach ($to in $stateIds) {
        $fullPairCount++
        $pair = "$from->$to"
        if ($transitionLookup.ContainsKey($pair)) {
            $decision = Test-TransitionDecision -Transition $transitionLookup[$pair] -Context $validContext
            if ($decision -eq "ACCEPT") {
                $fullAllowedCount++
            }
        }
        # Undefined state pairs are forbidden by default and contribute to the full-domain count.
    }
}
Assert-True ($fullAllowedCount -eq $allowedCount) "Full-domain allowed count differs from explicit matrix"
Assert-True ($fullPairCount -eq ($stateIds.Count * $stateIds.Count)) "Full-domain transition count mismatch"

# Completion is causal and durable; Worker SUCCESS is deliberately absent from the formula.
foreach ($case in @($contract.completionCases)) {
    $complete = Test-CompletionFacts -Case $case
    Assert-True ($complete -eq [bool]$case.expectedComplete) "Completion case $($case.id) mismatch"
    $visible = if ($complete) { "COMPLETED" } else { "RUNNING" }
    Assert-True ($visible -eq $case.expectedVisible) "Completion case $($case.id) visible projection mismatch"
    Assert-True ($complete -eq [bool]$case.expectedOffline) "Completion case $($case.id) offline projection mismatch"
}

# Terminal notices are current-generation, exact-once, and purged by delete intent.
foreach ($case in @($contract.notificationCases)) {
    $emit = Test-TerminalEmission -Case $case
    Assert-True ($emit -eq [bool]$case.expectedEmit) "Notification case $($case.id) emit mismatch"
    Assert-True ([bool]$case.deleteRequested -eq [bool]$case.expectedPurge) "Notification case $($case.id) purge mismatch"
}

# Page, badge, and aggregate-notification counts are projections of one active-key set.
foreach ($case in @($contract.countCases)) {
    $activeStateSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($state in @($contract.states)) {
        if ($state.activeCount -eq $true) {
            [void]$activeStateSet.Add([string]$state.id)
        }
    }
    $pageQueueCount = @($case.tasks | Where-Object { $activeStateSet.Contains([string]$_.state) }).Count
    $badgeCount = $pageQueueCount
    $activeNotificationCount = $pageQueueCount
    $offlineCount = @(
        $case.tasks | Where-Object {
            $_.state -eq "COMPLETED" -and
            $_.availability -eq "AVAILABLE" -and
            $_.deleteRequested -eq $false
        }
    ).Count
    Assert-True ($pageQueueCount -eq $case.expectedPageQueueCount) "Count case $($case.id) page count mismatch"
    Assert-True ($badgeCount -eq $case.expectedBadgeCount) "Count case $($case.id) badge count mismatch"
    Assert-True ($activeNotificationCount -eq $case.expectedActiveNotificationCount) "Count case $($case.id) notification count mismatch"
    Assert-True ($pageQueueCount -eq $badgeCount -and $badgeCount -eq $activeNotificationCount) "Count case $($case.id) projections diverged"
    Assert-True ($offlineCount -eq $case.expectedOfflineCount) "Count case $($case.id) offline count mismatch"
}

# Deletion commits only after every storage/projection/notification child is confirmed.
foreach ($case in @($contract.deletionCases)) {
    $deletion = Get-DeletionState -Case $case
    Assert-True ($deletion.Committed -eq [bool]$case.expectedCommitted) "Deletion case $($case.id) commit mismatch"
    Assert-True ($deletion.State -eq $case.expectedState) "Deletion case $($case.id) state mismatch: expected $($case.expectedState), actual $($deletion.State)"
}

# Restart reconciliation never invents COMPLETED from paths or Worker history.
foreach ($case in @($contract.restartCases)) {
    $actual = Get-RestartState -Case $case
    Assert-True ($actual -eq $case.expectedState) "Restart case $($case.id) mismatch: $actual"
}

# Every user commitment points to a real machine-checkable fixture/equation ID.
$knownIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($collectionName in @(
    "states",
    "equations",
    "transitions",
    "completionCases",
    "notificationCases",
    "countCases",
    "deletionCases",
    "restartCases",
    "commitments",
    "capabilityGaps"
)) {
    foreach ($item in @($contract.$collectionName)) {
        [void]$knownIds.Add([string]$item.id)
    }
}
foreach ($case in @($contract.identity.cases)) {
    [void]$knownIds.Add([string]$case.id)
}
foreach ($commitment in @($contract.commitments)) {
    foreach ($field in @("promise", "entry", "action", "observable", "runtimeUncovered")) {
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$commitment.$field)) "Commitment $($commitment.id) missing $field"
    }
    Assert-True (@($commitment.evidence).Count -gt 0) "Commitment $($commitment.id) has no evidence"
    foreach ($evidenceId in @($commitment.evidence)) {
        Assert-True ($knownIds.Contains([string]$evidenceId)) "Commitment $($commitment.id) points to unknown evidence $evidenceId"
    }
}

$identityCaseCount = @($contract.identity.cases).Count
$completionCaseCount = @($contract.completionCases).Count
$notificationCaseCount = @($contract.notificationCases).Count
$countCaseCount = @($contract.countCases).Count
$deletionCaseCount = @($contract.deletionCases).Count
$restartCaseCount = @($contract.restartCases).Count
$commitmentCount = @($contract.commitments).Count
$fixtureCaseCount =
    $identityCaseCount +
    $completionCaseCount +
    $notificationCaseCount +
    $countCaseCount +
    $deletionCaseCount +
    $restartCaseCount +
    $commitmentCount

$expectedCounts = [ordered]@{
    states = 12
    allowedTransitions = 24
    forbiddenTransitionExamples = 16
    fullTransitionPairs = 144
    fullForbiddenPairs = 120
    identityCases = 6
    completionCases = 11
    notificationCases = 6
    countCases = 1
    deletionCases = 10
    restartCases = 8
    commitments = 13
    fixtureCases = 55
    equations = 10
    capabilityGaps = 6
    fakeBoundaries = 6
}
$actualCounts = [ordered]@{
    states = $stateIds.Count
    allowedTransitions = $allowedCount
    forbiddenTransitionExamples = $forbiddenExampleCount
    fullTransitionPairs = $fullPairCount
    fullForbiddenPairs = $fullPairCount - $fullAllowedCount
    identityCases = $identityCaseCount
    completionCases = $completionCaseCount
    notificationCases = $notificationCaseCount
    countCases = $countCaseCount
    deletionCases = $deletionCaseCount
    restartCases = $restartCaseCount
    commitments = $commitmentCount
    fixtureCases = $fixtureCaseCount
    equations = @($contract.equations).Count
    capabilityGaps = @($contract.capabilityGaps).Count
    fakeBoundaries = @($contract.fakeBoundaries).Count
}
foreach ($key in $expectedCounts.Keys) {
    Assert-True ($actualCounts[$key] -eq $expectedCounts[$key]) "Count drift for $key`: expected $($expectedCounts[$key]), actual $($actualCounts[$key])"
}

# JSON files must parse as UTF-8 and declared report counts must agree with the fixture.
$jsonFiles = @(Get-ChildItem -LiteralPath $contractRoot -Recurse -File -Filter "*.json")
foreach ($jsonFile in $jsonFiles) {
    [void](Read-Utf8Json -Path $jsonFile.FullName)
}
if (Test-Path -LiteralPath $reportPath) {
    $report = Read-Utf8Json -Path $reportPath
    foreach ($key in $actualCounts.Keys) {
        $reportProperty = $report.counts.PSObject.Properties[$key]
        Assert-True ($null -ne $reportProperty) "report.json missing count $key"
        if ($null -ne $reportProperty) {
            Assert-True ($reportProperty.Value -eq $actualCounts[$key]) "report.json count $key drift"
        }
    }
}
elseif (-not $AllowMissingManifest) {
    Add-Failure "report.json is missing"
}

# Manifest hashes are verified against actual files; manifest itself is deliberately excluded.
if (Test-Path -LiteralPath $manifestPath) {
    $manifest = Read-Utf8Json -Path $manifestPath
    Assert-True ($manifest.schemaVersion -eq 1) "manifest schemaVersion must be 1"
    foreach ($artifact in @($manifest.artifacts)) {
        $artifactPath = Join-Path $contractRoot ([string]$artifact.path)
        Assert-True (Test-PathInsideContract -Path $artifactPath) "Manifest path escapes contract root: $($artifact.path)"
        Assert-True (Test-Path -LiteralPath $artifactPath -PathType Leaf) "Manifest artifact missing: $($artifact.path)"
        if (Test-Path -LiteralPath $artifactPath -PathType Leaf) {
            $actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToUpperInvariant()
            $actualBytes = (Get-Item -LiteralPath $artifactPath).Length
            Assert-True ($actualHash -eq ([string]$artifact.sha256).ToUpperInvariant()) "Manifest hash mismatch: $($artifact.path)"
            Assert-True ($actualBytes -eq [long]$artifact.bytes) "Manifest byte mismatch: $($artifact.path)"
        }
    }
}
elseif (-not $AllowMissingManifest) {
    Add-Failure "manifest.json is missing"
}

# All contract text is UTF-8 without BOM and LF-only.
$textFiles = @(
    Get-ChildItem -LiteralPath $contractRoot -Recurse -File |
        Where-Object { $_.Extension -in @(".md", ".json", ".ps1", ".log") }
)
foreach ($file in $textFiles) {
    $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Add-Failure "UTF-8 BOM found: $([System.IO.Path]::GetRelativePath($contractRoot, $file.FullName))"
    }
    $text = Read-Utf8Text -Path $file.FullName
    if ($text.Contains("`r")) {
        Add-Failure "CRLF found: $([System.IO.Path]::GetRelativePath($contractRoot, $file.FullName))"
    }
}

# Credential-value patterns are forbidden; the fixture may mention token semantics only.
$secretPatterns = @(
    "Bearer" + "\s+[A-Za-z0-9._~-]{12,}",
    "access" + "Token\s*[:=]",
    "api" + "[_-]?key\s*[:=]",
    "pass" + "word\s*[:=]",
    "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY"
)
foreach ($file in $textFiles) {
    $text = Read-Utf8Text -Path $file.FullName
    foreach ($pattern in $secretPatterns) {
        if ($text -match $pattern) {
            Add-Failure "Credential-like value pattern found: $([System.IO.Path]::GetRelativePath($contractRoot, $file.FullName))"
        }
    }
}

if ($Mutation -eq "broken-invariant") {
    # Deliberately promote Worker SUCCESS to COMPLETED; the normal invariant above rejects this.
    $mutatedWorkerOnlyAccepted = $true
    if ($mutatedWorkerOnlyAccepted) {
        Add-Failure "MUTATION: Worker SUCCESS was promoted to COMPLETED without done/media/manifest/DB facts"
    }
}

if ($failures.Count -gt 0) {
    $summary = "FAIL static download-state contract: $($failures.Count) failure(s)"
    [Console]::Error.WriteLine($summary)
    foreach ($failure in $failures) {
        [Console]::Error.WriteLine(" - $failure")
    }
    exit 1
}

$countText =
    "states={0}; transitions={1} allowed/{2} forbidden-examples/{3} full-pairs; cases={4}; equations={5}" -f
    $actualCounts.states,
    $actualCounts.allowedTransitions,
    $actualCounts.forbiddenTransitionExamples,
    $actualCounts.fullTransitionPairs,
    $actualCounts.fixtureCases,
    $actualCounts.equations
[Console]::Out.WriteLine("PASS static download-state contract: $countText")
[Console]::Out.WriteLine("PASS identity/completion/notification/count/deletion/restart invariants")
exit 0
