[CmdletBinding()]
param(
    [ValidateSet('Handoff', 'QaReady')]
    [string]$Mode,
    [string]$RepoRoot,
    [string]$ExpectedHead,
    [string]$TasksPath = 'docs/TASKS.md',
    [string]$MemoryIndex,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$script:failures = [System.Collections.Generic.List[object]]::new()
$script:warnings = [System.Collections.Generic.List[object]]::new()
$script:evidence = [System.Collections.Generic.List[string]]::new()

function Write-Usage {
    Write-Output 'USAGE:'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-handoff-lint.ps1 -Mode Handoff -RepoRoot <absolute-repo> -ExpectedHead <40-or-64-hex> [-TasksPath docs/TASKS.md] [-MemoryIndex <absolute-MEMORY.md>]'
    Write-Output '  pwsh -NoProfile -File <repo>/tools/architecture/v2-handoff-lint.ps1 -Mode QaReady -RepoRoot <absolute-repo> [-TasksPath docs/TASKS.md] [-MemoryIndex <absolute-MEMORY.md>]'
    Write-Output 'OUTPUT:'
    Write-Output '  Handoff success: HANDOFF_LINT=PASS'
    Write-Output '  QaReady success: QA_READY_LINT=PASS'
    Write-Output '  Failure: FAIL:<CODE>:<path>, nonzero exit'
}

if ($Help) {
    Write-Usage
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Mode)) {
    Write-Usage
    Write-Output 'FAIL:MODE_REQUIRED:'
    exit 64
}
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    Write-Usage
    Write-Output 'FAIL:REPO_ROOT_REQUIRED:'
    exit 64
}

function Add-Failure {
    param(
        [Parameter(Mandatory)][string]$Code,
        [AllowEmptyString()][string]$Subject = '',
        [AllowEmptyString()][string]$Detail = ''
    )

    $script:failures.Add([pscustomobject]@{
        Code = $Code
        Subject = $Subject
        Detail = $Detail
    })
}

function Add-Warning {
    param(
        [Parameter(Mandatory)][string]$Code,
        [AllowEmptyString()][string]$Subject = '',
        [AllowEmptyString()][string]$Detail = ''
    )

    $script:warnings.Add([pscustomobject]@{
        Code = $Code
        Subject = $Subject
        Detail = $Detail
    })
}

function Read-Utf8Text {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required UTF-8 file not found: $Path"
    }
    return [System.IO.File]::ReadAllText($Path, $utf8Strict)
}

function Test-PathWithin {
    param(
        [Parameter(Mandatory)][string]$Child,
        [Parameter(Mandatory)][string]$Parent
    )

    $childFull = [System.IO.Path]::GetFullPath($Child).TrimEnd('\', '/')
    $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    if ($childFull.Equals($parentFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    return $childFull.StartsWith(
        $parentFull + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    )
}

function Get-PathComponentStatus {
    param(
        [Parameter(Mandatory)][string]$BasePath,
        [Parameter(Mandatory)][AllowEmptyString()][string]$RelativePath
    )

    $cursor = [System.IO.Path]::GetFullPath($BasePath)
    try {
        $baseItem = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
    }
    catch {
        return [pscustomobject]@{ Status = 'MISSING'; Path = $cursor }
    }
    if ($baseItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        return [pscustomobject]@{ Status = 'REPARSE'; Path = $cursor }
    }

    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        return [pscustomobject]@{ Status = 'SAFE'; Path = $cursor }
    }
    foreach ($segment in ($RelativePath.Replace('\', '/').Split('/'))) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') {
            return [pscustomobject]@{ Status = 'INVALID'; Path = $cursor }
        }
        $cursor = Join-Path $cursor $segment
        try {
            $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        }
        catch {
            return [pscustomobject]@{ Status = 'MISSING'; Path = $cursor }
        }
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            return [pscustomobject]@{ Status = 'REPARSE'; Path = $cursor }
        }
    }
    return [pscustomobject]@{ Status = 'SAFE'; Path = $cursor }
}

function Normalize-RelativePath {
    param([Parameter(Mandatory)][string]$Path)
    return $Path.Replace('\', '/')
}

function Get-MarkerCount {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Marker
    )

    return [regex]::Matches(
        $Text,
        [regex]::Escape($Marker),
        [System.Text.RegularExpressions.RegexOptions]::None
    ).Count
}

function Get-MarkedBlock {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$BeginMarker,
        [Parameter(Mandatory)][string]$EndMarker,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$Subject
    )

    $beginCount = Get-MarkerCount -Text $Text -Marker $BeginMarker
    $endCount = Get-MarkerCount -Text $Text -Marker $EndMarker
    if ($beginCount -eq 0 -and $endCount -eq 0) {
        Add-Failure -Code "${Label}_MARKER_MISSING" -Subject $Subject
        return ''
    }
    if ($beginCount -ne 1 -or $endCount -ne 1) {
        Add-Failure -Code "${Label}_MARKER_MULTIPLE" -Subject $Subject `
            -Detail "begin=$beginCount end=$endCount"
        return ''
    }

    $start = $Text.IndexOf($BeginMarker, [System.StringComparison]::Ordinal)
    $end = $Text.IndexOf($EndMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -lt $start) {
        Add-Failure -Code "${Label}_MARKER_MALFORMED" -Subject $Subject
        return ''
    }
    $start += $BeginMarker.Length
    $content = $Text.Substring($start, $end - $start)
    if ([string]::IsNullOrWhiteSpace($content)) {
        Add-Failure -Code "${Label}_EMPTY" -Subject $Subject
    }
    return $content
}

function Get-OptionalMarkedBlock {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$BeginMarker,
        [Parameter(Mandatory)][string]$EndMarker,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$Subject
    )

    $beginCount = Get-MarkerCount -Text $Text -Marker $BeginMarker
    $endCount = Get-MarkerCount -Text $Text -Marker $EndMarker
    if ($beginCount -eq 0 -and $endCount -eq 0) {
        return ''
    }
    return Get-MarkedBlock -Text $Text -BeginMarker $BeginMarker -EndMarker $EndMarker `
        -Label $Label -Subject $Subject
}

function Get-TokenValues {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Key
    )

    $pattern = '(?im)^[ \t]*(?:>[ \t]*)?(?:[-*][ \t]+)?' +
        [regex]::Escape($Key) +
        '[ \t]*=[ \t]*(.*?)[ \t]*$'
    $values = [System.Collections.Generic.List[string]]::new()
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $values.Add($match.Groups[1].Value.Replace("`r", '').Trim())
    }
    return @($values)
}

function Get-SingleToken {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$Subject,
        [switch]$Required
    )

    $values = @(Get-TokenValues -Text $Text -Key $Key)
    if ($values.Count -eq 0) {
        if ($Required) {
            Add-Failure -Code "${Label}_TOKEN_MISSING" -Subject "$Subject/$Key"
        }
        return ''
    }
    if ($values.Count -gt 1) {
        Add-Failure -Code "${Label}_TOKEN_DUPLICATE" -Subject "$Subject/$Key"
        return ''
    }
    if ([string]::IsNullOrWhiteSpace($values[0]) -or
        $values[0] -match '^<[^>]+>$|^(?i:TBD|TODO|PLACEHOLDER)$') {
        Add-Failure -Code "${Label}_TOKEN_EMPTY" -Subject "$Subject/$Key"
        return ''
    }
    return $values[0]
}

function Test-CanonicalRepoRelativePath {
    param([AllowEmptyString()][string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or
        $Path.Contains('\') -or
        $Path.StartsWith('/') -or
        $Path.EndsWith('/') -or
        $Path.Contains('//') -or
        $Path.Contains(':') -or
        [System.IO.Path]::IsPathRooted($Path)) {
        return $false
    }
    foreach ($segment in $Path.Split('/')) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') {
            return $false
        }
    }
    return $true
}

function Get-TestSelectorFacts {
    param([AllowEmptyString()][string]$Command)

    $selectorPattern = '(?i)(?<![A-Za-z0-9_-])--tests(?=$|[=\s])'
    $matches = [regex]::Matches($Command, $selectorPattern)
    if ($matches.Count -eq 0) {
        return [pscustomobject]@{ HasSelector = $false; Invalid = $false }
    }

    foreach ($match in $matches) {
        $cursor = $match.Index + $match.Length
        if ($cursor -lt $Command.Length -and $Command[$cursor] -eq '=') {
            $cursor++
        }
        else {
            while ($cursor -lt $Command.Length -and [char]::IsWhiteSpace($Command[$cursor])) {
                $cursor++
            }
        }

        if ($cursor -ge $Command.Length) {
            return [pscustomobject]@{ HasSelector = $true; Invalid = $true }
        }

        $first = $Command[$cursor]
        if ($first -eq '"' -or $first -eq "'") {
            if ($cursor + 1 -ge $Command.Length -or $Command[$cursor + 1] -eq $first) {
                return [pscustomobject]@{ HasSelector = $true; Invalid = $true }
            }
            $closing = $Command.IndexOf($first, $cursor + 1)
            if ($closing -lt 0) {
                return [pscustomobject]@{ HasSelector = $true; Invalid = $true }
            }
            $quotedSelector = $Command.Substring($cursor + 1, $closing - $cursor - 1)
            if ([string]::IsNullOrWhiteSpace($quotedSelector)) {
                return [pscustomobject]@{ HasSelector = $true; Invalid = $true }
            }
            continue
        }

        if ($first -eq '-' -or [char]::IsWhiteSpace($first)) {
            return [pscustomobject]@{ HasSelector = $true; Invalid = $true }
        }
    }
    return [pscustomobject]@{ HasSelector = $true; Invalid = $false }
}

function Get-NamedTokenValues {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][string]$Key
    )

    $pattern = '(?i)(?<![A-Za-z0-9_])' + [regex]::Escape($Key) +
        '[ \t]*=[ \t]*(?:"([^"]+)"|''([^'']+)''|([^;\s|]+))'
    $values = [System.Collections.Generic.List[string]]::new()
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $value = if (-not [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) {
            $match.Groups[1].Value
        }
        elseif (-not [string]::IsNullOrWhiteSpace($match.Groups[2].Value)) {
            $match.Groups[2].Value
        }
        else {
            $match.Groups[3].Value
        }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $values.Add($value.Trim())
        }
    }
    return @($values)
}

function Test-WorkflowRoleContract {
    param(
        [AllowEmptyString()][string]$ActiveNextRole,
        [AllowEmptyString()][string]$QaBlock,
        [AllowEmptyString()][string]$RoleText,
        [Parameter(Mandatory)][string]$Subject
    )

    $manualOwner = ''
    if (-not [string]::IsNullOrWhiteSpace($QaBlock)) {
        $manualOwner = Get-SingleToken -Text $QaBlock -Key 'MANUAL_OWNER' -Label 'QA' `
            -Subject $Subject -Required
        if (-not [string]::IsNullOrWhiteSpace($ActiveNextRole) -and
            -not [string]::IsNullOrWhiteSpace($manualOwner) -and
            $ActiveNextRole -cne $manualOwner) {
            Add-Failure -Code 'QA_MANUAL_OWNER_MISMATCH' -Subject $manualOwner `
                -Detail "ACTIVE_NEXT_ROLE=$ActiveNextRole"
        }
    }

    $retiredRoles = [System.Collections.Generic.List[string]]::new()
    foreach ($retiredValue in @(Get-TokenValues -Text $RoleText -Key 'RETIRED_ROLE')) {
        foreach ($role in ($retiredValue -split '[;,]')) {
            $trimmed = $role.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
                $retiredRoles.Add($trimmed)
            }
        }
    }
    foreach ($retiredRole in $retiredRoles) {
        if (-not [string]::IsNullOrWhiteSpace($ActiveNextRole) -and
            $ActiveNextRole.Equals($retiredRole, [System.StringComparison]::Ordinal)) {
            Add-Failure -Code 'ACTIVE_ROLE_RETIRED' -Subject $retiredRole
        }
        if (-not [string]::IsNullOrWhiteSpace($manualOwner) -and
            $manualOwner.Equals($retiredRole, [System.StringComparison]::Ordinal)) {
            Add-Failure -Code 'QA_ROLE_RETIRED' -Subject $retiredRole
        }
    }
}

function Invoke-GitProcess {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $script:gitPath -C $Root @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { [string]$_ })
    }
}

function Get-CurrentHead {
    param([Parameter(Mandatory)][string]$Root)
    $result = Invoke-GitProcess -Root $Root -Arguments @('rev-parse', 'HEAD')
    if ($result.ExitCode -ne 0 -or $result.Lines.Count -eq 0) {
        return ''
    }
    return $result.Lines[0].Trim()
}

function Test-CommitObject {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Commit
    )

    $result = Invoke-GitProcess -Root $Root -Arguments @('cat-file', '-e', "$Commit^{commit}")
    return $result.ExitCode -eq 0
}

function Test-Ancestor {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Ancestor,
        [Parameter(Mandatory)][string]$Descendant
    )

    $result = Invoke-GitProcess -Root $Root -Arguments @('merge-base', '--is-ancestor', $Ancestor, $Descendant)
    if ($result.ExitCode -eq 0) {
        return $true
    }
    if ($result.ExitCode -eq 1) {
        return $false
    }
    throw "git merge-base --is-ancestor failed: $($result.Lines -join "`n")"
}

function Resolve-RepoFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$FailureCode,
        [Parameter(Mandatory)][string]$Subject
    )

    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) {
        [System.IO.Path]::GetFullPath($Path)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $Root $Path))
    }
    if (-not (Test-PathWithin -Child $candidate -Parent $Root)) {
        Add-Failure -Code $FailureCode -Subject $Subject -Detail 'path escapes repository root'
        return ''
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        Add-Failure -Code $FailureCode -Subject $Subject -Detail 'file not found'
        return ''
    }
    return $candidate
}

function Add-EvidencePath {
    param([Parameter(Mandatory)][string]$Path)
    if (-not $script:evidence.Contains($Path)) {
        $script:evidence.Add($Path)
    }
}

function Test-Evidence {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Values,
        [Parameter(Mandatory)][string]$Label
    )

    $paths = [System.Collections.Generic.List[string]]::new()
    foreach ($value in $Values) {
        foreach ($part in ($value -split ';')) {
            $path = $part.Trim()
            if (-not [string]::IsNullOrWhiteSpace($path)) {
                $paths.Add($path)
            }
        }
    }
    if ($paths.Count -eq 0) {
        Add-Failure -Code "${Label}_MISSING" -Subject 'EVIDENCE'
        return
    }
    foreach ($path in $paths) {
        if (-not [System.IO.Path]::IsPathRooted($path)) {
            Add-Failure -Code "${Label}_NOT_ABSOLUTE" -Subject $path
            continue
        }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            Add-Failure -Code "${Label}_MISSING" -Subject $path
            continue
        }
        if ((Get-Item -LiteralPath $path).Length -eq 0) {
            Add-Failure -Code "${Label}_EMPTY" -Subject $path
            continue
        }
        Add-EvidencePath -Path $path
    }
}

function Test-Handoff {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$TasksFile,
        [Parameter(Mandatory)][string]$Text,
        [AllowNull()][string]$MemoryFile
    )

    $active = Get-MarkedBlock -Text $Text `
        -BeginMarker '<!-- V2_HANDOFF_ACTIVE_BEGIN -->' `
        -EndMarker '<!-- V2_HANDOFF_ACTIVE_END -->' `
        -Label 'ACTIVE' -Subject $TasksFile
    $object = Get-MarkedBlock -Text $Text `
        -BeginMarker '<!-- V2_HANDOFF_OBJECT_BEGIN -->' `
        -EndMarker '<!-- V2_HANDOFF_OBJECT_END -->' `
        -Label 'HANDOFF_OBJECT' -Subject $TasksFile
    $qaForRoles = Get-OptionalMarkedBlock -Text $Text `
        -BeginMarker '<!-- V2_QA_READY_BEGIN -->' `
        -EndMarker '<!-- V2_QA_READY_END -->' `
        -Label 'QA' -Subject $TasksFile

    $activeNextRole = ''
    if (-not [string]::IsNullOrWhiteSpace($active)) {
        foreach ($key in @(
            'TASK_ID',
            'WORKTREE_POLICY',
            'CANONICAL_WORKTREE',
            'CANONICAL_BRANCH',
            'EXPECTED_HEAD_SOURCE',
            'READ_ORDER',
            'NEXT_ROLE',
            'NEXT_PATH',
            'STOP_CONDITION',
            'SCOPE',
            'P1',
            'P2'
        )) {
            $tokenValue = Get-SingleToken -Text $active -Key $key -Label 'ACTIVE' `
                -Subject $TasksFile -Required
            if ($key -eq 'NEXT_ROLE') {
                $activeNextRole = $tokenValue
            }
        }
        $activeEvidence = @(Get-TokenValues -Text $active -Key 'EVIDENCE')
        Test-Evidence -Values $activeEvidence -Label 'EVIDENCE'

        $forbiddenBindings = [regex]::Matches(
            $active,
            '(?im)^[ \t]*(?:>[ \t]*)?(?:[-*][ \t]+)?(?:CURRENT_HEAD|DOCS_COMMIT|CONTROL_PLANE_HEAD_INPUT|CODE_INTEGRATION_HEAD|HANDOFF_BASE_HEAD|CANDIDATE_HEAD)[ \t]*='
        )
        if ($forbiddenBindings.Count -gt 0) {
            Add-Failure -Code 'ACTIVE_HEAD_BINDING' -Subject $TasksFile `
                -Detail 'ACTIVE contains a legacy current-HEAD binding'
        }

        $candidateCounts = [regex]::Matches(
            $active,
            '(?im)^[ \t]*(?:>[ \t]*)?(?:[-*][ \t]+)?(?:CANDIDATE_SIZE|CANDIDATE_TEST_COUNT|CANDIDATE_TEST_RESULTS)[ \t]*='
        )
        if ($candidateCounts.Count -gt 0) {
            Add-Failure -Code 'ACTIVE_CANDIDATE_COUNT' -Subject $TasksFile `
                -Detail 'ACTIVE must not duplicate candidate-size test counts'
        }

        $legacyStates = [regex]::Matches(
            $active,
            '(?im)^[ \t]*(?:>[ \t]*)?(?:[-*][ \t]+)?(?:BLOCKED_BY_SCOPE|OLD_NEXT_ROLE|CURRENT_STATUS|OLD_OWNER|PREVIOUS_OWNER)[ \t]*='
        )
        if ($legacyStates.Count -gt 0) {
            Add-Failure -Code 'ACTIVE_LEGACY_STATE' -Subject $TasksFile `
                -Detail 'ACTIVE contains legacy owner/status residue'
        }
    }
    Test-WorkflowRoleContract -ActiveNextRole $activeNextRole -QaBlock $qaForRoles `
        -RoleText ($active + "`n" + $qaForRoles) -Subject $TasksFile

    $currentHead = Get-CurrentHead -Root $Root
    if ([string]::IsNullOrWhiteSpace($currentHead)) {
        Add-Failure -Code 'HEAD_UNAVAILABLE' -Subject $Root
    }
    if (-not [string]::IsNullOrWhiteSpace($active) -and -not [string]::IsNullOrWhiteSpace($currentHead)) {
        foreach ($match in [regex]::Matches($active, '\b[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?\b')) {
            if ($match.Value.Equals($currentHead, [System.StringComparison]::OrdinalIgnoreCase)) {
                Add-Failure -Code 'ACTIVE_SELF_SHA' -Subject $TasksFile
            }
        }
    }

    if ($ExpectedHead -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$') {
        Add-Failure -Code 'EXPECTED_HEAD_INVALID' -Subject $ExpectedHead
    }
    elseif ($ExpectedHead -cne $currentHead) {
        Add-Failure -Code 'HEAD_PIN_MISMATCH' -Subject $ExpectedHead `
            -Detail "current=$currentHead"
    }

    $status = Invoke-GitProcess -Root $Root -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    if ($status.ExitCode -ne 0) {
        Add-Failure -Code 'WORKTREE_STATUS_FAILED' -Subject $Root
    }
    elseif (@($status.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
        Add-Failure -Code 'WORKTREE_DIRTY' -Subject $Root `
            -Detail ((@($status.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) -join '; ')
    }

    $branchResult = Invoke-GitProcess -Root $Root -Arguments @('branch', '--show-current')
    $branch = if ($branchResult.ExitCode -eq 0 -and $branchResult.Lines.Count -gt 0) {
        $branchResult.Lines[0].Trim()
    }
    else {
        ''
    }
    if (-not [string]::IsNullOrWhiteSpace($active)) {
        $canonicalBranch = Get-SingleToken -Text $active -Key 'CANONICAL_BRANCH' -Label 'ACTIVE' `
            -Subject $TasksFile -Required
        if (-not [string]::IsNullOrWhiteSpace($canonicalBranch) -and
            $branch -cne $canonicalBranch) {
            Add-Failure -Code 'BRANCH_MISMATCH' -Subject $branch `
                -Detail "expected=$canonicalBranch"
        }

        $worktreePolicy = Get-SingleToken -Text $active -Key 'WORKTREE_POLICY' -Label 'ACTIVE' `
            -Subject $TasksFile -Required
        if ($worktreePolicy -eq 'EXACT_ABSOLUTE') {
            $canonicalWorktree = Get-SingleToken -Text $active -Key 'CANONICAL_WORKTREE' -Label 'ACTIVE' `
                -Subject $TasksFile -Required
            if (-not [string]::IsNullOrWhiteSpace($canonicalWorktree)) {
                $expectedRoot = [System.IO.Path]::GetFullPath($canonicalWorktree).TrimEnd('\', '/')
                $actualRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
                if (-not $expectedRoot.Equals($actualRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                    Add-Failure -Code 'WORKTREE_MISMATCH' -Subject $actualRoot `
                        -Detail "expected=$expectedRoot"
                }
            }
        }
        elseif ($worktreePolicy -ne 'ANY') {
            Add-Failure -Code 'WORKTREE_POLICY_INVALID' -Subject $worktreePolicy
        }
    }

    $baseHead = ''
    $maxChangedPaths = 0
    $allowedPaths = @()
    $requiredAncestors = @()
    $failedCandidate = ''
    if (-not [string]::IsNullOrWhiteSpace($object)) {
        $baseHead = Get-SingleToken -Text $object -Key 'BASE_HEAD' -Label 'OBJECT' `
            -Subject $TasksFile -Required
        $maxChangedText = Get-SingleToken -Text $object -Key 'MAX_CHANGED_PATHS' -Label 'OBJECT' `
            -Subject $TasksFile -Required
        if (-not [int]::TryParse($maxChangedText, [ref]$maxChangedPaths) -or $maxChangedPaths -le 0) {
            Add-Failure -Code 'MAX_CHANGED_PATHS_INVALID' -Subject $maxChangedText
        }
        $allowedPaths = @(Get-TokenValues -Text $object -Key 'ALLOWED_PATH')
        $requiredAncestors = @(Get-TokenValues -Text $object -Key 'REQUIRED_ANCESTOR')
        $failedCandidateToken = Get-SingleToken -Text $object -Key 'FAILED_CANDIDATE' -Label 'OBJECT' `
            -Subject $TasksFile -Required

        $objectShapeValid = $true
        if ([string]::IsNullOrWhiteSpace($baseHead)) {
            $objectShapeValid = $false
        }
        elseif ($baseHead -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$') {
            Add-Failure -Code 'BASE_HEAD_INVALID' -Subject $baseHead
            $objectShapeValid = $false
        }
        if (-not [int]::TryParse($maxChangedText, [ref]$maxChangedPaths) -or $maxChangedPaths -le 0) {
            $objectShapeValid = $false
        }
        if ($allowedPaths.Count -eq 0) {
            Add-Failure -Code 'ALLOWED_PATHS_MISSING' -Subject $TasksFile
            $objectShapeValid = $false
        }
        $normalizedAllowed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($allowedPathValue in $allowedPaths) {
            if (-not (Test-CanonicalRepoRelativePath -Path $allowedPathValue)) {
                Add-Failure -Code 'ALLOWED_PATH_INVALID' -Subject $allowedPathValue
                $objectShapeValid = $false
                continue
            }
            if (-not $normalizedAllowed.Add($allowedPathValue)) {
                Add-Failure -Code 'ALLOWED_PATH_DUPLICATE' -Subject $allowedPathValue
                $objectShapeValid = $false
            }
        }

        if ($requiredAncestors.Count -eq 0) {
            Add-Failure -Code 'REQUIRED_ANCESTOR_TOKEN_MISSING' -Subject $TasksFile
            $objectShapeValid = $false
        }
        else {
            $noneAncestors = @($requiredAncestors | Where-Object {
                $_.Equals('NONE', [System.StringComparison]::OrdinalIgnoreCase)
            })
            if ($noneAncestors.Count -gt 0) {
                if ($noneAncestors.Count -ne 1 -or $requiredAncestors.Count -ne 1) {
                    Add-Failure -Code 'REQUIRED_ANCESTOR_NONE_MIXED' -Subject $TasksFile
                    $objectShapeValid = $false
                }
            }
            else {
                foreach ($ancestor in $requiredAncestors) {
                    if ($ancestor -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$') {
                        Add-Failure -Code 'REQUIRED_ANCESTOR_INVALID' -Subject $ancestor
                        $objectShapeValid = $false
                    }
                }
            }
        }

        if ([string]::IsNullOrWhiteSpace($failedCandidateToken)) {
            $objectShapeValid = $false
        }
        elseif ($failedCandidateToken.Equals('NONE', [System.StringComparison]::OrdinalIgnoreCase)) {
            $failedCandidate = ''
        }
        elseif ($failedCandidateToken -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$') {
            Add-Failure -Code 'FAILED_CANDIDATE_INVALID' -Subject $failedCandidateToken
            $objectShapeValid = $false
        }
        else {
            $failedCandidate = $failedCandidateToken
        }

        if ($objectShapeValid) {
            $objectObjectsValid = $true
            if (-not (Test-CommitObject -Root $Root -Commit $baseHead)) {
                Add-Failure -Code 'BASE_HEAD_MISSING' -Subject $baseHead
                $objectObjectsValid = $false
            }
            $nonNoneAncestors = @($requiredAncestors | Where-Object {
                -not $_.Equals('NONE', [System.StringComparison]::OrdinalIgnoreCase)
            })
            foreach ($ancestor in $nonNoneAncestors) {
                if (-not (Test-CommitObject -Root $Root -Commit $ancestor)) {
                    Add-Failure -Code 'REQUIRED_ANCESTOR_MISSING' -Subject $ancestor
                    $objectObjectsValid = $false
                }
            }
            if (-not [string]::IsNullOrWhiteSpace($failedCandidate) -and
                -not (Test-CommitObject -Root $Root -Commit $failedCandidate)) {
                Add-Failure -Code 'FAILED_CANDIDATE_MISSING' -Subject $failedCandidate
                $objectObjectsValid = $false
            }

            $objectRelationsValid = $objectObjectsValid
            if ($objectObjectsValid -and -not [string]::IsNullOrWhiteSpace($currentHead)) {
                if (-not (Test-Ancestor -Root $Root -Ancestor $baseHead -Descendant $currentHead)) {
                    Add-Failure -Code 'BASE_NOT_ANCESTOR' -Subject $baseHead `
                        -Detail "candidate=$currentHead"
                    $objectRelationsValid = $false
                }
                foreach ($ancestor in $nonNoneAncestors) {
                    if (-not (Test-Ancestor -Root $Root -Ancestor $ancestor -Descendant $currentHead)) {
                        Add-Failure -Code 'REQUIRED_ANCESTOR_NOT_ANCESTOR' -Subject $ancestor
                        $objectRelationsValid = $false
                    }
                }
                if (-not [string]::IsNullOrWhiteSpace($failedCandidate) -and
                    $failedCandidate -eq $currentHead) {
                    Add-Failure -Code 'FAILED_CANDIDATE_IS_CURRENT' -Subject $failedCandidate
                    $objectRelationsValid = $false
                }
            }

            if ($objectRelationsValid -and -not [string]::IsNullOrWhiteSpace($currentHead) -and
                (Test-CommitObject -Root $Root -Commit $currentHead)) {
                $diffResult = Invoke-GitProcess -Root $Root -Arguments @('diff', '--name-only', "$baseHead..$currentHead")
                if ($diffResult.ExitCode -ne 0) {
                    Add-Failure -Code 'CHANGED_PATHS_UNAVAILABLE' -Subject $TasksFile
                }
                else {
                    $changedPaths = @($diffResult.Lines |
                        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                        ForEach-Object { Normalize-RelativePath -Path $_.Trim() })
                    if ($changedPaths.Count -eq 0) {
                        Add-Failure -Code 'CHANGED_PATHS_EMPTY' -Subject $baseHead
                    }
                    if ($changedPaths.Count -gt $maxChangedPaths) {
                        Add-Failure -Code 'CHANGED_PATH_LIMIT' -Subject ([string]$changedPaths.Count) `
                            -Detail "max=$maxChangedPaths"
                    }
                    foreach ($changedPath in $changedPaths) {
                        if (-not $normalizedAllowed.Contains($changedPath)) {
                            Add-Failure -Code 'PATH_OUTSIDE_SCOPE' -Subject $changedPath
                        }
                    }
                }
            }
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($MemoryFile)) {
        Test-MemoryIndex -Path $MemoryFile
    }
}

function Test-MemoryIndex {
    param([Parameter(Mandatory)][string]$Path)

    if (-not [System.IO.Path]::IsPathRooted($Path)) {
        Add-Failure -Code 'MEMORY_INDEX_NOT_ABSOLUTE' -Subject $Path
        return
    }
    $indexFull = [System.IO.Path]::GetFullPath($Path)
    $store = Split-Path -Parent $indexFull
    $storePrefix = $store.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $indexFull.StartsWith($storePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-Failure -Code 'MEMORY_INDEX_NOT_ABSOLUTE' -Subject $Path
        return
    }
    $indexRelative = $indexFull.Substring($storePrefix.Length)
    $indexComponents = Get-PathComponentStatus -BasePath $store -RelativePath $indexRelative
    if ($indexComponents.Status -eq 'REPARSE') {
        Add-Failure -Code 'MEMORY_INDEX_UNSAFE_REPARSE' -Subject $indexComponents.Path
        return
    }
    if ($indexComponents.Status -ne 'SAFE') {
        Add-Failure -Code 'MEMORY_INDEX_MISSING' -Subject $Path
        return
    }
    $indexItem = Get-Item -LiteralPath $indexComponents.Path -Force
    if ($indexItem.PSIsContainer) {
        Add-Failure -Code 'MEMORY_INDEX_MISSING' -Subject $Path
        return
    }
    try {
        $text = [System.IO.File]::ReadAllText($indexComponents.Path, $utf8Strict)
    }
    catch {
        Add-Failure -Code 'MEMORY_INDEX_UTF8' -Subject $Path -Detail $_.Exception.Message
        return
    }

    $lines = [System.IO.File]::ReadAllLines($indexComponents.Path, $utf8Strict)
    $bytes = $indexItem.Length
    if ($lines.Count -gt 200 -or $bytes -gt 25600) {
        Add-Failure -Code 'MEMORY_INDEX_HARD_LINE_LIMIT' -Subject $Path `
            -Detail "lines=$($lines.Count) bytes=$bytes"
    }
    elseif ($lines.Count -gt 150 -or $bytes -gt 20480) {
        Add-Warning -Code 'MEMORY_INDEX_SOFT_LIMIT' -Subject $Path `
            -Detail "lines=$($lines.Count) bytes=$bytes"
    }

    $projectMatch = [regex]::Match(
        $text,
        '(?ims)^##[ \t]+project[ \t]*\r?\n(.*?)(?=^##[ \t]+|\z)'
    )
    if (-not $projectMatch.Success) {
        Add-Failure -Code 'MEMORY_PROJECT_SECTION_MISSING' -Subject $Path
        return
    }

    $links = [regex]::Matches($projectMatch.Groups[1].Value, '\[[^\]]*\]\(([^)]+)\)')
    if ($links.Count -eq 0) {
        Add-Failure -Code 'MEMORY_PROJECT_POINTER_MISSING' -Subject $Path
        return
    }

    $validPointers = 0
    foreach ($link in $links) {
        $pointer = $link.Groups[1].Value.Trim().Trim('<', '>')
        if ($pointer -match '^(?i:file://)') {
            Add-Failure -Code 'MEMORY_PROJECT_UNSAFE_POINTER' -Subject $pointer
            continue
        }
        $pathPart = ($pointer -split '#', 2)[0]
        if (-not $pathPart.EndsWith('.md', [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        if ([System.IO.Path]::IsPathRooted($pathPart)) {
            Add-Failure -Code 'MEMORY_PROJECT_UNSAFE_POINTER' -Subject $pointer `
                -Detail 'absolute path'
            continue
        }
        $normalized = $pathPart.Replace('\', '/')
        $invalidSegments = @($normalized.Split('/') | Where-Object {
            [string]::IsNullOrWhiteSpace($_) -or $_ -eq '.' -or $_ -eq '..'
        })
        if ($invalidSegments.Count -gt 0) {
            Add-Failure -Code 'MEMORY_PROJECT_UNSAFE_POINTER' -Subject $pointer `
                -Detail 'invalid path component'
            continue
        }
        $candidate = [System.IO.Path]::GetFullPath((Join-Path $store $pathPart.Replace('/', '\')))
        if (-not (Test-PathWithin -Child $candidate -Parent $store)) {
            Add-Failure -Code 'MEMORY_PROJECT_UNSAFE_POINTER' -Subject $pointer `
                -Detail 'escape'
            continue
        }
        $componentStatus = Get-PathComponentStatus -BasePath $store -RelativePath $normalized
        if ($componentStatus.Status -eq 'REPARSE') {
            Add-Failure -Code 'MEMORY_PROJECT_UNSAFE_POINTER' -Subject $pointer `
                -Detail "reparse-point=$($componentStatus.Path)"
            continue
        }
        if ($componentStatus.Status -ne 'SAFE') {
            Add-Failure -Code 'MEMORY_PROJECT_POINTER_MISSING' -Subject $pointer
            continue
        }
        $item = Get-Item -LiteralPath $componentStatus.Path -Force
        if ($item.PSIsContainer) {
            Add-Failure -Code 'MEMORY_PROJECT_POINTER_MISSING' -Subject $pointer
            continue
        }
        $validPointers++
    }
    if ($validPointers -eq 0) {
        Add-Failure -Code 'MEMORY_PROJECT_POINTER_MISSING' -Subject $Path
    }
}

function Convert-TableCells {
    param([Parameter(Mandatory)][string]$Line)

    $trimmed = $Line.Trim()
    if (-not $trimmed.StartsWith('|') -or -not $trimmed.EndsWith('|')) {
        return @()
    }
    $inside = $trimmed.Substring(1, $trimmed.Length - 2)
    return @($inside.Split('|') | ForEach-Object { $_.Trim() })
}

function Get-MatrixRows {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$BeginMarker,
        [Parameter(Mandatory)][string]$EndMarker,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$Subject,
        [Parameter(Mandatory)][string[]]$ExpectedHeaders
    )

    $block = Get-MarkedBlock -Text $Text -BeginMarker $BeginMarker -EndMarker $EndMarker `
        -Label $Label -Subject $Subject
    if ([string]::IsNullOrWhiteSpace($block)) {
        return @()
    }

    $tableLines = @($block -split "`n" | Where-Object { $_.Trim().StartsWith('|') })
    if ($tableLines.Count -lt 3) {
        Add-Failure -Code "${Label}_TABLE_MISSING" -Subject $Subject
        return @()
    }
    $header = @(Convert-TableCells -Line $tableLines[0])
    if ($header.Count -ne $ExpectedHeaders.Count) {
        Add-Failure -Code "${Label}_HEADER" -Subject $Subject
        return @()
    }
    for ($index = 0; $index -lt $ExpectedHeaders.Count; $index++) {
        if (-not $header[$index].Equals($ExpectedHeaders[$index], [System.StringComparison]::OrdinalIgnoreCase)) {
            Add-Failure -Code "${Label}_HEADER" -Subject $Subject `
                -Detail "column=$($ExpectedHeaders[$index])"
            return @()
        }
    }

    $rows = [System.Collections.Generic.List[object]]::new()
    for ($lineIndex = 2; $lineIndex -lt $tableLines.Count; $lineIndex++) {
        $cells = @(Convert-TableCells -Line $tableLines[$lineIndex])
        if ($cells.Count -ne $ExpectedHeaders.Count) {
            Add-Failure -Code "${Label}_ROW_COLUMN_COUNT" -Subject ([string]($lineIndex + 1)) `
                -Detail "expected=$($ExpectedHeaders.Count) actual=$($cells.Count)"
            continue
        }
        $rows.Add([pscustomobject]@{
            Cells = $cells
            Line = $lineIndex + 1
        })
    }
    return @($rows)
}

function Test-QaReady {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$TasksFile,
        [Parameter(Mandatory)][string]$Text,
        [AllowNull()][string]$MemoryFile
    )

    $active = Get-MarkedBlock -Text $Text `
        -BeginMarker '<!-- V2_HANDOFF_ACTIVE_BEGIN -->' `
        -EndMarker '<!-- V2_HANDOFF_ACTIVE_END -->' `
        -Label 'ACTIVE' -Subject $TasksFile
    $qa = Get-MarkedBlock -Text $Text `
        -BeginMarker '<!-- V2_QA_READY_BEGIN -->' `
        -EndMarker '<!-- V2_QA_READY_END -->' `
        -Label 'QA' -Subject $TasksFile
    if ([string]::IsNullOrWhiteSpace($qa)) {
        return
    }

    $qaReady = Get-SingleToken -Text $qa -Key 'QA_READY' -Label 'QA' -Subject $TasksFile -Required
    if ($qaReady -and $qaReady -ne 'YES') {
        Add-Failure -Code 'QA_READY_NOT_YES' -Subject $qaReady
    }
    Get-SingleToken -Text $qa -Key 'UNCOVERED' -Label 'QA' -Subject $TasksFile -Required | Out-Null
    $stopCondition = Get-SingleToken -Text $qa -Key 'STOP_CONDITION' -Label 'QA' -Subject $TasksFile
    if ([string]::IsNullOrWhiteSpace($stopCondition) -and -not [string]::IsNullOrWhiteSpace($active)) {
        Get-SingleToken -Text $active -Key 'STOP_CONDITION' -Label 'QA' -Subject $TasksFile -Required | Out-Null
    }
    Get-SingleToken -Text $qa -Key 'MANUAL_LIMITATION' -Label 'QA' -Subject $TasksFile -Required | Out-Null
    $activeNextRole = if (-not [string]::IsNullOrWhiteSpace($active)) {
        Get-SingleToken -Text $active -Key 'NEXT_ROLE' -Label 'ACTIVE' -Subject $TasksFile -Required
    }
    else {
        ''
    }
    Test-WorkflowRoleContract -ActiveNextRole $activeNextRole -QaBlock $qa `
        -RoleText ($active + "`n" + $qa) -Subject $TasksFile

    $invariantRows = @(Get-MatrixRows -Text $qa `
        -BeginMarker '<!-- V2_QA_INVARIANT_MATRIX_BEGIN -->' `
        -EndMarker '<!-- V2_QA_INVARIANT_MATRIX_END -->' `
        -Label 'QA_INVARIANT' -Subject $TasksFile `
        -ExpectedHeaders @('Invariant ID', 'Entry', 'Action', 'Observable result', 'Automation/fixture evidence', 'Device evidence'))
    if ($invariantRows.Count -eq 0) {
        Add-Failure -Code 'QA_INVARIANT_MATRIX_EMPTY' -Subject $TasksFile
    }
    foreach ($row in $invariantRows) {
        $cells = @($row.Cells)
        $id = $cells[0]
        if ($id -notmatch '^INV-[A-Za-z0-9._-]+$') {
            Add-Failure -Code 'QA_INVARIANT_ID' -Subject $id
        }
        for ($index = 0; $index -lt $cells.Count; $index++) {
            if ([string]::IsNullOrWhiteSpace($cells[$index])) {
                Add-Failure -Code 'QA_INVARIANT_FIELD_EMPTY' -Subject "$id/column$($index + 1)"
            }
        }
        $deviceEvidence = $cells[5]
        if ($deviceEvidence -match '^N/A:') {
            if ([string]::IsNullOrWhiteSpace($deviceEvidence.Substring(4))) {
                Add-Failure -Code 'QA_DEVICE_NA_REASON' -Subject $id
            }
        }
    }

    $testRows = @(Get-MatrixRows -Text $qa `
        -BeginMarker '<!-- V2_QA_TEST_MATRIX_BEGIN -->' `
        -EndMarker '<!-- V2_QA_TEST_MATRIX_END -->' `
        -Label 'QA_TEST' -Subject $TasksFile `
        -ExpectedHeaders @('Test ID', 'Target', 'Command', 'Selector status', 'Equivalent evidence'))
    if ($testRows.Count -eq 0) {
        Add-Failure -Code 'QA_TEST_MATRIX_EMPTY' -Subject $TasksFile
    }
    $qaOutsideMatrix = [regex]::Replace(
        $qa,
        '(?s)<!-- V2_QA_TEST_MATRIX_BEGIN -->.*?<!-- V2_QA_TEST_MATRIX_END -->',
        ''
    )
    $selectorScanText = $active + "`n" + $qaOutsideMatrix
    $selectorScanLine = 0
    foreach ($line in ($selectorScanText -split "`n")) {
        $selectorScanLine++
        $scanFacts = Get-TestSelectorFacts -Command $line
        if ($scanFacts.HasSelector) {
            Add-Failure -Code 'TEST_SELECTOR_OUTSIDE_MATRIX' -Subject "line=$selectorScanLine"
        }
    }
    foreach ($row in $testRows) {
        $cells = @($row.Cells)
        $testId = $cells[0]
        $target = $cells[1]
        $command = $cells[2]
        $selectorStatus = $cells[3]
        $equivalentEvidence = $cells[4]
        if ([string]::IsNullOrWhiteSpace($testId)) {
            Add-Failure -Code 'QA_TEST_ID_EMPTY' -Subject $TasksFile
        }
        if ([string]::IsNullOrWhiteSpace($target)) {
            Add-Failure -Code 'QA_TEST_TARGET_EMPTY' -Subject $testId
        }
        else {
            Resolve-RepoFile -Root $Root -Path $target -FailureCode 'QA_TEST_TARGET_MISSING' `
                -Subject $testId | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($command)) {
            Add-Failure -Code 'QA_TEST_COMMAND_EMPTY' -Subject $testId
        }
        elseif (-not [string]::IsNullOrWhiteSpace($command)) {
            $selectorFacts = Get-TestSelectorFacts -Command $command
            if ($selectorFacts.HasSelector) {
                if ($selectorFacts.Invalid) {
                    Add-Failure -Code 'TEST_SELECTOR_INVALID' -Subject $testId
                }
                $verified = $selectorStatus -match '(?i)(?:^|;)[ \t]*(?:TEST_SELECTOR_VERIFIED[ \t]*=[ \t]*PASS|PASS)(?:[ \t]*;|$)'
                $equivalent = $selectorStatus -match '(?i)(?:^|;)[ \t]*(?:NO_MATCHING_TESTS|EQUIVALENT)(?:[ \t]*;|$)'
                if ($verified) {
                    $selectorEvidence = @(Get-NamedTokenValues `
                        -Text ($selectorStatus + ';' + $equivalentEvidence) `
                        -Key 'TEST_SELECTOR_EVIDENCE')
                    if ($selectorEvidence.Count -eq 0) {
                        Add-Failure -Code 'TEST_SELECTOR_EVIDENCE_MISSING' -Subject $testId
                    }
                    else {
                        Test-Evidence -Values $selectorEvidence -Label 'TEST_SELECTOR_EVIDENCE'
                    }
                }
                elseif ($equivalent) {
                    Test-Evidence -Values @($equivalentEvidence) -Label 'TEST_EQUIVALENT_EVIDENCE'
                }
                else {
                    Add-Failure -Code 'TEST_SELECTOR_UNVERIFIED' -Subject $testId
                }
            }
        }
    }

    $p1Rows = @(Get-MatrixRows -Text $qa `
        -BeginMarker '<!-- V2_QA_P1_MATRIX_BEGIN -->' `
        -EndMarker '<!-- V2_QA_P1_MATRIX_END -->' `
        -Label 'QA_P1' -Subject $TasksFile `
        -ExpectedHeaders @('P1 ID', 'RED', 'GREEN', 'Evidence'))
    $p1Token = if (-not [string]::IsNullOrWhiteSpace($active)) {
        Get-SingleToken -Text $active -Key 'P1' -Label 'ACTIVE' -Subject $TasksFile -Required
    }
    else {
        ''
    }
    $p1Ids = @([regex]::Matches($p1Token, 'P1-[A-Za-z0-9._-]+') | ForEach-Object { $_.Value } | Select-Object -Unique)
    if ($p1Ids.Count -eq 0 -and -not [string]::IsNullOrWhiteSpace($p1Token)) {
        Add-Failure -Code 'QA_P1_ID_MISSING' -Subject $p1Token
    }
    if ($p1Rows.Count -eq 0) {
        Add-Failure -Code 'QA_P1_MATRIX_EMPTY' -Subject $TasksFile
    }
    foreach ($row in $p1Rows) {
        $cells = @($row.Cells)
        $id = $cells[0]
        if ($id -notmatch '^P1-[A-Za-z0-9._-]+$') {
            Add-Failure -Code 'QA_P1_ID' -Subject $id
        }
        if ([string]::IsNullOrWhiteSpace($cells[1])) {
            Add-Failure -Code 'P1_RED_MISSING' -Subject $id
        }
        if ([string]::IsNullOrWhiteSpace($cells[2])) {
            Add-Failure -Code 'P1_GREEN_MISSING' -Subject $id
        }
        if ([string]::IsNullOrWhiteSpace($cells[3])) {
            Add-Failure -Code 'P1_EVIDENCE_MISSING' -Subject $id
        }
        else {
            Test-Evidence -Values @($cells[3]) -Label 'P1_EVIDENCE'
        }
    }
    foreach ($id in $p1Ids) {
        if (@($p1Rows | Where-Object { $_.Cells[0] -eq $id }).Count -eq 0) {
            Add-Failure -Code 'QA_P1_ROW_MISSING' -Subject $id
        }
    }

    if ($Text -match '(?im)^[ \t]*(?:>[ \t]*)?(?:[-*][ \t]+)?MERGE_PREP_AUTHORIZED[ \t]*=[ \t]*YES[ \t]*$') {
        Add-Failure -Code 'MERGE_PREP_CONTRADICTION' -Subject $TasksFile
    }

    if (-not [string]::IsNullOrWhiteSpace($MemoryFile)) {
        Test-MemoryIndex -Path $MemoryFile
    }
}

try {
    $resolvedRepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)
    if (-not (Test-Path -LiteralPath $resolvedRepoRoot -PathType Container)) {
        throw "Repository root not found: $resolvedRepoRoot"
    }
    $gitCommand = Get-Command git -CommandType Application | Select-Object -First 1
    if ($null -eq $gitCommand) {
        throw 'git executable not found'
    }
    $script:gitPath = $gitCommand.Source

    $resolvedTasksPath = if ([System.IO.Path]::IsPathRooted($TasksPath)) {
        [System.IO.Path]::GetFullPath($TasksPath)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $resolvedRepoRoot $TasksPath))
    }
    if (-not (Test-PathWithin -Child $resolvedTasksPath -Parent $resolvedRepoRoot)) {
        Write-Output "FAIL:TASKS_PATH_ESCAPE:$resolvedTasksPath"
        exit 2
    }
    if (-not (Test-Path -LiteralPath $resolvedTasksPath -PathType Leaf)) {
        Write-Output "FAIL:TASKS_PATH_MISSING:$resolvedTasksPath"
        exit 2
    }
    try {
        $tasksText = Read-Utf8Text -Path $resolvedTasksPath
    }
    catch {
        Write-Output "FAIL:TASKS_UTF8:$resolvedTasksPath"
        Write-Output "DETAIL:TASKS_UTF8:$($_.Exception.Message)"
        exit 2
    }

    if ($Mode -eq 'Handoff') {
        if ([string]::IsNullOrWhiteSpace($ExpectedHead)) {
            Add-Failure -Code 'EXPECTED_HEAD_REQUIRED' -Subject ''
        }
        Test-Handoff -Root $resolvedRepoRoot -TasksFile $resolvedTasksPath -Text $tasksText `
            -MemoryFile $MemoryIndex
    }
    elseif ($Mode -eq 'QaReady') {
        Test-QaReady -Root $resolvedRepoRoot -TasksFile $resolvedTasksPath -Text $tasksText `
            -MemoryFile $MemoryIndex
    }

    foreach ($warning in $script:warnings) {
        Write-Output "WARN:$($warning.Code):$($warning.Subject)"
        if (-not [string]::IsNullOrWhiteSpace($warning.Detail)) {
            Write-Output "DETAIL:$($warning.Code):$($warning.Detail)"
        }
    }
    if ($script:failures.Count -gt 0) {
        foreach ($failure in $script:failures) {
            Write-Output "FAIL:$($failure.Code):$($failure.Subject)"
            if (-not [string]::IsNullOrWhiteSpace($failure.Detail)) {
                Write-Output "DETAIL:$($failure.Code):$($failure.Detail)"
            }
        }
        Write-Output 'FAIL'
        exit 2
    }

    $passToken = if ($Mode -eq 'Handoff') { 'HANDOFF_LINT=PASS' } else { 'QA_READY_LINT=PASS' }
    Write-Output $passToken
    if ($script:evidence.Count -gt 0) {
        Write-Output "EVIDENCE=$($script:evidence -join ';')"
    }
    exit 0
}
catch {
    Write-Output "FAIL:UNEXPECTED_ERROR:$resolvedTasksPath"
    Write-Output "DETAIL:UNEXPECTED_ERROR:$($_.Exception.Message)"
    Write-Output 'FAIL'
    exit 3
}
