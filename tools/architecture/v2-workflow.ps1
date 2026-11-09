[CmdletBinding()]
param(
    [ValidateSet('Snapshot', 'UpdateState', 'GatePlan', 'Handoff', 'QaReady', 'Closeout')]
    [string]$Mode,
    [string]$RepoRoot,
    [string]$TasksPath = 'docs/TASKS.md',
    [string]$ExpectedHead,
    [string]$MemoryIndex,
    [string[]]$StateUpdate = @(),
    [string[]]$ChangedPath = @(),
    [string]$BaseHead,
    [string]$StateEffectMatrixPath,
    [string]$LinterPath,
    [string]$EngramoryRoot,
    [string]$PythonPath,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$script:repositoryPath = $null
$script:gitPath = $null
$script:tasksFile = $null
$script:pwshPath = (Get-Process -Id $PID).Path
$normalizedStateUpdate = @(
    $StateUpdate |
        ForEach-Object { $_ -split '\|\|' } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$normalizedChangedPath = @(
    $ChangedPath |
        ForEach-Object { $_ -split '\|\|' } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$script:options = [pscustomobject]@{
    RepoRootInput = $RepoRoot
    TasksPathInput = $TasksPath
    ExpectedHead = $ExpectedHead
    MemoryIndex = $MemoryIndex
    StateUpdate = $normalizedStateUpdate
    ChangedPath = $normalizedChangedPath
    BaseHead = $BaseHead
    StateEffectMatrixPath = $StateEffectMatrixPath
    LinterPath = $LinterPath
    EngramoryRoot = $EngramoryRoot
    PythonPath = $PythonPath
}

function Write-Usage {
    Write-Output 'USAGE:'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Help'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Mode Snapshot -RepoRoot <absolute-repo> [-BaseHead <commit>]'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Mode UpdateState -RepoRoot <absolute-repo> -StateUpdate "ACTIVE:KEY=value||QA:KEY=value"'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Mode GatePlan -RepoRoot <absolute-repo> -ChangedPath "path1||path2" [-StateEffectMatrixPath <absolute-file>]'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Mode Handoff -RepoRoot <absolute-repo> -ExpectedHead <commit> [-MemoryIndex <absolute-MEMORY.md>]'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Mode QaReady -RepoRoot <absolute-repo> [-MemoryIndex <absolute-MEMORY.md>]'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-workflow.ps1 -Mode Closeout -RepoRoot <absolute-repo> -ExpectedHead <commit> -MemoryIndex <absolute-MEMORY.md>'
    Write-Output 'MODES:'
    Write-Output '  Snapshot: worktree, branch, HEAD, cleanliness, ACTIVE/QA tokens, HANDOFF_OBJECT and changed paths.'
    Write-Output '  UpdateState: atomically updates one unique ACTIVE or QA marker-block key; rejects missing/duplicate/empty/invalid state.'
    Write-Output '  GatePlan: FAST for docs, CANDIDATE for control-plane scripts/tests, FULL for product/architecture paths; unknown paths fail closed.'
    Write-Output '  Handoff / QaReady: delegate v2-handoff-lint.ps1 and preserve its PASS/FAIL tokens and exit code.'
    Write-Output '  Closeout: Handoff -> Engramory checker -> Engramory doctor -> final Git clean. This is a control-plane PASS only.'
    Write-Output 'STATE FAILURE CODES:'
    Write-Output '  STATE_MARKER_MISSING, STATE_MARKER_DUPLICATE, STATE_KEY_MISSING, STATE_KEY_DUPLICATE, STATE_KEY_EMPTY,'
    Write-Output '  STATE_ENCODING_INVALID, STATE_ROLE_INVALID, STATE_ROLE_RETIRED, STATE_OWNER_MISMATCH, STATE_PATH_INVALID.'
    Write-Output 'GATE FAILURE CODES:'
    Write-Output '  WORKFLOW_GATE_PLAN_EMPTY, WORKFLOW_GATE_PATH_UNMAPPED, WORKFLOW_STATE_EFFECT_MATRIX_MISSING, WORKFLOW_STATE_EFFECT_COVERAGE_GAP.'
    Write-Output 'CLOSEOUT FAILURE CODES:'
    Write-Output '  CLOSEOUT_HANDOFF_FAILED, CLOSEOUT_ENGRAMORY_CHECK_FAILED, CLOSEOUT_ENGRAMORY_DOCTOR_FAILED, CLOSEOUT_REPO_DIRTY, CLOSEOUT_HEAD_MISMATCH.'
    Write-Output 'EXIT CODES: 0 success; 2 policy/gate failure; child nonzero is passed through; 3 unexpected error; 64 argument error.'
}

function Write-Failure {
    param(
        [Parameter(Mandatory)][string]$Code,
        [AllowEmptyString()][string]$Subject = '',
        [int]$ExitCode = 2
    )

    Write-Output ("FAIL:{0}:{1}" -f $Code, $Subject)
    Write-Output 'FAIL'
    exit $ExitCode
}

function Initialize-Repository {
    if ([string]::IsNullOrWhiteSpace($script:options.RepoRootInput)) {
        Write-Usage
        Write-Failure -Code 'REPO_ROOT_REQUIRED' -ExitCode 64
    }

    try {
        $script:repositoryPath = [System.IO.Path]::GetFullPath($script:options.RepoRootInput)
    }
    catch {
        Write-Failure -Code 'REPO_ROOT_INVALID' -Subject $RepoRoot -ExitCode 64
    }
    if (-not (Test-Path -LiteralPath $script:repositoryPath -PathType Container)) {
        Write-Failure -Code 'REPO_ROOT_MISSING' -Subject $script:repositoryPath
    }

    $gitCommand = Get-Command git -CommandType Application | Select-Object -First 1
    if ($null -eq $gitCommand) {
        Write-Failure -Code 'GIT_NOT_FOUND' -ExitCode 3
    }
    $script:gitPath = $gitCommand.Source

    $resolvedTasks = if ([System.IO.Path]::IsPathRooted($script:options.TasksPathInput)) {
        [System.IO.Path]::GetFullPath($script:options.TasksPathInput)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $script:repositoryPath $script:options.TasksPathInput))
    }
    $rootPrefix = $script:repositoryPath.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTasks.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Write-Failure -Code 'STATE_PATH_INVALID' -Subject $resolvedTasks
    }
    if (-not (Test-Path -LiteralPath $resolvedTasks -PathType Leaf)) {
        Write-Failure -Code 'TASKS_PATH_MISSING' -Subject $resolvedTasks
    }
    $script:tasksFile = $resolvedTasks
}

function Invoke-Git {
    param(
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $script:gitPath -C $script:repositoryPath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { [string]$_ })
        Raw = ($output -join "`n")
    }
}

function Read-LfUtf8File {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$InvalidCode
    )

    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
    }
    catch {
        Write-Failure -Code $InvalidCode -Subject $Path
    }
    if ($bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF) {
        Write-Failure -Code $InvalidCode -Subject "$Path#BOM"
    }

    try {
        $text = $utf8Strict.GetString($bytes)
    }
    catch {
        Write-Failure -Code $InvalidCode -Subject "$Path#UTF8"
    }
    if ($text.Contains("`r")) {
        Write-Failure -Code $InvalidCode -Subject "$Path#CR"
    }
    return $text
}

function Get-MarkedBlock {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][string]$BeginMarker,
        [Parameter(Mandatory)][string]$EndMarker,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$MissingCode,
        [Parameter(Mandatory)][string]$DuplicateCode
    )

    $beginCount = [regex]::Matches($Text, [regex]::Escape($BeginMarker), [System.Text.RegularExpressions.RegexOptions]::None).Count
    $endCount = [regex]::Matches($Text, [regex]::Escape($EndMarker), [System.Text.RegularExpressions.RegexOptions]::None).Count
    if ($beginCount -eq 0 -or $endCount -eq 0) {
        Write-Failure -Code $MissingCode -Subject $Label
    }
    if ($beginCount -ne 1 -or $endCount -ne 1) {
        Write-Failure -Code $DuplicateCode -Subject $Label
    }

    $beginIndex = $Text.IndexOf($BeginMarker, [System.StringComparison]::Ordinal)
    $endIndex = $Text.IndexOf($EndMarker, [System.StringComparison]::Ordinal)
    if ($beginIndex -lt 0 -or $endIndex -lt 0 -or $beginIndex -ge $endIndex) {
        Write-Failure -Code $DuplicateCode -Subject "$Label#ORDER"
    }

    $contentStart = $beginIndex + $BeginMarker.Length
    $contentLength = $endIndex - $contentStart
    return [pscustomobject]@{
        Label = $Label
        ContentStart = $contentStart
        ContentLength = $contentLength
        Content = $Text.Substring($contentStart, $contentLength)
    }
}

function Get-TokenEntries {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text
    )

    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($match in [regex]::Matches($Text, '(?m)^[ \t]*-[ \t]*(?<key>[A-Za-z0-9_]+)[ \t]*=(?<value>[^\n]*)$')) {
        $entries.Add([pscustomobject]@{
            Key = $match.Groups['key'].Value
            Value = $match.Groups['value'].Value.Trim()
        })
    }
    return @($entries)
}

function Get-SingleTokenValue {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Block,
        [Parameter(Mandatory)][string]$Key,
        [string]$MissingCode = 'STATE_KEY_MISSING',
        [string]$DuplicateCode = 'STATE_KEY_DUPLICATE'
    )

    $matches = @([regex]::Matches(
        $Block,
        ('(?m)^[ \t]*-[ \t]*{0}[ \t]*=[^\n]*$' -f [regex]::Escape($Key)),
        [System.Text.RegularExpressions.RegexOptions]::None
    ))
    if ($matches.Count -eq 0) {
        Write-Failure -Code $MissingCode -Subject $Key
    }
    if ($matches.Count -gt 1) {
        Write-Failure -Code $DuplicateCode -Subject $Key
    }
    $valueMatch = [regex]::Match(
        $matches[0].Value,
        ('^[ \t]*-[ \t]*{0}[ \t]*=(?<value>[^\n]*)$' -f [regex]::Escape($Key))
    )
    return $valueMatch.Groups['value'].Value.Trim()
}

function Get-ChangedPaths {
    param(
        [Parameter(Mandatory)][string]$BaseCommit
    )

    $commitCheck = Invoke-Git -Arguments @('rev-parse', '--verify', "$BaseCommit^{commit}")
    if ($commitCheck.ExitCode -ne 0) {
        Write-Failure -Code 'BASE_HEAD_INVALID' -Subject $BaseCommit
    }

    $diff = Invoke-Git -Arguments @('diff', '--name-only', $BaseCommit, '--')
    if ($diff.ExitCode -ne 0) {
        Write-Failure -Code 'GIT_DIFF_FAILED' -Subject $BaseCommit -ExitCode 3
    }
    $untracked = Invoke-Git -Arguments @('ls-files', '--others', '--exclude-standard')
    if ($untracked.ExitCode -ne 0) {
        Write-Failure -Code 'GIT_LS_FILES_FAILED' -ExitCode 3
    }

    return @(
        @($diff.Lines) + @($untracked.Lines) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { $_.Replace('\', '/') } |
            Sort-Object -Unique
    )
}

function Invoke-Snapshot {
    Initialize-Repository
    $text = Read-LfUtf8File -Path $script:tasksFile -InvalidCode 'STATE_ENCODING_INVALID'
    $active = Get-MarkedBlock -Text $text -BeginMarker '<!-- V2_HANDOFF_ACTIVE_BEGIN -->' `
        -EndMarker '<!-- V2_HANDOFF_ACTIVE_END -->' -Label 'ACTIVE' `
        -MissingCode 'STATE_MARKER_MISSING' -DuplicateCode 'STATE_MARKER_DUPLICATE'
    $qa = Get-MarkedBlock -Text $text -BeginMarker '<!-- V2_QA_READY_BEGIN -->' `
        -EndMarker '<!-- V2_QA_READY_END -->' -Label 'QA' `
        -MissingCode 'STATE_MARKER_MISSING' -DuplicateCode 'STATE_MARKER_DUPLICATE'
    $handoffObject = Get-MarkedBlock -Text $text -BeginMarker '<!-- V2_HANDOFF_OBJECT_BEGIN -->' `
        -EndMarker '<!-- V2_HANDOFF_OBJECT_END -->' -Label 'HANDOFF_OBJECT' `
        -MissingCode 'HANDOFF_OBJECT_MARKER_MISSING' -DuplicateCode 'HANDOFF_OBJECT_MARKER_DUPLICATE'

    $branch = Invoke-Git -Arguments @('branch', '--show-current')
    $head = Invoke-Git -Arguments @('rev-parse', 'HEAD')
    $status = Invoke-Git -Arguments @('status', '--short', '--untracked-files=all')
    if ($branch.ExitCode -ne 0 -or $head.ExitCode -ne 0 -or $status.ExitCode -ne 0) {
        Write-Failure -Code 'SNAPSHOT_GIT_FAILED' -ExitCode 3
    }

    $baseCommit = if (-not [string]::IsNullOrWhiteSpace($script:options.BaseHead)) {
        $script:options.BaseHead
    }
    else {
        Get-SingleTokenValue -Block $handoffObject.Content -Key 'BASE_HEAD'
    }
    $changed = @(Get-ChangedPaths -BaseCommit $baseCommit)

    Write-Output "WORKTREE=$script:repositoryPath"
    Write-Output ("BRANCH={0}" -f (@($branch.Lines) -join ''))
    Write-Output ("HEAD={0}" -f (@($head.Lines) -join ''))
    Write-Output ("CLEAN={0}" -f $(if (@($status.Lines).Count -eq 0) { 'YES' } else { 'NO' }))
    Write-Output 'ACTIVE_MARKERS=UNIQUE'
    Write-Output 'QA_READY_MARKERS=UNIQUE'
    Write-Output 'HANDOFF_OBJECT=UNIQUE'
    foreach ($entry in @(Get-TokenEntries -Text $active.Content)) {
        Write-Output ("ACTIVE.{0}={1}" -f $entry.Key, $entry.Value)
    }
    foreach ($entry in @(Get-TokenEntries -Text $qa.Content)) {
        Write-Output ("QA.{0}={1}" -f $entry.Key, $entry.Value)
    }
    foreach ($entry in @(Get-TokenEntries -Text $handoffObject.Content)) {
        Write-Output ("HANDOFF_OBJECT.{0}={1}" -f $entry.Key, $entry.Value)
    }
    Write-Output ("CHANGED_PATHS_COUNT={0}" -f $changed.Count)
    foreach ($path in $changed) {
        Write-Output "CHANGED_PATH=$path"
    }
}

function Test-RoleValue {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Subject
    )

    if ($Value -cnotmatch '^[A-Z][A-Z0-9_]*$') {
        Write-Failure -Code 'STATE_ROLE_INVALID' -Subject $Subject
    }
}

function Assert-UniqueSingleTokenKeys {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Block,
        [Parameter(Mandatory)][string]$BlockName
    )

    $seenKeys = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($entry in @(Get-TokenEntries -Text $Block)) {
        if ($entry.Key -ceq 'EVIDENCE' -or $entry.Key -ceq 'RETIRED_ROLE') {
            continue
        }
        if (-not $seenKeys.Add($entry.Key)) {
            Write-Failure -Code 'STATE_KEY_DUPLICATE' -Subject "$BlockName`:$($entry.Key)"
        }
    }
}

function Invoke-UpdateState {
    Initialize-Repository
    if ($script:options.StateUpdate.Count -eq 0) {
        Write-Usage
        Write-Failure -Code 'STATE_UPDATE_EMPTY' -ExitCode 64
    }

    $text = Read-LfUtf8File -Path $script:tasksFile -InvalidCode 'STATE_ENCODING_INVALID'
    $active = Get-MarkedBlock -Text $text -BeginMarker '<!-- V2_HANDOFF_ACTIVE_BEGIN -->' `
        -EndMarker '<!-- V2_HANDOFF_ACTIVE_END -->' -Label 'ACTIVE' `
        -MissingCode 'STATE_MARKER_MISSING' -DuplicateCode 'STATE_MARKER_DUPLICATE'
    $qa = Get-MarkedBlock -Text $text -BeginMarker '<!-- V2_QA_READY_BEGIN -->' `
        -EndMarker '<!-- V2_QA_READY_END -->' -Label 'QA' `
        -MissingCode 'STATE_MARKER_MISSING' -DuplicateCode 'STATE_MARKER_DUPLICATE'

    $blocks = @{
        ACTIVE = $active.Content
        QA = $qa.Content
    }
    Assert-UniqueSingleTokenKeys -Block $blocks['ACTIVE'] -BlockName 'ACTIVE'
    Assert-UniqueSingleTokenKeys -Block $blocks['QA'] -BlockName 'QA'

    $seenUpdates = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $roleKeys = @('NEXT_ROLE', 'MANUAL_OWNER', 'RETIRED_ROLE')

    foreach ($specification in $script:options.StateUpdate) {
        $match = [regex]::Match($specification, '^(?<block>ACTIVE|QA):(?<key>[A-Za-z0-9_]+)=(?<value>.*)$')
        if (-not $match.Success) {
            Write-Failure -Code 'STATE_UPDATE_INVALID' -Subject $specification
        }
        $blockName = $match.Groups['block'].Value
        $key = $match.Groups['key'].Value
        $value = $match.Groups['value'].Value
        $identity = "$blockName`:$key"
        if (-not $seenUpdates.Add($identity)) {
            Write-Failure -Code 'STATE_KEY_DUPLICATE' -Subject $identity
        }
        if ([string]::IsNullOrWhiteSpace($value)) {
            Write-Failure -Code 'STATE_KEY_EMPTY' -Subject $identity
        }
        if ($value.Contains("`r") -or $value.Contains("`n")) {
            Write-Failure -Code 'STATE_ENCODING_INVALID' -Subject $identity
        }
        if ($roleKeys -contains $key) {
            Test-RoleValue -Value $value -Subject $identity
        }

        $linePattern = ('(?m)^[ \t]*-[ \t]*{0}[ \t]*=[^\n]*$' -f [regex]::Escape($key))
        $keyMatches = @([regex]::Matches($blocks[$blockName], $linePattern))
        if ($keyMatches.Count -eq 0) {
            Write-Failure -Code 'STATE_KEY_MISSING' -Subject $identity
        }
        if ($keyMatches.Count -gt 1) {
            Write-Failure -Code 'STATE_KEY_DUPLICATE' -Subject $identity
        }

        $blocks[$blockName] = [regex]::Replace(
            $blocks[$blockName],
            $linePattern,
            {
                param($lineMatch)
                $prefixMatch = [regex]::Match($lineMatch.Value, '^(?<prefix>[ \t]*-[ \t]*[A-Za-z0-9_]+[ \t]*=)')
                return $prefixMatch.Groups['prefix'].Value + $value
            }
        )
    }

    $nextRole = Get-SingleTokenValue -Block $blocks['ACTIVE'] -Key 'NEXT_ROLE'
    $manualOwner = Get-SingleTokenValue -Block $blocks['QA'] -Key 'MANUAL_OWNER'
    Test-RoleValue -Value $nextRole -Subject 'ACTIVE:NEXT_ROLE'
    Test-RoleValue -Value $manualOwner -Subject 'QA:MANUAL_OWNER'
    if ($nextRole -cne $manualOwner) {
        Write-Failure -Code 'STATE_OWNER_MISMATCH' -Subject $manualOwner
    }

    $retiredRoles = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in @(Get-TokenEntries -Text $blocks['ACTIVE'])) {
        if ($entry.Key -cne 'RETIRED_ROLE') {
            continue
        }
        foreach ($role in ($entry.Value -split '[;,]')) {
            $trimmed = $role.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
                $retiredRoles.Add($trimmed)
            }
        }
    }
    foreach ($retiredRole in $retiredRoles) {
        if ($nextRole.Equals($retiredRole, [System.StringComparison]::Ordinal)) {
            Write-Failure -Code 'STATE_ROLE_RETIRED' -Subject $nextRole
        }
        if ($manualOwner.Equals($retiredRole, [System.StringComparison]::Ordinal)) {
            Write-Failure -Code 'STATE_ROLE_RETIRED' -Subject $manualOwner
        }
    }

    $updatedText = $text.Remove($qa.ContentStart, $qa.ContentLength).Insert($qa.ContentStart, $blocks['QA'])
    $updatedText = $updatedText.Remove($active.ContentStart, $active.ContentLength).Insert($active.ContentStart, $blocks['ACTIVE'])

    $parent = Split-Path -Parent $script:tasksFile
    $tempPath = Join-Path $parent ('.v2-workflow-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [System.IO.File]::WriteAllText($tempPath, $updatedText, $utf8NoBom)
        $writtenBytes = [System.IO.File]::ReadAllBytes($tempPath)
        if (($writtenBytes.Length -ge 3 -and
                $writtenBytes[0] -eq 0xEF -and
                $writtenBytes[1] -eq 0xBB -and
                $writtenBytes[2] -eq 0xBF) -or
            ([System.Text.Encoding]::UTF8.GetString($writtenBytes).Contains("`r"))) {
            Write-Failure -Code 'STATE_ENCODING_INVALID' -Subject "$script:tasksFile#OUTPUT"
        }
        [System.IO.File]::Move($tempPath, $script:tasksFile, $true)
    }
    catch {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force
        }
        throw
    }

    Write-Output 'STATE_UPDATE=PASS'
    Write-Output ("UPDATED={0}" -f $script:options.StateUpdate.Count)
    Write-Output "TASKS=$script:tasksFile"
}

function Test-CanonicalRelativePath {
    param([Parameter(Mandatory)][string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or
        $Path.StartsWith('/') -or
        $Path.EndsWith('/') -or
        $Path.Contains('//') -or
        $Path.Contains('\') -or
        ($Path -split '/') -contains '.' -or
        ($Path -split '/') -contains '..') {
        return $false
    }
    return $true
}

function Test-StateEffectMatrix {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Write-Failure -Code 'WORKFLOW_STATE_EFFECT_MATRIX_MISSING' -Subject $Path
    }
    $text = Read-LfUtf8File -Path $Path -InvalidCode 'WORKFLOW_STATE_EFFECT_MATRIX_INVALID'
    $categories = @(
        'SUCCESS'
        'EMPTY_RESULT'
        'PERMISSION'
        'TIMEOUT_ERROR'
        'CANCEL'
        'RETRY_DUPLICATE'
        'RESTART_RECOVERY'
    )
    foreach ($category in $categories) {
        $pattern = ('(?m)^[ \t]*(?:[-*][ \t]*)?{0}[ \t]*=[ \t]*(?<value>[^\n]+)$' -f [regex]::Escape($category))
        $match = [regex]::Match($text, $pattern)
        if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups['value'].Value)) {
            Write-Failure -Code 'WORKFLOW_STATE_EFFECT_COVERAGE_GAP' -Subject $category
        }
    }
}

function Invoke-GatePlan {
    Initialize-Repository
    $paths = @($script:options.ChangedPath | ForEach-Object { $_.Replace('\', '/') } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($paths.Count -eq 0) {
        $baseCommit = $script:options.BaseHead
        if ([string]::IsNullOrWhiteSpace($baseCommit)) {
            $text = Read-LfUtf8File -Path $script:tasksFile -InvalidCode 'STATE_ENCODING_INVALID'
            $handoffObject = Get-MarkedBlock -Text $text -BeginMarker '<!-- V2_HANDOFF_OBJECT_BEGIN -->' `
                -EndMarker '<!-- V2_HANDOFF_OBJECT_END -->' -Label 'HANDOFF_OBJECT' `
                -MissingCode 'HANDOFF_OBJECT_MARKER_MISSING' -DuplicateCode 'HANDOFF_OBJECT_MARKER_DUPLICATE'
            $baseCommit = Get-SingleTokenValue -Block $handoffObject.Content -Key 'BASE_HEAD'
        }
        $paths = @(Get-ChangedPaths -BaseCommit $baseCommit)
    }
    if ($paths.Count -eq 0) {
        Write-Failure -Code 'WORKFLOW_GATE_PLAN_EMPTY' -Subject $null
    }

    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $lane = 'FAST'
    $productModuleRoots = @(
        'app'
        'core'
        'feature'
        'domain'
        'data'
        'setup'
        'settings'
        'modes'
        'player'
        'build-logic'
        'gradle'
    )
    foreach ($path in $paths) {
        if (-not (Test-CanonicalRelativePath -Path $path)) {
            Write-Failure -Code 'WORKFLOW_GATE_PATH_UNMAPPED' -Subject $path
        }
        if (-not $seen.Add($path)) {
            Write-Failure -Code 'WORKFLOW_GATE_PATH_DUPLICATE' -Subject $path
        }

        $isFast = $path -cin @(
            'AGENTS.md'
            'GLOSSARY.md'
            'GLOSSARY-MAP.md'
        ) -or $path -cmatch '^docs/.+\.md$'
        $isCandidate = $path -cin @(
            'tools/architecture/v2-workflow.ps1'
            'tools/architecture/v2-workflow.tests.ps1'
            'tools/architecture/v2-handoff-lint.ps1'
            'tools/architecture/v2-handoff-lint.tests.ps1'
        )
        $isProductModule = $false
        foreach ($moduleRoot in $productModuleRoots) {
            if ($path.StartsWith("$moduleRoot/", [System.StringComparison]::Ordinal)) {
                $isProductModule = $true
                break
            }
        }
        $isFull = $isProductModule -or
            $path -cin @('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties')

        if ($isFull) {
            $lane = 'FULL'
        }
        elseif ($isCandidate -and $lane -ne 'FULL') {
            $lane = 'CANDIDATE'
        }
        elseif (-not $isFast -and -not $isCandidate) {
            Write-Failure -Code 'WORKFLOW_GATE_PATH_UNMAPPED' -Subject $path
        }
    }

    if ($lane -eq 'FULL') {
        if ([string]::IsNullOrWhiteSpace($script:options.StateEffectMatrixPath)) {
            Write-Failure -Code 'WORKFLOW_STATE_EFFECT_MATRIX_MISSING' -Subject $null
        }
        $matrixPath = [System.IO.Path]::GetFullPath($script:options.StateEffectMatrixPath)
        Test-StateEffectMatrix -Path $matrixPath
        Write-Output "STATE_EFFECT_MATRIX=PASS:$matrixPath"
    }
    else {
        Write-Output 'STATE_EFFECT_MATRIX=NOT_REQUIRED_CONTROL_PLANE'
    }

    $workflowPath = Join-Path $script:repositoryPath 'tools\architecture\v2-workflow.ps1'
    $workflowTests = Join-Path $script:repositoryPath 'tools\architecture\v2-workflow.tests.ps1'
    $handoffTests = Join-Path $script:repositoryPath 'tools\architecture\v2-handoff-lint.tests.ps1'
    $snapshot = "pwsh -NoProfile -ExecutionPolicy Bypass -File `"$workflowPath`" -Mode Snapshot -RepoRoot `"$script:repositoryPath`""
    $workflowGateTests = "pwsh -NoProfile -ExecutionPolicy Bypass -File `"$workflowTests`" -Filter `"GatePlan*`""
    $workflowFullTests = "pwsh -NoProfile -ExecutionPolicy Bypass -File `"$workflowTests`""
    $handoffFullTests = "pwsh -NoProfile -ExecutionPolicy Bypass -File `"$handoffTests`""

    Write-Output "GATE_LANE=$lane"
    foreach ($path in $paths) {
        Write-Output "GATE_PATH=$path"
    }
    if ($lane -eq 'FAST') {
        Write-Output "GATE_COMMAND=FAST:1=$workflowGateTests"
        Write-Output "GATE_COMMAND=FAST:2=$snapshot"
    }
    elseif ($lane -eq 'CANDIDATE') {
        Write-Output "GATE_COMMAND=CANDIDATE:1=$workflowFullTests"
        Write-Output "GATE_COMMAND=CANDIDATE:2=$handoffFullTests"
    }
    else {
        Write-Output "GATE_COMMAND=FULL:1=$workflowFullTests"
        Write-Output "GATE_COMMAND=FULL:2=$handoffFullTests"
        $gradleCommand = 'pwsh -NoProfile -ExecutionPolicy Bypass -Command "& { $env:JAVA_HOME=''F:\Develop\Android\Android Studio\jbr''; & ''__REPO__\gradlew.bat'' '':app:phone:assembleDebug'' ''--console=plain'' }"'.Replace(
            '__REPO__',
            $script:repositoryPath
        )
        Write-Output "GATE_COMMAND=FULL:3=$gradleCommand"
    }
    Write-Output 'HARNESS_RETRY_POLICY=SAME_OWNER_5_TO_10_MIN_ONE_RETRY'
}

function Get-LinterFilePath {
    if (-not [string]::IsNullOrWhiteSpace($script:options.LinterPath)) {
        $candidate = if ([System.IO.Path]::IsPathRooted($script:options.LinterPath)) {
            [System.IO.Path]::GetFullPath($script:options.LinterPath)
        }
        else {
            [System.IO.Path]::GetFullPath((Join-Path $script:repositoryPath $script:options.LinterPath))
        }
    }
    else {
        $candidate = Join-Path $script:repositoryPath 'tools\architecture\v2-handoff-lint.ps1'
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        Write-Failure -Code 'LINTER_NOT_FOUND' -Subject $candidate -ExitCode 3
    }
    return $candidate
}

function Invoke-LinterMode {
    param(
        [Parameter(Mandatory)][ValidateSet('Handoff', 'QaReady')][string]$LinterMode,
        [switch]$PassExit
    )

    Initialize-Repository
    $linter = Get-LinterFilePath
    $arguments = @(
        '-NoProfile'
        '-ExecutionPolicy'
        'Bypass'
        '-File'
        $linter
        '-Mode'
        $LinterMode
        '-RepoRoot'
        $script:repositoryPath
        '-TasksPath'
        $script:options.TasksPathInput
    )
    if ($LinterMode -eq 'Handoff') {
        if ([string]::IsNullOrWhiteSpace($script:options.ExpectedHead)) {
            Write-Usage
            Write-Failure -Code 'EXPECTED_HEAD_REQUIRED' -ExitCode 64
        }
        $arguments += @('-ExpectedHead', $script:options.ExpectedHead)
    }
    if (-not [string]::IsNullOrWhiteSpace($script:options.MemoryIndex)) {
        $arguments += @('-MemoryIndex', $script:options.MemoryIndex)
    }

    $output = @(& $script:pwshPath @arguments 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if ($PassExit) {
        return [pscustomobject]@{
            ExitCode = $exitCode
            Lines = $output
        }
    }
    foreach ($line in $output) {
        Write-Output $line
    }
    exit $exitCode
}

function Invoke-ChildProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = @(& $FilePath @Arguments 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Lines = $output
    }
}

function Write-ChildOutput {
    param([Parameter(Mandatory)]$Result)
    foreach ($line in $Result.Lines) {
        Write-Output $line
    }
}

function Invoke-Closeout {
    Initialize-Repository
    if ([string]::IsNullOrWhiteSpace($script:options.ExpectedHead)) {
        Write-Usage
        Write-Failure -Code 'EXPECTED_HEAD_REQUIRED' -ExitCode 64
    }
    if ([string]::IsNullOrWhiteSpace($script:options.MemoryIndex)) {
        Write-Usage
        Write-Failure -Code 'MEMORY_INDEX_REQUIRED' -ExitCode 64
    }
    $memoryIndexPath = [System.IO.Path]::GetFullPath($script:options.MemoryIndex)
    if (-not (Test-Path -LiteralPath $memoryIndexPath -PathType Leaf)) {
        Write-Failure -Code 'MEMORY_INDEX_MISSING' -Subject $memoryIndexPath -ExitCode 64
    }
    $memoryRoot = Split-Path -Parent $memoryIndexPath
    $engramoryBase = if ([string]::IsNullOrWhiteSpace($script:options.EngramoryRoot)) {
        Join-Path $env:USERPROFILE '.agents\skills\engramory'
    }
    else {
        $script:options.EngramoryRoot
    }
    $engramoryBase = [System.IO.Path]::GetFullPath($engramoryBase)
    $checker = Join-Path $engramoryBase 'tools\engramory_check.py'
    $doctor = Join-Path $engramoryBase 'tools\engramory_doctor.py'
    if (-not (Test-Path -LiteralPath $checker -PathType Leaf)) {
        Write-Failure -Code 'ENGRAMORY_CHECKER_MISSING' -Subject $checker -ExitCode 3
    }
    if (-not (Test-Path -LiteralPath $doctor -PathType Leaf)) {
        Write-Failure -Code 'ENGRAMORY_DOCTOR_MISSING' -Subject $doctor -ExitCode 3
    }
    $pythonExecutable = if ([string]::IsNullOrWhiteSpace($script:options.PythonPath)) {
        $pythonCommand = Get-Command python -CommandType Application | Select-Object -First 1
        if ($null -eq $pythonCommand) {
            Write-Failure -Code 'PYTHON_NOT_FOUND' -ExitCode 3
        }
        $pythonCommand.Source
    }
    else {
        $script:options.PythonPath
    }

    Write-Output 'CLOSEOUT_STEP=HANDOFF'
    $handoff = Invoke-LinterMode -LinterMode Handoff -PassExit
    Write-ChildOutput -Result $handoff
    if ($handoff.ExitCode -ne 0) {
        Write-Failure -Code 'CLOSEOUT_HANDOFF_FAILED' -Subject ([string]$handoff.ExitCode) -ExitCode $handoff.ExitCode
    }

    Write-Output 'CLOSEOUT_STEP=ENGRAMORY_CHECK'
    $checkResult = Invoke-ChildProcess -FilePath $pythonExecutable -Arguments @($checker, $memoryIndexPath)
    Write-ChildOutput -Result $checkResult
    if ($checkResult.ExitCode -ne 0) {
        Write-Failure -Code 'CLOSEOUT_ENGRAMORY_CHECK_FAILED' -Subject ([string]$checkResult.ExitCode) -ExitCode $checkResult.ExitCode
    }

    Write-Output 'CLOSEOUT_STEP=ENGRAMORY_DOCTOR'
    $doctorResult = Invoke-ChildProcess -FilePath $pythonExecutable -Arguments @($doctor, $memoryRoot)
    Write-ChildOutput -Result $doctorResult
    if ($doctorResult.ExitCode -ne 0) {
        Write-Failure -Code 'CLOSEOUT_ENGRAMORY_DOCTOR_FAILED' -Subject ([string]$doctorResult.ExitCode) -ExitCode $doctorResult.ExitCode
    }

    Write-Output 'CLOSEOUT_STEP=FINAL_GIT_CLEAN'
    $headResult = Invoke-Git -Arguments @('rev-parse', 'HEAD')
    if ($headResult.ExitCode -ne 0) {
        Write-Failure -Code 'CLOSEOUT_GIT_FAILED' -ExitCode 3
    }
    $actualHead = (@($headResult.Lines) -join '').Trim()
    if ($actualHead -cne $script:options.ExpectedHead) {
        Write-Failure -Code 'CLOSEOUT_HEAD_MISMATCH' -Subject $actualHead
    }
    $statusResult = Invoke-Git -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    if ($statusResult.ExitCode -ne 0) {
        Write-Failure -Code 'CLOSEOUT_GIT_FAILED' -ExitCode 3
    }
    if (@($statusResult.Lines).Count -ne 0) {
        Write-Failure -Code 'CLOSEOUT_REPO_DIRTY' -Subject $script:repositoryPath
    }

    Write-Output 'CLOSEOUT_CONTROL_PLANE=PASS'
    Write-Output 'PRODUCT_QA=NOT_ASSESSED'
    Write-Output 'INTEGRATION=NOT_ASSESSED'
    Write-Output 'RELEASE=NOT_ASSESSED'
}

if ($Help) {
    Write-Usage
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Mode)) {
    Write-Usage
    Write-Failure -Code 'MODE_REQUIRED' -ExitCode 64
}

try {
    switch ($Mode) {
        'Snapshot' { Invoke-Snapshot }
        'UpdateState' { Invoke-UpdateState }
        'GatePlan' { Invoke-GatePlan }
        'Handoff' { Invoke-LinterMode -LinterMode Handoff }
        'QaReady' { Invoke-LinterMode -LinterMode QaReady }
        'Closeout' { Invoke-Closeout }
    }
    exit 0
}
catch {
    Write-Output "FAIL:UNEXPECTED_ERROR:$($_.Exception.Message)"
    Write-Output 'FAIL'
    exit 3
}
