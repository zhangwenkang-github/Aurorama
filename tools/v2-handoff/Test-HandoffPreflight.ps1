[CmdletBinding()]
param(
    [string]$RepoRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$repositoryRoot = if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
}
else {
    [System.IO.Path]::GetFullPath($RepoRoot)
}

$contractPath = Join-Path $PSScriptRoot "HANDOFF_BOOTSTRAP_V2.json"
$preflightPath = Join-Path $PSScriptRoot "Invoke-HandoffPreflight.ps1"
$testPath = $PSCommandPath
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8Output
$OutputEncoding = $utf8Output

$testFailures = [System.Collections.Generic.List[string]]::new()
$testResults = [System.Collections.Generic.List[object]]::new()

function Assert-Condition {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Name
    )

    if (-not $Condition) {
        $testFailures.Add($Name)
        throw "ASSERT-FAILED $Name"
    }
}

function Assert-StrictUtf8Lf {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    Assert-Condition `
        -Condition (Test-Path -LiteralPath $Path -PathType Leaf) `
        -Name "$Name-EXISTS"
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-Condition `
        -Condition (-not (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        )) `
        -Name "$Name-NO-BOM"
    Assert-Condition `
        -Condition ([Array]::IndexOf($bytes, [byte]13) -lt 0) `
        -Name "$Name-LF"
    try {
        [void]$utf8Strict.GetString($bytes)
    }
    catch {
        Assert-Condition -Condition $false -Name "$Name-UTF8"
    }
}

function Write-LfJson {
    param(
        [Parameter(Mandatory)][object]$Value,
        [Parameter(Mandatory)][string]$Path
    )

    $text = $Value | ConvertTo-Json -Depth 100
    $text = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $text.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $text += "`n"
    }
    [System.IO.File]::WriteAllText($Path, $text, $utf8Output)
}

function Write-LfText {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Path
    )

    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8Output)
}

function Invoke-FixtureGit {
    param(
        [Parameter(Mandatory)][string]$RepositoryPath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$CaptureOutput
    )

    $output = @(& git -C $RepositoryPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "FIXTURE-GIT-FAILED $($Arguments -join ' ') :: $($output -join ' ')"
    }
    if ($CaptureOutput) {
        return (($output -join "`n").Trim())
    }
    return $null
}

function Copy-FixtureFile {
    param(
        [Parameter(Mandatory)][string]$SourceRepository,
        [Parameter(Mandatory)][string]$DestinationRepository,
        [Parameter(Mandatory)][string]$RelativePath
    )

    $sourcePath = Join-Path $SourceRepository $RelativePath
    $destinationPath = Join-Path $DestinationRepository $RelativePath
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "FIXTURE-SOURCE-MISSING $RelativePath"
    }
    [void][System.IO.Directory]::CreateDirectory(
        [System.IO.Path]::GetDirectoryName($destinationPath)
    )
    [System.IO.File]::Copy($sourcePath, $destinationPath, $true)
}

function Get-FixtureUtf8LfSha256 {
    param([Parameter(Mandatory)][string]$TextValue)

    $normalized = $TextValue.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    $bytes = $utf8Output.GetBytes($normalized)
    return [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($bytes)
    ).ToLowerInvariant()
}

function Get-FixtureBytesSha256 {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    return [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($Bytes)
    ).ToLowerInvariant()
}

function Write-FixtureCanonicalJsonElement {
    param(
        [Parameter(Mandatory)][System.Text.Json.Utf8JsonWriter]$Writer,
        [Parameter(Mandatory)][System.Text.Json.JsonElement]$Element
    )

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $Writer.WriteStartObject()
        $propertyNames = [string[]]@(
            $Element.EnumerateObject() | ForEach-Object { $_.Name }
        )
        [Array]::Sort($propertyNames, [System.StringComparer]::Ordinal)
        foreach ($propertyName in $propertyNames) {
            $Writer.WritePropertyName($propertyName)
            Write-FixtureCanonicalJsonElement `
                -Writer $Writer `
                -Element $Element.GetProperty($propertyName)
        }
        $Writer.WriteEndObject()
        return
    }
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        $Writer.WriteStartArray()
        foreach ($item in $Element.EnumerateArray()) {
            Write-FixtureCanonicalJsonElement -Writer $Writer -Element $item
        }
        $Writer.WriteEndArray()
        return
    }
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::String) {
        $Writer.WriteStringValue($Element.GetString())
        return
    }
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Number) {
        $Writer.WriteRawValue($Element.GetRawText(), $true)
        return
    }
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::True) {
        $Writer.WriteBooleanValue($true)
        return
    }
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::False) {
        $Writer.WriteBooleanValue($false)
        return
    }
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Null) {
        $Writer.WriteNullValue()
        return
    }
    throw "Unsupported fixture canonical JSON value kind: $($Element.ValueKind)"
}

function Get-FixtureImmutableStateSha256 {
    param([Parameter(Mandatory)][object]$Contract)

    $fields = [string[]]@($Contract.validation.immutableStateFields)
    [Array]::Sort($fields, [System.StringComparer]::Ordinal)
    $projection = [ordered]@{}
    foreach ($fieldName in $fields) {
        $projection[$fieldName] = $Contract.PSObject.Properties[$fieldName].Value
    }
    $serialized = ConvertTo-Json -InputObject $projection -Compress -Depth 100
    $document = [System.Text.Json.JsonDocument]::Parse($serialized)
    $memory = [System.IO.MemoryStream]::new()
    $writerOptions = [System.Text.Json.JsonWriterOptions]::new()
    $writerOptions.Indented = $false
    $writer = [System.Text.Json.Utf8JsonWriter]::new($memory, $writerOptions)
    try {
        Write-FixtureCanonicalJsonElement `
            -Writer $writer `
            -Element $document.RootElement
        $writer.Flush()
        return Get-FixtureUtf8LfSha256 -TextValue (
            $utf8Output.GetString($memory.ToArray())
        )
    }
    finally {
        $writer.Dispose()
        $document.Dispose()
        $memory.Dispose()
    }
}

function Get-FixtureDeviceLockState {
    param([Parameter(Mandatory)][string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $text = $utf8Strict.GetString($bytes)
    $lines = @($text.Replace("`r", "").Split("`n"))
    $start = -1
    $end = $lines.Count
    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex] -ceq "## 当前占用") {
            $start = $lineIndex + 1
            break
        }
    }
    if ($start -lt 0) {
        throw "FIXTURE-DEVICE-LOCK-SECTION-MISSING"
    }
    for ($lineIndex = $start; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex].StartsWith("## ", [System.StringComparison]::Ordinal)) {
            $end = $lineIndex
            break
        }
    }
    $activeCount = @(
        for ($lineIndex = $start; $lineIndex -lt $end; $lineIndex++) {
            if ($lines[$lineIndex] -match '^### .*— ACTIVE(?:\s|$)') {
                $lines[$lineIndex]
            }
        }
    ).Count
    return [pscustomobject]@{
        state = if ($activeCount -eq 0) {
            "RELEASED_ACTIVE_0"
        }
        else {
            "ACTIVE_COUNT_$activeCount"
        }
        sha256 = Get-FixtureBytesSha256 -Bytes $bytes
    }
}

function New-PostAuditRepositoryFixture {
    param(
        [Parameter(Mandatory)][string]$RootDirectory,
        [Parameter(Mandatory)][string]$BaseContractText
    )

    $historicalPostCommit = "b37f07331fbde5d3fbfe994c5bb5787a51737805"
    & git -C $repositoryRoot cat-file -e "$historicalPostCommit^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "FIXTURE-HISTORICAL-POST-COMMIT-MISSING"
    }

    $postRepo = Join-Path $RootDirectory "post-audit-repo"
    [void][System.IO.Directory]::CreateDirectory($postRepo)
    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("init", "-b", "codex/v2-integration") `
        -CaptureOutput)
    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("config", "user.name", "Aurorama Fixture"))
    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("config", "user.email", "fixture@example.invalid"))

    $baselinePath = Join-Path $postRepo "fixture-baseline.txt"
    [System.IO.File]::WriteAllText(
        $baselinePath,
        "handoff fixture baseline`n",
        $utf8Output
    )
    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("add", "fixture-baseline.txt"))
    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("commit", "-m", "fixture baseline"))
    $baselineCommit = Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("rev-parse", "HEAD") `
        -CaptureOutput

    $copyFiles = @(
        "AGENTS.md",
        "docs/PROJECT_RULES.md",
        "docs/PROJECT_PLAN.md",
        "docs/V2_PLAN.md",
        "docs/TASKS.md",
        "docs/LESSONS.md",
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json",
        "tools/v2-handoff/Invoke-HandoffPreflight.ps1",
        "tools/v2-handoff/Test-HandoffPreflight.ps1",
        ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/report.json"
    )
    foreach ($relativePath in $copyFiles) {
        Copy-FixtureFile `
            -SourceRepository $repositoryRoot `
            -DestinationRepository $postRepo `
            -RelativePath $relativePath
    }

    $qaToolsRelative = ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools"
    $sourceQaToolsRoot = Join-Path $repositoryRoot $qaToolsRelative
    foreach ($sourceTool in @(
        Get-ChildItem -LiteralPath $sourceQaToolsRoot -File -Recurse -Force
    )) {
        $relativeInsideTools = [System.IO.Path]::GetRelativePath(
            $sourceQaToolsRoot,
            $sourceTool.FullName
        )
        Copy-FixtureFile `
            -SourceRepository $repositoryRoot `
            -DestinationRepository $postRepo `
            -RelativePath (($qaToolsRelative -replace "/", "\") + "\" + $relativeInsideTools)
    }
    Copy-FixtureFile `
        -SourceRepository $repositoryRoot `
        -DestinationRepository $postRepo `
        -RelativePath ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools.sha256"

    $sourceContractPath = Join-Path $postRepo `
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
    $historicalProjectPlanPath = Join-Path $postRepo "docs/PROJECT_PLAN.md"
    $historicalProjectPlanLines = @(
        & git -C $repositoryRoot show `
            "$($historicalPostCommit):docs/PROJECT_PLAN.md" 2>$null
    )
    if ($LASTEXITCODE -ne 0) {
        throw "FIXTURE-HISTORICAL-PROJECT-PLAN-READ-FAILED"
    }
    Write-LfText `
        -Text (($historicalProjectPlanLines -join "`n") + "`n") `
        -Path $historicalProjectPlanPath
    $sourceContract = $BaseContractText | ConvertFrom-Json -Depth 100 -DateKind String
    $sourceContract.generatedAt = [DateTimeOffset]::Now.ToString("o")
    $sourceContract.inputBaseline.worktree = $postRepo
    $sourceContract.inputBaseline.commit = $baselineCommit
    $sourceContract.inputBaseline.qaV8HandoffHead = $baselineCommit
    $sourceContract.validation.handoffPhase = "PRE_HANDOFF_AUDIT"
    $sourceContract.validation.activePromptExpectedRoleId =
        "PRE_HANDOFF_AUDIT_OWNER"
    $sourceContract.validation.qaPromptRequiredStatus =
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    $sourceContract.qaRoundState.status =
        "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT"
    $sourceContract.qaRoundState.independentIntegrationReview = "PASS"
    $sourceContract.qaRoundState.preHandoffAudit =
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    $sourceContract.qaRoundState.padQaPromptDispatch = "BLOCKED"
    $sourceContract.qaRoundState.coreStepsExecuted = 0
    $sourceContract.qaRoundState.padTenMinuteCoreRun = "NOT_RUN"
    $sourceContract.qaRoundState.k60Registration = "NOT_REGISTERED"
    $sourceContract.qaRoundState.k60CoreRun = "NOT_RUN"
    $sourceContract.qaRoundState.cleanup = "PASS_CLEANUP_AND_RESTORE"
    $sourceContract.qaRoundState.padLockActiveCount = 0
    $sourceContract.auditReceipt = $null
    $sourceContract.activePrompt = "toolRepair"
    $sourceContract.next_prompt_path =
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json#/prompts/toolRepair/textLines"
    $sourceContract.nextPromptPath = $sourceContract.next_prompt_path
    $sourceContract.prompts.toolRepair.roleId = "PRE_HANDOFF_AUDIT_OWNER"
    $sourceContract.prompts.toolRepair.status = "ACTIVE"
    $sourceContract.prompts.toolRepair.dispatchable = $true
    $sourceContract.prompts.toolRepair.retiredReason = $null
    $sourceContract.prompts.padQa.roleId = "PAD_QA_OWNER"
    $sourceContract.prompts.padQa.status =
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    $sourceContract.prompts.padQa.dispatchable = $false
    $sourceContract.prompts.padQa.retiredReason = $null
    $sourceReviewGate = @(
        $sourceContract.requiredGates.lifecycle |
            Where-Object { $_.id -ceq "INDEPENDENT_INTEGRATION_REVIEW_PASS" }
    )[0]
    $sourceReviewGate.owner = "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
    $sourceReviewGate.status = "PASS"
    foreach ($gate in @($sourceContract.requiredGates.lifecycle)) {
        if ($gate.id -cin @("PRE_HANDOFF_AUDIT_PASS", "QA_DISPATCHABLE")) {
            $gate.status = "BLOCKED"
        }
        elseif ($gate.id -ne "INDEPENDENT_INTEGRATION_REVIEW_PASS") {
            $gate.status = "PASS"
        }
    }
    Write-LfJson -Value $sourceContract -Path $sourceContractPath
    $sourceContractText = [System.IO.File]::ReadAllText(
        $sourceContractPath,
        $utf8Strict
    )

    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("add", "-A"))
    [void](Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("commit", "-m", "fixture audited source"))
    $sourceCommit = Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("rev-parse", "HEAD") `
        -CaptureOutput
    $sourceTree = Invoke-FixtureGit `
        -RepositoryPath $postRepo `
        -Arguments @("rev-parse", "HEAD^{tree}") `
        -CaptureOutput

    $qaPromptText = @($sourceContract.prompts.padQa.textLines) -join "`n"
    $qaPromptSha256 = Get-FixtureUtf8LfSha256 -TextValue $qaPromptText
    $rejectedScopeJson = $sourceContract.rejectedScope |
        ConvertTo-Json -Compress -Depth 20
    $rejectedScopeHash = Get-FixtureUtf8LfSha256 -TextValue $rejectedScopeJson
    $memoryIndexPath = [string]$sourceContract.validation.memoryIndexPath
    $memoryIndexSha256 = Get-FixtureBytesSha256 `
        -Bytes ([System.IO.File]::ReadAllBytes($memoryIndexPath))
    $deviceLockPath = [string]$sourceContract.evidence.deviceLockPath
    $deviceLockSnapshot = Get-FixtureDeviceLockState -Path $deviceLockPath
    $immutableStateSha256 = Get-FixtureImmutableStateSha256 `
        -Contract $sourceContract

    $postContract = [System.IO.File]::ReadAllText(
        $sourceContractPath,
        $utf8Strict
    ) | ConvertFrom-Json -Depth 100 -DateKind String
    $postContract.validation.handoffPhase = "POST_HANDOFF_AUDIT_READY"
    $postContract.qaRoundState.lastQaVerdict = "BLOCKED_TOOL_NONCONVERGENCE"
    $postContract.qaRoundState.status = "POST_HANDOFF_AUDIT_READY"
    $postContract.qaRoundState.preHandoffAudit = "PASS"
    $postContract.qaRoundState.padQaPromptDispatch = "PASS"
    $postContract.qaRoundState.coreStepsExecuted = 0
    $postContract.qaRoundState.padTenMinuteCoreRun = "NOT_RUN"
    $postContract.qaRoundState.k60Registration = "NOT_REGISTERED"
    $postContract.qaRoundState.k60CoreRun = "NOT_RUN"
    $postContract.qaRoundState.cleanup = "PASS_CLEANUP_AND_RESTORE"
    $postContract.qaRoundState.padLockActiveCount = 0
    $postContract.activePrompt = "padQa"
    $postContract.next_prompt_path =
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json#/prompts/padQa/textLines"
    $postContract.nextPromptPath = $postContract.next_prompt_path
    $postContract.prompts.toolRepair.status = "RETIRED_AFTER_AUDIT_PASS"
    $postContract.prompts.toolRepair.dispatchable = $false
    $postContract.prompts.toolRepair.retiredReason = "PRE_HANDOFF_AUDIT_PASS"
    $postContract.prompts.padQa.status = "ACTIVE"
    $postContract.prompts.padQa.dispatchable = $true
    $postContract.prompts.padQa.retiredReason = $null
    $postContract.validation.activePromptExpectedRoleId = "PAD_QA_OWNER"
    $postContract.validation.qaPromptRequiredStatus = "ACTIVE"
    foreach ($gate in @($postContract.requiredGates.lifecycle)) {
        $gate.status = "PASS"
    }
    $postContract.auditReceipt = [ordered]@{
        schema = "aurorama.handoff-audit-receipt.v1"
        verdict = "PASS"
        auditorRole = "PRE_HANDOFF_AUDIT_OWNER"
        auditedSourceCommit = $sourceCommit
        auditedSourceTree = $sourceTree
        auditedAt = [DateTimeOffset]::Now.ToString("o")
        qaPromptSha256 = $qaPromptSha256
        toolsDigest = "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4"
        deviceLockState = $deviceLockSnapshot.state
        deviceLockSha256 = $deviceLockSnapshot.sha256
        qaEvidenceRoot = ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v9"
        rejectedScopeHash = $rejectedScopeHash
        memoryIndexSha256 = $memoryIndexSha256
        immutableStateSha256 = $immutableStateSha256
        transitionPaths = @(
            "docs/TASKS.md",
            "docs/V2_PLAN.md",
            "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
        )
    }

    foreach ($relativePath in @(
        "docs/V2_PLAN.md",
        "docs/TASKS.md"
    )) {
        $documentPath = Join-Path $postRepo $relativePath
        $historicalLines = @(
            & git -C $repositoryRoot show `
                "$($historicalPostCommit):$relativePath" 2>$null
        )
        if ($LASTEXITCODE -ne 0) {
            throw "FIXTURE-HISTORICAL-POST-DOC-READ-FAILED $relativePath"
        }
        Write-LfText -Text (($historicalLines -join "`n") + "`n") -Path $documentPath
    }
    Write-LfJson -Value $postContract -Path $sourceContractPath

    $artifactPaths = @(
        $postContract.acceptedArtifacts.main.path,
        $postContract.acceptedArtifacts.peer.path
    )
    foreach ($relativeArtifactPath in $artifactPaths) {
        $sourceArtifact = Join-Path $repositoryRoot $relativeArtifactPath
        $destinationArtifact = Join-Path $postRepo $relativeArtifactPath
        [void][System.IO.Directory]::CreateDirectory(
            [System.IO.Path]::GetDirectoryName($destinationArtifact)
        )
        [System.IO.File]::Copy($sourceArtifact, $destinationArtifact, $true)
    }
    $excludePath = Join-Path $postRepo ".git/info/exclude"
    Write-LfText `
        -Text ".planning/v2-g1/G1-L1-KOKORO-EXACT/harness/build/outputs/" `
        -Path $excludePath

    $postContractText = [System.IO.File]::ReadAllText(
        $sourceContractPath,
        $utf8Strict
    )
    $postTasksPath = Join-Path $postRepo "docs/TASKS.md"
    $postTasksText = [System.IO.File]::ReadAllText(
        $postTasksPath,
        $utf8Strict
    )
    return [pscustomobject]@{
        repositoryRoot = $postRepo
        contractPath = $sourceContractPath
        baselineCommit = $baselineCommit
        sourceCommit = $sourceCommit
        sourceTree = $sourceTree
        sourceContractText = $sourceContractText
        contractText = $postContractText
        tasksPath = $postTasksPath
        tasksText = $postTasksText
    }
}

function Remove-FixtureDirectory {
    param(
        [Parameter(Mandatory)][string]$Path
    )

    if (-not [System.IO.Directory]::Exists($Path)) {
        return
    }
    $systemTemp = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::GetTempPath()
    ).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    if (
        -not $resolvedPath.StartsWith(
            $systemTemp,
            [System.StringComparison]::OrdinalIgnoreCase
        )
    ) {
        throw "UNSAFE-FIXTURE-CLEANUP"
    }

    foreach ($item in @(
        Get-ChildItem -LiteralPath $resolvedPath -Force -Recurse
    )) {
        if (
            $item.Attributes -band
            [System.IO.FileAttributes]::ReadOnly
        ) {
            [System.IO.File]::SetAttributes(
                $item.FullName,
                [System.IO.FileAttributes]::Normal
            )
        }
    }
    [System.IO.Directory]::Delete($resolvedPath, $true)
}

function New-SourcePreMutationPostFixture {
    param(
        [Parameter(Mandatory)][string]$PostRepository,
        [Parameter(Mandatory)][string]$BasePostContractText,
        [Parameter(Mandatory)][string]$SourceContractText,
        [Parameter(Mandatory)][scriptblock]$Mutate,
        [scriptblock]$MutatePost,
        [Parameter(Mandatory)][string]$CommitMessage,
        [switch]$WithUtf8Bom
    )

    $sourceContractRelativePath =
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
    $sourceContractPath = Join-Path `
        $PostRepository `
        $sourceContractRelativePath
    $sourceContract = $SourceContractText |
        ConvertFrom-Json -Depth 100 -DateKind String
    & $Mutate $sourceContract
    Write-LfJson -Value $sourceContract -Path $sourceContractPath
    if ($WithUtf8Bom) {
        $sourceText = [System.IO.File]::ReadAllText(
            $sourceContractPath,
            $utf8Strict
        )
        [System.IO.File]::WriteAllText(
            $sourceContractPath,
            $sourceText,
            [System.Text.UTF8Encoding]::new($true)
        )
    }
    [void](Invoke-FixtureGit `
        -RepositoryPath $PostRepository `
        -Arguments @("add", "--", $sourceContractRelativePath))
    [void](Invoke-FixtureGit `
        -RepositoryPath $PostRepository `
        -Arguments @("commit", "-m", $CommitMessage))
    $newSourceCommit = Invoke-FixtureGit `
        -RepositoryPath $PostRepository `
        -Arguments @("rev-parse", "HEAD") `
        -CaptureOutput
    $newSourceTree = Invoke-FixtureGit `
        -RepositoryPath $PostRepository `
        -Arguments @("rev-parse", "HEAD^{tree}") `
        -CaptureOutput

    $postContract = $BasePostContractText |
        ConvertFrom-Json -Depth 100 -DateKind String
    $postContract.auditReceipt.auditedSourceCommit = $newSourceCommit
    $postContract.auditReceipt.auditedSourceTree = $newSourceTree
    $postContract.auditReceipt.auditedAt =
        [DateTimeOffset]::Now.ToString("o")
    if ($null -ne $MutatePost) {
        & $MutatePost $postContract
    }
    $postContract.auditReceipt.immutableStateSha256 =
        Get-FixtureImmutableStateSha256 -Contract $sourceContract
    Write-LfJson -Value $postContract -Path $sourceContractPath

    return [pscustomobject]@{
        sourceCommit = $newSourceCommit
        sourceTree = $newSourceTree
        postContractPath = $sourceContractPath
    }
}

function Invoke-Preflight {
    param(
        [string]$FixturePath,
        [string]$RepoRootOverride,
        [ValidateSet("next", "padQa", "none")]
        [string]$PromptName = "next",
        [switch]$EmitPrompt
    )

    $effectiveRepoRoot = if ([string]::IsNullOrWhiteSpace($RepoRootOverride)) {
        $repositoryRoot
    }
    else {
        [System.IO.Path]::GetFullPath($RepoRootOverride)
    }
    $arguments = @(
        "-NoProfile",
        "-File",
        $preflightPath,
        "-RepoRoot",
        $effectiveRepoRoot
    )
    if (-not [string]::IsNullOrWhiteSpace($FixturePath)) {
        $arguments += @("-ContractPath", $FixturePath)
    }
    if ($PromptName -ne "next") {
        $arguments += @("-Prompt", $PromptName)
    }
    if ($EmitPrompt) {
        $arguments += "-EmitPrompt"
    }

    $output = @(& pwsh @arguments 2>&1)
    return [pscustomobject]@{
        exitCode = $LASTEXITCODE
        output = $output
        firstLine = if ($output.Count -gt 0) { [string]$output[0] } else { "" }
    }
}

function Add-CaseResult {
    param(
        [Parameter(Mandatory)][string]$Case,
        [Parameter(Mandatory)][int]$ExpectedExit,
        [Parameter(Mandatory)][object]$Result
    )

    $passed = $Result.exitCode -eq $ExpectedExit
    if ($ExpectedExit -eq 0) {
        $passed = $passed -and $Result.firstLine.StartsWith(
            "PASS ",
            [System.StringComparison]::Ordinal
        )
    }
    else {
        $passed = $passed -and $Result.firstLine.StartsWith(
            "FAIL_CLOSED ",
            [System.StringComparison]::Ordinal
        )
    }
    if (-not $passed) {
        Write-Output (
            "CASE_DIAGNOSTIC {0} expected={1} actual={2}" -f
            $Case,
            $ExpectedExit,
            $Result.exitCode
        )
        foreach ($diagnosticLine in @($Result.output | Select-Object -First 8)) {
            Write-Output ("CASE_OUTPUT " + [string]$diagnosticLine)
        }
    }
    Assert-Condition -Condition $passed -Name "CASE-$Case"
    $testResults.Add([pscustomobject]@{
        case = $Case
        expected = $ExpectedExit
        actual = $Result.exitCode
        result = "PASS"
    })
    Write-Output (
        "CASE {0} expected={1} actual={2} result=PASS" -f
        $Case,
        $ExpectedExit,
        $Result.exitCode
    )
}

function Get-Gate {
    param(
        [Parameter(Mandatory)][object]$Contract,
        [Parameter(Mandatory)][string]$GateId
    )

    return @(
        $Contract.requiredGates.lifecycle |
            Where-Object { $_.id -ceq $GateId }
    )[0]
}

function Get-Role {
    param(
        [Parameter(Mandatory)][object]$Contract,
        [Parameter(Mandatory)][string]$RoleId
    )

    return @(
        $Contract.roles |
            Where-Object { $_.id -ceq $RoleId }
    )[0]
}

function Get-ToolsMtimeSnapshot {
    param(
        [Parameter(Mandatory)][string]$ToolsRoot,
        [Parameter(Mandatory)][string]$ManifestPath
    )

    $rootItem = Get-Item -LiteralPath $ToolsRoot
    $manifestItem = Get-Item -LiteralPath $ManifestPath
    $files = @(
        Get-ChildItem -LiteralPath $ToolsRoot -File -Recurse -Force |
            Sort-Object -Property FullName
    )
    $fileSnapshot = @(
        foreach ($file in $files) {
            "{0}|{1}|{2}" -f
            $file.FullName,
            $file.Length,
            $file.LastWriteTimeUtc.ToString("o")
        }
    )
    return (
        @(
            $rootItem.LastWriteTimeUtc.ToString("o"),
            $manifestItem.Length,
            $manifestItem.LastWriteTimeUtc.ToString("o")
        ) + $fileSnapshot
    ) -join "`n"
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $tempBase ("aurorama-b6-preflight-" + [guid]::NewGuid().ToString("N")))
)
if (-not $tempDirectory.StartsWith(
    $tempBase,
    [System.StringComparison]::OrdinalIgnoreCase
)) {
    throw "Unsafe temporary directory."
}

$qaToolsRoot = Join-Path $repositoryRoot ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools"
$qaManifestPath = Join-Path $repositoryRoot ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools.sha256"
$toolsMtimeBefore = Get-ToolsMtimeSnapshot `
    -ToolsRoot $qaToolsRoot `
    -ManifestPath $qaManifestPath

try {
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    $baseContractText = [System.IO.File]::ReadAllText(
        $contractPath,
        $utf8Strict
    )
    $baseContract = $baseContractText | ConvertFrom-Json -Depth 100 -DateKind String

    Add-CaseResult `
        -Case "current-contract" `
        -ExpectedExit 0 `
        -Result (Invoke-Preflight)

    $negativeCases = @(
        @{
            name = "audit-pass-without-allowed-phase"
            mutate = {
                param($contract)
                $contract.validation.handoffPhase = "PRE_HANDOFF_AUDIT"
                $contract.auditReceipt = $null
                $contract.qaRoundState.preHandoffAudit = "PASS"
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").status = "PASS"
            }
        },
        @{
            name = "audit-owner-drift"
            mutate = {
                param($contract)
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").owner = "PAD_QA_OWNER"
            }
        },
        @{
            name = "review-requiredBefore-missing"
            mutate = {
                param($contract)
                $gate = Get-Gate $contract "INDEPENDENT_INTEGRATION_REVIEW_PASS"
                [void]$gate.PSObject.Properties.Remove("requiredBefore")
            }
        },
        @{
            name = "first-action-arbitrary-path"
            mutate = {
                param($contract)
                $badPath = "C:\Windows\Temp\arbitrary.ps1"
                $command = @($contract.prompts.toolRepair.command)
                $command[3] = $badPath
                $contract.prompts.toolRepair.command = $command
                $badAction = $contract.prompts.toolRepair.firstAction.Replace(
                    $preflightPath,
                    $badPath
                )
                $contract.prompts.toolRepair.firstAction = $badAction
                $contract.firstReadOnlyActions.toolRepair[0] = $badAction
                $contract.prompts.toolRepair.textLines[2] = (
                    "第一条只读动作必须实际执行：" + $badAction +
                    "。若非零或 FAIL_CLOSED，立即停止并只报压缩失败。"
                )
            }
        },
        @{
            name = "first-action-unknown-argument"
            mutate = {
                param($contract)
                $command = @($contract.prompts.toolRepair.command)
                $contract.prompts.toolRepair.command = @($command + "-Unknown")
                $badAction = $contract.prompts.toolRepair.firstAction + " -Unknown"
                $contract.prompts.toolRepair.firstAction = $badAction
                $contract.firstReadOnlyActions.toolRepair[0] = $badAction
                $contract.prompts.toolRepair.textLines[2] = (
                    "第一条只读动作必须实际执行：" + $badAction +
                    "。若非零或 FAIL_CLOSED，立即停止并只报压缩失败。"
                )
            }
        },
        @{
            name = "gate-order-swapped"
            mutate = {
                param($contract)
                $gates = @($contract.requiredGates.lifecycle)
                $contract.requiredGates.lifecycle = @(
                    $gates[0],
                    $gates[1],
                    $gates[2],
                    $gates[4],
                    $gates[3],
                    $gates[5]
                )
            }
        },
        @{
            name = "gate-duplicate"
            mutate = {
                param($contract)
                $gates = @($contract.requiredGates.lifecycle)
                $contract.requiredGates.lifecycle = @(
                    $gates[0],
                    $gates[1],
                    $gates[2],
                    $gates[3],
                    $gates[3],
                    $gates[5]
                )
            }
        },
        @{
            name = "gate-missing"
            mutate = {
                param($contract)
                $gates = @($contract.requiredGates.lifecycle)
                $contract.requiredGates.lifecycle = @(
                    $gates[0],
                    $gates[1],
                    $gates[2],
                    $gates[3],
                    $gates[4]
                )
            }
        },
        @{
            name = "qa-pass-before-audit"
            mutate = {
                param($contract)
                $contract.qaRoundState.padQaPromptDispatch = "PASS"
                (Get-Gate $contract "QA_DISPATCHABLE").status = "PASS"
            }
        },
        @{
            name = "top-level-field-missing"
            mutate = {
                param($contract)
                [void]$contract.PSObject.Properties.Remove("readOrder")
            }
        },
        @{
            name = "top-level-field-extra"
            mutate = {
                param($contract)
                $contract | Add-Member `
                    -NotePropertyName undocumentedMutationScope `
                    -NotePropertyValue "ALLOW"
            }
        },
        @{
            name = "role-table-incomplete"
            mutate = {
                param($contract)
                $contract.roles = @(
                    $contract.roles |
                        Where-Object { $_.id -cne "PRE_HANDOFF_AUDIT_OWNER" }
                )
            }
        },
        @{
            name = "window-30-minutes"
            mutate = {
                param($contract)
                $contract.devicePolicy.DEVICE_WINDOW_MINUTES = 30
            }
        },
        @{
            name = "k60-before-pad"
            mutate = {
                param($contract)
                $devices = @($contract.devicePolicy.devices)
                $contract.devicePolicy.DEVICE_ORDER = "K60->Pad"
                $contract.devicePolicy.devices = @($devices[1], $devices[0])
            }
        },
        @{
            name = "retired-round3-prompt-owner"
            mutate = {
                param($contract)
                $contract.prompts.toolRepair.roleId =
                    "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER"
            }
        },
        @{
            name = "pre-audit-receipt-present"
            mutate = {
                param($contract)
                $contract.auditReceipt = [ordered]@{}
            }
        },
        @{
            name = "pre-padQa-dispatchable"
            mutate = {
                param($contract)
                $contract.prompts.padQa.dispatchable = $true
            }
        },
        @{
            name = "handoff-phase-unknown"
            mutate = {
                param($contract)
                $contract.validation.handoffPhase = "POST_AUDIT"
            }
        },
        @{
            name = "qa-prompt-stale-force-add"
            mutate = {
                param($contract)
                $contract.prompts.padQa.textLines[10] +=
                    " 尚未 commit 的 force-add 集成说明仍有效。"
            }
        },
        @{
            name = "round4-role-missing"
            mutate = {
                param($contract)
                $contract.roles = @(
                    $contract.roles |
                        Where-Object {
                            $_.id -cne
                            "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER"
                        }
                )
            }
        },
        @{
            name = "role-padqa-production-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role $contract "PAD_QA_OWNER"
                $role.writeScope = @(
                    $role.writeScope + "app/phone/src/main/**"
                )
            }
        },
        @{
            name = "role-tool-repair-production-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role $contract "TOOL_REPAIR_OWNER"
                $role.writeScope = @(
                    $role.writeScope + "app/phone/src/main/**"
                )
            }
        },
        @{
            name = "role-round8-review-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role `
                    $contract `
                    "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER"
                $role.writeScope = @("docs/TASKS.md")
            }
        },
        @{
            name = "role-round9-review-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role `
                    $contract `
                    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
                $role.writeScope = @("docs/TASKS.md")
            }
        },
        @{
            name = "role-audit-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role $contract "PRE_HANDOFF_AUDIT_OWNER"
                $role.writeScope = @("docs/TASKS.md")
            }
        },
        @{
            name = "role-root-qa-evidence-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role $contract "ROOT_INTEGRATION"
                $role.writeScope = @(
                    $role.writeScope +
                    ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v9/**"
                )
            }
        },
        @{
            name = "role-root-production-writeScope"
            mutate = {
                param($contract)
                $role = Get-Role $contract "ROOT_INTEGRATION"
                $role.writeScope = @(
                    $role.writeScope + "app/phone/src/main/**"
                )
            }
        },
        @{
            name = "role-unknown"
            mutate = {
                param($contract)
                $unknownRole = $contract.roles[0] |
                    ConvertTo-Json -Depth 20 |
                    ConvertFrom-Json -Depth 20
                $unknownRole.id = "UNKNOWN_ROLE"
                $contract.roles = @($contract.roles + $unknownRole)
            }
        },
        @{
            name = "role-duplicate"
            mutate = {
                param($contract)
                $duplicateRole = (Get-Role $contract "PAD_QA_OWNER") |
                    ConvertTo-Json -Depth 20 |
                    ConvertFrom-Json -Depth 20
                $contract.roles = @($contract.roles + $duplicateRole)
            }
        },
        @{
            name = "role-writeScope-whitespace"
            mutate = {
                param($contract)
                $role = Get-Role $contract "PAD_QA_OWNER"
                $role.writeScope = @(
                    " .planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v9/**"
                )
            }
        },
        @{
            name = "role-writeScope-traversal"
            mutate = {
                param($contract)
                $role = Get-Role $contract "TOOL_REPAIR_OWNER"
                $role.writeScope = @(
                    ".planning/v2-g1/../app/phone/src/main/**"
                )
            }
        },
        @{
            name = "role-writeScope-wildcard-overreach"
            mutate = {
                param($contract)
                $role = Get-Role $contract "TOOL_REPAIR_OWNER"
                $role.writeScope = @(
                    ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/**"
                )
            }
        },
        @{
            name = "role-writeScope-duplicate"
            mutate = {
                param($contract)
                $role = Get-Role $contract "PAD_QA_OWNER"
                $role.writeScope = @(
                    $role.writeScope[0],
                    $role.writeScope[0]
                )
            }
        },
        @{
            name = "role-kind-drift"
            mutate = {
                param($contract)
                (Get-Role $contract "ROOT_INTEGRATION").kind = "SPECIALIST"
            }
        },
        @{
            name = "role-freshness-drift"
            mutate = {
                param($contract)
                (Get-Role $contract "DOC_PROTOCOL_OWNER").freshness =
                    "THREAD_BOUND"
            }
        },
        @{
            name = "role-phase-drift"
            mutate = {
                param($contract)
                (Get-Role `
                    $contract `
                    "HANDOFF_ROLE_SCOPE_HARDENING_REPAIR_OWNER"
                ).phaseLimit = "MANY"
            }
        },
        @{
            name = "role-reuse-drift"
            mutate = {
                param($contract)
                (Get-Role $contract "PAD_QA_OWNER").reuse = "ALLOWED"
            }
        },
        @{
            name = "role-k60-premature-device-access"
            mutate = {
                param($contract)
                (Get-Role $contract "K60_QA_OWNER").deviceAccess = "PAD_ONLY"
            }
        },
        @{
            name = "role-owner-identity-blank"
            mutate = {
                param($contract)
                (Get-Role `
                    $contract `
                    "HANDOFF_ROLE_SCOPE_HARDENING_REPAIR_OWNER"
                ).identity = " "
            }
        },
        @{
            name = "pre-round8-status-residual"
            mutate = {
                param($contract)
                $contract.qaRoundState.status =
                    "ROLE_SCOPE_P1_REPAIRED_AWAITING_FRESH_ROUND8_REVIEW"
                $contract.qaRoundState.independentIntegrationReview =
                    "BLOCKED_PENDING_ROUND8_REVIEW"
                (Get-Gate `
                    $contract `
                    "INDEPENDENT_INTEGRATION_REVIEW_PASS"
                ).owner = "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER"
            }
        },
        @{
            name = "deferred-padQa-active"
            mutate = {
                param($contract)
                $contract.prompts.padQa.status = "ACTIVE"
                $contract.prompts.padQa.dispatchable = $true
                $contract.prompts.padQa.retiredReason = "SHOULD_NOT_BE_PRESENT"
            }
        },
        @{
            name = "deferred-qa-gate-pass"
            mutate = {
                param($contract)
                (Get-Gate $contract "QA_DISPATCHABLE").status = "PASS"
            }
        },
        @{
            name = "deferred-product-assessed"
            mutate = {
                param($contract)
                $contract.qaRoundState.productFindings.P0 = "0"
            }
        },
        @{
            name = "deferred-k60-unlocked"
            mutate = {
                param($contract)
                $contract.qaRoundState.k60Registration = "REGISTERED"
                $contract.qaRoundState.k60CoreRun = "RUN"
            }
        },
        @{
            name = "deferred-audit-receipt-missing"
            mutate = {
                param($contract)
                $contract.auditReceipt = $null
            }
        },
        @{
            name = "deferred-historical-lock-hash-rewritten"
            mutate = {
                param($contract)
                $contract.auditReceipt.deviceLockSha256 = ("f" * 64)
            }
        },
        @{
            name = "deferred-phase-still-post"
            mutate = {
                param($contract)
                $contract.validation.handoffPhase = "POST_HANDOFF_AUDIT_READY"
            }
        }
    )

    foreach ($case in $negativeCases) {
        $fixture = $baseContractText | ConvertFrom-Json -Depth 100 -DateKind String
        & $case.mutate $fixture
        $fixturePath = Join-Path $tempDirectory ($case.name + ".json")
        Write-LfJson -Value $fixture -Path $fixturePath
        Add-CaseResult `
            -Case $case.name `
            -ExpectedExit 2 `
            -Result (Invoke-Preflight -FixturePath $fixturePath)
    }

    Add-CaseResult `
        -Case "direct-padQa-request" `
        -ExpectedExit 2 `
        -Result (Invoke-Preflight -PromptName "padQa")

    $deferredEmitResult = Invoke-Preflight -EmitPrompt
    Add-CaseResult `
        -Case "deferred-emit-no-qa-prompt" `
        -ExpectedExit 0 `
        -Result $deferredEmitResult
    $deferredEmitText = @($deferredEmitResult.output) -join "`n"
    Assert-Condition `
        -Condition (
            $deferredEmitText.Contains(
                "当前合同没有可派发 QA prompt",
                [System.StringComparison]::Ordinal
            ) -and
            $deferredEmitText.Contains(
                "G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS",
                [System.StringComparison]::Ordinal
            ) -and
            $deferredEmitText.Contains(
                "G2_RUNTIME_CONTRACT_PACKAGE_READY",
                [System.StringComparison]::Ordinal
            ) -and
            $deferredEmitText.Contains(
                "PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA",
                [System.StringComparison]::Ordinal
            ) -and
            $deferredEmitText.Contains(
                "K60_GATED_LOCKED",
                [System.StringComparison]::Ordinal
            ) -and
            $deferredEmitText.Contains(
                "V2-D210",
                [System.StringComparison]::Ordinal
            ) -and
            -not $deferredEmitText.Contains(
                "专业身份：PAD_QA_OWNER",
                [System.StringComparison]::Ordinal
            )
        ) `
        -Name "DEFERRED-EMIT-NO-QA-ROLE"

    $g2TransitionProbe = Join-Path `
        $repositoryRoot `
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/preflight-transition-probe.json"
    try {
        Write-LfText -Text "{`"probe`":true}`n" -Path $g2TransitionProbe
        $g2TransitionResult = Invoke-Preflight
        Assert-Condition `
            -Condition (
                $g2TransitionResult.exitCode -eq 0 -and
                $g2TransitionResult.firstLine.StartsWith(
                    "PASS ",
                    [System.StringComparison]::Ordinal
                )
            ) `
            -Name "POST-QA-G2-TRANSITION-ALLOWED"
        Write-Output "ASSERT POST-QA-G2-TRANSITION-ALLOWED=PASS"
    }
    finally {
        if (Test-Path -LiteralPath $g2TransitionProbe) {
            [System.IO.File]::Delete($g2TransitionProbe)
        }
    }

    $postFixture = New-PostAuditRepositoryFixture `
        -RootDirectory $tempDirectory `
        -BaseContractText $baseContractText
    $postBaseText = $postFixture.contractText
    $postBaseContract = $postBaseText | ConvertFrom-Json -Depth 100 -DateKind String
    $postV2PlanPath = Join-Path $postFixture.repositoryRoot "docs/V2_PLAN.md"
    $postProjectPlanPath = Join-Path $postFixture.repositoryRoot "docs/PROJECT_PLAN.md"
    $postV2PlanText = [System.IO.File]::ReadAllText(
        $postV2PlanPath,
        $utf8Strict
    )
    $postProjectPlanText = [System.IO.File]::ReadAllText(
        $postProjectPlanPath,
        $utf8Strict
    )
    $postTasksText = [System.IO.File]::ReadAllText(
        $postFixture.tasksPath,
        $utf8Strict
    )

    $preReviewPassReplacements = [ordered]@{
        "ROUND8_PASS_PRE_STATE_REPAIRED_AWAITING_FRESH_ROUND9_REVIEW" =
            "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT"
        "BLOCKED_PENDING_ROUND9_REVIEW" = "PASS"
        "INDEPENDENT_INTEGRATION_REVIEW_PASS=BLOCKED" =
            "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS"
    }
    $preReviewPassDocumentTexts = [ordered]@{}
    $historicalPreCommit = [string]$baseContract.auditReceipt.auditedSourceCommit
    if ($historicalPreCommit -notmatch "^[0-9a-f]{40}$") {
        throw "FIXTURE-HISTORICAL-PRE-COMMIT-INVALID"
    }
    foreach ($relativePath in @(
        "docs/PROJECT_PLAN.md",
        "docs/V2_PLAN.md",
        "docs/TASKS.md"
    )) {
        $committedLines = @(
            & git -C $repositoryRoot show `
                "$($historicalPreCommit):$relativePath" 2>$null
        )
        if ($LASTEXITCODE -ne 0) {
            throw "FIXTURE-PRE-REVIEW-PASS-DOC-READ-FAILED $relativePath"
        }
        $documentText = ($committedLines -join "`n") + "`n"
        foreach ($replacementName in $preReviewPassReplacements.Keys) {
            $documentText = $documentText.Replace(
                $replacementName,
                $preReviewPassReplacements[$replacementName]
            )
        }
        $preReviewPassDocumentTexts[$relativePath] = $documentText
    }

    Write-LfText -Text $postFixture.sourceContractText -Path $postFixture.contractPath
    foreach ($relativePath in $preReviewPassDocumentTexts.Keys) {
        Write-LfText `
            -Text $preReviewPassDocumentTexts[$relativePath] `
            -Path (Join-Path $postFixture.repositoryRoot $relativePath)
    }
    Add-CaseResult `
        -Case "pre-review-pass-awaiting-audit-valid" `
        -ExpectedExit 0 `
        -Result (Invoke-Preflight `
            -FixturePath $postFixture.contractPath `
            -RepoRootOverride $postFixture.repositoryRoot)

    $reviewPassPendingText = $preReviewPassDocumentTexts["docs/V2_PLAN.md"]
    $reviewPassLines = @($reviewPassPendingText -split "`n")
    $reviewPassActiveStart = [Array]::FindIndex(
        [string[]]$reviewPassLines,
        [Predicate[string]] {
            param($line)
            $line.StartsWith("### ACTIVE", [System.StringComparison]::Ordinal)
        }
    )
    $reviewPassActiveEnd = [Array]::FindIndex(
        [string[]]$reviewPassLines,
        $reviewPassActiveStart + 1,
        [Predicate[string]] {
            param($line)
            $line.StartsWith("### HISTORY", [System.StringComparison]::Ordinal)
        }
    )
    if ($reviewPassActiveStart -lt 0 -or $reviewPassActiveEnd -lt 0) {
        throw "FIXTURE-PRE-REVIEW-PASS-ACTIVE-RANGE-INVALID"
    }
    $reviewPassActiveText = @(
        $reviewPassLines[$reviewPassActiveStart..($reviewPassActiveEnd - 1)]
    ) -join "`n"
    $reviewPassActiveText = $reviewPassActiveText.Replace(
        "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT",
        "ROUND9_REVIEW_TOKEN_REMOVED"
    )
    $reviewPassTokenDriftText = @(
        @($reviewPassLines[0..($reviewPassActiveStart - 1)])
        @($reviewPassActiveText -split "`n")
        @($reviewPassLines[$reviewPassActiveEnd..($reviewPassLines.Count - 1)])
    ) -join "`n"
    Write-LfText -Text $reviewPassTokenDriftText -Path $postV2PlanPath
    Add-CaseResult `
        -Case "pre-review-pass-active-token-mismatch" `
        -ExpectedExit 2 `
        -Result (Invoke-Preflight `
            -FixturePath $postFixture.contractPath `
            -RepoRootOverride $postFixture.repositoryRoot)
    Write-LfText -Text $reviewPassPendingText -Path $postV2PlanPath

    $preReviewPassNegativeCases = @(
        @{
            name = "pre-review-pass-audit-gate-pass"
            mutate = {
                param($contract)
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").status = "PASS"
            }
        },
        @{
            name = "pre-review-pass-qa-gate-pass"
            mutate = {
                param($contract)
                (Get-Gate $contract "QA_DISPATCHABLE").status = "PASS"
            }
        },
        @{
            name = "pre-review-pass-qa-round-audit-pass"
            mutate = {
                param($contract)
                $contract.qaRoundState.preHandoffAudit = "PASS"
            }
        },
        @{
            name = "pre-review-pass-padQa-dispatchable"
            mutate = {
                param($contract)
                $contract.qaRoundState.padQaPromptDispatch = "PASS"
                $contract.prompts.padQa.status = "ACTIVE"
                $contract.prompts.padQa.dispatchable = $true
            }
        },
        @{
            name = "pre-review-pass-receipt-nonnull"
            mutate = {
                param($contract)
                $contract.auditReceipt = [ordered]@{}
            }
        },
        @{
            name = "pre-review-pass-review-gate-blocked"
            mutate = {
                param($contract)
                (Get-Gate `
                    $contract `
                    "INDEPENDENT_INTEGRATION_REVIEW_PASS"
                ).status = "BLOCKED"
            }
        }
    )
    foreach ($case in $preReviewPassNegativeCases) {
        Write-LfText -Text $postFixture.sourceContractText -Path $postFixture.contractPath
        Write-LfText -Text $reviewPassPendingText -Path $postV2PlanPath
        $fixture = $postFixture.sourceContractText |
            ConvertFrom-Json -Depth 100 -DateKind String
        & $case.mutate $fixture
        Write-LfJson -Value $fixture -Path $postFixture.contractPath
        Add-CaseResult `
            -Case $case.name `
            -ExpectedExit 2 `
            -Result (Invoke-Preflight `
                -FixturePath $postFixture.contractPath `
                -RepoRootOverride $postFixture.repositoryRoot)
    }

    Write-LfText -Text $postBaseText -Path $postFixture.contractPath
    Write-LfText -Text $postV2PlanText -Path $postV2PlanPath
    Write-LfText -Text $postProjectPlanText -Path $postProjectPlanPath
    Write-LfText -Text $postTasksText -Path $postFixture.tasksPath

    Add-CaseResult `
        -Case "post-audit-ready-valid" `
        -ExpectedExit 0 `
        -Result (Invoke-Preflight `
            -FixturePath $postFixture.contractPath `
            -RepoRootOverride $postFixture.repositoryRoot)

    Add-CaseResult `
        -Case "post-audit-direct-padQa" `
        -ExpectedExit 0 `
        -Result (Invoke-Preflight `
            -FixturePath $postFixture.contractPath `
            -RepoRootOverride $postFixture.repositoryRoot `
            -PromptName "padQa")

    $sourceNegativeCases = @(
        @{
            name = "source-audit-gate-pass"
            mutate = {
                param($contract)
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").status = "PASS"
            }
        },
        @{
            name = "source-qa-gate-pass"
            mutate = {
                param($contract)
                (Get-Gate $contract "QA_DISPATCHABLE").status = "PASS"
            }
        },
        @{
            name = "source-qa-round-audit-pass"
            mutate = {
                param($contract)
                $contract.qaRoundState.preHandoffAudit = "PASS"
            }
        },
        @{
            name = "source-qa-round-dispatch-pass"
            mutate = {
                param($contract)
                $contract.qaRoundState.padQaPromptDispatch = "PASS"
            }
        },
        @{
            name = "source-padQa-active-dispatchable"
            mutate = {
                param($contract)
                $contract.prompts.padQa.status = "ACTIVE"
                $contract.prompts.padQa.dispatchable = $true
            }
        },
        @{
            name = "source-active-prompt-padQa"
            mutate = {
                param($contract)
                $contract.activePrompt = "padQa"
            }
        },
        @{
            name = "source-receipt-nonnull"
            mutate = {
                param($contract)
                $contract.auditReceipt = [ordered]@{}
            }
        },
        @{
            name = "source-audit-owner-drift"
            mutate = {
                param($contract)
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").owner =
                    "PAD_QA_OWNER"
            }
        },
        @{
            name = "source-audit-required-before-missing"
            mutate = {
                param($contract)
                $gate = Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS"
                [void]$gate.PSObject.Properties.Remove("requiredBefore")
            }
        },
        @{
            name = "source-anchor-pointer-drift"
            mutate = {
                param($contract)
                $contract.validation.requiredTextAnchors = @(
                    $contract.validation.requiredTextAnchors |
                        Where-Object { $_.id -cne "LESSONS" }
                )
            }
        },
        @{
            name = "source-tools-digest-drift"
            mutate = {
                param($contract)
                $contract.qaTools.digestSha256 = ("0" * 64)
            }
        },
        @{
            name = "source-apk-sha-drift"
            mutate = {
                param($contract)
                $contract.acceptedArtifacts.main.sha256 = ("0" * 64)
            }
        },
        @{
            name = "source-contract-utf8-bom"
            withUtf8Bom = $true
            mutate = {
                param($contract)
                $null = $contract
            }
        }
    )
    foreach ($case in $sourceNegativeCases) {
        $sourceFixture = New-SourcePreMutationPostFixture `
            -PostRepository $postFixture.repositoryRoot `
            -BasePostContractText $postBaseText `
            -SourceContractText $postFixture.sourceContractText `
            -Mutate $case.mutate `
            -CommitMessage ("fixture " + $case.name) `
            -WithUtf8Bom:($case.ContainsKey("withUtf8Bom") -and [bool]$case.withUtf8Bom)
        Add-CaseResult `
            -Case $case.name `
            -ExpectedExit 2 `
            -Result (Invoke-Preflight `
                -FixturePath $sourceFixture.postContractPath `
                -RepoRootOverride $postFixture.repositoryRoot)
    }

    $synchronizedPadQaProductionScopeMutation = {
        param($contract)
        $role = Get-Role $contract "PAD_QA_OWNER"
        $role.writeScope = @(
            $role.writeScope + "app/phone/src/main/**"
        )
    }
    $synchronizedScopeFixture = New-SourcePreMutationPostFixture `
        -PostRepository $postFixture.repositoryRoot `
        -BasePostContractText $postBaseText `
        -SourceContractText $postFixture.sourceContractText `
        -Mutate $synchronizedPadQaProductionScopeMutation `
        -MutatePost $synchronizedPadQaProductionScopeMutation `
        -CommitMessage "fixture source-current-synchronized-padqa-scope"
    $synchronizedScopeResult = Invoke-Preflight `
        -FixturePath $synchronizedScopeFixture.postContractPath `
        -RepoRootOverride $postFixture.repositoryRoot
    Assert-Condition `
        -Condition ((@($synchronizedScopeResult.output) -join "`n") -match `
            "ROLE-WRITE-SCOPE-(SET-DRIFT|PRODUCTION)-PAD_QA_OWNER") `
        -Name "SOURCE-CURRENT-SYNCHRONIZED-PADQA-SEMANTIC-SCOPE"
    Add-CaseResult `
        -Case "source-current-synchronized-padqa-production-scope" `
        -ExpectedExit 2 `
        -Result $synchronizedScopeResult

    Write-LfText -Text $postBaseText -Path $postFixture.contractPath
    $anchorSpaceContract = $postBaseText |
        ConvertFrom-Json -Depth 100 -DateKind String
    $tasksAnchor = @(
        $anchorSpaceContract.validation.requiredTextAnchors |
            Where-Object { $_.path -ceq "docs/TASKS.md" }
    )[0]
    $tasksAnchorSpaceTokens = @(
        "ANCHOR_P1_REPAIRED_AWAITING_FRESH_ROUND6_REVIEW",
        "ROUND6_READONLY_INTEGRATION_REVIEW_OWNER",
        "PAD_10_MIN_CORE_NOT_RUN",
        "K60_NOT_REGISTERED_NOT_RUN",
        "PRODUCT_P0_P3_NOT_ASSESSED",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "25/25"
    ) | ForEach-Object { " " * $_.Length }
    $tasksAnchor | Add-Member `
        -NotePropertyName "tokens" `
        -NotePropertyValue $tasksAnchorSpaceTokens
    Write-LfJson -Value $anchorSpaceContract -Path $postFixture.contractPath
    $preTasksLines = @(
        & git -C $postFixture.repositoryRoot show `
            "$($postFixture.sourceCommit):docs/TASKS.md" 2>$null
    )
    if ($LASTEXITCODE -ne 0) {
        throw "FIXTURE-PRE-TASKS-READ-FAILED"
    }
    $preTasksText = ($preTasksLines -join "`n") + "`n"
    $harmlessWhitespaceComments = @(
        $tasksAnchorSpaceTokens |
            ForEach-Object { "<!--$($_ )-->" }
    ) -join "`n"
    Write-LfText `
        -Text ($preTasksText + $harmlessWhitespaceComments) `
        -Path (Join-Path $postFixture.repositoryRoot "docs/TASKS.md")
    Add-CaseResult `
        -Case "post-anchor-tokens-spaces-pretasks-comment" `
        -ExpectedExit 2 `
        -Result (Invoke-Preflight `
            -FixturePath $postFixture.contractPath `
            -RepoRootOverride $postFixture.repositoryRoot)
    Write-LfText `
        -Text $postFixture.tasksText `
        -Path $postFixture.tasksPath

    $siblingCommit = Invoke-FixtureGit `
        -RepositoryPath $postFixture.repositoryRoot `
        -Arguments @(
            "commit-tree",
            $postFixture.sourceTree,
            "-p",
            $postFixture.baselineCommit,
            "-m",
            "fixture sibling"
        ) `
        -CaptureOutput

    $postNegativeCases = @(
        @{
            name = "post-receipt-missing"
            mutate = {
                param($contract)
                $contract.auditReceipt = $null
            }
        },
        @{
            name = "post-receipt-extra-field"
            mutate = {
                param($contract)
                $contract.auditReceipt | Add-Member `
                    -NotePropertyName skipped `
                    -NotePropertyValue "YES"
            }
        },
        @{
            name = "post-verdict-not-pass"
            mutate = {
                param($contract)
                $contract.auditReceipt.verdict = "BLOCKED"
            }
        },
        @{
            name = "post-auditor-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.auditorRole = "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER"
            }
        },
        @{
            name = "post-source-not-ancestor"
            mutate = {
                param($contract)
                $contract.auditReceipt.auditedSourceCommit = $siblingCommit
            }
        },
        @{
            name = "post-source-tree-mismatch"
            mutate = {
                param($contract)
                $contract.auditReceipt.auditedSourceTree =
                    ("0" * 40)
            }
        },
        @{
            name = "post-immutable-accepted-versionName"
            mutate = {
                param($contract)
                $contract.acceptedArtifacts.main.versionName = "tampered-version"
            }
        },
        @{
            name = "post-role-writeScope-append"
            mutate = {
                param($contract)
                $role = @(
                    $contract.roles |
                        Where-Object { $_.id -ceq "PAD_QA_OWNER" }
                )[0]
                $role.writeScope = @($role.writeScope + "app/phone/src/main/**")
            }
        },
        @{
            name = "post-stop-condition-deleted"
            mutate = {
                param($contract)
                $contract.stopConditions = @(
                    $contract.stopConditions |
                        Where-Object { $_ -cne "NEW_P0_P2_OR_EVIDENCE_TAMPER" }
                )
            }
        },
        @{
            name = "post-unknown-nested-field"
            mutate = {
                param($contract)
                $contract.acceptedArtifacts.main | Add-Member `
                    -NotePropertyName undocumentedField `
                    -NotePropertyValue "ALLOW"
            }
        },
        @{
            name = "post-non-allowlisted-pointer"
            mutate = {
                param($contract)
                $contract.qaTools.postAuditPhaseRepair.testStatus = "TAMPERED"
            }
        },
        @{
            name = "post-immutable-state-hash-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.immutableStateSha256 = ("0" * 64)
            }
        },
        @{
            name = "post-tools-digest-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.toolsDigest = ("0" * 64)
            }
        },
        @{
            name = "post-qa-prompt-hash-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.qaPromptSha256 = ("0" * 64)
            }
        },
        @{
            name = "post-device-lock-state-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.deviceLockState = "ACTIVE_COUNT_1"
            }
        },
        @{
            name = "post-device-lock-hash-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.deviceLockSha256 = ("0" * 64)
            }
        },
        @{
            name = "post-evidence-root-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.qaEvidenceRoot =
                    ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v10"
            }
        },
        @{
            name = "post-rejected-scope-hash-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.rejectedScopeHash = ("0" * 64)
            }
        },
        @{
            name = "post-memory-index-hash-drift"
            mutate = {
                param($contract)
                $contract.auditReceipt.memoryIndexSha256 = ("0" * 64)
            }
        },
        @{
            name = "post-transition-path-outside"
            mutate = {
                param($contract)
                $contract.auditReceipt.transitionPaths = @(
                    "docs/PROJECT_PLAN.md",
                    "docs/TASKS.md",
                    "docs/V2_PLAN.md",
                    "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
                )
            }
        },
        @{
            name = "post-audit-gate-blocked"
            mutate = {
                param($contract)
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").status = "BLOCKED"
            }
        },
        @{
            name = "post-qa-gate-blocked"
            mutate = {
                param($contract)
                (Get-Gate $contract "QA_DISPATCHABLE").status = "BLOCKED"
            }
        },
        @{
            name = "post-padQa-owner-drift"
            mutate = {
                param($contract)
                $contract.prompts.padQa.roleId =
                    "PRE_HANDOFF_AUDIT_OWNER"
            }
        },
        @{
            name = "post-padQa-first-action-drift"
            mutate = {
                param($contract)
                $badAction = $contract.prompts.padQa.firstAction + " -Unknown"
                $contract.prompts.padQa.firstAction = $badAction
                $contract.firstReadOnlyActions.padQa[0] = $badAction
                $contract.prompts.padQa.textLines[2] = (
                    "第一条只读动作必须实际执行：" + $badAction +
                    "。任何非零立即停止。"
                )
            }
        },
        @{
            name = "post-qa-status-blocked"
            mutate = {
                param($contract)
                $contract.prompts.padQa.status =
                    "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
                $contract.prompts.padQa.dispatchable = $false
            }
        },
        @{
            name = "post-single-anchor-token-drift"
            mutate = {
                param($contract)
                $tasksAnchor = @(
                    $contract.validation.requiredTextAnchors |
                        Where-Object { $_.id -ceq "TASKS" }
                )[0]
                $tasksAnchor | Add-Member `
                    -NotePropertyName "tokens" `
                    -NotePropertyValue @("ROUND6_ANCHOR_DRIFT")
            }
        },
        @{
            name = "post-unknown-anchor-added"
            mutate = {
                param($contract)
                $contract.validation.requiredTextAnchors = @(
                    $contract.validation.requiredTextAnchors +
                    [pscustomobject]@{
                        id = "UNKNOWN"
                        path = "docs/TASKS.md"
                    }
                )
            }
        },
        @{
            name = "post-blank-active-required-token"
            mutate = {
                param($contract)
                $contract.validation.activeRequiredTokens = @(" ")
            }
        }
    )

    foreach ($case in $postNegativeCases) {
        Write-LfText -Text $postBaseText -Path $postFixture.contractPath
        Write-LfText `
            -Text $postFixture.tasksText `
            -Path $postFixture.tasksPath
        $fixture = $postBaseText | ConvertFrom-Json -Depth 100 -DateKind String
        & $case.mutate $fixture
        Write-LfJson -Value $fixture -Path $postFixture.contractPath
        Add-CaseResult `
            -Case $case.name `
            -ExpectedExit 2 `
            -Result (Invoke-Preflight `
                -FixturePath $postFixture.contractPath `
                -RepoRootOverride $postFixture.repositoryRoot)
    }

    Write-LfText -Text $postBaseText -Path $postFixture.contractPath
    $outsidePath = Join-Path $postFixture.repositoryRoot "docs/outside-transition.md"
    Write-LfText -Text "outside transition`n" -Path $outsidePath
    Add-CaseResult `
        -Case "post-transition-diff-outside" `
        -ExpectedExit 2 `
        -Result (Invoke-Preflight `
            -FixturePath $postFixture.contractPath `
            -RepoRootOverride $postFixture.repositoryRoot)
    [System.IO.File]::Delete($outsidePath)

    Write-LfText -Text $postBaseText -Path $postFixture.contractPath

    foreach ($scriptFile in @($preflightPath, $testPath)) {
        $tokens = $null
        $parseErrors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile(
            $scriptFile,
            [ref]$tokens,
            [ref]$parseErrors
        )
        Assert-Condition `
            -Condition ($parseErrors.Count -eq 0) `
            -Name ("AST-" + [System.IO.Path]::GetFileName($scriptFile))
    }
    Write-Output "STATIC AST=2/2 PASS"

    $strictFiles = @(
        $contractPath,
        $preflightPath,
        $testPath,
        (Join-Path $repositoryRoot "AGENTS.md"),
        (Join-Path $repositoryRoot "docs/PROJECT_RULES.md"),
        (Join-Path $repositoryRoot "docs/PROJECT_PLAN.md"),
        (Join-Path $repositoryRoot "docs/V2_PLAN.md"),
        (Join-Path $repositoryRoot "docs/TASKS.md"),
        (Join-Path $repositoryRoot "docs/LESSONS.md")
    )
    foreach ($strictFile in $strictFiles) {
        Assert-StrictUtf8Lf `
            -Path $strictFile `
            -Name ("UTF8-" + [System.IO.Path]::GetFileName($strictFile))
    }
    [void](Get-Content -LiteralPath $contractPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100 -DateKind String)
    Write-Output "STATIC JSON=PASS UTF8_NO_BOM_LF=$($strictFiles.Count)/$($strictFiles.Count)"

    $activeText = Get-Content -LiteralPath (Join-Path $repositoryRoot "docs/V2_PLAN.md") -Raw -Encoding utf8
    $activeCount = [regex]::Matches($activeText, "(?m)^### ACTIVE").Count
    Assert-Condition -Condition ($activeCount -eq 1) -Name "ACTIVE-COUNT-1"

    $tasksLines = @(
        Get-Content -LiteralPath (Join-Path $repositoryRoot "docs/TASKS.md") -Encoding utf8
    )
    $inProgressIndex = [Array]::IndexOf(
        [string[]]$tasksLines,
        "## In Progress"
    )
    Assert-Condition -Condition ($inProgressIndex -ge 0) -Name "TASKS-HEADING"
    $tasksEnd = $tasksLines.Count
    for ($lineIndex = $inProgressIndex + 1; $lineIndex -lt $tasksLines.Count; $lineIndex++) {
        if ($tasksLines[$lineIndex].StartsWith("## ", [System.StringComparison]::Ordinal)) {
            $tasksEnd = $lineIndex
            break
        }
    }
    $taskCount = @(
        for ($lineIndex = $inProgressIndex + 1; $lineIndex -lt $tasksEnd; $lineIndex++) {
            if ($tasksLines[$lineIndex] -match '^\| `V2-[^`]+`') {
                $tasksLines[$lineIndex]
            }
        }
    ).Count
    Assert-Condition -Condition ($taskCount -eq 3) -Name "TASKS-COUNT-3"
    Write-Output "STATIC ACTIVE=1 TASKS=3 PASS"

    $toolFiles = @(
        Get-ChildItem -LiteralPath $qaToolsRoot -File -Recurse -Force |
            Where-Object { $_.Name -ne "tools.sha256" }
    )
    $toolByPath = @{}
    foreach ($toolFile in $toolFiles) {
        $relativePath = [System.IO.Path]::GetRelativePath(
            $qaToolsRoot,
            $toolFile.FullName
        ).Replace("\", "/")
        $toolByPath[$relativePath] = $toolFile.FullName
    }
    $relativePaths = [string[]]@($toolByPath.Keys)
    [Array]::Sort($relativePaths, [System.StringComparer]::Ordinal)
    $manifestLines = @(
        foreach ($relativePath in $relativePaths) {
            $sha = (Get-FileHash `
                -LiteralPath $toolByPath[$relativePath] `
                -Algorithm SHA256).Hash.ToLowerInvariant()
            "$sha  tools/$relativePath"
        }
    )
    $manifestText = ($manifestLines -join "`n") + "`n"
    $manifestBytes = $utf8Output.GetBytes($manifestText)
    $digest = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($manifestBytes)
    ).ToLowerInvariant()
    $expectedDigest = "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4"
    Assert-Condition -Condition ($relativePaths.Count -eq 31) -Name "TOOLS-COUNT-31"
    Assert-Condition -Condition ($manifestBytes.Length -eq 2984) -Name "TOOLS-MANIFEST-2984"
    Assert-Condition -Condition ($digest -ceq $expectedDigest) -Name "TOOLS-DIGEST"
    Assert-Condition `
        -Condition ((Get-FileHash -LiteralPath $qaManifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $expectedDigest) `
        -Name "TOOLS-MANIFEST-SHA"
    $canonicalLines = @($baseContract.qaTools.canonicalManifestLines)
    Assert-Condition `
        -Condition (($manifestLines -join "`n") -ceq ($canonicalLines -join "`n")) `
        -Name "TOOLS-31-OF-31"
    Write-Output "STATIC TOOLS=31/31 MANIFEST=2984 DIGEST=$digest PASS"

    $toolsMtimeAfter = Get-ToolsMtimeSnapshot `
        -ToolsRoot $qaToolsRoot `
        -ManifestPath $qaManifestPath
    Assert-Condition `
        -Condition ($toolsMtimeAfter -ceq $toolsMtimeBefore) `
        -Name "QA-TOOLS-MTIME-UNCHANGED"
    Write-Output "STATIC QA_TOOLS_MTIME=UNCHANGED PASS"

    $diffCheck = @(& git -C $repositoryRoot diff --check 2>&1)
    Assert-Condition `
        -Condition ($LASTEXITCODE -eq 0) `
        -Name "GIT-DIFF-CHECK"
    Write-Output "STATIC GIT_DIFF_CHECK=PASS"

    $stagedDiff = @(& git -C $repositoryRoot diff --cached --name-only 2>&1)
    Assert-Condition `
        -Condition ($LASTEXITCODE -eq 0 -and $stagedDiff.Count -eq 0) `
        -Name "NO-STAGED-CHANGES"
    Write-Output "STATIC STAGED=0 PASS"

    Assert-Condition `
        -Condition (-not (
            @(& git -C $repositoryRoot status --short -- .gitignore 2>&1).Count -gt 0
        )) `
        -Name "GITIGNORE-UNCHANGED"
    Write-Output "STATIC GITIGNORE=UNCHANGED PASS"
}
catch {
    Write-Output ("SUMMARY passed={0} failed={1}" -f $testResults.Count, $testFailures.Count)
    Write-Output ("TEST_FAILURE " + $_.Exception.Message)
    exit 2
}
finally {
    Remove-FixtureDirectory -Path $tempDirectory
    if ([System.IO.Directory]::Exists($tempDirectory)) {
        Write-Output "TEMP_CLEANUP=FAILED"
        exit 2
    }
}

if ($testFailures.Count -gt 0) {
    Write-Output ("SUMMARY passed={0} failed={1}" -f $testResults.Count, $testFailures.Count)
    exit 2
}

Write-Output ("SUMMARY passed={0} failed=0 TEMP_CLEANUP=PASS" -f $testResults.Count)
exit 0
