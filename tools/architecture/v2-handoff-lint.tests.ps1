[CmdletBinding()]
param(
    [string]$Filter = '*'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$linterPath = Join-Path $PSScriptRoot 'v2-handoff-lint.ps1'
$pwshPath = (Get-Process -Id $PID).Path
$gitCommand = Get-Command git -CommandType Application | Select-Object -First 1
if ($null -eq $gitCommand) {
    throw 'git executable not found'
}
$gitPath = $gitCommand.Source
$script:passed = 0
$script:failed = 0
$currentHandoffHeading = -join ([int[]]@(
    0x5F53
    0x524D
    0x4EA4
    0x63A5
    0x72B6
    0x6001
) | ForEach-Object { [char]$_ })

function Write-Utf8File {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $normalized = $Text.Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText(
        $Path,
        $normalized,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Invoke-Git {
    param(
        [Parameter(Mandatory)][string]$FixtureRoot,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $gitPath -C $FixtureRoot @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit $exitCode`: $($output -join "`n")"
    }
    return @($output | ForEach-Object { [string]$_ })
}

function Invoke-Linter {
    param(
        [Parameter(Mandatory)][ValidateSet('Handoff', 'QaReady')][string]$Mode,
        [Parameter(Mandatory)][string]$FixtureRoot,
        [Parameter(Mandatory)][AllowEmptyString()][string]$ExpectedHead,
        [Parameter(Mandatory)][string]$TasksPath,
        [AllowNull()][string]$MemoryIndex
    )

    $arguments = @(
        '-NoProfile'
        '-ExecutionPolicy'
        'Bypass'
        '-File'
        $linterPath
        '-Mode'
        $Mode
        '-RepoRoot'
        $FixtureRoot
        '-TasksPath'
        $TasksPath
    )
    if ($Mode -eq 'Handoff') {
        $arguments += @('-ExpectedHead', $ExpectedHead)
    }
    if (-not [string]::IsNullOrWhiteSpace($MemoryIndex)) {
        $arguments += @('-MemoryIndex', $MemoryIndex)
    }

    $output = & $pwshPath @arguments 2>&1
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { [string]$_ })
        Raw = ($output -join "`n")
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-ExitCode {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][int]$Expected
    )

    Assert-True -Condition ($Result.ExitCode -eq $Expected) -Message (
        "Expected exit $Expected, got $($Result.ExitCode). $($Result.Raw)"
    )
}

function Assert-ContainsToken {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][string]$Token
    )

    Assert-True -Condition ($Result.Raw.Contains($Token, [System.StringComparison]::Ordinal)) -Message (
        "Missing token '$Token'. $($Result.Raw)"
    )
}

function Invoke-FixtureTest {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Body
    )

    if ($Name -notlike $Filter) {
        return
    }

    try {
        & $Body
        $script:passed++
        Write-Output "PASS $Name"
    }
    catch {
        $script:failed++
        Write-Output "FAIL $Name :: $($_.Exception.Message)"
    }
}

function Get-TasksText {
    param(
        [Parameter(Mandatory)][string]$FixtureRoot,
        [Parameter(Mandatory)][string]$BaseHead,
        [Parameter(Mandatory)][string]$FailedHead,
        [Parameter(Mandatory)][string]$EvidenceRoot,
        [string]$ActiveExtra = '',
        [string]$ObjectExtra = '',
        [string]$QaOverride = '',
        [string]$TestCommand = 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1',
        [string]$SelectorStatus = 'NOT_APPLICABLE',
        [string]$SelectorEvidence = '',
        [string]$EquivalentEvidence = '',
        [string]$P1Green = 'qa-green.txt',
        [string[]]$RetiredRoles = @('AURORAMA_V2_W1B_1B_OWNER_2026_11_07'),
        [string[]]$AllowedPaths = @(
            'AGENTS.md'
            'docs/LESSONS.md'
            'docs/PROJECT_RULES.md'
            'docs/TASKS.md'
            'tools/architecture/v2-handoff-lint.ps1'
            'tools/architecture/v2-handoff-lint.tests.ps1'
        )
    )

    $handoffEvidence = Join-Path $EvidenceRoot 'handoff.txt'
    $redEvidence = Join-Path $EvidenceRoot 'qa-red.txt'
    $greenEvidence = Join-Path $EvidenceRoot $P1Green

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# Fixture tasks')
    $lines.Add('')
    $lines.Add('<!-- V2_HANDOFF_ACTIVE_BEGIN -->')
    $lines.Add('## ACTIVE fixture task')
    $lines.Add('')
    $lines.Add('- TASK_ID=FIXTURE-001')
    $lines.Add('- WORKTREE_POLICY=EXACT_ABSOLUTE')
    $lines.Add("- CANONICAL_WORKTREE=$FixtureRoot")
    $lines.Add('- CANONICAL_BRANCH=codex/v2-executable-workflow-hardening')
    $lines.Add('- EXPECTED_HEAD_SOURCE=MANAGER_EXTERNAL_PIN')
    $lines.Add('- READ_ORDER=AGENTS.md > docs/PROJECT_RULES.md > docs/TASKS.md')
    $lines.Add('- NEXT_ROLE=FRESH_INDEPENDENT_QA_OWNER')
    foreach ($retiredRole in $RetiredRoles) {
        if (-not [string]::IsNullOrWhiteSpace($retiredRole)) {
            $lines.Add("- RETIRED_ROLE=$retiredRole")
        }
    }
    $lines.Add('- NEXT_PATH=tools/architecture/v2-handoff-lint.tests.ps1')
    $lines.Add('- STOP_CONDITION=Any contract drift stops dispatch.')
    $lines.Add('- SCOPE=Fixture validation only.')
    $lines.Add("- EVIDENCE=$handoffEvidence")
    $lines.Add('- P1=P1-001;P1-002')
    $lines.Add('- P2=P2-001')
    if (-not [string]::IsNullOrWhiteSpace($ActiveExtra)) {
        foreach ($line in ($ActiveExtra -split "`n")) {
            $lines.Add($line)
        }
    }
    $lines.Add('<!-- V2_HANDOFF_ACTIVE_END -->')
    $lines.Add('')
    $lines.Add('<!-- V2_HANDOFF_OBJECT_BEGIN -->')
    $lines.Add("- BASE_HEAD=$BaseHead")
    $lines.Add("- REQUIRED_ANCESTOR=$BaseHead")
    $lines.Add("- FAILED_CANDIDATE=$FailedHead")
    $lines.Add('- MAX_CHANGED_PATHS=6')
    foreach ($allowedPath in $AllowedPaths) {
        $lines.Add("- ALLOWED_PATH=$allowedPath")
    }
    if (-not [string]::IsNullOrWhiteSpace($ObjectExtra)) {
        foreach ($line in ($ObjectExtra -split "`n")) {
            $lines.Add($line)
        }
    }
    $lines.Add('<!-- V2_HANDOFF_OBJECT_END -->')
    $lines.Add('')
    $lines.Add('<!-- V2_QA_READY_BEGIN -->')
    $lines.Add('- QA_READY=YES')
    $lines.Add('- UNCOVERED=No independent QA has run yet.')
    $lines.Add('- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER')
    $lines.Add('- MANUAL_LIMITATION=Device facts require the fresh QA owner.')
    $lines.Add('')
    $lines.Add('<!-- V2_QA_INVARIANT_MATRIX_BEGIN -->')
    $lines.Add('| Invariant ID | Entry | Action | Observable result | Automation/fixture evidence | Device evidence |')
    $lines.Add('|---|---|---|---|---|---|')
    $lines.Add('| INV-SOURCE-01 | docs/TASKS.md | Run lint | PASS token appears | tests fixture | N/A:fixture only |')
    $lines.Add('<!-- V2_QA_INVARIANT_MATRIX_END -->')
    $lines.Add('')
    $lines.Add('<!-- V2_QA_TEST_MATRIX_BEGIN -->')
    $lines.Add('| Test ID | Target | Command | Selector status | Equivalent evidence |')
    $lines.Add('|---|---|---|---|---|')
    $selectorCell = if ([string]::IsNullOrWhiteSpace($SelectorEvidence)) {
        $SelectorStatus
    }
    else {
        "$SelectorStatus;TEST_SELECTOR_EVIDENCE=$SelectorEvidence"
    }
    $lines.Add("| TEST-001 | tools/architecture/v2-handoff-lint.tests.ps1 | $TestCommand | $selectorCell | $EquivalentEvidence |")
    $lines.Add('<!-- V2_QA_TEST_MATRIX_END -->')
    $lines.Add('')
    $lines.Add('<!-- V2_QA_P1_MATRIX_BEGIN -->')
    $lines.Add('| P1 ID | RED | GREEN | Evidence |')
    $lines.Add('|---|---|---|---|')
    $lines.Add("| P1-001 | Missing linter fails | Positive fixture passes | $redEvidence;$greenEvidence |")
    $lines.Add("| P1-002 | Invalid selector fails | Selector gate passes | $redEvidence;$greenEvidence |")
    $lines.Add('<!-- V2_QA_P1_MATRIX_END -->')
    if (-not [string]::IsNullOrWhiteSpace($QaOverride)) {
        $lines.Add($QaOverride)
    }
    $lines.Add('<!-- V2_QA_READY_END -->')
    return ($lines -join "`n")
}

function Get-StableDocText {
    param([Parameter(Mandatory)][string]$RelativePath)

    return @"
# Fixture $RelativePath

<!-- V2_STABLE_DOC_STATE_POINTER_BEGIN -->
- DYNAMIC_STATE_SOURCE=docs/TASKS.md
- CURRENT_STATE_IN_THIS_FILE=NONE
<!-- V2_STABLE_DOC_STATE_POINTER_END -->

This file retains stable decisions and historical facts only. Current worktree, branch, gates,
P1/P2, next role/path and stop condition are owned by docs/TASKS.md.

## HISTORY

- Stable fixture fact.
"@
}

function New-Fixture {
    param(
        [ValidateSet('Legacy', 'Stable')]
        [string]$PathSet = 'Legacy'
    )

    $fixtureParent = Join-Path ([System.IO.Path]::GetTempPath()) ("v2-handoff-lint-" + [guid]::NewGuid().ToString('N'))
    $fixtureRoot = Join-Path $fixtureParent 'repo'
    $evidenceRoot = Join-Path $fixtureParent 'evidence'
    $memoryRoot = Join-Path $fixtureParent 'memory'
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $memoryRoot -Force | Out-Null

    Write-Utf8File -Path (Join-Path $evidenceRoot 'handoff.txt') -Text "HANDOFF evidence`n"
    Write-Utf8File -Path (Join-Path $evidenceRoot 'qa-red.txt') -Text "RED evidence`n"
    Write-Utf8File -Path (Join-Path $evidenceRoot 'qa-green.txt') -Text "GREEN evidence`n"
    Write-Utf8File -Path (Join-Path $evidenceRoot 'qa-selector.txt') -Text "SELECTOR evidence`n"
    Write-Utf8File -Path (Join-Path $evidenceRoot 'qa-equivalent.txt') -Text "EQUIVALENT evidence`n"
    Write-Utf8File -Path (Join-Path $memoryRoot 'project-note.md') -Text "Fixture project note.`n"
    $safeMemory = Join-Path $memoryRoot 'MEMORY.md'
    Write-Utf8File -Path $safeMemory -Text @'
# Memory Index

## project
- [Fixture project](project-note.md) - fixture state
'@

    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('init', '-q', '-b', 'codex/v2-executable-workflow-hardening') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'user.email', 'fixture@example.invalid') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'user.name', 'Fixture Owner') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'core.autocrlf', 'false') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'core.eol', 'lf') | Out-Null

    $stableDocPaths = @(
        'docs/PROJECT_PLAN.md'
        'docs/V2_PLAN.md'
        'docs/ARCHITECTURE.md'
    )
    foreach ($stableDocPath in $stableDocPaths) {
        Write-Utf8File -Path (Join-Path $fixtureRoot $stableDocPath) `
            -Text (Get-StableDocText -RelativePath $stableDocPath)
    }
    Write-Utf8File -Path (Join-Path $fixtureRoot 'seed.txt') -Text "seed`n"
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('commit', '-q', '-m', 'base') | Out-Null
    $baseHead = [string](Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $baseHead = $baseHead.Trim()

    $legacyAllowedPaths = @(
        'AGENTS.md'
        'docs/LESSONS.md'
        'docs/PROJECT_RULES.md'
        'docs/TASKS.md'
        'tools/architecture/v2-handoff-lint.ps1'
        'tools/architecture/v2-handoff-lint.tests.ps1'
    )
    $stableAllowedPaths = @(
        'docs/PROJECT_PLAN.md'
        'docs/V2_PLAN.md'
        'docs/ARCHITECTURE.md'
        'docs/TASKS.md'
        'tools/architecture/v2-handoff-lint.ps1'
        'tools/architecture/v2-handoff-lint.tests.ps1'
    )
    $allowedFixturePaths = if ($PathSet -eq 'Stable') {
        $stableAllowedPaths
    }
    else {
        $legacyAllowedPaths
    }
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('checkout', '-q', '-b', 'failed-candidate') | Out-Null
    foreach ($relativePath in $allowedFixturePaths) {
        Write-Utf8File -Path (Join-Path $fixtureRoot $relativePath) -Text "failed candidate $relativePath`n"
    }
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('commit', '-q', '-m', 'failed candidate') | Out-Null
    $failedHead = [string](Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $failedHead = $failedHead.Trim()
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @(
        'checkout', '-q', '-B', 'codex/v2-executable-workflow-hardening', $failedHead
    ) | Out-Null

    $tasksPath = 'docs/TASKS.md'
    $tasksText = Get-TasksText -FixtureRoot $fixtureRoot -BaseHead $baseHead -FailedHead $failedHead `
        -EvidenceRoot $evidenceRoot -AllowedPaths $allowedFixturePaths
    Write-Utf8File -Path (Join-Path $fixtureRoot 'docs/TASKS.md') -Text $tasksText
    foreach ($relativePath in $allowedFixturePaths) {
        if ($relativePath -eq 'docs/TASKS.md') {
            continue
        }
        Write-Utf8File -Path (Join-Path $fixtureRoot $relativePath) -Text "fixture $relativePath`n"
    }
    foreach ($stableDocPath in $stableDocPaths) {
        Write-Utf8File -Path (Join-Path $fixtureRoot $stableDocPath) `
            -Text (Get-StableDocText -RelativePath $stableDocPath)
    }
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('commit', '-q', '-m', 'candidate') | Out-Null
    $candidateHead = [string](Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $candidateHead = $candidateHead.Trim()

    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('checkout', '-q', '-B', 'non-ancestor', $baseHead) | Out-Null
    Write-Utf8File -Path (Join-Path $fixtureRoot 'sibling.txt') -Text "sibling candidate`n"
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('commit', '-q', '-m', 'non-ancestor sibling') | Out-Null
    $siblingHead = [string](Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $siblingHead = $siblingHead.Trim()
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('checkout', '-q', 'codex/v2-executable-workflow-hardening') | Out-Null

    return [pscustomobject]@{
        Parent = $fixtureParent
        Root = $fixtureRoot
        BaseHead = $baseHead
        FailedHead = $failedHead
        SiblingHead = $siblingHead
        CandidateHead = $candidateHead
        TasksPath = $tasksPath
        TasksFile = (Join-Path $fixtureRoot $tasksPath)
        TasksText = $tasksText
        AllowedPaths = $allowedFixturePaths
        EvidenceRoot = $evidenceRoot
        SafeMemoryIndex = $safeMemory
        MemoryRoot = $memoryRoot
    }
}

function Update-TasksAndCommit {
    param(
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][string]$Text
    )

    Write-Utf8File -Path $Fixture.TasksFile -Text $Text
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('add', '--', $Fixture.TasksPath) | Out-Null
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('commit', '-q', '-m', 'mutation') | Out-Null
    return [string](Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
}

function Commit-AllAndGetHead {
    param([Parameter(Mandatory)]$Fixture)
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('commit', '-q', '-m', 'mutation') | Out-Null
    return [string](Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
}

function Update-RepoFileAndCommit {
    param(
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text
    )

    Write-Utf8File -Path (Join-Path $Fixture.Root $RelativePath) -Text $Text
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('add', '--', $RelativePath) | Out-Null
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('commit', '-q', '-m', 'stable doc mutation') | Out-Null
    return [string](Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
}

function Remove-Fixture {
    param([Parameter(Mandatory)]$Fixture)

    $resolved = [System.IO.Path]::GetFullPath($Fixture.Parent)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $leaf = Split-Path -Leaf $resolved
    if (-not $resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not $leaf.StartsWith('v2-handoff-lint-', [System.StringComparison]::Ordinal)) {
        throw "Refusing to remove unexpected fixture path: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Remove-ReparsePointFixture {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) {
        throw "Refusing to remove a non-reparse fixture path: $Path"
    }
    Remove-Item -LiteralPath $Path -Force
}

Invoke-FixtureTest -Name 'positive fixture passes Handoff and QaReady' -Body {
    $fixture = New-Fixture
    try {
        $handoff = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.CandidateHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $handoff -Expected 0
        Assert-ContainsToken -Result $handoff -Token 'HANDOFF_LINT=PASS'

        $qa = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $qa -Expected 0
        Assert-ContainsToken -Result $qa -Token 'QA_READY_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc PROJECT_PLAN missing pointer fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text "# Missing pointer`n"
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_POINTER_MISSING:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc V2_PLAN missing pointer fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/V2_PLAN.md' -Text "# Missing pointer`n"
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_POINTER_MISSING:docs/V2_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc ARCHITECTURE missing pointer fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/ARCHITECTURE.md' -Text "# Missing pointer`n"
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_POINTER_MISSING:docs/ARCHITECTURE.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc duplicate pointer fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $valid = Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md'
        $mutated = $valid + "`n<!-- V2_STABLE_DOC_STATE_POINTER_BEGIN -->`n- duplicate`n<!-- V2_STABLE_DOC_STATE_POINTER_END -->`n"
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_POINTER_MULTIPLE:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc empty pointer fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = @"
# Empty pointer

<!-- V2_STABLE_DOC_STATE_POINTER_BEGIN -->

<!-- V2_STABLE_DOC_STATE_POINTER_END -->
"@
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_POINTER_EMPTY:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc pointer missing required token fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md').Replace(
            '- CURRENT_STATE_IN_THIS_FILE=NONE',
            '- CURRENT_STATE_IN_THIS_FILE=PRESENT'
        )
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_POINTER_TOKEN_INVALID:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc pointer SHA fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md').Replace(
            '- CURRENT_STATE_IN_THIS_FILE=NONE',
            "- CURRENT_STATE_IN_THIS_FILE=NONE`n- CODE_INTEGRATION_HEAD=$($fixture.BaseHead)"
        )
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_CURRENT_STATE_TOKEN:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc pointer NEXT_ROLE fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md').Replace(
            '- CURRENT_STATE_IN_THIS_FILE=NONE',
            "- CURRENT_STATE_IN_THIS_FILE=NONE`n- NEXT_ROLE=STALE_OWNER"
        )
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:STABLE_DOC_CURRENT_STATE_TOKEN:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc handoff ACTIVE marker fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') +
            "`n<!-- V2_HANDOFF_ACTIVE_BEGIN -->`n"
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ACTIVE_MARKER_OUTSIDE_TASKS:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc unlabeled ACTIVE heading fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md').Replace(
            '## HISTORY',
            '## ACTIVE - stale current snapshot'
        )
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ACTIVE_HEADING_OUTSIDE_TASKS:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc plain current handoff block fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $block = @"

## $currentHandoffHeading
- NEXT_ROLE=STALE_CURRENT_OWNER
- NEXT_PATH=docs/TASKS.md
- CODE_INTEGRATION_HEAD=$($fixture.BaseHead)
- CONTROL_PLANE_HEAD_INPUT=$($fixture.BaseHead)
- CONTEXT_WATERMARK=100000/1048576
"@
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') + $block
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result `
            -Token 'FAIL:STABLE_DOC_CURRENT_STATE_OUTSIDE_HISTORY:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc CURRENT_STATE heading token fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') +
            "`n## CURRENT_STATE`n- NEXT_PATH=docs/TASKS.md`n"
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result `
            -Token 'FAIL:STABLE_DOC_CURRENT_STATE_OUTSIDE_HISTORY:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc ACTIVE heading token fails Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') +
            "`n## ACTIVE - stale snapshot`n- NEXT_ROLE=STALE_ACTIVE_OWNER`n"
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ACTIVE_HEADING_OUTSIDE_TASKS:docs/PROJECT_PLAN.md'
        Assert-ContainsToken -Result $result `
            -Token 'FAIL:STABLE_DOC_CURRENT_STATE_OUTSIDE_HISTORY:docs/PROJECT_PLAN.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc all controlled tokens in ordinary section fail Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        foreach ($token in @(
            'NEXT_ROLE'
            'NEXT_PATH'
            'CODE_INTEGRATION_HEAD'
            'CONTROL_PLANE_HEAD_INPUT'
            'CONTEXT_WATERMARK'
        )) {
            $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') +
                "`n## Operational notes`n- $token=STALE_VALUE`n"
            $newHead = Update-RepoFileAndCommit -Fixture $fixture `
                -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
            $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
                -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
            Assert-ExitCode -Result $result -Expected 2
            Assert-ContainsToken -Result $result `
                -Token 'FAIL:STABLE_DOC_CURRENT_STATE_OUTSIDE_HISTORY:docs/PROJECT_PLAN.md'
            Assert-ContainsToken -Result $result -Token "token=$token"
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc pointer controlled tokens all fail Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        foreach ($token in @(
            'NEXT_ROLE'
            'NEXT_PATH'
            'CODE_INTEGRATION_HEAD'
            'CONTROL_PLANE_HEAD_INPUT'
            'CONTEXT_WATERMARK'
        )) {
            $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md').Replace(
                '- CURRENT_STATE_IN_THIS_FILE=NONE',
                "- CURRENT_STATE_IN_THIS_FILE=NONE`n- $token=STALE_VALUE"
            )
            $newHead = Update-RepoFileAndCommit -Fixture $fixture `
                -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
            $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
                -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
            Assert-ExitCode -Result $result -Expected 2
            Assert-ContainsToken -Result $result `
                -Token 'FAIL:STABLE_DOC_CURRENT_STATE_TOKEN:docs/PROJECT_PLAN.md'
            Assert-ContainsToken -Result $result -Token $token
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc HISTORY fixed tokens and SHA pass Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $block = @"

## HISTORY - 2026-10-01 fixed snapshot
- NEXT_ROLE=HISTORICAL_OWNER
- NEXT_PATH=docs/TASKS.md
- CODE_INTEGRATION_HEAD=$($fixture.BaseHead)
- CONTROL_PLANE_HEAD_INPUT=$($fixture.BaseHead)
- CONTEXT_WATERMARK=100000/1048576
"@
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') + $block
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'HANDOFF_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc explicit audit block tokens pass Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $block = @'

## Audit examples

```text AUDIT_STABLE_DOC_STATE
- NEXT_ROLE=AUDIT_OWNER
- NEXT_PATH=docs/TASKS.md
- CODE_INTEGRATION_HEAD=1111111111111111111111111111111111111111
- CONTROL_PLANE_HEAD_INPUT=2222222222222222222222222222222222222222
- CONTEXT_WATERMARK=100000/1048576
```
'@
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') + $block
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'HANDOFF_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'stable doc ordinary fixed SHA passes Handoff' -Body {
    $fixture = New-Fixture -PathSet Stable
    try {
        $mutated = (Get-StableDocText -RelativePath 'docs/PROJECT_PLAN.md') +
            "`n## Stable evidence`n- Fixed audit object $($fixture.BaseHead)`n"
        $newHead = Update-RepoFileAndCommit -Fixture $fixture `
            -RelativePath 'docs/PROJECT_PLAN.md' -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'HANDOFF_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'dirty worktree fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        Write-Utf8File -Path (Join-Path $fixture.Root 'AGENTS.md') -Text "dirty`n"
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.CandidateHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:WORKTREE_DIRTY:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'HEAD pin drift fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.FailedHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:HEAD_PIN_MISMATCH:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'branch drift fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        Invoke-Git -FixtureRoot $fixture.Root -Arguments @('checkout', '-q', '-b', 'wrong-branch') | Out-Null
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.CandidateHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:BRANCH_MISMATCH:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'multiple ACTIVE blocks fail Handoff' -Body {
    $fixture = New-Fixture
    try {
        $extra = @'

<!-- V2_HANDOFF_ACTIVE_BEGIN -->
## ACTIVE duplicate
<!-- V2_HANDOFF_ACTIVE_END -->
'@
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text ($fixture.TasksText + $extra)
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ACTIVE_MARKER_MULTIPLE:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'old current-state token in ACTIVE fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- P2=P2-001',
            "- P2=P2-001`n- CURRENT_HEAD=$($fixture.CandidateHead)"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ACTIVE_HEAD_BINDING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'old role token in ACTIVE fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- P2=P2-001',
            "- P2=P2-001`n- OLD_NEXT_ROLE=AURORAMA_V2_W1B_1B_OWNER"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ACTIVE_LEGACY_STATE:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'out-of-scope path fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        Write-Utf8File -Path (Join-Path $fixture.Root 'unexpected.txt') -Text "unexpected`n"
        $newHead = Commit-AllAndGetHead -Fixture $fixture
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:PATH_OUTSIDE_SCOPE:unexpected.txt'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'scope rename from outside source fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        Invoke-Git -FixtureRoot $fixture.Root -Arguments @(
            'mv', '-f', 'seed.txt', 'tools/architecture/v2-handoff-lint.ps1'
        ) | Out-Null
        $newHead = Commit-AllAndGetHead -Fixture $fixture
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:PATH_OUTSIDE_SCOPE:seed.txt'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'scope rename between allowed paths passes Handoff' -Body {
    $fixture = New-Fixture
    try {
        Invoke-Git -FixtureRoot $fixture.Root -Arguments @(
            'mv', '-f', 'docs/LESSONS.md', 'AGENTS.md'
        ) | Out-Null
        $newHead = Commit-AllAndGetHead -Fixture $fixture
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'HANDOFF_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'seventh changed path fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        Write-Utf8File -Path (Join-Path $fixture.Root 'docs/SEVENTH.md') -Text "seventh`n"
        $newHead = Commit-AllAndGetHead -Fixture $fixture
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:CHANGED_PATH_LIMIT:7'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'base not ancestor of candidate fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            "- BASE_HEAD=$($fixture.BaseHead)",
            "- BASE_HEAD=$($fixture.SiblingHead)"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:BASE_NOT_ANCESTOR:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'FAILED_CANDIDATE non-ancestor fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            "- FAILED_CANDIDATE=$($fixture.FailedHead)",
            "- FAILED_CANDIDATE=$($fixture.SiblingHead)"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token "FAIL:FAILED_CANDIDATE_NOT_ANCESTOR:$($fixture.SiblingHead)"
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'missing evidence fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $missing = Join-Path $fixture.EvidenceRoot 'missing.txt'
        $mutated = $fixture.TasksText.Replace(
            (Join-Path $fixture.EvidenceRoot 'handoff.txt'),
            $missing
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:EVIDENCE_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'zero-byte evidence fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $zero = Join-Path $fixture.EvidenceRoot 'zero.txt'
        Write-Utf8File -Path $zero -Text ''
        $mutated = $fixture.TasksText.Replace(
            (Join-Path $fixture.EvidenceRoot 'handoff.txt'),
            $zero
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:EVIDENCE_EMPTY:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'missing QA_READY block fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = [regex]::Replace(
            $fixture.TasksText,
            '(?s)<!-- V2_QA_READY_BEGIN -->.*?<!-- V2_QA_READY_END -->',
            ''
        )
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:QA_MARKER_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'unverified --tests selector fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '| TEST-001 | tools/architecture/v2-handoff-lint.tests.ps1 | pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 | NOT_APPLICABLE |  |',
            '| TEST-001 | tools/architecture/v2-handoff-lint.tests.ps1 | pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoMatch | NOT_VERIFIED |  |'
        )
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_UNVERIFIED:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'P1 missing GREEN fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $red = Join-Path $fixture.EvidenceRoot 'qa-red.txt'
        $green = Join-Path $fixture.EvidenceRoot 'qa-green.txt'
        $mutated = $fixture.TasksText.Replace(
            "| P1-001 | Missing linter fails | Positive fixture passes | $red;$green |",
            "| P1-001 | Missing linter fails |  | $red;$green |"
        )
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:P1_GREEN_MISSING:P1-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'MERGE_PREP_AUTHORIZED contradiction fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- MANUAL_LIMITATION=Device facts require the fresh QA owner.',
            "- MANUAL_LIMITATION=Device facts require the fresh QA owner.`n- MERGE_PREP_AUTHORIZED=YES"
        )
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MERGE_PREP_CONTRADICTION:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'memory index hard line limit fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $lines = [System.Collections.Generic.List[string]]::new()
        $lines.Add('# Memory Index')
        $lines.Add('')
        $lines.Add('## project')
        $lines.Add('- [Fixture project](project-note.md) - fixture state')
        while ($lines.Count -le 201) {
            $lines.Add('')
        }
        Write-Utf8File -Path $fixture.SafeMemoryIndex -Text ($lines -join "`n")
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.CandidateHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MEMORY_INDEX_HARD_LINE_LIMIT:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'memory project traversal pointer fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $outside = Join-Path $fixture.Parent 'outside.md'
        Write-Utf8File -Path $outside -Text "outside`n"
        Write-Utf8File -Path $fixture.SafeMemoryIndex -Text @'
# Memory Index

## project
- [Escape](../outside.md) - unsafe
'@
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.CandidateHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MEMORY_PROJECT_UNSAFE_POINTER:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'quoted empty selector with verified claim fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests ""' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' `
            -SelectorEvidence (Join-Path $fixture.EvidenceRoot 'qa-selector.txt')
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_INVALID:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'trailing selector with verified claim fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' `
            -SelectorEvidence (Join-Path $fixture.EvidenceRoot 'qa-selector.txt')
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_INVALID:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'empty equals selector with verified claim fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests=' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' `
            -SelectorEvidence (Join-Path $fixture.EvidenceRoot 'qa-selector.txt')
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_INVALID:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'whitespace-only selector with verified claim fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests "   "' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' `
            -SelectorEvidence (Join-Path $fixture.EvidenceRoot 'qa-selector.txt')
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_INVALID:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'selector followed by another option fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests --filter NoSuchTest' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' `
            -SelectorEvidence (Join-Path $fixture.EvidenceRoot 'qa-selector.txt')
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_INVALID:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'verified selector requires locator evidence' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoSuchTest' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS'
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_EVIDENCE_MISSING:TEST-001'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'verified selector rejects nonexistent locator evidence' -Body {
    $fixture = New-Fixture
    try {
        $missing = Join-Path $fixture.EvidenceRoot 'missing-selector.txt'
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoSuchTest' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' -SelectorEvidence $missing
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_EVIDENCE_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'verified selector with existing locator evidence passes QaReady' -Body {
    $fixture = New-Fixture
    try {
        $selectorEvidence = Join-Path $fixture.EvidenceRoot 'qa-selector.txt'
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoSuchTest' `
            -SelectorStatus 'TEST_SELECTOR_VERIFIED=PASS' -SelectorEvidence $selectorEvidence
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'QA_READY_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'no matching selectors rejects nonexistent equivalent evidence' -Body {
    $fixture = New-Fixture
    try {
        $missing = Join-Path $fixture.EvidenceRoot 'missing-equivalent.txt'
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoMatch' `
            -SelectorStatus 'NO_MATCHING_TESTS' -EquivalentEvidence $missing
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_EQUIVALENT_EVIDENCE_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'no matching selectors rejects empty equivalent evidence explicitly' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoMatch' `
            -SelectorStatus 'NO_MATCHING_TESTS' -EquivalentEvidence ''
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_EQUIVALENT_EVIDENCE_MISSING:TEST-001'
        Assert-True -Condition (-not $result.Raw.Contains('UNEXPECTED_ERROR', [System.StringComparison]::Ordinal)) `
            -Message "Empty equivalent evidence must use its explicit failure code. $($result.Raw)"
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'no matching selectors with existing equivalent evidence passes QaReady' -Body {
    $fixture = New-Fixture
    try {
        $equivalent = Join-Path $fixture.EvidenceRoot 'qa-equivalent.txt'
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -TestCommand 'pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoMatch' `
            -SelectorStatus 'NO_MATCHING_TESTS' -EquivalentEvidence $equivalent
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'QA_READY_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'selector command outside QA test matrix fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = Get-TasksText -FixtureRoot $fixture.Root -BaseHead $fixture.BaseHead `
            -FailedHead $fixture.FailedHead -EvidenceRoot $fixture.EvidenceRoot `
            -ActiveExtra '- EXTRA_TEST_COMMAND=pwsh -NoProfile -File tools/architecture/v2-handoff-lint.tests.ps1 --tests NoSuchTest'
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:TEST_SELECTOR_OUTSIDE_MATRIX:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'memory project pointer through parent junction fails QaReady' -Body {
    $fixture = New-Fixture
    $junction = Join-Path $fixture.MemoryRoot 'escape'
    try {
        $outside = Join-Path $fixture.Parent 'outside-memory'
        New-Item -ItemType Directory -Path $outside -Force | Out-Null
        Write-Utf8File -Path (Join-Path $outside 'outside.md') -Text "outside`n"
        try {
            New-Item -ItemType Junction -Path $junction -Target $outside -ErrorAction Stop | Out-Null
        }
        catch {
            throw "JUNCTION_FIXTURE_UNAVAILABLE: $($_.Exception.Message)"
        }
        Write-Utf8File -Path $fixture.SafeMemoryIndex -Text @'
# Memory Index

## project
- [Escape](escape/outside.md) - unsafe
'@
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MEMORY_PROJECT_UNSAFE_POINTER:'
    }
    finally {
        Remove-ReparsePointFixture -Path $junction
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'memory index under ancestor junction fails Handoff' -Body {
    $fixture = New-Fixture
    $linkedStore = Join-Path $fixture.Parent 'memory-link'
    try {
        try {
            New-Item -ItemType Junction -Path $linkedStore -Target $fixture.MemoryRoot -ErrorAction Stop | Out-Null
        }
        catch {
            throw "JUNCTION_FIXTURE_UNAVAILABLE: $($_.Exception.Message)"
        }
        $linkedIndex = Join-Path $linkedStore 'MEMORY.md'
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $fixture.CandidateHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $linkedIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MEMORY_INDEX_UNSAFE_REPARSE:'
    }
    finally {
        Remove-ReparsePointFixture -Path $linkedStore
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'memory project absolute pointer fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $outside = Join-Path $fixture.Parent 'outside.md'
        Write-Utf8File -Path $outside -Text "outside`n"
        Write-Utf8File -Path $fixture.SafeMemoryIndex -Text @"
# Memory Index

## project
- [Escape]($outside) - unsafe
"@
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MEMORY_PROJECT_UNSAFE_POINTER:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'memory project file URI pointer fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $outside = Join-Path $fixture.Parent 'outside.md'
        Write-Utf8File -Path $outside -Text "outside`n"
        $uri = 'file:///' + ($outside.Replace('\', '/'))
        Write-Utf8File -Path $fixture.SafeMemoryIndex -Text @"
# Memory Index

## project
- [Escape]($uri) - unsafe
"@
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:MEMORY_PROJECT_UNSAFE_POINTER:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'empty HANDOFF_OBJECT block fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = [regex]::Replace(
            $fixture.TasksText,
            '(?s)(<!-- V2_HANDOFF_OBJECT_BEGIN -->).*?(<!-- V2_HANDOFF_OBJECT_END -->)',
            '$1$2'
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:HANDOFF_OBJECT_EMPTY:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'missing REQUIRED_ANCESTOR token fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = [regex]::Replace($fixture.TasksText, '(?m)^- REQUIRED_ANCESTOR=.*\r?\n?', '')
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:REQUIRED_ANCESTOR_TOKEN_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'missing FAILED_CANDIDATE token fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = [regex]::Replace($fixture.TasksText, '(?m)^- FAILED_CANDIDATE=.*\r?\n?', '')
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:OBJECT_TOKEN_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'NONE sentinels for optional object sets pass Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            "- REQUIRED_ANCESTOR=$($fixture.BaseHead)",
            '- REQUIRED_ANCESTOR=NONE'
        ).Replace(
            "- FAILED_CANDIDATE=$($fixture.FailedHead)",
            '- FAILED_CANDIDATE=NONE'
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 0
        Assert-ContainsToken -Result $result -Token 'HANDOFF_LINT=PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'mixed REQUIRED_ANCESTOR NONE token fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            "- REQUIRED_ANCESTOR=$($fixture.BaseHead)",
            "- REQUIRED_ANCESTOR=NONE`n- REQUIRED_ANCESTOR=$($fixture.BaseHead)"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:REQUIRED_ANCESTOR_NONE_MIXED:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'invalid BASE_HEAD fails before changed-path diff' -Body {
    $fixture = New-Fixture
    try {
        Write-Utf8File -Path (Join-Path $fixture.Root 'docs/SEVENTH.md') -Text "seventh`n"
        $mutated = $fixture.TasksText.Replace(
            "- BASE_HEAD=$($fixture.BaseHead)",
            '- BASE_HEAD=NONE'
        )
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $newHead = Commit-AllAndGetHead -Fixture $fixture
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:BASE_HEAD_INVALID:NONE'
        Assert-True -Condition (-not $result.Raw.Contains('CHANGED_PATH_LIMIT:', [System.StringComparison]::Ordinal)) `
            -Message "Invalid base must not enter changed-path diff. $($result.Raw)"
        Assert-True -Condition (-not $result.Raw.Contains('PATH_OUTSIDE_SCOPE:', [System.StringComparison]::Ordinal)) `
            -Message "Invalid base must not enter scope diff. $($result.Raw)"
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'retired NEXT_ROLE fails Handoff even when owner matches' -Body {
    $fixture = New-Fixture
    try {
        $retired = 'AURORAMA_V2_W1B_1B_OWNER_2026_11_07'
        $mutated = $fixture.TasksText.Replace(
            '- NEXT_ROLE=FRESH_INDEPENDENT_QA_OWNER',
            "- NEXT_ROLE=$retired"
        ).Replace(
            '- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER',
            "- MANUAL_OWNER=$retired"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token "FAIL:ACTIVE_ROLE_RETIRED:$retired"
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'retired NEXT_ROLE fails Handoff without QA block' -Body {
    $fixture = New-Fixture
    try {
        $retired = 'AURORAMA_V2_W1B_1B_OWNER_2026_11_07'
        $mutated = [regex]::Replace(
            $fixture.TasksText,
            '(?s)<!-- V2_QA_READY_BEGIN -->.*?<!-- V2_QA_READY_END -->',
            ''
        ).Replace(
            '- NEXT_ROLE=FRESH_INDEPENDENT_QA_OWNER',
            "- NEXT_ROLE=$retired"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token "FAIL:ACTIVE_ROLE_RETIRED:$retired"
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'MANUAL_OWNER mismatch fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER',
            '- MANUAL_OWNER=OTHER_QA_OWNER'
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:QA_MANUAL_OWNER_MISMATCH:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'MANUAL_OWNER mismatch fails QaReady' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER',
            '- MANUAL_OWNER=OTHER_QA_OWNER'
        )
        Write-Utf8File -Path $fixture.TasksFile -Text $mutated
        $result = Invoke-Linter -Mode QaReady -FixtureRoot $fixture.Root -ExpectedHead '' `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:QA_MANUAL_OWNER_MISMATCH:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'duplicate MANUAL_OWNER fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER',
            "- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER`n- MANUAL_OWNER=FRESH_INDEPENDENT_QA_OWNER"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:QA_TOKEN_DUPLICATE:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'ALLOWED_PATH dot fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1',
            "- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1`n- ALLOWED_PATH=."
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ALLOWED_PATH_INVALID:.'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'ALLOWED_PATH backslash fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1',
            "- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1`n- ALLOWED_PATH=docs\LESSONS.md"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ALLOWED_PATH_INVALID:docs\LESSONS.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'ALLOWED_PATH repeated slash fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1',
            "- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1`n- ALLOWED_PATH=docs//LESSONS.md"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ALLOWED_PATH_INVALID:docs//LESSONS.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'ALLOWED_PATH trailing slash fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1',
            "- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1`n- ALLOWED_PATH=docs/LESSONS.md/"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ALLOWED_PATH_INVALID:docs/LESSONS.md/'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'ALLOWED_PATH dot segment fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1',
            "- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1`n- ALLOWED_PATH=./AGENTS.md"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ALLOWED_PATH_INVALID:./AGENTS.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'duplicate canonical ALLOWED_PATH fails Handoff' -Body {
    $fixture = New-Fixture
    try {
        $mutated = $fixture.TasksText.Replace(
            '- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1',
            "- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1`n- ALLOWED_PATH=tools/architecture/v2-handoff-lint.tests.ps1"
        )
        $newHead = Update-TasksAndCommit -Fixture $fixture -Text $mutated
        $result = Invoke-Linter -Mode Handoff -FixtureRoot $fixture.Root -ExpectedHead $newHead `
            -TasksPath $fixture.TasksPath -MemoryIndex $fixture.SafeMemoryIndex
        Assert-ExitCode -Result $result -Expected 2
        Assert-ContainsToken -Result $result -Token 'FAIL:ALLOWED_PATH_DUPLICATE:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Write-Output "SUMMARY passed=$script:passed failed=$script:failed filter=$Filter"
if ($script:failed -gt 0) {
    exit 1
}
if ($script:passed -eq 0) {
    Write-Output 'FAIL:NO_TESTS_MATCHED:'
    exit 3
}
exit 0
