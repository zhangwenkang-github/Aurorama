[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$testsRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$contractRoot = [System.IO.Path]::GetFullPath((Join-Path $testsRoot ".."))
$repositoryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $contractRoot "..\..\..")
)
$fixturesRoot = Join-Path $testsRoot "fixtures"
$collectorPath = Join-Path $contractRoot "Invoke-G2RuntimeCollector.ps1"
$finalizerPath = Join-Path $contractRoot "Finalize-G2RuntimeReport.ps1"
$cleanupPath = Join-Path $contractRoot "Test-G2CleanupRestore.ps1"
$manifestPath = Join-Path $contractRoot "runtime-tools.manifest.json"
$contractPath = Join-Path $contractRoot "contract.json"
$authorizationPath = Join-Path $contractRoot "authorization-receipt.json"
$schemaPath = Join-Path $contractRoot "report.schema.json"
$policyPath = Join-Path $contractRoot "G2RuntimeStatePolicy.ps1"
$auditReceiptSchemaPath = Join-Path `
    $contractRoot `
    "repair-audit-receipt.schema.json"
$auditReceiptPath = Join-Path `
    $contractRoot `
    "repair-audit-receipt.json"

$script:passed = 0
$script:failed = 0
$script:negativeChecks = 0
$script:failures = [System.Collections.Generic.List[string]]::new()
$script:ExpectedG2ToolsPassed = 116
$script:ExpectedG2DynamicNegative = 188
$script:ExpectedHandoffPassed = 108
$script:ExpectedHandoffDynamicNegative = 0

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal {
    param(
        [AllowNull()]$Actual,
        [AllowNull()]$Expected,
        [Parameter(Mandatory)][string]$Message
    )
    if ($Actual -cne $Expected) {
        throw "$Message expected=[$Expected] actual=[$Actual]"
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory)][scriptblock]$Action,
        [Parameter(Mandatory)][string]$MessagePattern
    )
    $script:negativeChecks++
    $threw = $false
    try {
        & $Action
    } catch {
        $threw = $true
        Assert-True `
            -Condition ([string]$_.Exception.Message -notmatch
                "not recognized as a name of a cmdlet|Could not find a part of the path") `
            -Message "Expected behavior exception, not a missing dependency."
        Assert-True `
            -Condition ([string]$_.Exception.Message -match $MessagePattern) `
            -Message (
                "Exception mismatch expected-pattern=[{0}] actual=[{1}]" -f
                $MessagePattern,
                $_.Exception.Message
            )
    }
    Assert-True -Condition $threw -Message "Expected exception was not thrown."
}

function Invoke-Case {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    try {
        & $Action
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        $script:failures.Add("$Name :: $($_.Exception.Message)")
        Write-Output "FAIL $Name :: $($_.Exception.Message)"
    }
}

function Read-Json {
    param([Parameter(Mandatory)][string]$Path)
    return (
        Get-Content -LiteralPath $Path -Raw -Encoding utf8 |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function Get-TestOrdinalSortedStrings {
    param([AllowNull()][string[]]$Value)
    [string[]]$copy = @($Value)
    [Array]::Sort($copy, [System.StringComparer]::Ordinal)
    return $copy
}

function New-TestTempRoot {
    $tempBase = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::GetTempPath()
    )
    $tempRoot = Join-Path `
        $tempBase `
        ("aurorama-g2-test-" + [Guid]::NewGuid().ToString("N"))
    [void][System.IO.Directory]::CreateDirectory($tempRoot)
    return [pscustomobject]@{
        Base = $tempBase
        Root = [System.IO.Path]::GetFullPath($tempRoot)
    }
}

function Remove-TestTempRoot {
    param([Parameter(Mandatory)]$Temp)
    $root = [System.IO.Path]::GetFullPath([string]$Temp.Root)
    $leaf = Split-Path -Leaf $root
    Assert-True `
        -Condition ($root.StartsWith(
            [string]$Temp.Base,
            [System.StringComparison]::OrdinalIgnoreCase
        )) `
        -Message "Unsafe temporary root."
    Assert-True `
        -Condition ($leaf.StartsWith(
            "aurorama-g2-test-",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Unexpected temporary root name."
    if ([System.IO.Directory]::Exists($root)) {
        [System.IO.Directory]::Delete($root, $true)
    }
    Assert-True `
        -Condition (-not [System.IO.Directory]::Exists($root)) `
        -Message "Temporary root was not removed."
}

function Write-TestJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    $text = ($Value | ConvertTo-Json -Depth 100).
        Replace("`r`n", "`n").
        Replace("`r", "`n")
    [System.IO.File]::WriteAllText(
        $Path,
        $text,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Get-TestGitValue {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = @(& git -C $repositoryRoot @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Test Git command failed: git $($Arguments -join ' ')"
    }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Initialize-TestSyntheticAuditProvenance {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)][string]$MinimumAuditedCommit
    )
    $objectDirectory = Join-Path $Temp.Root "synthetic-audit-objects"
    [void][System.IO.Directory]::CreateDirectory($objectDirectory)
    $actualObjectDirectory = Get-TestGitValue @(
        "rev-parse",
        "--path-format=absolute",
        "--git-path",
        "objects"
    )
    $environmentNames = @(
        "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_AUTHOR_NAME",
        "GIT_AUTHOR_EMAIL",
        "GIT_AUTHOR_DATE",
        "GIT_COMMITTER_NAME",
        "GIT_COMMITTER_EMAIL",
        "GIT_COMMITTER_DATE"
    )
    $previousEnvironment = @{}
    foreach ($name in $environmentNames) {
        $previousEnvironment[$name] =
            [Environment]::GetEnvironmentVariable($name, "Process")
    }
    try {
        $env:GIT_OBJECT_DIRECTORY = $objectDirectory
        $env:GIT_ALTERNATE_OBJECT_DIRECTORIES = $actualObjectDirectory
        $env:GIT_AUTHOR_NAME = "G2 Synthetic Audit Fixture"
        $env:GIT_AUTHOR_EMAIL = "g2-synthetic-audit@example.invalid"
        $env:GIT_AUTHOR_DATE = "946684800 +0000"
        $env:GIT_COMMITTER_NAME = "G2 Synthetic Audit Fixture"
        $env:GIT_COMMITTER_EMAIL = "g2-synthetic-audit@example.invalid"
        $env:GIT_COMMITTER_DATE = "946684800 +0000"
        $tree = Get-TestGitValue @(
            "rev-parse",
            "$MinimumAuditedCommit^{tree}"
        )
        $auditedCommit = Get-TestGitValue @(
            "commit-tree",
            $tree,
            "-p",
            $MinimumAuditedCommit,
            "-m",
            "G2 synthetic strict-descendant audit fixture"
        )
        $parent = Get-TestGitValue @(
            "rev-parse",
            "$auditedCommit^1"
        )
        $head = Get-TestGitValue @("rev-parse", "HEAD")
        Assert-Equal `
            -Actual $parent `
            -Expected $MinimumAuditedCommit `
            -Message "Synthetic audit parent"
        Assert-True `
            -Condition ($auditedCommit -cne $MinimumAuditedCommit) `
            -Message "Synthetic audit commit equals minimum."
        Assert-True `
            -Condition ($auditedCommit -cne $head) `
            -Message "Synthetic audit commit equals current HEAD."
        return [pscustomobject]@{
            Mode = "SYNTHETIC_STRICT_DESCENDANT"
            MinimumAuditedCommit = $MinimumAuditedCommit
            AuditedCommit = $auditedCommit
            Parent = $parent
            ObjectDirectory = $objectDirectory
            PreviousEnvironment = $previousEnvironment
        }
    } catch {
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable(
                $name,
                $previousEnvironment[$name],
                "Process"
            )
        }
        throw
    }
}

function Restore-TestSyntheticAuditProvenance {
    param(
        [Parameter(Mandatory)]$Provenance,
        [Parameter(Mandatory)]$Temp
    )
    foreach ($name in $Provenance.PreviousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $Provenance.PreviousEnvironment[$name],
            "Process"
        )
    }
    if ([System.IO.Directory]::Exists([string]$Temp.Root)) {
        Get-ChildItem -LiteralPath $Temp.Root -Recurse -Force |
            ForEach-Object {
                if (
                    $_.Attributes -band
                        [System.IO.FileAttributes]::ReadOnly
                ) {
                    $_.Attributes =
                        $_.Attributes -band
                        (-bnot [System.IO.FileAttributes]::ReadOnly)
                }
            }
    }
    Remove-TestTempRoot -Temp $Temp
}

function Copy-TestObject {
    param([Parameter(Mandatory)]$Value)
    return (
        $Value |
            ConvertTo-Json -Depth 100 |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Invoke-TestCatching {
    param([Parameter(Mandatory)][scriptblock]$Action)
    try {
        & $Action
        return [pscustomobject]@{
            Threw = $false
            ErrorMessage = ""
        }
    } catch {
        return [pscustomobject]@{
            Threw = $true
            ErrorMessage = [string]$_.Exception.Message
        }
    }
}

function Get-TestRelativeEntrySet {
    param([Parameter(Mandatory)][string]$Root)
    $fullRoot = [System.IO.Path]::GetFullPath($Root)
    if (-not [System.IO.Directory]::Exists($fullRoot)) {
        return @()
    }
    $entries = @(
        Get-ChildItem -LiteralPath $fullRoot -Recurse -Force |
            ForEach-Object {
                [System.IO.Path]::GetRelativePath(
                    $fullRoot,
                    $_.FullName
                ).Replace("\", "/")
            }
    )
    return @(Get-TestOrdinalSortedStrings -Value $entries)
}

function New-FutureContractFixture {
    param(
        [Parameter(Mandatory)]$SourceContract,
        [Parameter(Mandatory)][string]$ManifestPath,
        [Parameter(Mandatory)][string]$Destination
    )
    $futureManifest = (
        [System.IO.File]::ReadAllText(
            $ManifestPath,
            [System.Text.UTF8Encoding]::new($false, $true)
        ) |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
    $testEntry = @(
        $futureManifest.files |
            Where-Object {
                [string]$_.path -ceq "tests/Test-G2RuntimeTools.ps1"
            }
    )
    Assert-Equal `
        -Actual $testEntry.Count `
        -Expected 1 `
        -Message "Future manifest test entry"
    $testEntry[0].sha256 = Get-FileSha256 (
        Join-Path $testsRoot "Test-G2RuntimeTools.ps1"
    )
    $futureStatus = "PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA"
    $futureManifest.status = $futureStatus
    $futureManifestPath = Join-Path $Destination "future-manifest.json"
    Write-TestJson -Path $futureManifestPath -Value $futureManifest

    $future = (
        $SourceContract |
            ConvertTo-Json -Depth 100 |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
    $auditedCommit = $script:SyntheticAuditProvenance.AuditedCommit
    $future.status = $future.toolStatePolicy.qaReadyStatus
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        $future.tools.$toolName.status = $future.status
        $future.tools.$toolName.deviceAccess =
            $future.toolStatePolicy.qaReadyToolDeviceAccess
    }
    $future.tools.statePolicy.status = $future.status
    $future.tools.runtimeManifest.status = $future.status
    $future.tools.runtimeManifest.sha256 = Get-FileSha256 $futureManifestPath
    $future.authorization.contractPackageReady = $true
    $future.authorization.qaDispatchable = $true
    $future.authorization.activePrompt =
        $future.toolStatePolicy.qaReadyActivePrompt
    $future.authorization.generatedExecutableQaPrompt = $true
    if (
        $null -eq
        $future.authorization.PSObject.Properties["qaDispatch"]
    ) {
        $future.authorization | Add-Member `
            -NotePropertyName qaDispatch `
            -NotePropertyValue $null
    }
    $future.authorization.qaDispatch = New-TestQaDispatchFixture `
        -Contract $future `
        -AuditedCommit $auditedCommit
    $repairAuditExpectations = [pscustomobject][ordered]@{
        status = "SETTLED_FROM_FINAL_AUDIT"
        source = [pscustomobject][ordered]@{
            auditedCommit = $auditedCommit
            g2ToolsCommand = (
                "pwsh -NoProfile -File " +
                ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/" +
                "tests/Test-G2RuntimeTools.ps1"
            )
            handoffCommand = (
                "pwsh -NoProfile -File " +
                "tools/v2-handoff/Test-HandoffPreflight.ps1"
            )
            staticSelfTestCommand = (
                "collector/finalizer/cleanup -StaticSelfTest"
            )
        }
        preflightCounts = [pscustomobject][ordered]@{
            inputFiles = 1
            roles = 22
            tasks = 3
            gates = 6
            currentTransitions = 62
        }
        g2ToolsCounts = [pscustomobject][ordered]@{
            files = 4
            testsPassed = $script:ExpectedG2ToolsPassed
            testsFailed = 0
            dynamicNegative = $script:ExpectedG2DynamicNegative
            staticSelfTests = 3
        }
        handoffCounts = [pscustomobject][ordered]@{
            passed = $script:ExpectedHandoffPassed
            failed = 0
            dynamicNegative = $script:ExpectedHandoffDynamicNegative
            tempCleanupPassed = $true
        }
        boundaryViolations = 0
    }
    if ($null -eq $future.PSObject.Properties["validation"]) {
        $future | Add-Member `
            -NotePropertyName validation `
            -NotePropertyValue ([pscustomobject]@{})
    }
    if (
        $null -eq
        $future.validation.PSObject.Properties["repairAuditExpectations"]
    ) {
        $future.validation | Add-Member `
            -NotePropertyName repairAuditExpectations `
            -NotePropertyValue $repairAuditExpectations
    } else {
        $future.validation.repairAuditExpectations =
            $repairAuditExpectations
    }
    $futureContractPath = Join-Path $Destination "future-contract.json"
    Write-TestJson -Path $futureContractPath -Value $future
    return [pscustomobject]@{
        Contract = $future
        ContractPath = $futureContractPath
        Manifest = $futureManifest
        ManifestPath = $futureManifestPath
    }
}

function New-TestQaDispatchFixture {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$AuditedCommit
    )
    $ownerRole = "G2_PAD_QA_OWNER_2026_11_06"
    $firstReadOnlyAction =
        "pwsh -NoProfile -File tools/v2-handoff/Invoke-HandoffPreflight.ps1 -EmitPrompt"
    $worktree =
        "F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama"
    $branch = "codex/v2-integration"
    $readOrder = @(
        "AGENTS.md",
        "docs/PROJECT_PLAN.md",
        "docs/V2_PLAN.md",
        "docs/TASKS.md",
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/CHECKPOINT.md",
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/README.md",
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/contract.json"
    )
    $nonGoals = @($Contract.nonGoals | ForEach-Object { [string]$_ })
    $stopConditions = @(
        $Contract.stopConditions |
            ForEach-Object { [string]$_ }
    )
    $productGateIds = @(
        $Contract.productGates |
            ForEach-Object { [string]$_.id }
    )
    $device = [pscustomobject][ordered]@{
        order = "Pad->K60"
        first = "Pad"
        serial = "43af8627"
        roleId = $ownerRole
        k60 = "FORBIDDEN_UNTIL_PAD_FULL_CORE_PASS_AND_RELEASED_ACTIVE_0"
    }
    $window = [pscustomobject][ordered]@{
        minutes = 10
        seconds = 600
        attempts = 1
        continuous = $true
        extensionAllowed = $false
        retryAllowed = $false
    }
    $promptLines = [System.Collections.Generic.List[string]]::new()
    $promptLines.Add("You are $ownerRole.")
    $promptLines.Add("First read-only action: $firstReadOnlyAction")
    $promptLines.Add("Worktree: $worktree")
    $promptLines.Add("Branch: $branch")
    $promptLines.Add(
        "Head: $AuditedCommit (exact audited commit; clean new expected state)"
    )
    $promptLines.Add("Read order:")
    foreach ($entry in $readOrder) {
        $promptLines.Add("- $entry")
    }
    $promptLines.Add(
        "Write scope: .planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2/**"
    )
    $promptLines.Add(
        "Device: Pad $($device.serial) only; K60 $($device.k60)."
    )
    $promptLines.Add(
        "Window: exactly 10 minutes / 600 seconds / one attempt / continuous; extension and retry are forbidden."
    )
    $promptLines.Add("Product gates:")
    foreach ($gateId in $productGateIds) {
        $promptLines.Add("- $gateId")
    }
    $promptLines.Add("Non-goals:")
    foreach ($nonGoal in $nonGoals) {
        $promptLines.Add("- $nonGoal")
    }
    $promptLines.Add("Stop conditions:")
    foreach ($stopCondition in $stopConditions) {
        $promptLines.Add("- $stopCondition")
    }
    $promptText = $promptLines -join "`n"
    $promptSha256 = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            [System.Text.UTF8Encoding]::new($false).GetBytes($promptText)
        )
    ).ToLowerInvariant()
    return [pscustomobject][ordered]@{
        schema = "aurorama.g2-pad-qa-dispatch.v1"
        ownerRole = $ownerRole
        actionClass = "READ_ONLY_PREFLIGHT"
        firstReadOnlyAction = $firstReadOnlyAction
        worktree = $worktree
        branch = $branch
        head = $AuditedCommit
        headExpectation = "EXACT_AUDITED_COMMIT_CLEAN"
        readOrder = $readOrder
        writeScope = @(
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2/**"
        )
        device = $device
        window = $window
        productGateIds = $productGateIds
        nonGoals = $nonGoals
        stopConditions = $stopConditions
        promptText = $promptText
        promptSha256 = $promptSha256
    }
}

function Update-TestQaPromptBinding {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Receipt,
        [Parameter(Mandatory)][string]$AuditedCommit
    )
    $dispatch = $Contract.authorization.qaDispatch
    $previousHead = [string]$dispatch.head
    $dispatch.head = $AuditedCommit
    $dispatch.promptText = ([string]$dispatch.promptText).Replace(
        $previousHead,
        $AuditedCommit
    )
    $dispatch.promptSha256 = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            [System.Text.UTF8Encoding]::new($false).GetBytes(
                [string]$dispatch.promptText
            )
        )
    ).ToLowerInvariant()
    $Receipt.qaPromptSha256 = $dispatch.promptSha256
}

function New-RepairAuditReceiptFixture {
    param(
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)][string]$ManifestPath,
        [Parameter(Mandatory)][string]$Destination
    )
    $auditedCommit = $script:SyntheticAuditProvenance.AuditedCommit
    $parent = $script:SyntheticAuditProvenance.Parent
    $handoff = Read-Json (
        Join-Path $repositoryRoot "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
    )
    $lockPath = [string]$handoff.evidence.deviceLockPath
    $manifest = Read-Json $ManifestPath
    $expectations = $Contract.validation.repairAuditExpectations
    $toolFiles = @(
        $manifest.files |
            Where-Object { [string]$_.kind -ceq "tool" }
    )
    $qaPadV1 = @(
        $Contract.attemptRecovery.historicalPreWindowBlock.evidenceFiles |
            ForEach-Object {
                [pscustomobject]@{
                    path = [string]$_.path
                    sha256 = [string]$_.sha256
                }
            }
    )
    $receipt = [pscustomobject][ordered]@{
        schema = "aurorama.g2-repair-audit-receipt.v1"
        version = "1.0.0"
        auditorRole =
            "G2_RUNTIME_TOOL_STATE_BINDING_AUDIT_OWNER_2026_11_06"
        auditedCommit = $auditedCommit
        parent = $parent
        auditedAt = [DateTimeOffset]::UtcNow.ToString(
            "yyyy-MM-ddTHH:mm:ss.fffffffZ",
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        verdict = "PASS"
        scope = [pscustomobject][ordered]@{
            contractId = [string]$Contract.contractId
            contractRoot = [string]$Contract.evidence.root
            mode = "AUDIT_PASS_PENDING_FRESH_PAD_QA"
            qaExecuted = $false
            deviceTouched = $false
            networkUsed = $false
        }
        preflightCounts = [pscustomobject][ordered]@{
            inputFiles = [int]$expectations.preflightCounts.inputFiles
            roles = [int]$expectations.preflightCounts.roles
            tasks = [int]$expectations.preflightCounts.tasks
            gates = [int]$expectations.preflightCounts.gates
            currentTransitions =
                [int]$expectations.preflightCounts.currentTransitions
        }
        g2ToolsCounts = [pscustomobject][ordered]@{
            files = $toolFiles.Count
            testsPassed =
                [int]$expectations.g2ToolsCounts.testsPassed
            testsFailed =
                [int]$expectations.g2ToolsCounts.testsFailed
            dynamicNegative =
                [int]$expectations.g2ToolsCounts.dynamicNegative
            staticSelfTests =
                [int]$expectations.g2ToolsCounts.staticSelfTests
        }
        handoffCounts = [pscustomobject][ordered]@{
            passed = [int]$expectations.handoffCounts.passed
            failed = [int]$expectations.handoffCounts.failed
            dynamicNegative =
                [int]$expectations.handoffCounts.dynamicNegative
            tempCleanupPassed =
                [bool]$expectations.handoffCounts.tempCleanupPassed
        }
        toolsDigest = [string]$Contract.tools.digestSha256
        candidate = [pscustomobject][ordered]@{
            mainSha256 = [string]$Contract.candidate.main.sha256
            focusPeerSha256 =
                [string]$Contract.candidate.focusPeer.sha256
        }
        qaPromptSha256 =
            [string]$Contract.authorization.qaDispatch.promptSha256
        qaPadV1 = $qaPadV1
        externalLock = [pscustomobject][ordered]@{
            state = "RELEASED_ACTIVE_0"
            sha256 = Get-FileSha256 $lockPath
        }
        boundaryViolations = [int]$expectations.boundaryViolations
        nextRole = "G2_PAD_QA_OWNER_2026_11_06"
    }
    $receiptPath = Join-Path $Destination "repair-audit-receipt.json"
    Write-TestJson -Path $receiptPath -Value $receipt
    return [pscustomobject]@{
        Receipt = $receipt
        Path = $receiptPath
        LockPath = $lockPath
        AuditedCommit = $auditedCommit
        Parent = $parent
    }
}

function Copy-G2HistoricalFixture {
    param(
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$Source
    )
    [void][System.IO.Directory]::CreateDirectory($Destination)
    foreach ($fileName in @(
        "blocker.json",
        "cleanup-before.json",
        "cleanup-after.json",
        "cleanup.json",
        "device-lock.json"
    )) {
        [System.IO.File]::Copy(
            (Join-Path $Source $fileName),
            (Join-Path $Destination $fileName)
        )
    }
}

function Assert-Utf8LfNoBom {
    param([Parameter(Mandatory)][string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-True `
        -Condition (-not (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        )) `
        -Message "BOM present: $Path"
    Assert-True `
        -Condition ([Array]::IndexOf($bytes, [byte]13) -lt 0) `
        -Message "CR byte present: $Path"
    [void]$utf8Strict.GetString($bytes)
}

$toolPaths = @($collectorPath, $finalizerPath, $cleanupPath)
$toolsPresent = @($toolPaths | Where-Object { Test-Path -LiteralPath $_ }).Count -eq 3

Invoke-Case "STATIC-TOOLS-EXIST" {
    foreach ($toolPath in $toolPaths) {
        Assert-True `
            -Condition (Test-Path -LiteralPath $toolPath -PathType Leaf) `
            -Message "Missing tool: $toolPath"
    }
    foreach ($policyArtifact in @($policyPath, $auditReceiptSchemaPath)) {
        Assert-True `
            -Condition (Test-Path -LiteralPath $policyArtifact -PathType Leaf) `
            -Message "Missing state-policy artifact: $policyArtifact"
    }
}

Invoke-Case "STATIC-UTF8-LF-NO-BOM" {
    foreach ($path in @($toolPaths + @(
        $policyPath,
        $auditReceiptSchemaPath,
        $manifestPath,
        (Join-Path $testsRoot "Test-G2RuntimeTools.ps1")
    ))) {
        Assert-True `
            -Condition (Test-Path -LiteralPath $path -PathType Leaf) `
            -Message "Missing file: $path"
        Assert-Utf8LfNoBom -Path $path
    }
}

if ($toolsPresent) {
    . $collectorPath
    . $finalizerPath
    . $cleanupPath
}
if (Test-Path -LiteralPath $policyPath -PathType Leaf) {
    . $policyPath
}

$contract = Read-Json $contractPath
$authorization = Read-Json $authorizationPath
$observations = Read-Json (Join-Path $fixturesRoot "observations-complete.json")
$cleanupFixture = Read-Json (Join-Path $fixturesRoot "cleanup-complete.json")
$script:SyntheticAuditTemp = New-TestTempRoot
$script:SyntheticAuditProvenance =
    Initialize-TestSyntheticAuditProvenance `
        -Temp $script:SyntheticAuditTemp `
        -MinimumAuditedCommit (
            [string]$contract.toolStatePolicy.minimumAuditedCommit
        )

function New-FixturePassReport {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    $report.verdict = "PASS"
    $report.productVerdict = "PASS"
    $report.uncovered = @()
    $report.productFindings.P0 = @()
    $report.productFindings.P1 = @()
    $report.productFindings.P2 = @()
    $report.productFindings.P3 = @()
    return $report
}

function New-TestFinalizerBindingContext {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Observations
    )
    $root = Join-Path $Temp.Root "qa-pad-v2"
    [void][System.IO.Directory]::CreateDirectory($root)
    Write-TestJson `
        -Path (Join-Path $root "collector-registration.json") `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-collector-registration.v1"
            contractId = $Contract.contractId
            toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
            qaOwnerRole = "G2_PAD_QA_OWNER_2026_11_06"
            deviceRole = "Pad"
            serial = "43af8627"
            registeredAt = "2026-11-06T12:00:00.000+08:00"
            attemptScope = $Contract.evidence.padRuntimeRoot
            attemptState = "REGISTERED_WINDOW_NOT_STARTED"
            lockState = "ACTIVE_COUNT_1"
            attemptNumber = 1
            windowSeconds = 600
            candidateMainSha256 = $Contract.candidate.main.sha256
            candidateFocusPeerSha256 = $Contract.candidate.focusPeer.sha256
            sourceCommit = $Contract.candidate.sourceCommit
            toolsDigest = $Contract.tools.digestSha256
            predecessor = $null
            authoritativeLockState = "RELEASED_ACTIVE_0"
            authoritativeLockSha256 = ("c" * 64)
            recoveryBasis = [pscustomobject]@{
                outcome = "PRE_WINDOW_TOOL_BLOCK"
                historicalWindowConsumed = $false
                blockerEvidencePointer = (
                    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/" +
                    "qa-pad-v1/blocker.json"
                )
                blockerSha256 = ("a" * 64)
                historicalDeviceLockEvidencePointer = (
                    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/" +
                    "qa-pad-v1/device-lock.json"
                )
                historicalDeviceLockSha256 = ("b" * 64)
                authoritativeLockState = "RELEASED_ACTIVE_0"
                authoritativeLockSha256 = ("c" * 64)
            }
        })
    Write-TestJson `
        -Path (Join-Path $root "collector-session.json") `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-collector-session.v1"
            contractId = $Contract.contractId
            toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
            qaOwnerRole = "G2_PAD_QA_OWNER_2026_11_06"
            evidenceRoot = $Contract.evidence.padRuntimeRoot
            attemptScope = $Contract.evidence.padRuntimeRoot
            attemptState = "WINDOW_STARTED"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptNumber = 1
            windowSeconds = 600
            extended = $false
            retry = $false
            continuous = $true
            hostLogcatProcessStopped = $true
            startedAt = $Observations.execution.startedAt
            completedAt = $Observations.execution.completedAt
            candidateMainSha256 = $Contract.candidate.main.sha256
            candidateFocusPeerSha256 = $Contract.candidate.focusPeer.sha256
            sourceCommit = $Contract.candidate.sourceCommit
            toolsDigest = $Contract.tools.digestSha256
            uid = [int]$Observations.zeroNetwork.uid
            device = [pscustomobject][ordered]@{
                serial = $Observations.device.serial
                model = $Observations.device.model
                sdk = [int]$Observations.device.sdk
                abi = $Observations.device.abi
                user = [int]$Observations.device.user
            }
            commandCount = 0
            commands = @()
            snapshots = @()
            hostLogcatProcessId = $null
            error = $null
        })
    Write-TestJson `
        -Path (Join-Path $root "device-lock.json") `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $Contract.evidence.padRuntimeRoot
            state = "RELEASED_ACTIVE_0"
            activeCount = 0
            attemptNumber = 1
            registeredAt = "2026-11-06T12:00:00.000+08:00"
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = ("a" * 64)
            releasedAt = "2026-11-06T12:20:00.000+08:00"
        })
    return [pscustomobject]@{
        Contract = $Contract
        Evidence = [pscustomobject]@{
            RelativePath = $Contract.evidence.padRuntimeRoot
            FullPath = $root
        }
        ToolsDigest = $Contract.tools.digestSha256
    }
}

function New-TestPadPredecessorFixture {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Observations
    )
    $padRoot = Join-Path $Temp.Root $Contract.evidence.padRuntimeRoot
    [void][System.IO.Directory]::CreateDirectory($padRoot)
    $report = New-FixturePassReport
    $reportPath = Join-Path $padRoot "report.json"
    Write-TestJson -Path $reportPath -Value $report
    $historicalBlocker =
        $Contract.attemptRecovery.historicalPreWindowBlock.evidenceFiles |
            Where-Object { [string]$_.path -ceq "blocker.json" } |
            Select-Object -First 1
    $historicalBlockerSha256 = [string]$historicalBlocker.sha256
    $lockPath = Join-Path $padRoot "device-lock.json"
    Write-TestJson `
        -Path $lockPath `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $Contract.evidence.padRuntimeRoot
            state = "RELEASED_ACTIVE_0"
            activeCount = 0
            attemptNumber = 1
            registeredAt = "2026-11-06T12:00:00.000+08:00"
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = $historicalBlockerSha256
            releasedAt = "2026-11-06T12:20:00.000+08:00"
        })
    $context = [pscustomobject]@{
        Contract = $Contract
        RepositoryRoot = $Temp.Root
    }
    $predecessor = Get-G2PadPredecessor `
        -Context $context `
        -PadRoot $padRoot
    return [pscustomobject]@{
        Temp = $Temp
        Context = $context
        PadRoot = $padRoot
        ReportPath = $reportPath
        LockPath = $lockPath
        Report = $report
        Predecessor = $predecessor
    }
}

function New-TestK60RegistrationFixture {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Observations
    )
    $pad = New-TestPadPredecessorFixture `
        -Temp $Temp `
        -Contract $Contract `
        -Observations $Observations
    $k60Root = Join-Path $Temp.Root $Contract.evidence.k60RuntimeRoot
    [void][System.IO.Directory]::CreateDirectory($k60Root)
    $registration = [pscustomobject]@{
        schema = "aurorama.g2-collector-registration.v1"
        contractId = $Contract.contractId
        toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
        qaOwnerRole = "G2_K60_QA_OWNER_2026_11_06"
        deviceRole = "K60"
        serial = "8e875894"
        attemptNumber = 1
        windowSeconds = 600
        attemptScope = $Contract.evidence.k60RuntimeRoot
        attemptState = "REGISTERED_WINDOW_NOT_STARTED"
        registeredAt = "2026-11-06T12:00:00.000+08:00"
        candidateMainSha256 = $Contract.candidate.main.sha256
        candidateFocusPeerSha256 = $Contract.candidate.focusPeer.sha256
        sourceCommit = $Contract.candidate.sourceCommit
        toolsDigest = $Contract.tools.digestSha256
        predecessor = $pad.Predecessor
        recoveryBasis = $null
        authoritativeLockState = "RELEASED_ACTIVE_0"
        authoritativeLockSha256 = ("a" * 64)
        lockState = "ACTIVE_COUNT_1"
    }
    $registrationPath = Join-Path $k60Root "collector-registration.json"
    Write-TestJson -Path $registrationPath -Value $registration
    $lockPath = Join-Path $k60Root "device-lock.json"
    Write-TestJson `
        -Path $lockPath `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "K60"
            serial = "8e875894"
            attemptScope = $Contract.evidence.k60RuntimeRoot
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            registeredAt = "2026-11-06T12:00:00.000+08:00"
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = $null
            releasedAt = $null
        })
    $context = [pscustomobject]@{
        Contract = $Contract
        RepositoryRoot = $Temp.Root
        Evidence = [pscustomobject]@{
            RelativePath = $Contract.evidence.k60RuntimeRoot
            FullPath = $k60Root
        }
        ToolsDigest = $Contract.tools.digestSha256
    }
    return [pscustomobject]@{
        Pad = $pad
        Context = $context
        Registration = $registration
        RegistrationPath = $registrationPath
        LockPath = $lockPath
    }
}

function New-TestFutureCleanupFixture {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Observations
    )
    $future = New-FutureContractFixture `
        -SourceContract $contract `
        -ManifestPath $manifestPath `
        -Destination $Temp.Root
    $receipt = New-RepairAuditReceiptFixture `
        -Contract $future.Contract `
        -ManifestPath $future.ManifestPath `
        -Destination $Temp.Root
    $k60 = New-TestK60RegistrationFixture `
        -Temp $Temp `
        -Contract $future.Contract `
        -Observations $Observations
    $k60Evidence = [pscustomobject]@{
        RelativePath = $future.Contract.evidence.k60RuntimeRoot
        FullPath = Join-Path `
            $Temp.Root `
            $future.Contract.evidence.k60RuntimeRoot
    }
    $context = [pscustomobject]@{
        Contract = $future.Contract
        Evidence = $k60Evidence
        RepositoryRoot = $Temp.Root
        StatePolicy = [pscustomobject]@{
            Mode = "AUDIT_PASS_PENDING_FRESH_PAD_QA"
            QaDispatchable = $true
        }
    }
    return [pscustomobject]@{
        Future = $future
        Receipt = $receipt
        K60 = $k60
        Context = $context
        PadReportPath = $k60.Pad.ReportPath
        PadLockPath = $k60.Pad.LockPath
    }
}

Invoke-Case "STATE-ARTIFACT-SHAPES-REJECT-CASE-VARIANTS" {
    $temp = New-TestTempRoot
    try {
        $binding = New-TestFinalizerBindingContext `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $registration = Read-Json (
            Join-Path $binding.Evidence.FullPath "collector-registration.json"
        )
        $registrationLockState = $registration.lockState
        [void]$registration.PSObject.Properties.Remove("lockState")
        $registration | Add-Member `
            -NotePropertyName LockState `
            -NotePropertyValue $registrationLockState
        Assert-Throws `
            -MessagePattern "FIELD|REGISTRATION|STATE" `
            -Action {
                Assert-G2StateRegistrationShape `
                    -Registration $registration `
                    -Name "TEST_REGISTRATION"
            }

        $session = Read-Json (
            Join-Path $binding.Evidence.FullPath "collector-session.json"
        )
        $sessionAttemptState = $session.attemptState
        [void]$session.PSObject.Properties.Remove("attemptState")
        $session | Add-Member `
            -NotePropertyName AttemptState `
            -NotePropertyValue $sessionAttemptState
        Assert-Throws `
            -MessagePattern "FIELD|SESSION|STATE" `
            -Action {
                Assert-G2StateSessionShape `
                    -Session $session `
                    -Name "TEST_SESSION"
            }

        $lock = Read-Json (
            Join-Path $binding.Evidence.FullPath "device-lock.json"
        )
        $lockState = $lock.state
        [void]$lock.PSObject.Properties.Remove("state")
        $lock | Add-Member `
            -NotePropertyName State `
            -NotePropertyValue $lockState
        Assert-Throws `
            -MessagePattern "FIELD|LOCK|STATE" `
            -Action {
                Assert-G2StateDeviceLockShape `
                    -Lock $lock `
                    -Name "TEST_LOCK"
            }

        $report = New-FixturePassReport
        $verdict = $report.verdict
        [void]$report.PSObject.Properties.Remove("verdict")
        $report | Add-Member `
            -NotePropertyName Verdict `
            -NotePropertyValue $verdict
        Assert-Throws `
            -MessagePattern "FIELD|REPORT|STATE" `
            -Action {
                Assert-G2StateRuntimeReportShape `
                    -Report $report `
                    -Name "TEST_REPORT"
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CONTRACT-RUNTIME-TOOLS-BOUND" {
    Assert-Equal `
        -Actual $contract.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Contract status"
    Assert-Equal `
        -Actual $contract.authorization.activePrompt `
        -Expected "none" `
        -Message "No downstream prompt while R3 repair is active"
    Assert-Equal `
        -Actual $contract.authorization.qaDispatchable `
        -Expected $false `
        -Message "QA dispatch before fresh audit"
    Assert-Equal `
        -Actual $contract.authorization.contractPackageReady `
        -Expected $false `
        -Message "R3 repair state is not ready"
    Assert-Equal `
        -Actual $contract.tools.runtimeCollector.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Collector status"
    Assert-Equal `
        -Actual $contract.tools.runtimeFinalizer.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Finalizer status"
    Assert-Equal `
        -Actual $contract.tools.cleanupVerifier.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Cleanup status"
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        Assert-Equal `
            -Actual $contract.tools.$toolName.roleId `
            -Expected "G2_RUNTIME_TOOL_OWNER_2026_11_06" `
            -Message "$toolName owner"
        Assert-Equal `
            -Actual $contract.tools.$toolName.deviceAccess `
            -Expected "STATIC_SELF_TEST_ONLY_THIS_PHASE" `
            -Message "$toolName device access"
    }
}

Invoke-Case "CONTRACT-TOOL-STATE-POLICY-BOUND" {
    Assert-Equal `
        -Actual $contract.toolStatePolicy.schema `
        -Expected "aurorama.g2-tool-state-policy.v1" `
        -Message "Tool state policy schema"
    Assert-Equal `
        -Actual $contract.toolStatePolicy.preAuditStatus `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Pre-audit state"
    Assert-Equal `
        -Actual $contract.toolStatePolicy.qaReadyStatus `
        -Expected "PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA" `
        -Message "QA-ready state"
    Assert-Equal `
        -Actual $contract.toolStatePolicy.qaReadyActivePrompt `
        -Expected "G2_PAD_QA_OWNER_2026_11_06" `
        -Message "QA-ready prompt"
    Assert-Equal `
        -Actual $contract.toolStatePolicy.requiredNextRole `
        -Expected "G2_PAD_QA_OWNER_2026_11_06" `
        -Message "Receipt next role"
    Assert-Equal `
        -Actual $contract.toolStatePolicy.minimumAuditedCommit `
        -Expected "6b04eb300256fc8384e5407cee24907e8343f949" `
        -Message "Minimum audit baseline"
}

Invoke-Case "CONTRACT-REPAIR-AUDIT-EXPECTATIONS-PENDING" {
    $validationProperty = $contract.PSObject.Properties["validation"]
    Assert-True `
        -Condition ($null -ne $validationProperty) `
        -Message "Contract validation object is missing."
    $expectationProperty =
        $contract.validation.PSObject.Properties["repairAuditExpectations"]
    Assert-True `
        -Condition ($null -ne $expectationProperty) `
        -Message "repairAuditExpectations field is missing."
    Assert-Equal `
        -Actual $contract.validation.repairAuditExpectations.status `
        -Expected "PENDING_SETTLEMENT_FINAL_AUDIT" `
        -Message "Pre-audit expectations status"
    Assert-True `
        -Condition (
            $null -eq $contract.validation.repairAuditExpectations.source -and
            $null -eq
                $contract.validation.repairAuditExpectations.
                    preflightCounts -and
            $null -eq
                $contract.validation.repairAuditExpectations.
                    g2ToolsCounts -and
            $null -eq
                $contract.validation.repairAuditExpectations.
                    handoffCounts -and
            $null -eq
                $contract.validation.repairAuditExpectations.
                    boundaryViolations
        ) `
        -Message "Pre-audit contract must not invent settled counts."
}

Invoke-Case "CONTRACT-STATE-SUPPORTS-PRE-AND-SETTLED-QA-PROMPT" {
    $temp = New-TestTempRoot
    try {
        $preState = Assert-G2RuntimeToolStatePolicy `
            -Contract $contract `
            -ContractRoot $contractRoot `
            -RepositoryRoot $repositoryRoot `
            -Action Validate
        Assert-Equal `
            -Actual $preState.Mode `
            -Expected "PRE_AUDIT_STATIC_ONLY" `
            -Message "Current contract must stay PRE."
        Assert-True `
            -Condition (
                $contract.authorization.generatedExecutableQaPrompt -eq
                    $false -and
                $null -eq $contract.authorization.qaDispatch
            ) `
            -Message "Current PRE contract must not carry or generate a prompt."

        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $dispatch = $future.Contract.authorization.qaDispatch
        Assert-Equal `
            -Actual $dispatch.ownerRole `
            -Expected "G2_PAD_QA_OWNER_2026_11_06" `
            -Message "Future prompt owner."
        Assert-Equal `
            -Actual $dispatch.device.first `
            -Expected "Pad" `
            -Message "Future prompt first device."
        Assert-Equal `
            -Actual $dispatch.window.minutes `
            -Expected 10 `
            -Message "Future prompt window."
        Assert-Equal `
            -Actual @($dispatch.productGateIds).Count `
            -Expected 13 `
            -Message "Future prompt gate count."
        Assert-True `
            -Condition $dispatch.promptText.Contains(
                "First read-only action: " +
                    "pwsh -NoProfile -File " +
                    "tools/v2-handoff/Invoke-HandoffPreflight.ps1 -EmitPrompt",
                [System.StringComparison]::Ordinal
            ) `
            -Message "Future prompt canonical preflight is missing."
        $expectedPromptSha = [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData(
                [System.Text.UTF8Encoding]::new($false).GetBytes(
                    [string]$dispatch.promptText
                )
            )
        ).ToLowerInvariant()
        Assert-Equal `
            -Actual $dispatch.promptSha256 `
            -Expected $expectedPromptSha `
            -Message "Future prompt hash."
        Assert-Equal `
            -Actual $receipt.Receipt.qaPromptSha256 `
            -Expected $dispatch.promptSha256 `
            -Message "Future receipt prompt binding."

        $settledState = Assert-G2RuntimeToolStatePolicy `
            -Contract $future.Contract `
            -ContractRoot $contractRoot `
            -RepositoryRoot $repositoryRoot `
            -Action Validate `
            -RepairAuditReceiptPathOverride $receipt.Path `
            -ManifestPathOverride $future.ManifestPath
        Assert-Equal `
            -Actual $settledState.Mode `
            -Expected "AUDIT_PASS_PENDING_FRESH_PAD_QA" `
            -Message "Future settled mode."
        Assert-Equal `
            -Actual $settledState.QaPromptSha256 `
            -Expected $dispatch.promptSha256 `
            -Message "Policy prompt hash."
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-UNBOUND-QA-PROMPT" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $contractCases = @(
            @{
                Pattern = "AUTHORIZATION|PROMPT|STATE"
                Mutate = {
                    param($value)
                    $value.authorization.generatedExecutableQaPrompt = $false
                }
            },
            @{
                Pattern = "AUTHORIZATION|PROMPT|STATE"
                Mutate = {
                    param($value)
                    $value.authorization.contractPackageReady = $false
                }
            },
            @{
                Pattern = "AUTHORIZATION|PROMPT|STATE"
                Mutate = {
                    param($value)
                    $value.authorization.activePrompt = "wrong-owner"
                }
            },
            @{
                Pattern = "PROMPT|DISPATCH"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch = $null
                }
            },
            @{
                Pattern = "PROMPT|FIELD|HASH"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.PSObject.Properties.
                        Remove("promptSha256")
                }
            },
            @{
                Pattern = "PROMPT|HASH|MISMATCH"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.promptSha256 = ("0" * 64)
                }
            },
            @{
                Pattern = "PROMPT|HASH|MISMATCH"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.promptText += "`ntamper"
                }
            },
            @{
                Pattern = "PROMPT|OWNER|IDENTITY"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.ownerRole = "wrong-owner"
                }
            },
            @{
                Pattern = "PROMPT|DEVICE|FIELD"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.device.PSObject.Properties.
                        Remove("k60")
                }
            },
            @{
                Pattern = "PROMPT|WINDOW|FIELD"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.window.PSObject.Properties.
                        Remove("retryAllowed")
                }
            },
            @{
                Pattern = "PROMPT|READ_ORDER|READORDER"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.readOrder = @()
                }
            },
            @{
                Pattern = "PROMPT|NON_GOALS|NONGOALS"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.nonGoals = @()
                }
            },
            @{
                Pattern = "PROMPT|STOP|FIELD"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.PSObject.Properties.
                        Remove("stopConditions")
                }
            },
            @{
                Pattern = "PROMPT|AUDIT|HEAD|COMMIT"
                Mutate = {
                    param($value)
                    $value.authorization.qaDispatch.head = ("1" * 40)
                }
            }
        )
        foreach ($contractCase in $contractCases) {
            $invalidContract = Copy-TestObject $future.Contract
            & $contractCase.Mutate $invalidContract
            Assert-Throws `
                -MessagePattern $contractCase.Pattern `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $invalidContract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Validate `
                        -RepairAuditReceiptPathOverride $receipt.Path `
                        -ManifestPathOverride $future.ManifestPath
                }
        }

        $receiptCases = @(
            @{
                Pattern = "RECEIPT|PROMPT|FIELD"
                Mutate = {
                    param($value)
                    $value.PSObject.Properties.Remove("qaPromptSha256")
                }
            },
            @{
                Pattern = "RECEIPT|PROMPT|HASH|MISMATCH"
                Mutate = {
                    param($value)
                    $value.qaPromptSha256 = ("0" * 64)
                }
            }
        )
        foreach ($receiptCase in $receiptCases) {
            $invalidReceipt = Copy-TestObject $receipt.Receipt
            & $receiptCase.Mutate $invalidReceipt
            Write-TestJson -Path $receipt.Path -Value $invalidReceipt
            Assert-Throws `
                -MessagePattern $receiptCase.Pattern `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $future.Contract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Validate `
                        -RepairAuditReceiptPathOverride $receipt.Path `
                        -ManifestPathOverride $future.ManifestPath
                }
        }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-CASE-VARIANT-UNKNOWN-AND-DUPLICATE-FIELDS" {
    $temp = New-TestTempRoot
    try {
        $statusCase = Copy-TestObject $contract
        $statusExpectation =
            $statusCase.validation.repairAuditExpectations
        $statusValue = $statusExpectation.status
        [void]$statusExpectation.PSObject.Properties.Remove("status")
        $statusExpectation | Add-Member `
            -NotePropertyName Status `
            -NotePropertyValue $statusValue
        Assert-Throws `
            -MessagePattern "FIELD|EXPECTATIONS|STATE" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $statusCase `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate
            }

        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root

        $countsCase = Copy-TestObject $future.Contract
        $countsExpectation =
            $countsCase.validation.repairAuditExpectations
        $countsValue = $countsExpectation.g2ToolsCounts
        [void]$countsExpectation.PSObject.Properties.Remove(
            "g2ToolsCounts"
        )
        $countsExpectation | Add-Member `
            -NotePropertyName G2ToolsCounts `
            -NotePropertyValue $countsValue
        Assert-Throws `
            -MessagePattern "FIELD|EXPECTATIONS|COUNTS|STATE" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $countsCase `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate `
                    -ManifestPathOverride $future.ManifestPath
            }

        $unknownCase = Copy-TestObject $future.Contract
        $unknownCase.validation.repairAuditExpectations.
            g2ToolsCounts | Add-Member `
                -NotePropertyName unexpected `
                -NotePropertyValue 1
        Assert-Throws `
            -MessagePattern "FIELD|EXPECTATIONS|COUNTS|STATE" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $unknownCase `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate `
                    -ManifestPathOverride $future.ManifestPath
            }

        $manifestText = [System.IO.File]::ReadAllText(
            $manifestPath,
            [System.Text.UTF8Encoding]::new($false, $true)
        )
        $schemaLine = '  "schema": "aurorama.g2-runtime-tools.v1",'
        Assert-True `
            -Condition $manifestText.Contains(
                $schemaLine,
                [System.StringComparison]::Ordinal
            ) `
            -Message "Manifest schema line is missing."
        $duplicateManifestPath = Join-Path `
            $temp.Root `
            "duplicate-manifest.json"
        $duplicateText = $manifestText.Replace(
            $schemaLine,
            $schemaLine + "`n" + $schemaLine
        )
        [System.IO.File]::WriteAllText(
            $duplicateManifestPath,
            $duplicateText,
            [System.Text.UTF8Encoding]::new($false)
        )
        $duplicateContract = Copy-TestObject $contract
        $duplicateContract.tools.runtimeManifest.sha256 =
            Get-FileSha256 $duplicateManifestPath
        Assert-Throws `
            -MessagePattern "DUPLICATE_PROPERTY|MANIFEST|STATE" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $duplicateContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate `
                    -ManifestPathOverride $duplicateManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-ROOT-CASE-VARIANT-AND-UNKNOWN-FIELDS" {
    $temp = New-TestTempRoot
    try {
        $overrideManifest = Read-Json $manifestPath
        foreach ($entry in @($overrideManifest.files)) {
            if ([string]$entry.path -ceq "tests/Test-G2RuntimeTools.ps1") {
                $entry.sha256 = Get-FileSha256 (
                    Join-Path $testsRoot "Test-G2RuntimeTools.ps1"
                )
            }
        }
        $overrideManifestPath = Join-Path $temp.Root "manifest.json"
        Write-TestJson `
            -Path $overrideManifestPath `
            -Value $overrideManifest
        $overrideManifestSha = Get-FileSha256 $overrideManifestPath

    $statusCase = Copy-TestObject $contract
    $statusCase.tools.runtimeManifest.sha256 = $overrideManifestSha
    $statusValue = $statusCase.status
    [void]$statusCase.PSObject.Properties.Remove("status")
    $statusCase | Add-Member `
        -NotePropertyName Status `
        -NotePropertyValue $statusValue
    Assert-Throws `
        -MessagePattern "FIELD|ROOT|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $statusCase `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $overrideManifestPath
        }

    $validationCase = Copy-TestObject $contract
    $validationCase.tools.runtimeManifest.sha256 = $overrideManifestSha
    $validationValue = $validationCase.validation
    [void]$validationCase.PSObject.Properties.Remove("validation")
    $validationCase | Add-Member `
        -NotePropertyName Validation `
        -NotePropertyValue $validationValue
    Assert-Throws `
        -MessagePattern "FIELD|ROOT|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $validationCase `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $overrideManifestPath
        }

    $unknownCase = Copy-TestObject $contract
    $unknownCase.tools.runtimeManifest.sha256 = $overrideManifestSha
    $unknownCase | Add-Member `
        -NotePropertyName unexpectedRoot `
        -NotePropertyValue $true
    Assert-Throws `
        -MessagePattern "FIELD|ROOT|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $unknownCase `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $overrideManifestPath
        }

    $authorizationCase = Copy-TestObject $contract
    $authorizationCase.tools.runtimeManifest.sha256 = $overrideManifestSha
    $qaDispatchable = $authorizationCase.authorization.qaDispatchable
    [void]$authorizationCase.authorization.PSObject.Properties.Remove(
        "qaDispatchable"
    )
    $authorizationCase.authorization | Add-Member `
        -NotePropertyName QaDispatchable `
        -NotePropertyValue $qaDispatchable
    Assert-Throws `
        -MessagePattern "FIELD|AUTH|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $authorizationCase `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $overrideManifestPath
        }

    $toolCase = Copy-TestObject $contract
    $toolCase.tools.runtimeManifest.sha256 = $overrideManifestSha
    $toolStatus = $toolCase.tools.runtimeCollector.status
    [void]$toolCase.tools.runtimeCollector.PSObject.Properties.Remove(
        "status"
    )
    $toolCase.tools.runtimeCollector | Add-Member `
        -NotePropertyName Status `
        -NotePropertyValue $toolStatus
    Assert-Throws `
        -MessagePattern "FIELD|TOOL|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $toolCase `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $overrideManifestPath
        }

    $devicePolicyCase = Copy-TestObject $contract
    $devicePolicyCase.tools.runtimeManifest.sha256 = $overrideManifestSha
    $deviceOrder = $devicePolicyCase.devicePolicy.deviceOrder
    [void]$devicePolicyCase.devicePolicy.PSObject.Properties.Remove(
        "deviceOrder"
    )
    $devicePolicyCase.devicePolicy | Add-Member `
        -NotePropertyName DeviceOrder `
        -NotePropertyValue $deviceOrder
    Assert-Throws `
        -MessagePattern "FIELD|DEVICE|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $devicePolicyCase `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $overrideManifestPath
        }

    $manifestCase = Copy-TestObject $overrideManifest
    $manifestStatus = $manifestCase.status
    [void]$manifestCase.PSObject.Properties.Remove("status")
    $manifestCase | Add-Member `
        -NotePropertyName Status `
        -NotePropertyValue $manifestStatus
    $manifestCasePath = Join-Path $temp.Root "manifest-Status.json"
    Write-TestJson -Path $manifestCasePath -Value $manifestCase
    $manifestCaseContract = Copy-TestObject $contract
    $manifestCaseContract.tools.runtimeManifest.sha256 =
        Get-FileSha256 $manifestCasePath
    Assert-Throws `
        -MessagePattern "FIELD|MANIFEST|STATE" `
        -Action {
            Assert-G2RuntimeToolStatePolicy `
                -Contract $manifestCaseContract `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action Validate `
                -ManifestPathOverride $manifestCasePath
        }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-HAS-INDEPENDENT-SETTLEMENT-AUDIT-BASELINE" {
    $command = Get-Command `
        Get-G2SettlementAuditBaseline `
        -ErrorAction SilentlyContinue
    Assert-True `
        -Condition ($null -ne $command) `
        -Message "Independent settlement audit baseline is missing."
    $baseline = Get-G2SettlementAuditBaseline
    Assert-Equal `
        -Actual $baseline.schema `
        -Expected "aurorama.g2-settlement-audit-baseline.v1" `
        -Message "Settlement baseline schema"
    Assert-Equal `
        -Actual $baseline.toolFilesExact `
        -Expected 4 `
        -Message "Settlement baseline tool files"
    Assert-Equal `
        -Actual $baseline.testsPassedExact `
        -Expected $script:ExpectedG2ToolsPassed `
        -Message "Settlement baseline exact test count"
    Assert-Equal `
        -Actual $baseline.testsFailedExact `
        -Expected 0 `
        -Message "Settlement baseline testsFailed"
    Assert-Equal `
        -Actual $baseline.dynamicNegativeExact `
        -Expected $script:ExpectedG2DynamicNegative `
        -Message "Settlement baseline exact dynamic negatives"
    Assert-Equal `
        -Actual $baseline.staticSelfTestsExact `
        -Expected 3 `
        -Message "Settlement baseline static self-tests"
    Assert-Equal `
        -Actual $baseline.handoffPassedExact `
        -Expected $script:ExpectedHandoffPassed `
        -Message "Settlement baseline exact handoff count"
    Assert-Equal `
        -Actual $baseline.handoffDynamicNegativeExact `
        -Expected $script:ExpectedHandoffDynamicNegative `
        -Message "Settlement baseline exact handoff dynamic negatives"
    Assert-Equal `
        -Actual $baseline.handoffFailedExact `
        -Expected 0 `
        -Message "Settlement baseline handoff failures"
    Assert-True `
        -Condition ([bool]$baseline.handoffTempCleanupPassedExact) `
        -Message "Settlement baseline temp cleanup"
    Assert-Equal `
        -Actual $baseline.boundaryViolationsExact `
        -Expected 0 `
        -Message "Settlement baseline boundary violations"
}

Invoke-Case "STATE-POLICY-PRE-REJECTS-SETTLED-OR-POPULATED-EXPECTATIONS" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $settledExpectations = Copy-TestObject `
            $future.Contract.validation.repairAuditExpectations

        $validPending = Copy-TestObject $contract
        $state = Assert-G2RuntimeToolStatePolicy `
            -Contract $validPending `
            -ContractRoot $contractRoot `
            -RepositoryRoot $repositoryRoot `
            -Action Validate
        Assert-Equal `
            -Actual $state.Mode `
            -Expected "PRE_AUDIT_STATIC_ONLY" `
            -Message "Canonical pending expectations mode"

        $settledPreContract = Copy-TestObject $contract
        $settledPreContract.validation.repairAuditExpectations =
            Copy-TestObject $settledExpectations
        Assert-Throws `
            -MessagePattern "PRE_AUDIT|REPAIR_AUDIT|EXPECTATIONS|PENDING|SETTLEMENT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $settledPreContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate
            }

        $populationFactories = @(
            {
                param($value, $settled)
                $value.source = Copy-TestObject $settled.source
            },
            {
                param($value, $settled)
                $value.preflightCounts =
                    Copy-TestObject $settled.preflightCounts
            },
            {
                param($value, $settled)
                $value.g2ToolsCounts =
                    Copy-TestObject $settled.g2ToolsCounts
            },
            {
                param($value, $settled)
                $value.handoffCounts =
                    Copy-TestObject $settled.handoffCounts
            },
            {
                param($value, $settled)
                $value.boundaryViolations = 0
            }
        )
        foreach ($populate in $populationFactories) {
            $pendingWithValue = Copy-TestObject $contract
            $pendingExpectations =
                $pendingWithValue.validation.repairAuditExpectations
            & $populate $pendingExpectations $settledExpectations
            Assert-Throws `
                -MessagePattern "PRE_AUDIT|REPAIR_AUDIT|EXPECTATIONS|PENDING|NULL" `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $pendingWithValue `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Validate
                }
        }

        $pendingWithExtraField = Copy-TestObject $contract
        $pendingWithExtraField.validation.repairAuditExpectations |
            Add-Member `
                -NotePropertyName unexpected `
                -NotePropertyValue $true
        Assert-Throws `
            -MessagePattern "FIELD|EXPECTATIONS|PRE_AUDIT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $pendingWithExtraField `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate
            }

        $pendingWithUnknownStatus = Copy-TestObject $contract
        $pendingWithUnknownStatus.validation.
            repairAuditExpectations.status = "PENDING"
        Assert-Throws `
            -MessagePattern "STATUS|EXPECTATIONS|PRE_AUDIT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $pendingWithUnknownStatus `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REQUIRES-EXACT-AUDITED-COUNTS" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $original = (
            $receipt.Receipt |
                ConvertTo-Json -Depth 100 |
                ConvertFrom-Json -Depth 100 -DateKind String
        )
        $mutations = @(
            {
                param($value)
                $value.g2ToolsCounts.testsPassed = 1
            },
            {
                param($value)
                $value.g2ToolsCounts.testsPassed =
                    $script:ExpectedG2ToolsPassed + 1
            },
            {
                param($value)
                $value.g2ToolsCounts.PSObject.Properties.Remove(
                    "testsPassed"
                )
            },
            {
                param($value)
                $value.g2ToolsCounts.testsPassed = $null
            },
            {
                param($value)
                $value.g2ToolsCounts.testsPassed = "1"
            },
            {
                param($value)
                $value.g2ToolsCounts.dynamicNegative = 0
            },
            {
                param($value)
                $value.g2ToolsCounts.dynamicNegative =
                    $script:ExpectedG2DynamicNegative + 1
            },
            {
                param($value)
                $value.preflightCounts.currentTransitions = 0
            },
            {
                param($value)
                $value.handoffCounts.passed = 0
            },
            {
                param($value)
                $value.handoffCounts.passed =
                    $script:ExpectedHandoffPassed + 1
            },
            {
                param($value)
                $value.handoffCounts.failed = 1
            },
            {
                param($value)
                $value.handoffCounts.dynamicNegative = 1
            },
            {
                param($value)
                $value.boundaryViolations = 1
            },
            {
                param($value)
                $value.g2ToolsCounts.files = 3
            }
        )
        foreach ($mutation in $mutations) {
            $copy = (
                $original |
                    ConvertTo-Json -Depth 100 |
                    ConvertFrom-Json -Depth 100 -DateKind String
            )
            & $mutation $copy
            Write-TestJson -Path $receipt.Path -Value $copy
            Assert-Throws `
                -MessagePattern "COUNT|EXPECT|RECEIPT|INTEGER|FIELD|MISMATCH" `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $future.Contract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Register `
                        -RepairAuditReceiptPathOverride $receipt.Path `
                        -ManifestPathOverride $future.ManifestPath
                }
        }

        $pendingContract = (
            $future.Contract |
                ConvertTo-Json -Depth 100 |
                ConvertFrom-Json -Depth 100 -DateKind String
        )
        $pendingContract.validation.repairAuditExpectations.status =
            "PENDING_SETTLEMENT_FINAL_AUDIT"
        Write-TestJson -Path $receipt.Path -Value $original
        Assert-Throws `
            -MessagePattern "EXPECT|SETTLEMENT|FINAL|AUDIT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $pendingContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $wrongSource = (
            $future.Contract |
                ConvertTo-Json -Depth 100 |
                ConvertFrom-Json -Depth 100 -DateKind String
        )
        $wrongSource.validation.repairAuditExpectations.source.auditedCommit =
            ("0" * 40)
        Assert-Throws `
            -MessagePattern "EXPECT|SOURCE|AUDITED|COMMIT|MISMATCH" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $wrongSource `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-SELF-CONSISTENT-LOW-COUNTS" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root

        $lowContract = Copy-TestObject $future.Contract
        $lowReceipt = Copy-TestObject $receipt.Receipt
        foreach ($target in @(
            $lowContract.validation.repairAuditExpectations.
                g2ToolsCounts,
            $lowReceipt.g2ToolsCounts
        )) {
            $target.testsPassed = 1
            $target.testsFailed = 0
            $target.dynamicNegative = 0
            $target.staticSelfTests = 3
        }
        foreach ($target in @(
            $lowContract.validation.repairAuditExpectations.handoffCounts,
            $lowReceipt.handoffCounts
        )) {
            $target.passed = 1
            $target.failed = 0
            $target.dynamicNegative = 0
            $target.tempCleanupPassed = $true
        }
        $lowContract.validation.repairAuditExpectations.
            boundaryViolations = 0
        $lowReceipt.boundaryViolations = 0
        Write-TestJson -Path $receipt.Path -Value $lowReceipt
        Assert-Throws `
            -MessagePattern "BASELINE|COUNT|SETTLEMENT|EXACT|INTEGER|STATE" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $lowContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $highContract = Copy-TestObject $future.Contract
        $highReceipt = Copy-TestObject $receipt.Receipt
        $highContract.validation.repairAuditExpectations.
            g2ToolsCounts.testsPassed =
                $script:ExpectedG2ToolsPassed + 1
        $highContract.validation.repairAuditExpectations.
            g2ToolsCounts.dynamicNegative =
                $script:ExpectedG2DynamicNegative + 1
        $highContract.validation.repairAuditExpectations.
            handoffCounts.passed =
                $script:ExpectedHandoffPassed + 1
        Write-TestJson -Path $receipt.Path -Value $highReceipt
        Assert-Throws `
            -MessagePattern "COUNT|RECEIPT|EXPECT|MISMATCH" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $highContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $synchronizedHighContract = Copy-TestObject $future.Contract
        $synchronizedHighReceipt = Copy-TestObject $receipt.Receipt
        foreach ($target in @(
            $synchronizedHighContract.validation.
                repairAuditExpectations.g2ToolsCounts,
            $synchronizedHighReceipt.g2ToolsCounts
        )) {
            $target.testsPassed = 111
            $target.dynamicNegative = 151
        }
        foreach ($target in @(
            $synchronizedHighContract.validation.
                repairAuditExpectations.handoffCounts,
            $synchronizedHighReceipt.handoffCounts
        )) {
            $target.passed = 109
        }
        Write-TestJson `
            -Path $receipt.Path `
            -Value $synchronizedHighReceipt
        Assert-Throws `
            -MessagePattern "EXACT|BASELINE|COUNT|MISMATCH" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $synchronizedHighContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $invalidMutations = @(
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    g2ToolsCounts.testsPassed = -1
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    g2ToolsCounts.dynamicNegative = $true
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    g2ToolsCounts.testsPassed = $null
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    g2ToolsCounts.PSObject.Properties.Remove("dynamicNegative")
                $receiptValue.g2ToolsCounts.PSObject.Properties.Remove(
                    "dynamicNegative"
                )
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    g2ToolsCounts.testsPassed =
                        [string]$script:ExpectedG2ToolsPassed
                $receiptValue.g2ToolsCounts.testsPassed =
                    [string]$script:ExpectedG2ToolsPassed
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    g2ToolsCounts | Add-Member `
                        -NotePropertyName unexpected `
                        -NotePropertyValue 1
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    source.auditedCommit = $null
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    source.auditedCommit = (
                        [string]$contractValue.validation.
                            repairAuditExpectations.source.auditedCommit +
                        "-string"
                    )
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    source.auditedCommit = $true
            }
        )
        foreach ($mutation in $invalidMutations) {
            $invalidContract = Copy-TestObject $future.Contract
            $invalidReceipt = Copy-TestObject $receipt.Receipt
            & $mutation $invalidContract $invalidReceipt
            Write-TestJson `
                -Path $receipt.Path `
                -Value $invalidReceipt
            Assert-Throws `
                -MessagePattern "BASELINE|COUNT|INTEGER|FIELD|SOURCE|AUDIT|COMMIT|EXPECT" `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $invalidContract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Register `
                        -RepairAuditReceiptPathOverride $receipt.Path `
                        -ManifestPathOverride $future.ManifestPath
                }
        }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-SYNCHRONIZED-PURE-HIGH-COUNTS" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $highContract = Copy-TestObject $future.Contract
        $highReceipt = Copy-TestObject $receipt.Receipt
        foreach ($target in @(
            $highContract.validation.repairAuditExpectations.
                g2ToolsCounts,
            $highReceipt.g2ToolsCounts
        )) {
            $target.testsPassed =
                $script:ExpectedG2ToolsPassed + 1
            $target.dynamicNegative =
                $script:ExpectedG2DynamicNegative + 1
        }
        foreach ($target in @(
            $highContract.validation.repairAuditExpectations.handoffCounts,
            $highReceipt.handoffCounts
        )) {
            $target.passed = $script:ExpectedHandoffPassed + 1
        }
        Write-TestJson -Path $receipt.Path -Value $highReceipt
        Assert-Throws `
            -MessagePattern "EXACT|BASELINE|COUNT|MISMATCH" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $highContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REQUIRES-EXACT-CURRENT-TRANSITIONS-62" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root

        $legalState = Assert-G2RuntimeToolStatePolicy `
            -Contract $future.Contract `
            -ContractRoot $contractRoot `
            -RepositoryRoot $repositoryRoot `
            -Action Register `
            -RepairAuditReceiptPathOverride $receipt.Path `
            -ManifestPathOverride $future.ManifestPath
        Assert-Equal `
            -Actual $legalState.Mode `
            -Expected "AUDIT_PASS_PENDING_FRESH_PAD_QA" `
            -Message "Legal currentTransitions=62 mode"

        $mutations = @(
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    preflightCounts.currentTransitions = 61
                $receiptValue.preflightCounts.currentTransitions = 61
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    preflightCounts.currentTransitions = 61
            },
            {
                param($contractValue, $receiptValue)
                $receiptValue.preflightCounts.currentTransitions = 61
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    preflightCounts.PSObject.Properties.Remove(
                        "currentTransitions"
                    )
            },
            {
                param($contractValue, $receiptValue)
                $receiptValue.preflightCounts.PSObject.Properties.Remove(
                    "currentTransitions"
                )
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    preflightCounts.currentTransitions = $null
            },
            {
                param($contractValue, $receiptValue)
                $receiptValue.preflightCounts.currentTransitions = $null
            },
            {
                param($contractValue, $receiptValue)
                $contractValue.validation.repairAuditExpectations.
                    preflightCounts.currentTransitions = "62"
            },
            {
                param($contractValue, $receiptValue)
                $receiptValue.preflightCounts.currentTransitions = "62"
            }
        )
        foreach ($mutation in $mutations) {
            $invalidContract = Copy-TestObject $future.Contract
            $invalidReceipt = Copy-TestObject $receipt.Receipt
            & $mutation $invalidContract $invalidReceipt
            Write-TestJson -Path $receipt.Path -Value $invalidReceipt
            Assert-Throws `
                -MessagePattern (
                    "STATE|INTEGER|MISMATCH|SCHEMA|FIELD|COUNT|EXPECT|RECEIPT"
                ) `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $invalidContract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Register `
                        -RepairAuditReceiptPathOverride $receipt.Path `
                        -ManifestPathOverride $future.ManifestPath
                }
        }

        $baseline = Get-G2SettlementAuditBaseline
        Assert-Equal `
            -Actual $baseline.currentTransitionsExact `
            -Expected 62 `
            -Message "Independent currentTransitions baseline"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-CURRENT-STATIC-ONLY" {
    $state = Assert-G2RuntimeToolStatePolicy `
        -Contract $contract `
        -ContractRoot $contractRoot `
        -RepositoryRoot $repositoryRoot `
        -Action Validate
    Assert-Equal `
        -Actual $state.Mode `
        -Expected "PRE_AUDIT_STATIC_ONLY" `
        -Message "Current state mode"
    Assert-Equal `
        -Actual $state.QaDispatchable `
        -Expected $false `
        -Message "Current QA dispatch"
    Assert-True `
        -Condition (-not (Test-Path -LiteralPath $auditReceiptPath)) `
        -Message "Current contract must not contain a repair-audit receipt."
    foreach ($action in @("Register", "Finalize", "Verify")) {
        Assert-Throws `
            -MessagePattern "PRE_AUDIT|ACTION|STATUS|POLICY" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action $action
            }
    }
}

Invoke-Case "STATE-POLICY-CURRENT-FORBIDS-ANY-RECEIPT-FILE" {
    $temp = New-TestTempRoot
    try {
        $receiptPath = Join-Path $temp.Root "repair-audit-receipt.json"
        Write-TestJson `
            -Path $receiptPath `
            -Value ([pscustomobject]@{ verdict = "PASS" })
        Assert-Throws `
            -MessagePattern "PRE_AUDIT|RECEIPT|FORBIDDEN" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Validate `
                    -RepairAuditReceiptPathOverride $receiptPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-QA-READY-EXACT-POLICY-CONSTANTS-ACCEPTED" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $baseline = Get-G2SettlementAuditBaseline
        Assert-Equal `
            -Actual $receipt.Receipt.g2ToolsCounts.testsPassed `
            -Expected $baseline.testsPassedExact `
            -Message "Legal future testsPassed"
        Assert-Equal `
            -Actual $receipt.Receipt.g2ToolsCounts.dynamicNegative `
            -Expected $baseline.dynamicNegativeExact `
            -Message "Legal future dynamic negatives"
        Assert-Equal `
            -Actual $receipt.Receipt.handoffCounts.passed `
            -Expected $baseline.handoffPassedExact `
            -Message "Legal future handoff passed"
        foreach ($action in @("Validate", "Register", "RunWindow")) {
            $state = Assert-G2RuntimeToolStatePolicy `
                -Contract $future.Contract `
                -ContractRoot $contractRoot `
                -RepositoryRoot $repositoryRoot `
                -Action $action `
                -RepairAuditReceiptPathOverride $receipt.Path `
                -ManifestPathOverride $future.ManifestPath
            Assert-Equal `
                -Actual $state.Mode `
                -Expected "AUDIT_PASS_PENDING_FRESH_PAD_QA" `
                -Message "Future state mode"
            Assert-Equal `
                -Actual $state.QaDispatchable `
                -Expected $true `
                -Message "Future QA dispatch"
        }
        $collectorContext = Get-G2ContractContext `
            -DeviceRole Pad `
            -RequestedEvidenceRoot $future.Contract.evidence.padRuntimeRoot `
            -Action Validate `
            -ContractPathOverride $future.ContractPath `
            -ManifestPathOverride $future.ManifestPath `
            -RepairAuditReceiptPathOverride $receipt.Path
        Assert-Equal `
            -Actual $collectorContext.StatePolicy.Mode `
            -Expected "AUDIT_PASS_PENDING_FRESH_PAD_QA" `
            -Message "Collector future Validator"
        $finalizerContext = Get-G2FinalizerContext `
            -DeviceRole Pad `
            -RequestedEvidenceRoot $future.Contract.evidence.padRuntimeRoot `
            -Action Finalize `
            -ContractPathOverride $future.ContractPath `
            -ManifestPathOverride $future.ManifestPath `
            -RepairAuditReceiptPathOverride $receipt.Path
        Assert-Equal `
            -Actual $finalizerContext.StatePolicy.QaDispatchable `
            -Expected $true `
            -Message "Finalizer future context"
        $cleanupContext = Get-G2CleanupContext `
            -DeviceRole Pad `
            -RequestedEvidenceRoot $future.Contract.evidence.padRuntimeRoot `
            -Action Verify `
            -ContractPathOverride $future.ContractPath `
            -ManifestPathOverride $future.ManifestPath `
            -RepairAuditReceiptPathOverride $receipt.Path
        Assert-Equal `
            -Actual $cleanupContext.StatePolicy.QaDispatchable `
            -Expected $true `
            -Message "Cleanup future context"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-QA-READY-REJECTS-MISSING-RECEIPT" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        Assert-Throws `
            -MessagePattern "RECEIPT|MISSING|POLICY" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride (
                        Join-Path $temp.Root "missing-receipt.json"
                    ) `
                    -ManifestPathOverride $future.ManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-RECEIPT-UNKNOWN-FIELDS" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $receipt.Receipt | Add-Member `
            -NotePropertyName unexpected `
            -NotePropertyValue $true
        Write-TestJson -Path $receipt.Path -Value $receipt.Receipt
        Assert-Throws `
            -MessagePattern "SCHEMA|FIELD|UNKNOWN|PROPERTY" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $receipt.Receipt.scope | Add-Member `
            -NotePropertyName unexpectedNested `
            -NotePropertyValue $true
        Write-TestJson -Path $receipt.Path -Value $receipt.Receipt
        Assert-Throws `
            -MessagePattern "SCHEMA|FIELD|UNKNOWN|PROPERTY" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-NON-LF-OR-BOM-RECEIPT" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $originalBytes = [System.IO.File]::ReadAllBytes($receipt.Path)
        [System.IO.File]::WriteAllBytes(
            $receipt.Path,
            [byte[]](@(0xEF, 0xBB, 0xBF) + $originalBytes)
        )
        Assert-Throws `
            -MessagePattern "BOM|UTF8|RECEIPT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }
        $text = [System.IO.File]::ReadAllText(
            $receipt.Path,
            [System.Text.UTF8Encoding]::new($false, $true)
        ).Substring(3).Replace("`n", "`r`n")
        [System.IO.File]::WriteAllText(
            $receipt.Path,
            $text,
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "CR|LF|UTF8|RECEIPT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-RECEIPT-IDENTITY-DRIFT" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $mutations = @(
            {
                param($value)
                $value.candidate.mainSha256 = "a" * 64
            },
            {
                param($value)
                $value.externalLock.sha256 = "b" * 64
            },
            {
                param($value)
                $value.toolsDigest = "c" * 64
            },
            {
                param($value)
                $value.qaPadV1[0].sha256 = "d" * 64
            },
            {
                param($value)
                $value.nextRole = "G2_K60_QA_OWNER_2026_11_06"
            },
            {
                param($value)
                $value.verdict = "FAIL"
            },
            {
                param($value)
                $value.boundaryViolations = 1
            }
        )
        foreach ($mutation in $mutations) {
            $copy = (
                $receipt.Receipt |
                    ConvertTo-Json -Depth 100 |
                    ConvertFrom-Json -Depth 100 -DateKind String
            )
            & $mutation $copy
            Write-TestJson -Path $receipt.Path -Value $copy
            Assert-Throws `
                -MessagePattern "MISMATCH|DRIFT|PASS|BOUNDARY|NEXT|SHA|DIGEST" `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $future.Contract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Register `
                        -RepairAuditReceiptPathOverride $receipt.Path `
                        -ManifestPathOverride $future.ManifestPath
                }
        }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-AUDIT-COMMIT-BINDING-DRIFT" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $minimumCommit = [string](
            $future.Contract.toolStatePolicy.minimumAuditedCommit
        )
        $minimumParent = Get-TestGitValue @(
            "rev-parse",
            "$minimumCommit^1"
        )
        $originalContract = Copy-TestObject $future.Contract
        $originalReceipt = Copy-TestObject $receipt.Receipt

        $equalContract = Copy-TestObject $future.Contract
        $equalContract.validation.repairAuditExpectations.source.auditedCommit =
            $minimumCommit
        $equalReceipt = Copy-TestObject $receipt.Receipt
        $equalReceipt.auditedCommit = $minimumCommit
        $equalReceipt.parent = $minimumParent
        Update-TestQaPromptBinding `
            -Contract $equalContract `
            -Receipt $equalReceipt `
            -AuditedCommit $minimumCommit
        Write-TestJson -Path $receipt.Path -Value $equalReceipt
        Assert-Throws `
            -MessagePattern "STRICT_DESCENDANT|AUDITED_COMMIT" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $equalContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $parentDriftReceipt = Copy-TestObject $originalReceipt
        $parentDriftReceipt.parent = $minimumParent
        Write-TestJson -Path $receipt.Path -Value $parentDriftReceipt
        Assert-Throws `
            -MessagePattern "PARENT|MISMATCH" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $originalContract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        Write-TestJson -Path $receipt.Path -Value $originalReceipt
        $accepted = Assert-G2RuntimeToolStatePolicy `
            -Contract $originalContract `
            -ContractRoot $contractRoot `
            -RepositoryRoot $repositoryRoot `
            -Action Register `
            -RepairAuditReceiptPathOverride $receipt.Path `
            -ManifestPathOverride $future.ManifestPath
        Assert-Equal `
            -Actual $accepted.Receipt.AuditedCommit `
            -Expected $originalReceipt.auditedCommit `
            -Message "Synthetic later audit descendant"
        Assert-Equal `
            -Actual $accepted.Receipt.Parent `
            -Expected $minimumCommit `
            -Message "Synthetic audit parent"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "STATE-POLICY-REJECTS-UNKNOWN-OR-INCONSISTENT-STATE" {
    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $receipt = New-RepairAuditReceiptFixture `
            -Contract $future.Contract `
            -ManifestPath $future.ManifestPath `
            -Destination $temp.Root
        $future.Contract.authorization.qaDispatchable = $false
        Assert-Throws `
            -MessagePattern "AUTHORIZATION|DISPATCH|STATE|POLICY" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }

        $future.Contract.authorization.qaDispatchable = $true
        $future.Contract.status = "R2_WIP_REPAIR_R3_IN_PROGRESS_OR_READY"
        Assert-Throws `
            -MessagePattern "STATUS|STATE|POLICY|UNKNOWN" `
            -Action {
                Assert-G2RuntimeToolStatePolicy `
                    -Contract $future.Contract `
                    -ContractRoot $contractRoot `
                    -RepositoryRoot $repositoryRoot `
                    -Action Register `
                    -RepairAuditReceiptPathOverride $receipt.Path `
                    -ManifestPathOverride $future.ManifestPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CURRENT-COLLECTOR-RUNTIME-ACTION-FAILS-CLOSED" {
    $registrationPath = Join-Path `
        $contractRoot `
        "qa-pad-v2/collector-registration.json"
    $sessionPath = Join-Path `
        $contractRoot `
        "qa-pad-v2/collector-session.json"
    Assert-True `
        -Condition (-not (Test-Path -LiteralPath $registrationPath)) `
        -Message "Pre-audit Register unexpectedly left registration evidence."
    $output = @(
        & pwsh -NoProfile -File $collectorPath `
            -Action Register `
            -DeviceRole Pad 2>&1
    )
    Assert-True `
        -Condition ($LASTEXITCODE -ne 0) `
        -Message "Pre-audit collector Register unexpectedly passed."
    Assert-True `
        -Condition (($output -join "`n") -match
            "PRE_AUDIT|ACTION|STATUS|POLICY") `
        -Message ("Unexpected pre-audit Register error: " + ($output -join " | "))
    Assert-True `
        -Condition (-not (Test-Path -LiteralPath $registrationPath)) `
        -Message "Pre-audit collector Register wrote registration evidence."
    Assert-True `
        -Condition (-not (Test-Path -LiteralPath $sessionPath)) `
        -Message "Pre-audit collector Register wrote session evidence."
}

Invoke-Case "CONTRACT-PREWINDOW-RECOVERY-SCOPE-BOUND" {
    $recovery = $contract.attemptRecovery
    Assert-Equal `
        -Actual $recovery.schema `
        -Expected "aurorama.g2-prewindow-recovery.v1" `
        -Message "Recovery schema"
    Assert-Equal `
        -Actual $contract.evidence.padRuntimeRoot `
        -Expected ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2" `
        -Message "Active Pad scope"
    Assert-Equal `
        -Actual $contract.evidence.k60RuntimeRoot `
        -Expected ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-k60-v2" `
        -Message "Active K60 scope"
    Assert-Equal `
        -Actual $recovery.historicalPreWindowBlock.root `
        -Expected ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1" `
        -Message "Historical Pad scope"
    Assert-Equal `
        -Actual $recovery.historicalPreWindowBlock.outcome `
        -Expected "PRE_WINDOW_TOOL_BLOCK" `
        -Message "Historical outcome"
    Assert-Equal `
        -Actual $contract.authorization.activePrompt `
        -Expected "none" `
        -Message "No downstream prompt during R3"
    Assert-Equal `
        -Actual $contract.authorization.qaDispatchable `
        -Expected $false `
        -Message "QA dispatch remains deferred"
    Assert-Equal `
        -Actual @($recovery.historicalPreWindowBlock.evidenceFiles).Count `
        -Expected 5 `
        -Message "Historical evidence count"
}

Invoke-Case "PREWINDOW-HISTORICAL-BLOCK-PROVES-NOT-STARTED" {
    $historicalRoot = Join-Path $contractRoot "qa-pad-v1"
    $proof = Assert-G2HistoricalPreWindowBlock `
        -Contract $contract `
        -HistoricalRoot $historicalRoot
    Assert-Equal `
        -Actual $proof.status `
        -Expected "BLOCKED_RUNTIME_TOOL_PATH_BINDING" `
        -Message "Historical blocker status"
    Assert-Equal `
        -Actual $proof.attemptWindow `
        -Expected "NOT_STARTED" `
        -Message "Historical window"
    Assert-Equal `
        -Actual $proof.freshInstall `
        -Expected "NOT_RUN" `
        -Message "Historical install"
    Assert-Equal `
        -Actual $proof.deviceLockState `
        -Expected "ACTIVE_COUNT_1" `
        -Message "Historical internal lock remains historical"
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-CONSUMED-BLOCKER" {
    $blocker = Read-Json (Join-Path $contractRoot "qa-pad-v1/blocker.json")
    $blocker.executed.attemptWindow = "STARTED"
    $blocker.rootCauseBoundary.windowConsumed = $true
    Assert-Throws `
        -MessagePattern "PREWINDOW|WINDOW|CONSUMED|STARTED" `
        -Action {
            Assert-G2HistoricalBlockerSemantics `
                -Contract $contract `
                -Blocker $blocker
        }
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-RETRY-ARTIFACT" {
    $temp = New-TestTempRoot
    try {
        Copy-G2HistoricalFixture `
            -Destination $temp.Root `
            -Source (Join-Path $contractRoot "qa-pad-v1")
        Write-TestJson `
            -Path (Join-Path $temp.Root "collector-registration.json") `
            -Value ([pscustomobject]@{
                schema = "aurorama.g2-collector-registration.v1"
                attemptNumber = 1
            })
        Assert-Throws `
            -MessagePattern "PREWINDOW|REGISTRATION|CONSUMED|RETRY" `
            -Action {
                Assert-G2HistoricalPreWindowBlock `
                    -Contract $contract `
                    -HistoricalRoot $temp.Root
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-MISSING-BLOCKER" {
    $temp = New-TestTempRoot
    try {
        Copy-G2HistoricalFixture `
            -Destination $temp.Root `
            -Source (Join-Path $contractRoot "qa-pad-v1")
        [System.IO.File]::Delete((Join-Path $temp.Root "blocker.json"))
        Assert-Throws `
            -MessagePattern "PREWINDOW|BLOCKER|MISSING" `
            -Action {
                Assert-G2HistoricalPreWindowBlock `
                    -Contract $contract `
                    -HistoricalRoot $temp.Root
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-EVIDENCE-DRIFT" {
    $temp = New-TestTempRoot
    try {
        Copy-G2HistoricalFixture `
            -Destination $temp.Root `
            -Source (Join-Path $contractRoot "qa-pad-v1")
        $path = Join-Path $temp.Root "cleanup-after.json"
        $bytes = [System.IO.File]::ReadAllBytes($path)
        $bytes[$bytes.Length - 2] = $bytes[$bytes.Length - 2] -bxor 1
        [System.IO.File]::WriteAllBytes($path, $bytes)
        Assert-Throws `
            -MessagePattern "PREWINDOW|SHA|HASH|DRIFT" `
            -Action {
                Assert-G2HistoricalPreWindowBlock `
                    -Contract $contract `
                    -HistoricalRoot $temp.Root
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-FRESH-SCOPE-ACCEPTS-PREWINDOW-HISTORY" {
    $temp = New-TestTempRoot
    try {
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "# Device Lock`n`n## 当前占用`n`n## 历史记录`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Invoke-G2DeviceRegistration `
            -Context $context `
            -DeviceRole Pad `
            -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
            -AuthoritativeLockPath $lockPath
        $registration = Read-Json (
            Join-Path $freshRoot "collector-registration.json"
        )
        $newLock = Read-Json (Join-Path $freshRoot "device-lock.json")
        $historicalLock = Read-Json (
            Join-Path $contractRoot "qa-pad-v1/device-lock.json"
        )
        Assert-Equal `
            -Actual $registration.attemptScope `
            -Expected $contract.evidence.padRuntimeRoot `
            -Message "Fresh registration scope"
        Assert-Equal `
            -Actual $registration.recoveryBasis.outcome `
            -Expected "PRE_WINDOW_TOOL_BLOCK" `
            -Message "Fresh registration lineage"
        Assert-Equal `
            -Actual $registration.recoveryBasis.authoritativeLockState `
            -Expected "RELEASED_ACTIVE_0" `
            -Message "Authoritative lock at registration"
        Assert-Equal `
            -Actual $newLock.state `
            -Expected "ACTIVE_COUNT_1" `
            -Message "Fresh instrumented lock"
        Assert-Equal `
            -Actual $historicalLock.state `
            -Expected "ACTIVE_COUNT_1" `
            -Message "Historical lock must remain unchanged"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-NEW-SCOPE-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory($freshRoot)
        Write-TestJson `
            -Path (Join-Path $freshRoot "collector-registration.json") `
            -Value ([pscustomobject]@{ attemptNumber = 1 })
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "## 当前占用`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|REGISTERED|RETRY" `
            -Action {
                Invoke-G2DeviceRegistration `
                    -Context $context `
                    -DeviceRole Pad `
                    -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
                    -AuthoritativeLockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-NEW-SCOPE-LOCK" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory($freshRoot)
        Write-TestJson `
            -Path (Join-Path $freshRoot "device-lock.json") `
            -Value ([pscustomobject]@{
                schema = "aurorama.g2-device-lock.v1"
                state = "RELEASED_ACTIVE_0"
                activeCount = 0
                attemptNumber = 1
            })
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "## 当前占用`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|REGISTERED|RETRY" `
            -Action {
                Invoke-G2DeviceRegistration `
                    -Context $context `
                    -DeviceRole Pad `
                    -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
                    -AuthoritativeLockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-HIDDEN-SCOPE-CONTENT" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory($freshRoot)
        [System.IO.File]::WriteAllText(
            (Join-Path $freshRoot ".unknown"),
            "unexpected",
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|EMPTY|CONTENT|REGISTERED" `
            -Action {
                Assert-G2NewAttemptScopeFresh `
                    -EvidenceFullPath $freshRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-UNKNOWN-SCOPE-DIRECTORY" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory(
            (Join-Path $freshRoot "unknown-directory")
        )
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|EMPTY|CONTENT|REGISTERED" `
            -Action {
                Assert-G2NewAttemptScopeFresh `
                    -EvidenceFullPath $freshRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-AUTHORITATIVE-ACTIVE-LOCK" {
    $temp = New-TestTempRoot
    try {
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "## 当前占用`n`n### Pad 43af8627 — ACTIVE`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Assert-Throws `
            -MessagePattern "AUTHORITATIVE|ACTIVE|LOCK" `
            -Action {
                Invoke-G2DeviceRegistration `
                    -Context $context `
                    -DeviceRole Pad `
                    -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
                    -AuthoritativeLockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "TOOL-MANIFEST-HASHES" {
    Assert-True `
        -Condition (Test-Path -LiteralPath $manifestPath -PathType Leaf) `
        -Message "Missing runtime tools manifest."
    $manifest = Read-Json $manifestPath
    Assert-Equal `
        -Actual $manifest.schema `
        -Expected "aurorama.g2-runtime-tools.v1" `
        -Message "Manifest schema"
    Assert-Equal `
        -Actual $manifest.ownerRole `
        -Expected "G2_RUNTIME_TOOL_OWNER_2026_11_06" `
        -Message "Manifest owner"
    Assert-Equal `
        -Actual $manifest.qaV9ReuseCount `
        -Expected 0 `
        -Message "Historical reuse count"
    foreach ($entry in @($manifest.files)) {
        $entryPath = [System.IO.Path]::GetFullPath(
            (Join-Path $contractRoot $entry.path)
        )
        Assert-True `
            -Condition $entryPath.StartsWith(
                ($contractRoot + [System.IO.Path]::DirectorySeparatorChar),
                [System.StringComparison]::OrdinalIgnoreCase
            ) `
            -Message "Manifest path escaped contract root: $($entry.path)"
        Assert-True `
            -Condition (Test-Path -LiteralPath $entryPath -PathType Leaf) `
            -Message "Manifest file missing: $($entry.path)"
        Assert-Equal `
            -Actual (Get-FileSha256 $entryPath) `
            -Expected $entry.sha256 `
            -Message "Manifest hash $($entry.path)"
    }
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        $tool = $contract.tools.$toolName
        $toolPath = [System.IO.Path]::GetFullPath(
            (Join-Path $repositoryRoot $tool.path)
        )
        Assert-Equal `
            -Actual (Get-FileSha256 $toolPath) `
            -Expected $tool.sha256 `
            -Message "Contract hash $toolName"
    }
    $digestPaths = @(
        $manifest.files |
            Where-Object { [string]$_.kind -ceq "tool" } |
            ForEach-Object { [string]$_.path }
    )
    $digestPaths = @(
        Get-TestOrdinalSortedStrings -Value $digestPaths
    )
    $digestFiles = @(
        $digestPaths |
            ForEach-Object {
                $digestPath = $_
                @(
                    $manifest.files |
                        Where-Object {
                            [string]$_.path -ceq $digestPath
                        }
                )[0]
            }
    )
    $digestText = [System.Text.StringBuilder]::new()
    foreach ($entry in $digestFiles) {
        $toolPath = Join-Path $contractRoot ([string]$entry.path)
        [void]$digestText.Append(
            (Get-FileSha256 $toolPath) +
                "  " +
                [string]$entry.path +
                "`n"
        )
    }
    $digest = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            [System.Text.Encoding]::UTF8.GetBytes($digestText.ToString())
        )
    ).ToLowerInvariant()
    Assert-Equal `
        -Actual $contract.tools.digestSha256 `
        -Expected $digest `
        -Message "Tools digest"
}

Invoke-Case "TOOL-MANIFEST-EXACT-11-UNIQUE-AND-KIND-SET" {
    $manifest = Read-Json $manifestPath
    $entries = @($manifest.files)
    Assert-Equal `
        -Actual $entries.Count `
        -Expected 11 `
        -Message "Manifest entry count"
    $pathSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($entry in $entries) {
        Assert-True `
            -Condition $pathSet.Add([string]$entry.path) `
            -Message "Manifest path casing duplicate: $($entry.path)"
    }
    Assert-Equal `
        -Actual $pathSet.Count `
        -Expected 11 `
        -Message "Manifest unique path count"
    foreach ($expectedKind in @(
        @("fixture", 5),
        @("schema", 1),
        @("test", 1),
        @("tool", 4)
    )) {
        Assert-Equal `
            -Actual @(
                $entries |
                    Where-Object {
                        [string]$_.kind -ceq [string]$expectedKind[0]
                    }
            ).Count `
            -Expected $expectedKind[1] `
            -Message "Manifest kind count $($expectedKind[0])"
    }
    Assert-Equal `
        -Actual @(
            $entries |
                Where-Object {
                    [string]$_.kind -notin @(
                        "fixture",
                        "schema",
                        "test",
                        "tool"
                    )
                }
        ).Count `
        -Expected 0 `
        -Message "Unknown manifest kind"
}

Invoke-Case "STATE-POLICY-REJECTS-MANIFEST-CARDINALITY-KIND-AND-SHA" {
    $temp = New-TestTempRoot
    try {
        $baseManifest = Read-Json $manifestPath
        $mutations = @(
            {
                param($value)
                $value.files = @($value.files | Select-Object -First 10)
            },
            {
                param($value)
                $value.files = @($value.files) + @($value.files[-1])
            },
            {
                param($value)
                $value.files[9] = (
                    $value.files[10] |
                        ConvertTo-Json -Depth 20 |
                        ConvertFrom-Json
                )
            },
            {
                param($value)
                $value.files[9].kind = "unknown"
            },
            {
                param($value)
                $value.files[9].sha256 = ("0" * 64)
            }
        )
        $mutationIndex = 0
        foreach ($mutation in $mutations) {
            $mutationIndex++
            $manifest = (
                $baseManifest |
                    ConvertTo-Json -Depth 100 |
                    ConvertFrom-Json -Depth 100 -DateKind String
            )
            & $mutation $manifest
            $mutatedManifestPath = Join-Path `
                $temp.Root `
                "manifest-$mutationIndex.json"
            Write-TestJson `
                -Path $mutatedManifestPath `
                -Value $manifest
            $driftedContract = (
                $contract |
                    ConvertTo-Json -Depth 100 |
                    ConvertFrom-Json -Depth 100 -DateKind String
            )
            $driftedContract.tools.runtimeManifest.sha256 =
                Get-FileSha256 $mutatedManifestPath
            Assert-Throws `
                -MessagePattern "MANIFEST|CARDINAL|UNIQUE|DUPLICATE|KIND|SHA|DIGEST" `
                -Action {
                    Assert-G2RuntimeToolStatePolicy `
                        -Contract $driftedContract `
                        -ContractRoot $contractRoot `
                        -RepositoryRoot $repositoryRoot `
                        -Action Validate `
                        -ManifestPathOverride $mutatedManifestPath
                }
        }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "NO-HISTORICAL-TOOL-REUSE" {
    foreach ($toolPath in @($toolPaths + $policyPath)) {
        $text = Get-Content -LiteralPath $toolPath -Raw -Encoding utf8
        Assert-True `
            -Condition (-not $text.Contains(
                "qa-v9",
                [System.StringComparison]::OrdinalIgnoreCase
            )) `
            -Message "Historical path reference in $toolPath"
    }
}

Invoke-Case "NO-CREDENTIAL-READS" {
    foreach ($toolPath in @($toolPaths + $policyPath)) {
        $text = Get-Content -LiteralPath $toolPath -Raw -Encoding utf8
        foreach ($forbidden in @(
            "资源.txt",
            "Authorization:",
            "password=",
            "accessToken",
            "clientSecret"
        )) {
            Assert-True `
                -Condition (-not $text.Contains(
                    $forbidden,
                    [System.StringComparison]::OrdinalIgnoreCase
                )) `
                -Message "Credential-shaped token [$forbidden] in $toolPath"
        }
    }
}

Invoke-Case "COLLECTOR-STATIC-SELF-TEST" {
    $output = @(
        & pwsh -NoProfile -File $collectorPath `
            -Action StaticSelfTest `
            -DeviceRole Pad 2>&1
    )
    Assert-Equal -Actual $LASTEXITCODE -Expected 0 -Message "Collector exit"
    Assert-True `
        -Condition (($output -join "`n").Contains("DEVICE_COMMANDS=0")) `
        -Message "Collector static mode reported device commands."
}

Invoke-Case "FINALIZER-STATIC-SELF-TEST" {
    $output = @(
        & pwsh -NoProfile -File $finalizerPath `
            -StaticSelfTest 2>&1
    )
    Assert-Equal -Actual $LASTEXITCODE -Expected 0 -Message "Finalizer exit"
    Assert-True `
        -Condition (($output -join "`n").Contains("DEVICE_COMMANDS=0")) `
        -Message "Finalizer static mode reported device commands."
}

Invoke-Case "CLEANUP-STATIC-SELF-TEST" {
    $output = @(
        & pwsh -NoProfile -File $cleanupPath `
            -StaticSelfTest `
            -DeviceRole Pad 2>&1
    )
    Assert-Equal -Actual $LASTEXITCODE -Expected 0 -Message "Cleanup exit"
    Assert-True `
        -Condition (($output -join "`n").Contains("DEVICE_COMMANDS=0")) `
        -Message "Cleanup static mode reported device commands."
}

Invoke-Case "PATH-ACCEPTS-EVIDENCE-RELATIVE-ROOT" {
    $expected = [System.IO.Path]::GetFullPath(
        (Join-Path $contractRoot "qa-pad-v1")
    )
    $resolved = Resolve-G2BoundedPath `
        -Root $contractRoot `
        -RelativePath "qa-pad-v1"
    Assert-Equal `
        -Actual $resolved `
        -Expected $expected `
        -Message "Evidence-relative path"
    Assert-True `
        -Condition (Test-Path -LiteralPath $resolved -PathType Container) `
        -Message "Pad evidence directory was not resolved."
}

Invoke-Case "PATH-REPO-RELATIVE-TOOL-VALIDATE-PASSES" {
    $output = @(
        & pwsh -NoProfile -File $collectorPath `
            -Action Validate `
            -DeviceRole Pad 2>&1
    )
    $exitCode = $LASTEXITCODE
    Assert-Equal `
        -Actual $exitCode `
        -Expected 0 `
        -Message ("Collector Validate failed: " + ($output -join " | "))
    Assert-True `
        -Condition (($output -join "`n").Contains(
            "PASS G2_RUNTIME_COLLECTOR_CONTRACT",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Collector Validate did not report contract PASS."
}

Invoke-Case "PATH-REJECTS-TRAVERSAL" {
    Assert-Throws `
        -MessagePattern "TRAVERSAL|OUTSIDE" `
        -Action {
            Resolve-G2BoundedPath `
                -Root $contractRoot `
                -RelativePath "../outside"
        }
}

Invoke-Case "PATH-REJECTS-ABSOLUTE" {
    Assert-Throws `
        -MessagePattern "ABSOLUTE|OUTSIDE" `
        -Action {
            Resolve-G2BoundedPath `
                -Root $contractRoot `
                -RelativePath "F:\outside\evidence"
        }
}

Invoke-Case "PATH-REJECTS-REPARSE-ESCAPE" {
    $temp = New-TestTempRoot
    $guardRoot = Join-Path $temp.Root "guard"
    $guardTarget = Join-Path $temp.Root "target"
    $guardLink = Join-Path $guardRoot "escape"
    try {
        [void][System.IO.Directory]::CreateDirectory($guardRoot)
        [void][System.IO.Directory]::CreateDirectory($guardTarget)
        [void](New-Item -ItemType Junction -Path $guardLink -Target $guardTarget)
        Assert-Throws `
            -MessagePattern "REPARSE|OUTSIDE" `
            -Action {
                Resolve-G2BoundedPath `
                    -Root $guardRoot `
                    -RelativePath "escape/file.txt"
            }
    } finally {
        if ([System.IO.Directory]::Exists($guardLink)) {
            [System.IO.Directory]::Delete($guardLink)
        }
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "V2-EVIDENCE-ROOT-JUNCTIONS-FAIL-CLOSED" {
    $temp = New-TestTempRoot
    $relativeRoot = ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2"
    $parent = Join-Path $temp.Root (
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT"
    )
    $junction = Join-Path $temp.Root $relativeRoot
    $target = Join-Path $temp.Root "junction-target"
    try {
        [void][System.IO.Directory]::CreateDirectory($parent)
        [void][System.IO.Directory]::CreateDirectory($target)
        [void](New-Item -ItemType Junction -Path $junction -Target $target)
        Assert-Throws `
            -MessagePattern "REPARSE|JUNCTION" `
            -Action {
                Resolve-G2EvidenceRoot `
                    -ContractRoot $parent `
                    -DeviceRole Pad `
                    -RepositoryBase $temp.Root
            }
        Assert-Throws `
            -MessagePattern "REPARSE|JUNCTION" `
            -Action {
                Resolve-G2ReportEvidenceRoot `
                    -DeviceRole Pad `
                    -RepositoryBase $temp.Root
            }
        Assert-Throws `
            -MessagePattern "REPARSE|JUNCTION" `
            -Action {
                Resolve-G2CleanupEvidenceRoot `
                    -DeviceRole Pad `
                    -RepositoryBase $temp.Root
            }
    } finally {
        if ([System.IO.Directory]::Exists($junction)) {
            [System.IO.Directory]::Delete($junction)
        }
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "PINNED-CONTRACT-IDENTITY-REJECTS-DRIFT" {
    foreach ($mutation in @(
        "sourceCommit",
        "mainSha256",
        "focusPeerSha256",
        "schemaSha256"
    )) {
        $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
        switch ($mutation) {
            "sourceCommit" {
                $drifted.candidate.sourceCommit = ("0" * 40)
            }
            "mainSha256" {
                $drifted.candidate.main.sha256 = ("0" * 64)
            }
            "focusPeerSha256" {
                $drifted.candidate.focusPeer.sha256 = ("0" * 64)
            }
            "schemaSha256" {
                $drifted.evidence.reportSchemaSha256 = ("0" * 64)
            }
        }
        Assert-Throws `
            -MessagePattern "PINNED|IDENTITY|SHA|COMMIT|SCHEMA" `
            -Action {
                Assert-G2PinnedIdentity -Contract $drifted
            }
    }
}

Invoke-Case "AUTH-K60-REJECTS-MISSING-CONDITIONAL-SCOPE" {
    $drifted = $authorization | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.scope.k60.authorized = $false
    $drifted.scope.k60.conditional = $false
    Assert-Throws `
        -MessagePattern "AUTHORIZATION|K60|CONDITIONAL" `
        -Action {
            Assert-G2AuthorizationReceipt `
                -Contract $contract `
                -Authorization $drifted `
                -DeviceRole K60
        }
}

Invoke-Case "AUTH-K60-ACCEPTS-CURRENT-RECEIPT" {
    Assert-G2AuthorizationReceipt `
        -Contract $contract `
        -Authorization $authorization `
        -DeviceRole K60
}

Invoke-Case "FINALIZER-REJECTS-PINNED-IDENTITY-DRIFT" {
    $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.candidate.main.sha256 = ("0" * 64)
    Assert-Throws `
        -MessagePattern "PINNED|IDENTITY|SHA" `
        -Action {
            Assert-G2ReportPinnedIdentity -Contract $drifted
        }
}

Invoke-Case "FINALIZER-REJECTS-AUTH-SCOPE-DRIFT" {
    $drifted = $authorization | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.scope.k60.conditional = $false
    Assert-Throws `
        -MessagePattern "AUTHORIZATION|CONDITIONAL" `
        -Action {
            Assert-G2ReportAuthorization `
                -Contract $contract `
                -Authorization $drifted
        }
}

Invoke-Case "CLEANUP-REJECTS-PINNED-IDENTITY-DRIFT" {
    $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.evidence.reportSchemaSha256 = ("0" * 64)
    Assert-Throws `
        -MessagePattern "PINNED|IDENTITY|SCHEMA|SHA" `
        -Action {
            Assert-G2CleanupPinnedIdentity -Contract $drifted
        }
}

Invoke-Case "CLEANUP-CONTEXT-ACCEPTS-CURRENT-SCHEMA-PIN" {
    $context = Get-G2CleanupContext `
        -DeviceRole Pad `
        -Action Validate
    Assert-Equal `
        -Actual $context.Evidence.RelativePath `
        -Expected $contract.evidence.padRuntimeRoot `
        -Message "Cleanup context scope"
    Assert-Equal `
        -Actual $context.Contract.evidence.reportSchemaSha256 `
        -Expected (Get-FileSha256 $schemaPath) `
        -Message "Cleanup context schema pin"
}

Invoke-Case "CLEANUP-REJECTS-SCHEMA-FILE-HASH-DRIFT" {
    $temp = New-TestTempRoot
    try {
        $driftedSchema = Join-Path $temp.Root "report.schema.json"
        [System.IO.File]::Copy($schemaPath, $driftedSchema)
        $bytes = [System.IO.File]::ReadAllBytes($driftedSchema)
        $bytes[$bytes.Length - 1] = $bytes[$bytes.Length - 1] -bxor 1
        [System.IO.File]::WriteAllBytes($driftedSchema, $bytes)
        Assert-Throws `
            -MessagePattern "CLEANUP.*SCHEMA.*(SHA|HASH|PINNED)" `
            -Action {
                Assert-G2CleanupSchemaPin `
                    -Contract $contract `
                    -SchemaPath $driftedSchema
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CLEANUP-REJECTS-OLD-SCHEMA-PIN" {
    $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.evidence.reportSchemaSha256 =
        "8cb6d8766e773107e9493b828239ee417c61cf703a3c7756f94b684729921046"
    Assert-Throws `
        -MessagePattern "PINNED|IDENTITY|SCHEMA|SHA" `
        -Action {
            Assert-G2CleanupPinnedIdentity -Contract $drifted
        }
}

Invoke-Case "CLEANUP-REJECTS-AUTH-SCOPE-DRIFT" {
    $drifted = $authorization | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.scope.k60.authorized = $false
    Assert-Throws `
        -MessagePattern "AUTHORIZATION|K60|SCOPE" `
        -Action {
            Assert-G2CleanupAuthorization `
                -Contract $contract `
                -Authorization $drifted
        }
}

Invoke-Case "DEVICE-REJECTS-UNKNOWN-SERIAL" {
    Assert-Throws `
        -MessagePattern "SERIAL|DEVICE" `
        -Action {
            Assert-G2DevicePolicy `
                -Contract $contract `
                -DeviceRole Pad `
                -Serial "deadbeef" `
                -Predecessor $null
        }
}

Invoke-Case "K60-REQUIRES-PAD-PREDECESSOR" {
    Assert-Throws `
        -MessagePattern "K60|PREDECESSOR|PAD" `
        -Action {
            Assert-G2DevicePolicy `
                -Contract $contract `
                -DeviceRole K60 `
                -Serial "8e875894" `
                -Predecessor $null
        }
}

Invoke-Case "K60-ACCEPTS-VALID-PREDECESSOR" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        Assert-G2DevicePolicy `
            -Contract $contract `
            -Context $fixture.Context `
            -DeviceRole K60 `
            -Serial "8e875894" `
            -Predecessor $fixture.Predecessor
        $caseVariant = Copy-TestObject $fixture.Predecessor
        $deviceOrder = $caseVariant.deviceOrder
        [void]$caseVariant.PSObject.Properties.Remove("deviceOrder")
        $caseVariant | Add-Member `
            -NotePropertyName DeviceOrder `
            -NotePropertyValue $deviceOrder
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|FIELD" `
            -Action {
                Assert-G2DevicePolicy `
                    -Contract $contract `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Serial "8e875894" `
                    -Predecessor $caseVariant
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-CLEANUP-GATE-PRECEDES-ADB-AND-DIRECTORY-WRITES" {
    foreach ($cleanupAction in @("CaptureBefore", "Verify")) {
        foreach ($condition in @(
            "PAD_MISSING",
            "PAD_NON_PASS",
            "OLD_SCOPE",
            "SHA_DRIFT",
            "ACTIVE_LOCK"
        )) {
            $temp = New-TestTempRoot
            try {
                $fixture = New-TestFutureCleanupFixture `
                    -Temp $temp `
                    -Observations $observations
                switch ($condition) {
                    "PAD_MISSING" {
                        [System.IO.File]::Delete(
                            $fixture.PadReportPath
                        )
                    }
                    "PAD_NON_PASS" {
                        $report = Read-Json $fixture.PadReportPath
                        $report.verdict = "FAIL"
                        $report.productVerdict = "FAIL"
                        Write-TestJson `
                            -Path $fixture.PadReportPath `
                            -Value $report
                    }
                    "OLD_SCOPE" {
                        $oldRoot = (
                            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/" +
                            "qa-pad-v1"
                        )
                        $report = Read-Json $fixture.PadReportPath
                        $report.evidenceRoot = $oldRoot
                        Write-TestJson `
                            -Path $fixture.PadReportPath `
                            -Value $report
                        $lock = Read-Json $fixture.PadLockPath
                        $lock.attemptScope = $oldRoot
                        Write-TestJson `
                            -Path $fixture.PadLockPath `
                            -Value $lock
                    }
                    "SHA_DRIFT" {
                        [System.IO.File]::AppendAllText(
                            $fixture.PadReportPath,
                            "`n",
                            [System.Text.UTF8Encoding]::new($false)
                        )
                    }
                    "ACTIVE_LOCK" {
                        $lock = Read-Json $fixture.PadLockPath
                        $lock.state = "ACTIVE_COUNT_1"
                        $lock.activeCount = 1
                        $lock.releasedAt = $null
                        Write-TestJson `
                            -Path $fixture.PadLockPath `
                            -Value $lock
                    }
                }
                $k60Root = Join-Path `
                    $temp.Root `
                    $fixture.Future.Contract.evidence.k60RuntimeRoot
                if ($condition -ceq "PAD_MISSING") {
                    Assert-True `
                        -Condition ($k60Root.StartsWith(
                            $temp.Root,
                            [System.StringComparison]::OrdinalIgnoreCase
                        )) `
                        -Message "Unsafe K60 fixture root."
                    if ([System.IO.Directory]::Exists($k60Root)) {
                        [System.IO.Directory]::Delete($k60Root, $true)
                    }
                }

                $script:k60CleanupSideEffects = [pscustomobject]@{
                    AdbCalls = 0
                    DirectoryCalls = 0
                    TextWrites = 0
                }
                function Invoke-G2CleanupAdb {
                    param(
                        [Parameter(Mandatory)][string]$Serial,
                        [Parameter(Mandatory)][string[]]$Arguments,
                        [switch]$AllowFailure
                    )
                    $script:k60CleanupSideEffects.AdbCalls++
                    throw "UNEXPECTED_K60_CLEANUP_ADB"
                }
                function New-G2CleanupDirectory {
                    param([Parameter(Mandatory)][string]$Path)
                    $script:k60CleanupSideEffects.DirectoryCalls++
                    throw "UNEXPECTED_K60_CLEANUP_DIRECTORY"
                }
                function Write-G2CleanupText {
                    param(
                        [Parameter(Mandatory)][string]$Path,
                        [Parameter(Mandatory)][string]$Text
                    )
                    $script:k60CleanupSideEffects.TextWrites++
                    throw "UNEXPECTED_K60_CLEANUP_WRITE"
                }

                Assert-Throws `
                    -MessagePattern "UNEXPECTED_K60_CLEANUP_ADB" `
                    -Action {
                        Invoke-G2CleanupAdb `
                            -Serial "43af8627" `
                            -Arguments @("shell", "true")
                    }
                Assert-Equal `
                    -Actual $script:k60CleanupSideEffects.AdbCalls `
                    -Expected 1 `
                    -Message "Fail-on-call ADB wrapper did not fire."
                Assert-Throws `
                    -MessagePattern "UNEXPECTED_K60_CLEANUP_DIRECTORY" `
                    -Action {
                        New-G2CleanupDirectory -Path (
                            Join-Path $temp.Root "must-not-exist"
                        )
                    }
                Assert-Equal `
                    -Actual $script:k60CleanupSideEffects.DirectoryCalls `
                    -Expected 1 `
                    -Message "Fail-on-call directory wrapper did not fire."
                Assert-Throws `
                    -MessagePattern "UNEXPECTED_K60_CLEANUP_WRITE" `
                    -Action {
                        Write-G2CleanupText `
                            -Path (Join-Path $temp.Root "must-not-exist.json") `
                            -Text "{}"
                    }
                Assert-Equal `
                    -Actual $script:k60CleanupSideEffects.TextWrites `
                    -Expected 1 `
                    -Message "Fail-on-call text wrapper did not fire."
                $script:k60CleanupSideEffects.AdbCalls = 0
                $script:k60CleanupSideEffects.DirectoryCalls = 0
                $script:k60CleanupSideEffects.TextWrites = 0
                $expectedFailure = switch ($condition) {
                    "PAD_MISSING" {
                        "K60_PREDECESSOR_REPORT_OR_LOCK_MISSING"
                    }
                    "SHA_DRIFT" {
                        "PAD_PREDECESSOR_BINDING_MISMATCH"
                    }
                    default {
                        "K60_PREDECESSOR_PAD_NOT_FULL_PASS_OR_LOCK_NOT_RELEASED"
                    }
                }

                $beforeEntries = Get-TestRelativeEntrySet -Root $k60Root
                $result = Invoke-TestCatching -Action {
                    $context = Get-G2CleanupContext `
                        -DeviceRole K60 `
                        -RequestedEvidenceRoot (
                            $fixture.Future.Contract.evidence.k60RuntimeRoot
                        ) `
                        -Action $cleanupAction `
                        -ContractPathOverride (
                            $fixture.Future.ContractPath
                        ) `
                        -ManifestPathOverride (
                            $fixture.Future.ManifestPath
                        ) `
                        -RepairAuditReceiptPathOverride (
                            $fixture.Receipt.Path
                        ) `
                        -RepositoryBaseOverride $temp.Root
                    switch ($cleanupAction) {
                        "CaptureBefore" {
                            Capture-G2CleanupBefore `
                                -Context $context `
                                -DeviceRole K60
                        }
                        "Verify" {
                            Invoke-G2CleanupVerification `
                                -Context $context `
                                -DeviceRole K60
                        }
                    }
                }
                Assert-True `
                    -Condition ([bool]$result.Threw) `
                    -Message (
                        "$cleanupAction/$condition predecessor gate did not fail."
                    )
                Assert-True `
                    -Condition (
                        [string]$result.ErrorMessage -match
                            [regex]::Escape($expectedFailure)
                    ) `
                    -Message (
                        "$cleanupAction/$condition hit an unrelated failure: " +
                        [string]$result.ErrorMessage
                    )
                Assert-Equal `
                    -Actual $script:k60CleanupSideEffects.AdbCalls `
                    -Expected 0 `
                    -Message "$cleanupAction/$condition ADB call count"
                Assert-Equal `
                    -Actual $script:k60CleanupSideEffects.DirectoryCalls `
                    -Expected 0 `
                    -Message "$cleanupAction/$condition directory call count"
                Assert-Equal `
                    -Actual $script:k60CleanupSideEffects.TextWrites `
                    -Expected 0 `
                    -Message "$cleanupAction/$condition file write count"
                if ($condition -ceq "PAD_MISSING") {
                    Assert-True `
                        -Condition (-not [System.IO.Directory]::Exists(
                            $k60Root
                        )) `
                        -Message (
                            "$cleanupAction/$condition created K60 directory."
                        )
                } else {
                    $afterEntries = Get-TestRelativeEntrySet -Root $k60Root
                    Assert-Equal `
                        -Actual ($afterEntries -join "`n") `
                        -Expected ($beforeEntries -join "`n") `
                        -Message (
                            "$cleanupAction/$condition changed K60 tree."
                        )
                }
            } finally {
                Remove-TestTempRoot -Temp $temp
            }
        }
    }
}

Invoke-Case "K60-CLEANUP-GATE-ACCEPTS-SAME-VALID-PREDECESSOR" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestFutureCleanupFixture `
            -Temp $temp `
            -Observations $observations
        foreach ($action in @("CaptureBefore", "Verify")) {
            $context = Get-G2CleanupContext `
                -DeviceRole K60 `
                -RequestedEvidenceRoot (
                    $fixture.Future.Contract.evidence.k60RuntimeRoot
                ) `
                -Action $action `
                -ContractPathOverride $fixture.Future.ContractPath `
                -ManifestPathOverride $fixture.Future.ManifestPath `
                -RepairAuditReceiptPathOverride $fixture.Receipt.Path `
                -RepositoryBaseOverride $temp.Root
            $predecessor = $context.Predecessor
            Assert-Equal `
                -Actual $predecessor.coreVerdict `
                -Expected "PASS" `
                -Message "$action predecessor core verdict"
            Assert-Equal `
                -Actual $predecessor.productVerdict `
                -Expected "PASS" `
                -Message "$action predecessor product verdict"
            Assert-Equal `
                -Actual $predecessor.lockStateAfterClosure `
                -Expected "RELEASED_ACTIVE_0" `
                -Message "$action predecessor lock state"
            Assert-Equal `
                -Actual $predecessor.evidencePointer `
                -Expected (
                    $fixture.Future.Contract.evidence.padRuntimeRoot +
                    "/report.json"
                ) `
                -Message "$action predecessor pointer"
        }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-FORGED-A-B-SHA-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestK60RegistrationFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $forged = $fixture.Registration | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $forged.predecessor.reportSha256 = ("a" * 64)
        $forged.predecessor.lockSha256 = ("b" * 64)
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|SHA|REPORT|LOCK|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Registration $forged
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-REPORT-SHA-DRIFT-AFTER-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestK60RegistrationFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        [System.IO.File]::AppendAllText(
            $fixture.Pad.ReportPath,
            "`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|SHA|REPORT|LOCK|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Registration $fixture.Registration
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-LOCK-SHA-DRIFT-AFTER-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestK60RegistrationFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        [System.IO.File]::AppendAllText(
            $fixture.Pad.LockPath,
            "`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|SHA|REPORT|LOCK|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Registration $fixture.Registration
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-DUPLICATE-PRODUCT-GATE" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $invalidReport = $fixture.Report | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $invalidReport.productGates[1].gateId =
            $invalidReport.productGates[0].gateId
        Write-TestJson -Path $fixture.ReportPath -Value $invalidReport
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|GATE|PRODUCT|MISMATCH" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-NON-PASS-CLEANUP-REPORT" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $invalidReport = $fixture.Report | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $invalidReport.cleanupAndRestore.status = "FAIL"
        Write-TestJson -Path $fixture.ReportPath -Value $invalidReport
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|CLEANUP|PASS|MISMATCH" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-ACTIVE-PAD-LOCK" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $activeLock = Read-Json $fixture.LockPath
        $activeLock.state = "ACTIVE_COUNT_1"
        $activeLock.activeCount = 1
        $activeLock.releasedAt = $null
        Write-TestJson -Path $fixture.LockPath -Value $activeLock
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|LOCK|RELEASED|ACTIVE" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-OLD-REPORT-AND-LOCK-SCOPE" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $oldReport = $fixture.Report | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $oldReport.evidenceRoot =
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
        Write-TestJson -Path $fixture.ReportPath -Value $oldReport
        $oldLock = Read-Json $fixture.LockPath
        $oldLock.attemptScope =
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
        Write-TestJson -Path $fixture.LockPath -Value $oldLock
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|PAD|ROOT|SCOPE|PASS|LOCK" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-HISTORICAL-PAD-PREDECESSOR" {
    $predecessor = [pscustomobject]@{
        deviceOrder = "Pad"
        coreVerdict = "PASS"
        lockStateAfterClosure = "RELEASED_ACTIVE_0"
        productVerdict = "PASS"
        evidenceRoot = (
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
        )
        candidateMainSha256 = $contract.candidate.main.sha256
        candidateFocusPeerSha256 = $contract.candidate.focusPeer.sha256
        evidencePointer = (
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1/report.json"
        )
        reportSha256 = ("a" * 64)
        lockSha256 = ("b" * 64)
    }
    Assert-Throws `
        -MessagePattern "K60|PREDECESSOR|PAD|EVIDENCE|SCOPE" `
        -Action {
            Assert-G2DevicePolicy `
                -Contract $contract `
                -DeviceRole K60 `
                -Serial "8e875894" `
                -Predecessor $predecessor
        }
}

Invoke-Case "WINDOW-CREATES-HOST-EVIDENCE-AFTER-ALL_PREFLIGHT_GATES" {
    $collectorText = [System.IO.File]::ReadAllText(
        $collectorPath,
        [System.Text.UTF8Encoding]::new($false, $true)
    )
    $windowStart = $collectorText.IndexOf(
        "function Invoke-G2Window",
        [System.StringComparison]::Ordinal
    )
    $registrationCheck = $collectorText.IndexOf(
        "COLLECTOR_REGISTRATION_MISSING",
        $windowStart,
        [System.StringComparison]::Ordinal
    )
    $registrationAssert = $collectorText.IndexOf(
        "Assert-G2CollectorRegistrationState",
        $windowStart,
        [System.StringComparison]::Ordinal
    )
    $deviceAssert = $collectorText.IndexOf(
        "Assert-G2DevicePolicy",
        $windowStart,
        [System.StringComparison]::Ordinal
    )
    $hostWriteIndexes = @(
        foreach ($marker in @(
            "New-G2CollectorDirectory",
            "Write-G2Json",
            "Write-G2Utf8LfText",
            "[System.IO.Directory]::CreateDirectory"
        )) {
            $collectorText.IndexOf(
                $marker,
                $windowStart,
                [System.StringComparison]::Ordinal
            )
        }
    ) | Where-Object { $_ -ge 0 }
    $firstHostWrite = (
        $hostWriteIndexes |
            Measure-Object -Minimum
    ).Minimum
    Assert-True `
        -Condition (
            $windowStart -ge 0 -and
            $registrationCheck -gt $windowStart -and
            $registrationAssert -gt $registrationCheck -and
            $deviceAssert -gt $registrationAssert -and
            $firstHostWrite -gt $deviceAssert
        ) `
        -Message (
            "RunWindow must create host evidence only after registration " +
            "and device policy gates."
        )

    $temp = New-TestTempRoot
    try {
        $future = New-FutureContractFixture `
            -SourceContract $contract `
            -ManifestPath $manifestPath `
            -Destination $temp.Root
        $evidenceRelative = $future.Contract.evidence.padRuntimeRoot
        $evidenceFullPath = Join-Path $temp.Root $evidenceRelative
        $context = [pscustomobject]@{
            Contract = $future.Contract
            Evidence = [pscustomobject]@{
                RelativePath = $evidenceRelative
                FullPath = $evidenceFullPath
            }
            RepositoryRoot = $temp.Root
            ToolsDigest = $future.Contract.tools.digestSha256
            StatePolicy = [pscustomobject]@{
                Mode = "AUDIT_PASS_PENDING_FRESH_PAD_QA"
                QaDispatchable = $true
            }
        }
        $script:g2WindowSideEffects = [pscustomobject]@{
            AdbCalls = 0
            DirectoryCalls = 0
            TextWrites = 0
        }
        function Invoke-G2Adb {
            param(
                [Parameter(Mandatory)][string]$Serial,
                [Parameter(Mandatory)][string[]]$Arguments,
                [switch]$AllowFailure
            )
            $script:g2WindowSideEffects.AdbCalls++
            throw "UNEXPECTED_G2_WINDOW_ADB"
        }
        function New-G2CollectorDirectory {
            param([Parameter(Mandatory)][string]$Path)
            $script:g2WindowSideEffects.DirectoryCalls++
            throw "UNEXPECTED_G2_WINDOW_DIRECTORY"
        }
        function Write-G2Utf8LfText {
            param(
                [Parameter(Mandatory)][string]$Path,
                [Parameter(Mandatory)][string]$Text
            )
            $script:g2WindowSideEffects.TextWrites++
            throw "UNEXPECTED_G2_WINDOW_WRITE"
        }
        Assert-Throws `
            -MessagePattern "UNEXPECTED_G2_WINDOW_ADB" `
            -Action {
                Invoke-G2Adb `
                    -Serial "43af8627" `
                    -Arguments @("shell", "true")
            }
        Assert-Throws `
            -MessagePattern "UNEXPECTED_G2_WINDOW_DIRECTORY" `
            -Action {
                New-G2CollectorDirectory -Path (
                    Join-Path $temp.Root "must-not-exist"
                )
            }
        Assert-Throws `
            -MessagePattern "UNEXPECTED_G2_WINDOW_WRITE" `
            -Action {
                Write-G2Utf8LfText `
                    -Path (Join-Path $temp.Root "must-not-exist.json") `
                    -Text "{}"
            }
        Assert-Equal `
            -Actual $script:g2WindowSideEffects.AdbCalls `
            -Expected 1 `
            -Message "Window ADB wrapper did not fire."
        Assert-Equal `
            -Actual $script:g2WindowSideEffects.DirectoryCalls `
            -Expected 1 `
            -Message "Window directory wrapper did not fire."
        Assert-Equal `
            -Actual $script:g2WindowSideEffects.TextWrites `
            -Expected 1 `
            -Message "Window file wrapper did not fire."
        $script:g2WindowSideEffects.AdbCalls = 0
        $script:g2WindowSideEffects.DirectoryCalls = 0
        $script:g2WindowSideEffects.TextWrites = 0

        $result = Invoke-TestCatching -Action {
            Invoke-G2Window `
                -Context $context `
                -DeviceRole Pad `
                -PlanPath $null
        }
        Assert-True `
            -Condition ([bool]$result.Threw) `
            -Message "RunWindow without registration did not fail closed."
        Assert-True `
            -Condition (
                [string]$result.ErrorMessage -match
                    "COLLECTOR_REGISTRATION_MISSING"
            ) `
            -Message (
                "RunWindow failed for the wrong reason: " +
                [string]$result.ErrorMessage
            )
        Assert-Equal `
            -Actual $script:g2WindowSideEffects.AdbCalls `
            -Expected 0 `
            -Message "RunWindow ADB calls before registration"
        Assert-Equal `
            -Actual $script:g2WindowSideEffects.DirectoryCalls `
            -Expected 0 `
            -Message "RunWindow directory calls before registration"
        Assert-Equal `
            -Actual $script:g2WindowSideEffects.TextWrites `
            -Expected 0 `
            -Message "RunWindow file writes before registration"
        Assert-True `
            -Condition (-not [System.IO.Directory]::Exists(
                $evidenceFullPath
            )) `
            -Message "RunWindow created the attempt directory early."
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "WINDOW-K60-PREDECESSOR-FAILS-BEFORE-HOST-SIDE-EFFECTS" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestFutureCleanupFixture `
            -Temp $temp `
            -Observations $observations
        $registration = Read-Json $fixture.K60.RegistrationPath
        $registration.predecessor.reportSha256 = ("0" * 64)
        Write-TestJson `
            -Path $fixture.K60.RegistrationPath `
            -Value $registration
        $evidenceRelative = $fixture.Future.Contract.evidence.k60RuntimeRoot
        $evidenceFullPath = Join-Path $temp.Root $evidenceRelative
        $beforeEntries = Get-TestRelativeEntrySet -Root $evidenceFullPath
        $context = [pscustomobject]@{
            Contract = $fixture.Future.Contract
            Evidence = [pscustomobject]@{
                RelativePath = $evidenceRelative
                FullPath = $evidenceFullPath
            }
            RepositoryRoot = $temp.Root
            ToolsDigest = $fixture.Future.Contract.tools.digestSha256
            StatePolicy = [pscustomobject]@{
                Mode = "AUDIT_PASS_PENDING_FRESH_PAD_QA"
                QaDispatchable = $true
            }
        }
        $script:k60WindowSideEffects = [pscustomobject]@{
            AdbCalls = 0
            DirectoryCalls = 0
            TextWrites = 0
        }
        function Invoke-G2Adb {
            param(
                [Parameter(Mandatory)][string]$Serial,
                [Parameter(Mandatory)][string[]]$Arguments,
                [switch]$AllowFailure
            )
            $script:k60WindowSideEffects.AdbCalls++
            throw "UNEXPECTED_K60_WINDOW_ADB"
        }
        function New-G2CollectorDirectory {
            param([Parameter(Mandatory)][string]$Path)
            $script:k60WindowSideEffects.DirectoryCalls++
            throw "UNEXPECTED_K60_WINDOW_DIRECTORY"
        }
        function Write-G2Utf8LfText {
            param(
                [Parameter(Mandatory)][string]$Path,
                [Parameter(Mandatory)][string]$Text
            )
            $script:k60WindowSideEffects.TextWrites++
            throw "UNEXPECTED_K60_WINDOW_WRITE"
        }
        $result = Invoke-TestCatching -Action {
            Invoke-G2Window `
                -Context $context `
                -DeviceRole K60 `
                -PlanPath $null
        }
        Assert-True `
            -Condition ([bool]$result.Threw) `
            -Message "RunWindow with a forged predecessor did not fail."
        Assert-True `
            -Condition (
                [string]$result.ErrorMessage -match
                    "K60|PREDECESSOR|SHA|MISMATCH"
            ) `
            -Message (
                "RunWindow K60 predecessor failed for the wrong reason: " +
                [string]$result.ErrorMessage
            )
        Assert-Equal `
            -Actual $script:k60WindowSideEffects.AdbCalls `
            -Expected 0 `
            -Message "K60 RunWindow ADB calls before predecessor"
        Assert-Equal `
            -Actual $script:k60WindowSideEffects.DirectoryCalls `
            -Expected 0 `
            -Message "K60 RunWindow directory calls before predecessor"
        Assert-Equal `
            -Actual $script:k60WindowSideEffects.TextWrites `
            -Expected 0 `
            -Message "K60 RunWindow writes before predecessor"
        $afterEntries = Get-TestRelativeEntrySet -Root $evidenceFullPath
        Assert-Equal `
            -Actual ($afterEntries -join "`n") `
            -Expected ($beforeEntries -join "`n") `
            -Message "K60 RunWindow changed evidence before predecessor gate."
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "FINALIZER-REJECTS-OLD-PAD-EVIDENCE-ROOT" {
    $report = New-FixturePassReport
    $report.evidenceRoot = (
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
    )
    Assert-Throws `
        -MessagePattern "EVIDENCE|SCOPE|PAD|ROOT" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-REJECTS-ACTIVE-NEW-SCOPE-LOCK" {
    $temp = New-TestTempRoot
    try {
        $lockPath = Join-Path $temp.Root "device-lock.json"
        Write-TestJson `
            -Path $lockPath `
            -Value ([pscustomobject]@{
                schema = "aurorama.g2-device-lock.v1"
                deviceRole = "Pad"
                serial = "43af8627"
                attemptScope = $contract.evidence.padRuntimeRoot
                state = "ACTIVE_COUNT_1"
                activeCount = 1
                attemptNumber = 1
                releasedAt = $null
            })
        Assert-Throws `
            -MessagePattern "FINALIZER|LOCK|RELEASED|ACTIVE" `
            -Action {
                Assert-G2FinalizerReleasedLock `
                    -Contract $contract `
                    -DeviceRole Pad `
                    -EvidenceRoot $contract.evidence.padRuntimeRoot `
                    -LockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "FINALIZER-ATTEMPT-BINDING-ACCEPTS-FRESH-SCOPE" {
    $temp = New-TestTempRoot
    try {
        $context = New-TestFinalizerBindingContext `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $binding = Assert-G2FinalizerAttemptBinding `
            -Context $context `
            -Observations $observations
        Assert-Equal `
            -Actual $binding.evidenceRoot `
            -Expected $contract.evidence.padRuntimeRoot `
            -Message "Finalizer binding scope"
        Assert-Equal `
            -Actual $binding.attemptNumber `
            -Expected 1 `
            -Message "Finalizer binding attempt"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "FINALIZER-ATTEMPT-BINDING-REJECTS-SESSION-ATTEMPT-TWO" {
    $temp = New-TestTempRoot
    try {
        $context = New-TestFinalizerBindingContext `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $sessionPath = Join-Path `
            $context.Evidence.FullPath `
            "collector-session.json"
        $session = Read-Json $sessionPath
        $session.attemptNumber = 2
        Write-TestJson -Path $sessionPath -Value $session
        Assert-Throws `
            -MessagePattern "FINALIZER|SESSION|ATTEMPT|BINDING" `
            -Action {
                Assert-G2FinalizerAttemptBinding `
                    -Context $context `
                    -Observations $observations
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "WINDOW-REJECTS-RELEASED-LOCK-BEFORE-COMPLETION" {
    $temp = New-TestTempRoot
    try {
        $authoritativeLockPath = Join-Path `
            $temp.Root `
            "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $authoritativeLockPath,
            "## 当前占用`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Invoke-G2DeviceRegistration `
            -Context $context `
            -DeviceRole Pad `
            -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
            -AuthoritativeLockPath $authoritativeLockPath
        $registration = Read-Json (
            Join-Path $freshRoot "collector-registration.json"
        )
        $lockPath = Join-Path $freshRoot "device-lock.json"
        $released = Read-Json $lockPath
        $released.state = "RELEASED_ACTIVE_0"
        $released.activeCount = 0
        $released.releasedAt = "2026-11-06T12:20:00.000+08:00"
        Write-TestJson -Path $lockPath -Value $released
        Assert-Throws `
            -MessagePattern "ACTIVE|LOCK|WINDOW|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $context `
                    -DeviceRole Pad `
                    -Registration $registration
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "WINDOW-REJECTS-SHORT-DURATION" {
    Assert-Throws `
        -MessagePattern "WINDOW|600" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 1 `
                -WindowSeconds 599 `
                -Extended $false `
                -Retry $false
        }
}

Invoke-Case "WINDOW-REJECTS-ATTEMPT-TWO" {
    Assert-Throws `
        -MessagePattern "ATTEMPT|RETRY" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 2 `
                -WindowSeconds 600 `
                -Extended $false `
                -Retry $false
        }
}

Invoke-Case "WINDOW-REJECTS-EXTENSION" {
    Assert-Throws `
        -MessagePattern "EXTENS|RETRY" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 1 `
                -WindowSeconds 600 `
                -Extended $true `
                -Retry $false
        }
}

Invoke-Case "WINDOW-REJECTS-RETRY-TRUE" {
    Assert-Throws `
        -MessagePattern "RETRY" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 1 `
                -WindowSeconds 600 `
                -Extended $false `
                -Retry $true
        }
}

Invoke-Case "NETSTATS-SUMS-ALL-UID-PARTITIONS" {
    $before = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "netstats-before.txt") `
        -Raw `
        -Encoding utf8
    $after = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "netstats-after.txt") `
        -Raw `
        -Encoding utf8
    $usageBefore = Get-G2NetStatsUidUsage -Text $before -Uid 10315
    $usageAfter = Get-G2NetStatsUidUsage -Text $after -Uid 10315
    Assert-True `
        -Condition ($usageBefore.PartitionCount -ge 3) `
        -Message "Not all UID partitions parsed."
    Assert-Equal `
        -Actual $usageBefore.RxBytes `
        -Expected 7000 `
        -Message "Before RX"
    Assert-Equal `
        -Actual $usageAfter.RxBytes `
        -Expected 7000 `
        -Message "After RX"
    $delta = New-G2NetStatsDelta -Before $usageBefore -After $usageAfter
    Assert-Equal -Actual $delta.WindowBytesDelta -Expected 0 -Message "Delta"
    Assert-Equal `
        -Actual $delta.AllPartitionsAccounted `
        -Expected $true `
        -Message "Partition accounting"
}

Invoke-Case "AUDIOTRACK-LOG-MAPS-GENERATION-TO-VOICE" {
    $lines = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "runtime-log-happy.log") `
        -Encoding utf8
    $events = @(Get-G2AudioTrackEvents -Lines $lines)
    Assert-Equal -Actual $events.Count -Expected 6 -Message "Event count"
    Assert-Equal `
        -Actual @($events | Where-Object voiceId -eq "zf_001").Count `
        -Expected 4 `
        -Message "zf events"
    Assert-Equal `
        -Actual @($events | Where-Object voiceId -eq "zm_010").Count `
        -Expected 2 `
        -Message "zm events"
    Assert-Equal `
        -Actual @($events | Where-Object kind -eq "START").Count `
        -Expected 3 `
        -Message "Start events"
    Assert-Equal `
        -Actual @($events | Where-Object kind -eq "COMPLETION").Count `
        -Expected 3 `
        -Message "Completion events"
}

Invoke-Case "AUDIO-GAP-CALCULATES-COMPLETION-TO-NEXT-START" {
    $lines = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "runtime-log-happy.log") `
        -Encoding utf8
    $events = @(Get-G2AudioTrackEvents -Lines $lines)
    $gap = Get-G2MaximumAudioGapSeconds -Events $events
    Assert-Equal -Actual $gap -Expected 2 -Message "Maximum gap"
}

Invoke-Case "AUDIO-GAP-REJECTS-OVER-FIFTEEN" {
    $events = @($observations.audioTrackEvents | ForEach-Object {
        $_.PSObject.Copy()
    })
    $events[3].timestamp = "2026-11-06T12:00:40.000+08:00"
    $gap = Get-G2MaximumAudioGapSeconds -Events $events
    Assert-True -Condition ($gap -gt 15) -Message "Fixture did not exceed 15s."
}

Invoke-Case "AUDIO-EVENTS-REJECT-START-WITHOUT-SENTENCE-MAP" {
    $lines = @(
        "2026-11-06 12:00:00.000  2000  2100 I KOKORO_RUNTIME: AUDIO_TRACK_STARTED owner=TTS generation=99 samples=48000 durationMillis=1000 longRun=true longRunAudioMillis=0"
    )
    Assert-Throws `
        -MessagePattern "SENTENCE|VOICE|generation" `
        -Action { Get-G2AudioTrackEvents -Lines $lines }
}

Invoke-Case "FINALIZER-MISSING-CORE-FIELD-FAILS-CLOSED" {
    $broken = $observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $broken.PSObject.Properties.Remove("audioTrackEvents")
    Assert-Throws `
        -MessagePattern "audioTrackEvents|MISSING|NOT_RUN" `
        -Action {
            New-G2RuntimeReportFromObservations `
                -Observations $broken `
                -Contract $contract `
                -Authorization $authorization `
                -ContractRoot $contractRoot `
                -FixtureOnly
        }
}

Invoke-Case "FINALIZER-NOT-RUN-CORE-IS-BLOCKED" {
    $notRun = $observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $notRun.audioFocus.status = "NOT_RUN"
    $report = New-G2RuntimeReportFromObservations `
        -Observations $notRun `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    Assert-Equal -Actual $report.verdict -Expected "BLOCKED" -Message "Verdict"
    Assert-True `
        -Condition ($report.productVerdict -cne "PASS") `
        -Message "NOT_RUN was allowed to pass."
}

Invoke-Case "FINALIZER-FIXTURE-CANNOT-PRODUCE-PASS" {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    Assert-Equal -Actual $report.verdict -Expected "BLOCKED" -Message "Verdict"
    $reportJson = $report | ConvertTo-Json -Depth 100
    Assert-True `
        -Condition ($reportJson | Test-Json -SchemaFile $schemaPath) `
        -Message "Generated fixture report did not match schema."
}

Invoke-Case "FINALIZER-FAILS-ON-PASS-GATE-WITH-NOT-RUN" {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    $report.verdict = "PASS"
    $report.productVerdict = "PASS"
    $report.productGates[0].status = "NOT_RUN"
    Assert-Throws `
        -MessagePattern "NOT_RUN|CORE|PASS" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-FAILS-ON-CLEANUP-MISMATCH" {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    $report.cleanupAndRestore.status = "FAIL"
    Assert-Throws `
        -MessagePattern "CLEANUP" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-NOT-ASSESSED-FINDINGS" {
    $report = New-FixturePassReport
    $report.productFindings.P0 = "NOT_ASSESSED"
    Assert-Throws `
        -MessagePattern "FINDINGS|NOT_ASSESSED|P0" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-SHORT-ELAPSED-WINDOW" {
    $report = New-FixturePassReport
    $report.execution.completedAt = "2026-11-06T12:09:59.000+08:00"
    Assert-Throws `
        -MessagePattern "WINDOW|ELAPSED|600" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-UNMATCHED-AUDIO-COMPLETION" {
    $report = New-FixturePassReport
    $report.audioTrackEvents[-1].trackSessionId = "TTS:unmatched"
    Assert-Throws `
        -MessagePattern "AUDIO|TRACK|COMPLETION|PAIR" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-K60-WITHOUT-PREDECESSOR" {
    $report = New-FixturePassReport
    $report.ownerRole = "G2_K60_QA_OWNER_2026_11_06"
    $report.evidenceRoot = $contract.evidence.k60RuntimeRoot
    $report.device.order = "K60"
    $report.device.role = "G2_K60_QA_OWNER_2026_11_06"
    $report.device.serial = "8e875894"
    $report.predecessor = $null
    Assert-Throws `
        -MessagePattern "K60|PREDECESSOR" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-MERGES-CLEANUP-VERIFIER-RESULT" {
    $observed = $observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $observed.cleanupAndRestore.status = "BLOCKED"
    $observed.cleanupAndRestore.packagesAbsent = $false
    $merged = Merge-G2CleanupIntoObservations `
        -Observations $observed `
        -Cleanup $cleanupFixture
    Assert-Equal `
        -Actual $merged.cleanupAndRestore.status `
        -Expected "PASS" `
        -Message "Merged cleanup status"
    Assert-Equal `
        -Actual $merged.cleanupAndRestore.deviceLockState `
        -Expected "RELEASED_ACTIVE_0" `
        -Message "Merged lock state"
}

Invoke-Case "CLEANUP-COMPLETE-FIXTURE-PASSES-STRUCTURE" {
    $result = New-G2CleanupResult -InputObject $cleanupFixture
    Assert-Equal -Actual $result.status -Expected "PASS" -Message "Status"
    Assert-Equal `
        -Actual @($result.failures).Count `
        -Expected 0 `
        -Message "Failures"
}

Invoke-Case "CLEANUP-REJECTS-EACH-RESIDUAL-CLASS" {
    $required = @(
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
    foreach ($propertyName in $required) {
        $broken = $cleanupFixture | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $broken.$propertyName = $false
        $result = New-G2CleanupResult -InputObject $broken
        Assert-Equal `
            -Actual $result.status `
            -Expected "FAIL" `
            -Message "Residual $propertyName"
    }
}

Invoke-Case "CLEANUP-REJECTS-HOST-LOG-ORPHAN" {
    $broken = $cleanupFixture | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $broken.hostLogcatOrphans = 1
    $result = New-G2CleanupResult -InputObject $broken
    Assert-Equal -Actual $result.status -Expected "FAIL" -Message "Status"
}

Invoke-Case "CLEANUP-REJECTS-LOCK-NOT-RELEASED" {
    $broken = $cleanupFixture | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $broken.deviceLockState = "ACTIVE_COUNT_1"
    $broken.deviceLockActiveCount = 1
    $result = New-G2CleanupResult -InputObject $broken
    Assert-Equal -Actual $result.status -Expected "FAIL" -Message "Status"
}

Invoke-Case "CLEANUP-LS-NOT-FOUND-IS-ABSENT" {
    $path = "/sdcard/Android/data/$mainPackage"
    Assert-True `
        -Condition (Test-G2CleanupPathAbsent `
            -ExitCode 1 `
            -Output "ls: ${path}: No such file or directory" `
            -Path $path) `
        -Message "ls not-found text was treated as a path."
}

Invoke-Case "CLEANUP-LS-CURRENT-PATH-IS-RESIDUAL" {
    $path = "/sdcard/Android/data/$mainPackage"
    Assert-True `
        -Condition (-not (Test-G2CleanupPathAbsent `
            -ExitCode 0 `
            -Output $path `
            -Path $path)) `
        -Message "Current filesystem path was treated as absent."
}

Invoke-Case "CLEANUP-AUDIO-FOCUS-IGNORES-HISTORY" {
    $dump = @"
Audio Focus stack entries (last is top of stack):

Notified focus events:
  event:GAIN source=historic clientId=$mainPackage
"@
    Assert-True `
        -Condition (-not (Test-G2CurrentAudioFocusPresent `
            -Dump $dump `
            -PackageName $mainPackage)) `
        -Message "Historical audio focus event was counted as current."
}

Invoke-Case "CLEANUP-AUDIO-FOCUS-DETECTS-CURRENT" {
    $dump = @"
Audio Focus stack entries (last is top of stack):
  source:binder clientId=$mainPackage gainRequest:3

Notified focus events:
  event:GAIN source=current clientId=$mainPackage
"@
    Assert-True `
        -Condition (Test-G2CurrentAudioFocusPresent `
            -Dump $dump `
            -PackageName $mainPackage) `
        -Message "Current audio focus owner was not detected."
}

Invoke-Case "CLEANUP-NO-SESSION-IGNORES-UNRELATED-LOGCAT" {
    $processes = @(
        [pscustomobject]@{
            ProcessId = 4321
            CommandLine = (
                "adb.exe -s other-device logcat -v threadtime,year"
            )
        }
    )
    $orphans = Get-G2HostLogcatOrphanCount `
        -Session $null `
        -Processes $processes `
        -Serial "43af8627"
    Assert-Equal `
        -Actual $orphans `
        -Expected 0 `
        -Message "Unrelated logcat process was counted as this session."
}

Invoke-Case "CLEANUP-LIVE-SESSION-LOGCAT-IS-ORPHAN" {
    $processes = @(
        [pscustomobject]@{
            ProcessId = 4321
            CommandLine = (
                "adb.exe -s 43af8627 logcat -v threadtime,year"
            )
        }
    )
    $session = [pscustomobject]@{
        hostLogcatProcessId = 4321
        hostLogcatProcessStopped = $false
    }
    $orphans = Get-G2HostLogcatOrphanCount `
        -Session $session `
        -Processes $processes `
        -Serial "43af8627"
    Assert-Equal `
        -Actual $orphans `
        -Expected 1 `
        -Message "Live session logcat process was not detected."
}

Invoke-Case "CLEANUP-SUCCESS-RELEASES-AND-REREADS-LOCK" {
    $temp = New-TestTempRoot
    $lockPath = Join-Path $temp.Root "device-lock.json"
    try {
        Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $contract.evidence.padRuntimeRoot
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            registeredAt = "2026-11-06T12:00:00.000+08:00"
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = ("a" * 64)
            releasedAt = $null
        })
        $occupancy = Complete-G2CleanupDeviceLock `
            -Path $lockPath `
            -AllOtherChecksPassed $true `
            -DeviceRole Pad `
            -Serial "43af8627" `
            -AttemptScope $contract.evidence.padRuntimeRoot
        Assert-Equal `
            -Actual $occupancy.state `
            -Expected "RELEASED_ACTIVE_0" `
            -Message "Returned lock state"
        Assert-Equal `
            -Actual $occupancy.activeCount `
            -Expected 0 `
            -Message "Returned active count"
        $external = Read-Json $lockPath
        Assert-Equal `
            -Actual $external.state `
            -Expected "RELEASED_ACTIVE_0" `
            -Message "External lock state"
        Assert-Equal `
            -Actual $external.activeCount `
            -Expected 0 `
            -Message "External active count"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CLEANUP-TRUE-ACTIVE-LOCK-STILL-FAILS" {
    $temp = New-TestTempRoot
    $lockPath = Join-Path $temp.Root "device-lock.json"
    try {
        Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $contract.evidence.padRuntimeRoot
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            registeredAt = "2026-11-06T12:00:00.000+08:00"
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = ("a" * 64)
            releasedAt = $null
        })
        $occupancy = Complete-G2CleanupDeviceLock `
            -Path $lockPath `
            -AllOtherChecksPassed $false `
            -DeviceRole Pad `
            -Serial "43af8627" `
            -AttemptScope $contract.evidence.padRuntimeRoot
        $observed = $cleanupFixture |
            ConvertTo-Json -Depth 20 |
            ConvertFrom-Json
        $observed.deviceLockState = $occupancy.state
        $observed.deviceLockActiveCount = $occupancy.activeCount
        $result = New-G2CleanupResult -InputObject $observed
        Assert-Equal `
            -Actual $result.status `
            -Expected "FAIL" `
            -Message "True ACTIVE lock"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CLEANUP-FILTERS-NONCANDIDATE-DUMPS" {
    $dump = @"
NotificationRecord(0x1: pkg=dev.aurorama.kokoro.harness text=candidate-state)
NotificationRecord(0x2: pkg=other.application text=unrelated-sensitive-marker)
MediaSessionRecord package=dev.aurorama.kokoro.harness ownerPid=2000
MediaSessionRecord package=other.application ownerPid=4000
AudioFocus owner=dev.aurorama.kokoro.harness state=ACTIVE
AudioFocus owner=other.application state=ACTIVE
"@
    $filtered = Select-G2CleanupCandidateText `
        -Text $dump `
        -Packages @(
            "dev.aurorama.kokoro.harness",
            "dev.aurorama.kokoro.focuspeer"
        )
    Assert-True `
        -Condition $filtered.Contains(
            "dev.aurorama.kokoro.harness",
            [System.StringComparison]::Ordinal
        ) `
        -Message "Candidate line was removed."
    Assert-True `
        -Condition (-not $filtered.Contains(
            "unrelated-sensitive-marker",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Unrelated dump content was retained."
    Assert-True `
        -Condition (-not $filtered.Contains(
            "other.application",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Unrelated package line was retained."
}

Invoke-Case "MEDIA-SESSION-IGNORES-LAST-PLAYED-HISTORY" {
    $dump = @"
Media Sessions
Sessions Stack - have 0 sessions:

Audio playback (lastly played comes first)
  dev.aurorama.kokoro.harness
"@
    Assert-True `
        -Condition (Test-G2ActiveMediaSessionAbsent -Dump $dump) `
        -Message "Last-played history was counted as active."
}

Invoke-Case "MEDIA-SESSION-DETECTS-ACTIVE-SESSION" {
    $dump = @"
Media Sessions
Sessions Stack - have 1 sessions:
  MediaSessionRecord
    ownerPid=2000 ownerUid=10315 package=dev.aurorama.kokoro.harness
"@
    Assert-True `
        -Condition (-not (Test-G2ActiveMediaSessionAbsent -Dump $dump)) `
        -Message "Active session was not detected."
}

Restore-TestSyntheticAuditProvenance `
    -Provenance $script:SyntheticAuditProvenance `
    -Temp $script:SyntheticAuditTemp

$finalSettlementBaseline = Get-G2SettlementAuditBaseline
Assert-Equal `
    -Actual $finalSettlementBaseline.testsPassedExact `
    -Expected $script:passed `
    -Message "Final executed tool test count"
Assert-Equal `
    -Actual $finalSettlementBaseline.dynamicNegativeExact `
    -Expected $script:negativeChecks `
    -Message "Final executed dynamic negative count"

Write-Output (
    "SUMMARY PASS={0} FAIL={1} NEGATIVE_CASES={2}" -f
    $script:passed,
    $script:failed,
    $script:negativeChecks
)

if ($script:failed -gt 0) {
    Write-Output ("FAILURES=" + ($script:failures -join " | "))
    exit 1
}

Write-Output "PASS G2_RUNTIME_TOOLS"
exit 0
