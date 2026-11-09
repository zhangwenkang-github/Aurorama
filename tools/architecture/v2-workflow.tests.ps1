[CmdletBinding()]
param(
    [string]$Filter = '*'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$workflowPath = Join-Path $PSScriptRoot 'v2-workflow.ps1'
$pwshPath = (Get-Process -Id $PID).Path
$gitCommand = Get-Command git -CommandType Application | Select-Object -First 1
if ($null -eq $gitCommand) {
    throw 'git executable not found'
}
$gitPath = $gitCommand.Source
$script:passed = 0
$script:failed = 0
$script:fixtures = [System.Collections.Generic.List[string]]::new()

function Write-Utf8File {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [switch]$WithBom,
        [switch]$CrLf
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $normalized = $Text.Replace("`r`n", "`n")
    if ($CrLf) {
        $normalized = $normalized.Replace("`n", "`r`n")
    }
    $encoding = if ($WithBom) {
        [System.Text.UTF8Encoding]::new($true)
    }
    else {
        [System.Text.UTF8Encoding]::new($false)
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $encoding)
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

function Invoke-Workflow {
    param(
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $pwshPath -NoProfile -ExecutionPolicy Bypass -File $workflowPath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { [string]$_ })
        Raw = ($output -join "`n")
    }
}

function Invoke-DisplayedCommand {
    param(
        [Parameter(Mandatory)][string]$Command
    )

    $output = & $pwshPath -NoProfile -Command $Command 2>&1
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

function Assert-Contains {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][string]$Token
    )

    Assert-True -Condition ($Result.Raw.Contains($Token, [System.StringComparison]::Ordinal)) -Message (
        "Missing token '$Token'. $($Result.Raw)"
    )
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][string]$Token
    )

    Assert-True -Condition (-not $Result.Raw.Contains($Token, [System.StringComparison]::Ordinal)) -Message (
        "Unexpected token '$Token'. $($Result.Raw)"
    )
}

function Get-GateCommand {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][string]$Lane,
        [Parameter(Mandatory)][int]$Index
    )

    $prefix = "GATE_COMMAND=${Lane}:${Index}="
    $matches = @($Result.Lines | Where-Object {
        $_.StartsWith($prefix, [System.StringComparison]::Ordinal)
    })
    Assert-True -Condition ($matches.Count -eq 1) -Message (
        "Expected one command for $Lane`:$Index, got $($matches.Count). $($Result.Raw)"
    )
    return $matches[0].Substring($prefix.Length)
}

function Assert-GateCommandFileTargets {
    param(
        [Parameter(Mandatory)]$Result
    )

    $commands = @($Result.Lines | Where-Object {
        $_.StartsWith('GATE_COMMAND=', [System.StringComparison]::Ordinal)
    })
    Assert-True -Condition ($commands.Count -gt 0) -Message "GatePlan emitted no commands. $($Result.Raw)"

    foreach ($line in $commands) {
        $command = $line.Substring('GATE_COMMAND='.Length)
        $fileMatches = [regex]::Matches($command, '-File\s+"(?<path>[^"]+)"')
        if ($command -match '(^|\s)-File(\s|$)' -and $fileMatches.Count -eq 0) {
            throw "Command has an unparseable -File target: $command"
        }
        foreach ($match in $fileMatches) {
            $target = $match.Groups['path'].Value
            $leaf = Split-Path -Leaf $target
            Assert-True -Condition (Test-Path -LiteralPath $target -PathType Leaf) -Message (
                "Gate command target does not exist: $target"
            )
            Assert-True -Condition ([System.IO.Path]::GetExtension($target) -ieq '.ps1') -Message (
                "Gate command -File target is not a .ps1 script: $target"
            )
            Assert-True -Condition ($leaf -ine 'pwsh.exe') -Message (
                "Gate command passes the PowerShell executable to -File: $target"
            )
        }
    }
}

function Assert-NoBomLf {
    param([Parameter(Mandatory)][string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-True -Condition (-not (
        $bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF
    )) -Message "File has a UTF-8 BOM: $Path"
    Assert-True -Condition ([array]::IndexOf($bytes, [byte]0x0D) -lt 0) -Message "File contains CR bytes: $Path"
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
        [Parameter(Mandatory)][string]$BaseHead
    )

    return @"
# Fixture task board

# HISTORY BEFORE

<!-- V2_HANDOFF_ACTIVE_BEGIN -->
### ACTIVE - FIXTURE

- TASK_ID=FIXTURE-001
- TASK_STAGE=BEFORE
- NEXT_ROLE=OLD_OWNER
- RETIRED_ROLE=RETIRED_OWNER
- NEXT_PATH=docs/TASKS.md
- STOP_CONDITION=Stop on fixture drift.
<!-- V2_HANDOFF_ACTIVE_END -->

# HISTORY MIDDLE

<!-- V2_HANDOFF_OBJECT_BEGIN -->
- BASE_HEAD=$BaseHead
- REQUIRED_ANCESTOR=$BaseHead
- FAILED_CANDIDATE=NONE
- MAX_CHANGED_PATHS=6
- ALLOWED_PATH=AGENTS.md
- ALLOWED_PATH=docs/TASKS.md
<!-- V2_HANDOFF_OBJECT_END -->

# HISTORY QA

<!-- V2_QA_READY_BEGIN -->
- QA_READY=NO
- UNCOVERED=Fixture QA has not run.
- STOP_CONDITION=Do not dispatch QA.
- MANUAL_OWNER=OLD_OWNER
<!-- V2_QA_READY_END -->

# HISTORY AFTER
"@
}

function New-Fixture {
    $fixtureParent = Join-Path ([System.IO.Path]::GetTempPath()) ('v2-workflow-tests-' + [guid]::NewGuid().ToString('N'))
    $fixtureRoot = Join-Path $fixtureParent 'repo'
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
    $script:fixtures.Add($fixtureParent)

    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('init', '-q', '-b', 'codex/v2-workflow-fixture') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'user.email', 'fixture@example.invalid') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'user.name', 'Fixture Owner') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'core.autocrlf', 'false') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('config', 'core.eol', 'lf') | Out-Null

    Write-Utf8File -Path (Join-Path $fixtureRoot 'seed.txt') -Text "seed`n"
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('commit', '-q', '-m', 'base') | Out-Null
    $baseHead = [string](Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $baseHead = $baseHead.Trim()

    $tasksText = Get-TasksText -BaseHead $baseHead
    $tasksFile = Join-Path $fixtureRoot 'docs/TASKS.md'
    Write-Utf8File -Path $tasksFile -Text $tasksText
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('commit', '-q', '-m', 'tasks') | Out-Null
    $head = [string](Invoke-Git -FixtureRoot $fixtureRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $head = $head.Trim()

    return [pscustomobject]@{
        Parent = $fixtureParent
        Root = $fixtureRoot
        BaseHead = $baseHead
        Head = $head
        TasksFile = $tasksFile
        TasksText = $tasksText
    }
}

function Install-GatePlanEntrypoints {
    param(
        [Parameter(Mandatory)]$Fixture
    )

    $toolNames = @(
        'v2-workflow.ps1'
        'v2-workflow.tests.ps1'
        'v2-handoff-lint.ps1'
        'v2-handoff-lint.tests.ps1'
    )
    $toolDirectory = Join-Path $Fixture.Root 'tools/architecture'
    New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
    foreach ($toolName in $toolNames) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $toolName) -Destination $toolDirectory
    }
    Write-Utf8File -Path (Join-Path $Fixture.Root 'gradlew.bat') -Text "@echo off`n"
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('add', '-A') | Out-Null
    Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('commit', '-q', '-m', 'workflow entrypoints') | Out-Null
    $head = [string](Invoke-Git -FixtureRoot $Fixture.Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
    $Fixture.Head = $head.Trim()
    return $Fixture
}

function Set-FixtureTasks {
    param(
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [switch]$WithBom,
        [switch]$CrLf
    )

    Write-Utf8File -Path $Fixture.TasksFile -Text $Text -WithBom:$WithBom -CrLf:$CrLf
}

function Remove-Fixture {
    param([Parameter(Mandatory)]$Fixture)

    $resolved = [System.IO.Path]::GetFullPath($Fixture.Parent)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $leaf = Split-Path -Leaf $resolved
    if (-not $resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not $leaf.StartsWith('v2-workflow-tests-', [System.StringComparison]::Ordinal)) {
        throw "Refusing to remove unexpected fixture path: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    [void]$script:fixtures.Remove($resolved)
}

function New-LinterStub {
    param([Parameter(Mandatory)]$Fixture)

    $stubPath = Join-Path $Fixture.Parent 'stub-linter.ps1'
    $stub = @'
param(
    [string]$Mode,
    [string]$RepoRoot,
    [string]$ExpectedHead,
    [string]$TasksPath,
    [string]$MemoryIndex
)
$logPath = $env:V2_WORKFLOW_TEST_STAGE_LOG
if (-not [string]::IsNullOrWhiteSpace($logPath)) {
    [System.IO.File]::AppendAllText($logPath, "HANDOFF`n")
}
if ($env:V2_WORKFLOW_TEST_FAIL_STAGE -eq 'HANDOFF') {
    Write-Output 'FAIL:STUB_HANDOFF:child'
    exit 17
}
if ($Mode -eq 'Handoff') {
    Write-Output "HANDOFF_LINT=STUB_PASS ExpectedHead=$ExpectedHead RepoRoot=$RepoRoot"
}
else {
    Write-Output "QA_READY_LINT=STUB_PASS RepoRoot=$RepoRoot"
}
exit 0
'@
    Write-Utf8File -Path $stubPath -Text $stub
    return $stubPath
}

function New-EngramoryStubs {
    param([Parameter(Mandatory)]$Fixture)

    $engramoryRoot = Join-Path $Fixture.Parent 'engramory'
    $toolsRoot = Join-Path $engramoryRoot 'tools'
    $checkerPath = Join-Path $toolsRoot 'engramory_check.py'
    $doctorPath = Join-Path $toolsRoot 'engramory_doctor.py'
    $template = @'
import os
import sys

stage = "__STAGE__"
failure_code = __FAILURE_CODE__
log_path = os.environ.get("V2_WORKFLOW_TEST_STAGE_LOG")
if log_path:
    with open(log_path, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(stage + "\n")
if os.environ.get("V2_WORKFLOW_TEST_FAIL_STAGE") == stage:
    print("FAIL:STUB_" + stage + ":child")
    raise SystemExit(failure_code)
print(stage + "=STUB_PASS")
'@
    Write-Utf8File -Path $checkerPath -Text $template.Replace('__STAGE__', 'CHECKER').Replace('__FAILURE_CODE__', '9')
    Write-Utf8File -Path $doctorPath -Text $template.Replace('__STAGE__', 'DOCTOR').Replace('__FAILURE_CODE__', '11')
    return [pscustomobject]@{
        Root = $engramoryRoot
        ToolsRoot = $toolsRoot
        CheckerPath = $checkerPath
        DoctorPath = $doctorPath
    }
}

Invoke-FixtureTest -Name 'Workflow wrapper Help smoke' -Body {
    $result = Invoke-Workflow -Arguments @('-Help')
    Assert-ExitCode -Result $result -Expected 0
    Assert-Contains -Result $result -Token 'Snapshot'
    Assert-Contains -Result $result -Token 'UpdateState'
    Assert-Contains -Result $result -Token 'Closeout'
}

Invoke-FixtureTest -Name 'Snapshot reports unique state and changed paths' -Body {
    $fixture = New-Fixture
    try {
        Write-Utf8File -Path (Join-Path $fixture.Root 'docs/untracked.md') -Text "untracked`n"
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'Snapshot',
            '-RepoRoot', $fixture.Root
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'CLEAN=NO'
        Assert-Contains -Result $result -Token 'ACTIVE_MARKERS=UNIQUE'
        Assert-Contains -Result $result -Token 'QA_READY_MARKERS=UNIQUE'
        Assert-Contains -Result $result -Token 'HANDOFF_OBJECT=UNIQUE'
        Assert-Contains -Result $result -Token 'ACTIVE.TASK_STAGE=BEFORE'
        Assert-Contains -Result $result -Token 'QA.QA_READY=NO'
        Assert-Contains -Result $result -Token 'CHANGED_PATH=docs/untracked.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState updates one unique token' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'STATE_UPDATE=PASS'
        $text = [System.IO.File]::ReadAllText($fixture.TasksFile, [System.Text.UTF8Encoding]::new($false, $true))
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- TASK_STAGE=AFTER$').Count) -eq 1) -Message $text
        Assert-True -Condition (-not $text.Contains('- TASK_STAGE=BEFORE', [System.StringComparison]::Ordinal)) -Message $text
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState updates one unique lowercase legacy token' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '- STOP_CONDITION=Stop on fixture drift.',
            "- STOP_CONDITION=Stop on fixture drift.`n- last_evidence=before"
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:last_evidence=after'
        )
        Assert-ExitCode -Result $result -Expected 0
        $text = [System.IO.File]::ReadAllText($fixture.TasksFile, [System.Text.UTF8Encoding]::new($false, $true))
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- last_evidence=after$').Count) -eq 1) -Message $text
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects zero matching key' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:UNKNOWN_KEY=value'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_KEY_MISSING:ACTIVE:UNKNOWN_KEY'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects duplicate key' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '- TASK_STAGE=BEFORE',
            "- TASK_STAGE=BEFORE`n- TASK_STAGE=AGAIN"
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_KEY_DUPLICATE:ACTIVE:TASK_STAGE'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects duplicate non-target ACTIVE key without writing' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '- TASK_ID=FIXTURE-001',
            "- TASK_ID=FIXTURE-001`n- TASK_ID=FIXTURE-002"
        )
        $before = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        $after = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_KEY_DUPLICATE:ACTIVE:TASK_ID'
        Assert-True -Condition ([Convert]::ToBase64String($before) -ceq [Convert]::ToBase64String($after)) -Message 'Duplicate ACTIVE key fixture was modified'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects duplicate non-target QA key without writing' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '- UNCOVERED=Fixture QA has not run.',
            "- UNCOVERED=Fixture QA has not run.`n- UNCOVERED=Fixture QA remains uncovered."
        )
        $before = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        $after = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_KEY_DUPLICATE:QA:UNCOVERED'
        Assert-True -Condition ([Convert]::ToBase64String($before) -ceq [Convert]::ToBase64String($after)) -Message 'Duplicate QA key fixture was modified'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState allows duplicate EVIDENCE and RETIRED_ROLE keys' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '- RETIRED_ROLE=RETIRED_OWNER',
            "- EVIDENCE=fixture/evidence-one.txt`n- EVIDENCE=fixture/evidence-two.txt`n- RETIRED_ROLE=RETIRED_OWNER`n- RETIRED_ROLE=OLDER_OWNER"
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'STATE_UPDATE=PASS'
        $text = [System.IO.File]::ReadAllText($fixture.TasksFile, [System.Text.UTF8Encoding]::new($false, $true))
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- TASK_STAGE=AFTER$').Count) -eq 1) -Message $text
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- EVIDENCE=fixture/evidence-(one|two)\.txt$').Count) -eq 2) -Message $text
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- RETIRED_ROLE=(RETIRED_OWNER|OLDER_OWNER)$').Count) -eq 2) -Message $text
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState isolates ACTIVE QA and HISTORY blocks' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 0
        $text = [System.IO.File]::ReadAllText($fixture.TasksFile, [System.Text.UTF8Encoding]::new($false, $true))
        Assert-Contains -Result $result -Token 'STATE_UPDATE=PASS'
        Assert-True -Condition ($text.Contains('# HISTORY BEFORE', [System.StringComparison]::Ordinal)) -Message $text
        Assert-True -Condition ($text.Contains('# HISTORY MIDDLE', [System.StringComparison]::Ordinal)) -Message $text
        Assert-True -Condition ($text.Contains('# HISTORY QA', [System.StringComparison]::Ordinal)) -Message $text
        Assert-True -Condition ($text.Contains('# HISTORY AFTER', [System.StringComparison]::Ordinal)) -Message $text
        Assert-True -Condition ($text.Contains('- QA_READY=NO', [System.StringComparison]::Ordinal)) -Message $text
        Assert-True -Condition ($text.Contains('- MANUAL_OWNER=OLD_OWNER', [System.StringComparison]::Ordinal)) -Message $text
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState writes LF without BOM' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-NoBomLf -Path $fixture.TasksFile
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects empty value' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE='
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_KEY_EMPTY:ACTIVE:TASK_STAGE'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects CR without writing' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText -CrLf
        $before = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        $after = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_ENCODING_INVALID:'
        Assert-True -Condition ([Convert]::ToBase64String($before) -ceq [Convert]::ToBase64String($after)) -Message 'CR fixture was modified'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects BOM without writing' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText -WithBom
        $before = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        $after = [System.IO.File]::ReadAllBytes($fixture.TasksFile)
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_ENCODING_INVALID:'
        Assert-True -Condition ([Convert]::ToBase64String($before) -ceq [Convert]::ToBase64String($after)) -Message 'BOM fixture was modified'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects retired role' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:NEXT_ROLE=RETIRED_OWNER||QA:MANUAL_OWNER=RETIRED_OWNER'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_ROLE_RETIRED:RETIRED_OWNER'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects illegal role' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:NEXT_ROLE=lowercase-role||QA:MANUAL_OWNER=lowercase-role'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_ROLE_INVALID:ACTIVE:NEXT_ROLE'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState binds ACTIVE NEXT_ROLE and QA MANUAL_OWNER' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:NEXT_ROLE=NEW_OWNER||QA:MANUAL_OWNER=NEW_OWNER'
        )
        Assert-ExitCode -Result $result -Expected 0
        $text = [System.IO.File]::ReadAllText($fixture.TasksFile, [System.Text.UTF8Encoding]::new($false, $true))
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- NEXT_ROLE=NEW_OWNER$').Count) -eq 1) -Message $text
        Assert-True -Condition (([regex]::Matches($text, '(?m)^- MANUAL_OWNER=NEW_OWNER$').Count) -eq 1) -Message $text
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects owner mismatch' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'QA:MANUAL_OWNER=OTHER_OWNER'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_OWNER_MISMATCH:OTHER_OWNER'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects tasks path outside repository' -Body {
    $fixture = New-Fixture
    try {
        $outsideTasks = Join-Path $fixture.Parent 'outside-TASKS.md'
        Write-Utf8File -Path $outsideTasks -Text $fixture.TasksText
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-TasksPath', $outsideTasks,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_PATH_INVALID:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects missing marker' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '<!-- V2_QA_READY_BEGIN -->',
            ''
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_MARKER_MISSING:QA'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'UpdateState rejects duplicate marker' -Body {
    $fixture = New-Fixture
    try {
        Set-FixtureTasks -Fixture $fixture -Text $fixture.TasksText.Replace(
            '<!-- V2_HANDOFF_ACTIVE_END -->',
            "<!-- V2_HANDOFF_ACTIVE_END -->`n<!-- V2_HANDOFF_ACTIVE_BEGIN -->`n<!-- V2_HANDOFF_ACTIVE_END -->"
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'UpdateState',
            '-RepoRoot', $fixture.Root,
            '-StateUpdate', 'ACTIVE:TASK_STAGE=AFTER'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:STATE_MARKER_DUPLICATE:ACTIVE'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan FAST Snapshot command executes against fixture' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'docs/PROJECT_RULES.md'
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=FAST'
        $workflowTests = Get-GateCommand -Result $result -Lane 'FAST' -Index 1
        $snapshotCommand = Get-GateCommand -Result $result -Lane 'FAST' -Index 2
        $snapshot = Invoke-DisplayedCommand -Command $snapshotCommand
        Assert-ExitCode -Result $snapshot -Expected 0
        Assert-Contains -Result $snapshot -Token "WORKTREE=$($fixture.Root)"
        Assert-Contains -Result $snapshot -Token "HEAD=$($fixture.Head)"
        Assert-Contains -Result $snapshot -Token 'CLEAN=YES'
        $workflowWrapper = Join-Path $fixture.Root 'tools\architecture\v2-workflow.ps1'
        Assert-True -Condition ($workflowTests.Contains((Join-Path $fixture.Root 'tools\architecture\v2-workflow.tests.ps1'))) -Message $workflowTests
        Assert-True -Condition ($snapshotCommand.Contains("-File `"$workflowWrapper`"")) -Message $snapshotCommand
        Assert-GateCommandFileTargets -Result $result
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan maps root glossary documents to FAST' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'GLOSSARY.md||GLOSSARY-MAP.md'
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=FAST'
        Assert-Contains -Result $result -Token 'GATE_PATH=GLOSSARY.md'
        Assert-Contains -Result $result -Token 'GATE_PATH=GLOSSARY-MAP.md'
        Assert-GateCommandFileTargets -Result $result
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan maps control-plane scripts to CANDIDATE' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'tools/architecture/v2-workflow.ps1||tools/architecture/v2-workflow.tests.ps1'
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=CANDIDATE'
        $workflowTests = Join-Path $fixture.Root 'tools\architecture\v2-workflow.tests.ps1'
        $handoffTests = Join-Path $fixture.Root 'tools\architecture\v2-handoff-lint.tests.ps1'
        Assert-True -Condition ((Get-GateCommand -Result $result -Lane 'CANDIDATE' -Index 1).Contains($workflowTests)) -Message $result.Raw
        Assert-True -Condition ((Get-GateCommand -Result $result -Lane 'CANDIDATE' -Index 2).Contains($handoffTests)) -Message $result.Raw
        Assert-GateCommandFileTargets -Result $result
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan maps product paths to FULL with state-effect matrix' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $matrix = Join-Path $fixture.Parent 'state-effect-matrix.txt'
        $matrixText = @"
SUCCESS=success is observable
EMPTY_RESULT=empty result is observable
PERMISSION=permission denial is observable
TIMEOUT_ERROR=timeout and exception are observable
CANCEL=cancel restores the prior state
RETRY_DUPLICATE=retry is idempotent
RESTART_RECOVERY=restart recovery is observable
"@
        Write-Utf8File -Path $matrix -Text $matrixText
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'app/phone/src/main/kotlin/Example.kt',
            '-StateEffectMatrixPath', $matrix
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=FULL'
        Assert-Contains -Result $result -Token "STATE_EFFECT_MATRIX=PASS:$matrix"
        Assert-GateCommandFileTargets -Result $result
        $gradleCommand = Get-GateCommand -Result $result -Lane 'FULL' -Index 3
        Assert-True -Condition $gradleCommand.Contains('pwsh -NoProfile -ExecutionPolicy Bypass -Command') -Message $gradleCommand
        Assert-True -Condition $gradleCommand.Contains('gradlew.bat') -Message $gradleCommand
        Assert-True -Condition $gradleCommand.Contains(':app:phone:assembleDebug') -Message $gradleCommand
        Assert-True -Condition $gradleCommand.Contains('--console=plain') -Message $gradleCommand
        Assert-True -Condition (Test-Path -LiteralPath (Join-Path $fixture.Root 'gradlew.bat') -PathType Leaf) -Message $gradleCommand
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan maps exact W1B product paths to FULL with state-effect matrix' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $matrix = Join-Path $fixture.Parent 'state-effect-matrix-w1b.txt'
        $matrixText = @"
SUCCESS=success is observable
EMPTY_RESULT=empty result is observable
PERMISSION=permission denial is observable
TIMEOUT_ERROR=timeout and exception are observable
CANCEL=cancel restores the prior state
RETRY_DUPLICATE=retry is idempotent
RESTART_RECOVERY=restart recovery is observable
"@
        Write-Utf8File -Path $matrix -Text $matrixText
        $w1bPaths = @(
            'app/phone/src/test/java/com/zhangwenkang/cinefin/setup/IdentityIngressContractTest.kt'
            'app/phone/src/test/java/com/zhangwenkang/cinefin/setup/OfflineIdentityCoordinatorTest.kt'
            'data/src/main/java/com/zhangwenkang/cinefin/api/JellyfinApiFactory.kt'
            'data/src/main/java/com/zhangwenkang/cinefin/identity/IdentityContract.kt'
            'data/src/main/java/com/zhangwenkang/cinefin/identity/IdentityWriteCoordinator.kt'
            'data/src/test/java/com/zhangwenkang/cinefin/identity/IdentityContractTest.kt'
            'data/src/test/java/com/zhangwenkang/cinefin/identity/IdentityWriteCoordinatorTest.kt'
            'docs/TASKS.md'
            'setup/src/main/java/com/zhangwenkang/cinefin/setup/data/SetupRepositoryImpl.kt'
            'setup/src/main/java/com/zhangwenkang/cinefin/setup/data/di/SetupDataModule.kt'
            'setup/src/main/java/com/zhangwenkang/cinefin/setup/domain/OfflineIdentity.kt'
            'setup/src/main/java/com/zhangwenkang/cinefin/setup/domain/SetupRepository.kt'
            'setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/addserver/AddServerViewModel.kt'
            'setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/users/UsersViewModel.kt'
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', ($w1bPaths -join '||'),
            '-StateEffectMatrixPath', $matrix
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=FULL'
        Assert-Contains -Result $result -Token "STATE_EFFECT_MATRIX=PASS:$matrix"
        foreach ($path in $w1bPaths) {
            Assert-Contains -Result $result -Token "GATE_PATH=$path"
        }
        Assert-GateCommandFileTargets -Result $result
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan maps exact player paths to FULL and keeps adjacent roots unmapped' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $matrix = Join-Path $fixture.Parent 'state-effect-matrix-player.txt'
        $matrixText = @"
SUCCESS=success is observable
EMPTY_RESULT=empty result is observable
PERMISSION=permission denial is observable
TIMEOUT_ERROR=timeout and exception are observable
CANCEL=cancel restores the prior state
RETRY_DUPLICATE=retry is idempotent
RESTART_RECOVERY=restart recovery is observable
"@
        Write-Utf8File -Path $matrix -Text $matrixText
        $playerPaths = @(
            'player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/SeekStateReducer.kt'
            'player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/SeekRequestCoordinator.kt'
            'player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/PlaybackSessionRules.kt'
            'player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/PlayerViewModel.kt'
            'player/local/src/test/java/com/zhangwenkang/cinefin/player/local/domain/SeekStateReducerTest.kt'
            'player/local/src/test/java/com/zhangwenkang/cinefin/player/local/domain/SeekRequestCoordinatorTest.kt'
            'player/local/src/test/java/com/zhangwenkang/cinefin/player/local/presentation/PlaybackSessionRulesTest.kt'
            'app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/PlayerControlOverlay.kt'
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', ($playerPaths -join '||'),
            '-StateEffectMatrixPath', $matrix
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=FULL'
        Assert-Contains -Result $result -Token "STATE_EFFECT_MATRIX=PASS:$matrix"
        foreach ($path in $playerPaths) {
            Assert-Contains -Result $result -Token "GATE_PATH=$path"
        }
        $gatePathCount =
            @($result.Raw -split "\r?\n" | Where-Object { $_ -like 'GATE_PATH=*' }).Count
        $fullCommandCount =
            @($result.Raw -split "\r?\n" | Where-Object { $_ -like 'GATE_COMMAND=FULL:*' }).Count
        Assert-True -Condition ($gatePathCount -eq 8) -Message $result.Raw
        Assert-True -Condition ($fullCommandCount -eq 3) -Message $result.Raw
        Assert-True -Condition (-not $result.Raw.Contains('WORKFLOW_GATE_PATH_UNMAPPED')) -Message $result.Raw
        Assert-GateCommandFileTargets -Result $result

        $missingMatrixResult = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', $playerPaths[0]
        )
        Assert-ExitCode -Result $missingMatrixResult -Expected 2
        Assert-Contains -Result $missingMatrixResult -Token 'FAIL:WORKFLOW_STATE_EFFECT_MATRIX_MISSING:'

        foreach ($nearMiss in @(
            'players/src/main/kotlin/Unknown.kt'
            'player-extra/src/main/kotlin/Unknown.kt'
        )) {
            $nearMissResult = Invoke-Workflow -Arguments @(
                '-Mode', 'GatePlan',
                '-RepoRoot', $fixture.Root,
                '-ChangedPath', $nearMiss
            )
            Assert-ExitCode -Result $nearMissResult -Expected 2
            Assert-Contains -Result $nearMissResult -Token "FAIL:WORKFLOW_GATE_PATH_UNMAPPED:$nearMiss"
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan maps W1B adjacent modules without broad prefix aliases' -Body {
    $fixture = New-Fixture
    try {
        $fixture = Install-GatePlanEntrypoints -Fixture $fixture
        $matrix = Join-Path $fixture.Parent 'state-effect-matrix-adjacent.txt'
        $matrixText = @"
SUCCESS=success is observable
EMPTY_RESULT=empty result is observable
PERMISSION=permission denial is observable
TIMEOUT_ERROR=timeout and exception are observable
CANCEL=cancel restores the prior state
RETRY_DUPLICATE=retry is idempotent
RESTART_RECOVERY=restart recovery is observable
"@
        Write-Utf8File -Path $matrix -Text $matrixText
        $modulePaths = @(
            'docs/TASKS.md'
            'tools/architecture/v2-workflow.ps1'
            'settings/src/main/java/com/zhangwenkang/cinefin/settings/presentation/settings/SettingsViewModel.kt'
            'modes/film/src/test/java/com/zhangwenkang/cinefin/film/presentation/search/SearchCatalogConsumerTest.kt'
        )
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', ($modulePaths -join '||'),
            '-StateEffectMatrixPath', $matrix
        )
        Assert-ExitCode -Result $result -Expected 0
        Assert-Contains -Result $result -Token 'GATE_LANE=FULL'
        foreach ($path in $modulePaths) {
            Assert-Contains -Result $result -Token "GATE_PATH=$path"
        }
        Assert-GateCommandFileTargets -Result $result

        foreach ($nearMiss in @(
            'setups/src/main/kotlin/Unknown.kt'
            'settings2/src/main/kotlin/Unknown.kt'
            'modes-extra/src/main/kotlin/Unknown.kt'
        )) {
            $nearMissResult = Invoke-Workflow -Arguments @(
                '-Mode', 'GatePlan',
                '-RepoRoot', $fixture.Root,
                '-ChangedPath', $nearMiss
            )
            Assert-ExitCode -Result $nearMissResult -Expected 2
            Assert-Contains -Result $nearMissResult -Token "FAIL:WORKFLOW_GATE_PATH_UNMAPPED:$nearMiss"
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan fails closed on unknown path' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'unknown/control-plane.ps1'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:WORKFLOW_GATE_PATH_UNMAPPED:unknown/control-plane.ps1'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan rejects empty changed-path set' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-BaseHead', $fixture.Head
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:WORKFLOW_GATE_PLAN_EMPTY:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan rejects duplicate changed paths' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'docs/PROJECT_RULES.md||docs/PROJECT_RULES.md'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:WORKFLOW_GATE_PATH_DUPLICATE:docs/PROJECT_RULES.md'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan requires state-effect matrix for FULL' -Body {
    $fixture = New-Fixture
    try {
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'core/example/src/main/kotlin/Example.kt'
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:WORKFLOW_STATE_EFFECT_MATRIX_MISSING:'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'GatePlan rejects state-effect coverage gap' -Body {
    $fixture = New-Fixture
    try {
        $matrix = Join-Path $fixture.Parent 'state-effect-gap.txt'
        $matrixText = @"
SUCCESS=success is observable
EMPTY_RESULT=empty result is observable
PERMISSION=permission denial is observable
TIMEOUT_ERROR=timeout and exception are observable
RETRY_DUPLICATE=retry is idempotent
RESTART_RECOVERY=restart recovery is observable
"@
        Write-Utf8File -Path $matrix -Text $matrixText
        $result = Invoke-Workflow -Arguments @(
            '-Mode', 'GatePlan',
            '-RepoRoot', $fixture.Root,
            '-ChangedPath', 'data/example/src/main/kotlin/Example.kt',
            '-StateEffectMatrixPath', $matrix
        )
        Assert-ExitCode -Result $result -Expected 2
        Assert-Contains -Result $result -Token 'FAIL:WORKFLOW_STATE_EFFECT_COVERAGE_GAP:CANCEL'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'Handoff and QaReady delegate and preserve PASS tokens' -Body {
    $fixture = New-Fixture
    try {
        $stub = New-LinterStub -Fixture $fixture
        $handoff = Invoke-Workflow -Arguments @(
            '-Mode', 'Handoff',
            '-RepoRoot', $fixture.Root,
            '-ExpectedHead', $fixture.Head,
            '-LinterPath', $stub
        )
        Assert-ExitCode -Result $handoff -Expected 0
        Assert-Contains -Result $handoff -Token 'HANDOFF_LINT=STUB_PASS'
        $qa = Invoke-Workflow -Arguments @(
            '-Mode', 'QaReady',
            '-RepoRoot', $fixture.Root,
            '-LinterPath', $stub
        )
        Assert-ExitCode -Result $qa -Expected 0
        Assert-Contains -Result $qa -Token 'QA_READY_LINT=STUB_PASS'
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'Handoff and QaReady pass through child failure codes' -Body {
    $fixture = New-Fixture
    try {
        $stub = New-LinterStub -Fixture $fixture
        $oldFailStage = $env:V2_WORKFLOW_TEST_FAIL_STAGE
        try {
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = 'HANDOFF'
            foreach ($mode in @('Handoff', 'QaReady')) {
                $arguments = @(
                    '-Mode', $mode,
                    '-RepoRoot', $fixture.Root,
                    '-LinterPath', $stub
                )
                if ($mode -eq 'Handoff') {
                    $arguments += @('-ExpectedHead', $fixture.Head)
                }
                $result = Invoke-Workflow -Arguments $arguments
                Assert-ExitCode -Result $result -Expected 17
                Assert-Contains -Result $result -Token 'FAIL:STUB_HANDOFF:child'
            }
        }
        finally {
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = $oldFailStage
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'Closeout runs Handoff checker doctor and clean in order' -Body {
    $fixture = New-Fixture
    try {
        $stub = New-LinterStub -Fixture $fixture
        $engramory = New-EngramoryStubs -Fixture $fixture
        $memoryIndex = Join-Path $fixture.Parent 'MEMORY.md'
        Write-Utf8File -Path $memoryIndex -Text "# Fixture memory`n"
        $stageLog = Join-Path $fixture.Parent 'stage.log'
        $oldStageLog = $env:V2_WORKFLOW_TEST_STAGE_LOG
        $oldFailStage = $env:V2_WORKFLOW_TEST_FAIL_STAGE
        try {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = $stageLog
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = ''
            $result = Invoke-Workflow -Arguments @(
                '-Mode', 'Closeout',
                '-RepoRoot', $fixture.Root,
                '-ExpectedHead', $fixture.Head,
                '-MemoryIndex', $memoryIndex,
                '-LinterPath', $stub,
                '-EngramoryRoot', $engramory.Root
            )
            Assert-ExitCode -Result $result -Expected 0
            Assert-Contains -Result $result -Token 'CLOSEOUT_CONTROL_PLANE=PASS'
            Assert-Contains -Result $result -Token 'PRODUCT_QA=NOT_ASSESSED'
            $stages = @(Get-Content -LiteralPath $stageLog -Encoding utf8 | Where-Object { $_ })
            Assert-True -Condition (($stages -join ',') -ceq 'HANDOFF,CHECKER,DOCTOR') -Message ($stages -join ',')
        }
        finally {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = $oldStageLog
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = $oldFailStage
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'Closeout stops on any child failure' -Body {
    $fixture = New-Fixture
    try {
        $stub = New-LinterStub -Fixture $fixture
        $engramory = New-EngramoryStubs -Fixture $fixture
        $memoryIndex = Join-Path $fixture.Parent 'MEMORY.md'
        Write-Utf8File -Path $memoryIndex -Text "# Fixture memory`n"
        $stageLog = Join-Path $fixture.Parent 'failure-stage.log'
        $oldStageLog = $env:V2_WORKFLOW_TEST_STAGE_LOG
        $oldFailStage = $env:V2_WORKFLOW_TEST_FAIL_STAGE
        try {
            foreach ($case in @(
                @{ Stage = 'HANDOFF'; Exit = 17; Expected = 'HANDOFF'; Code = 'CLOSEOUT_HANDOFF_FAILED' },
                @{ Stage = 'CHECKER'; Exit = 9; Expected = 'HANDOFF,CHECKER'; Code = 'CLOSEOUT_ENGRAMORY_CHECK_FAILED' },
                @{ Stage = 'DOCTOR'; Exit = 11; Expected = 'HANDOFF,CHECKER,DOCTOR'; Code = 'CLOSEOUT_ENGRAMORY_DOCTOR_FAILED' }
            )) {
                Write-Utf8File -Path $stageLog -Text ''
                $env:V2_WORKFLOW_TEST_STAGE_LOG = $stageLog
                $env:V2_WORKFLOW_TEST_FAIL_STAGE = $case.Stage
                $result = Invoke-Workflow -Arguments @(
                    '-Mode', 'Closeout',
                    '-RepoRoot', $fixture.Root,
                    '-ExpectedHead', $fixture.Head,
                    '-MemoryIndex', $memoryIndex,
                    '-LinterPath', $stub,
                    '-EngramoryRoot', $engramory.Root
                )
                Assert-ExitCode -Result $result -Expected $case.Exit
                Assert-Contains -Result $result -Token ("FAIL:{0}:{1}" -f $case.Code, $case.Exit)
                $stages = @(Get-Content -LiteralPath $stageLog -Encoding utf8 | Where-Object { $_ })
                Assert-True -Condition (($stages -join ',') -ceq $case.Expected) -Message (
                    "Stage order for $($case.Stage): $($stages -join ',')"
                )
            }
        }
        finally {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = $oldStageLog
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = $oldFailStage
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'Closeout final clean gate rejects HEAD mismatch' -Body {
    $fixture = New-Fixture
    try {
        $stub = New-LinterStub -Fixture $fixture
        $engramory = New-EngramoryStubs -Fixture $fixture
        $memoryIndex = Join-Path $fixture.Parent 'MEMORY.md'
        Write-Utf8File -Path $memoryIndex -Text "# Fixture memory`n"
        $oldStageLog = $env:V2_WORKFLOW_TEST_STAGE_LOG
        $oldFailStage = $env:V2_WORKFLOW_TEST_FAIL_STAGE
        try {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = ''
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = ''
            $result = Invoke-Workflow -Arguments @(
                '-Mode', 'Closeout',
                '-RepoRoot', $fixture.Root,
                '-ExpectedHead', '0000000000000000000000000000000000000000',
                '-MemoryIndex', $memoryIndex,
                '-LinterPath', $stub,
                '-EngramoryRoot', $engramory.Root
            )
            Assert-ExitCode -Result $result -Expected 2
            Assert-Contains -Result $result -Token 'FAIL:CLOSEOUT_HEAD_MISMATCH:'
        }
        finally {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = $oldStageLog
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = $oldFailStage
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

Invoke-FixtureTest -Name 'Closeout final clean gate rejects dirty worktree' -Body {
    $fixture = New-Fixture
    try {
        $stub = New-LinterStub -Fixture $fixture
        $engramory = New-EngramoryStubs -Fixture $fixture
        $memoryIndex = Join-Path $fixture.Parent 'MEMORY.md'
        Write-Utf8File -Path $memoryIndex -Text "# Fixture memory`n"
        Write-Utf8File -Path (Join-Path $fixture.Root 'dirty.txt') -Text "dirty`n"
        $oldStageLog = $env:V2_WORKFLOW_TEST_STAGE_LOG
        $oldFailStage = $env:V2_WORKFLOW_TEST_FAIL_STAGE
        try {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = ''
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = ''
            $result = Invoke-Workflow -Arguments @(
                '-Mode', 'Closeout',
                '-RepoRoot', $fixture.Root,
                '-ExpectedHead', $fixture.Head,
                '-MemoryIndex', $memoryIndex,
                '-LinterPath', $stub,
                '-EngramoryRoot', $engramory.Root
            )
            Assert-ExitCode -Result $result -Expected 2
            Assert-Contains -Result $result -Token 'FAIL:CLOSEOUT_REPO_DIRTY:'
        }
        finally {
            $env:V2_WORKFLOW_TEST_STAGE_LOG = $oldStageLog
            $env:V2_WORKFLOW_TEST_FAIL_STAGE = $oldFailStage
        }
    }
    finally {
        Remove-Fixture -Fixture $fixture
    }
}

foreach ($leftover in @($script:fixtures)) {
    $script:failed++
    Write-Output "FAIL Fixture cleanup leaked $leftover"
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
