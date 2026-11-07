[CmdletBinding()]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$BaselinePath = (Join-Path $PSScriptRoot 'v2-owner-baseline.json')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$findings = [System.Collections.Generic.List[object]]::new()
$staticInputLimits = @(
    'GRADLE_INCLUDES_LIMITED_TO_LITERAL_INCLUDE_CALLS',
    'KOTLIN_TOP_LEVEL_FUNCTIONS_AND_PROPERTIES_NOT_FULLY_INDEXED',
    'IMPORT_OWNER_RESOLUTION_USES_DECLARED_TYPES_ONLY',
    'NO_METHOD_OR_FIELD_USE_GRAPH'
)

function Add-Finding {
    param(
        [Parameter(Mandatory)][string]$Code,
        [Parameter(Mandatory)][string]$Severity,
        [AllowEmptyString()][string]$StableId = '',
        [Parameter(Mandatory)][string]$Subject,
        [Parameter(Mandatory)][string]$Message
    )

    $findings.Add([pscustomobject][ordered]@{
        code = $Code
        severity = $Severity
        stableId = $StableId
        subject = $Subject
        message = $Message
    })
}

function Read-Utf8Text {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file not found: $Path"
    }
    return [System.IO.File]::ReadAllText(
        $Path,
        [System.Text.UTF8Encoding]::new($false, $true)
    )
}

function Normalize-RelativePath {
    param([Parameter(Mandatory)][string]$Path)
    return $Path.Replace('\', '/')
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

function Get-ProjectTargets {
    param([Parameter(Mandatory)][string]$Text)

    $targets = [System.Collections.Generic.List[string]]::new()
    $pattern = '(?m)^\s*(?:implementation|api|compileOnly|runtimeOnly|kapt|ksp|testImplementation|androidTestImplementation|debugImplementation|releaseImplementation)\s*\(\s*projects\.([A-Za-z0-9_.]+)\s*\)'
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $targets.Add($match.Groups[1].Value.Replace('.', '/'))
    }
    return @($targets)
}

function Find-Cycles {
    param(
        [Parameter(Mandatory)][hashtable]$Adjacency,
        [Parameter(Mandatory)][string[]]$Nodes
    )

    $state = @{}
    $stack = [System.Collections.Generic.List[string]]::new()
    $cycles = [System.Collections.Generic.List[string]]::new()

    function Visit-Node {
        param([string]$NodeName)

        $state[$NodeName] = 1
        $stack.Add($NodeName)
        $nextNodes = if ($Adjacency.ContainsKey($NodeName)) {
            @($Adjacency[$NodeName] | Sort-Object -Unique)
        }
        else {
            @()
        }
        foreach ($nextNode in $nextNodes) {
            if (-not $state.ContainsKey($nextNode)) {
                $state[$nextNode] = 0
            }
            if ($state[$nextNode] -eq 0) {
                Visit-Node -NodeName $nextNode
            }
            elseif ($state[$nextNode] -eq 1) {
                $startIndex = $stack.IndexOf($nextNode)
                if ($startIndex -ge 0) {
                    $cycleNodes = @($stack[$startIndex..($stack.Count - 1)]) + $nextNode
                    $cycles.Add(($cycleNodes -join ' -> '))
                }
            }
        }
        $stack.RemoveAt($stack.Count - 1)
        $state[$NodeName] = 2
    }

    foreach ($nodeName in @($Nodes | Sort-Object -Unique)) {
        if (-not $state.ContainsKey($nodeName)) {
            $state[$nodeName] = 0
        }
        if ($state[$nodeName] -eq 0) {
            Visit-Node -NodeName $nodeName
        }
    }
    return @($cycles | Sort-Object -Unique)
}

function Test-SurfaceFile {
    param([Parameter(Mandatory)]$File)
    return $File.relativePath -match '(?i)(^|/)presentation/|(?:ViewModel|Activity|Service)\.(kt|java)$'
}

function Get-MatchingImports {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Files,
        [Parameter(Mandatory)][scriptblock]$FilePredicate,
        [Parameter(Mandatory)][string]$Pattern,
        [string[]]$Allowlist = @()
    )

    $matchRows = [System.Collections.Generic.List[object]]::new()
    foreach ($file in $Files) {
        if (-not (& $FilePredicate $file)) {
            continue
        }
        foreach ($importName in @($file.imports)) {
            if ($importName -notmatch $Pattern) {
                continue
            }
            if ($Allowlist -contains $importName) {
                continue
            }
            $matchRows.Add([pscustomobject]@{
                path = $file.relativePath
                import = $importName
            })
        }
    }
    return @($matchRows)
}

function New-CountMetrics {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Occurrences)
    return [ordered]@{
        imports = @($Occurrences).Count
        files = @($Occurrences | Select-Object -ExpandProperty path -Unique).Count
    }
}

function Get-SourceLineTotal {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Files)

    if ($Files.Count -eq 0) {
        return 0
    }
    return [int](($Files | Measure-Object lineCount -Sum).Sum)
}

function Test-DebtLimits {
    param(
        [Parameter(Mandatory)]$Violation,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Metrics
    )

    foreach ($property in $Violation.limits.PSObject.Properties) {
        $metricName = $property.Name
        if (-not $Metrics.Contains($metricName)) {
            Add-Finding -Code 'METRIC_UNAVAILABLE' -Severity 'P1' `
                -StableId $Violation.id -Subject "$($Violation.id):$metricName" `
                -Message "Guard did not produce required metric '$metricName'."
            continue
        }
        $limit = [int]$property.Value
        $observed = [int]$Metrics[$metricName]
        if ($observed -gt $limit) {
            Add-Finding -Code 'DEBT_RATCHET' -Severity 'P1' `
                -StableId $Violation.id -Subject "$($Violation.id):$metricName" `
                -Message "Metric '$metricName' increased from $limit to $observed."
        }
    }
}

try {
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedBaselinePath = [System.IO.Path]::GetFullPath($BaselinePath)
    if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) {
        throw "Repository root not found: $resolvedRoot"
    }

    $baselineText = Read-Utf8Text -Path $resolvedBaselinePath
    $baseline = $baselineText | ConvertFrom-Json
    $baselineSha256 = (Get-FileHash -LiteralPath $resolvedBaselinePath -Algorithm SHA256).Hash.ToLowerInvariant()

    $settingsPath = Join-Path $resolvedRoot 'settings.gradle.kts'
    $settingsText = Read-Utf8Text -Path $settingsPath
    $includeMatches = [regex]::Matches($settingsText, 'include\s*\(\s*"(:[^"]+)"\s*\)')
    $includedModules = @(
        $includeMatches |
            ForEach-Object { $_.Groups[1].Value.TrimStart(':').Replace(':', '/') } |
            Sort-Object -Unique
    )
    $expectedOwnerModules = @($baseline.ownerModules | Sort-Object -Unique)

    $duplicateIncludes = @(
        $includeMatches |
            ForEach-Object { $_.Groups[1].Value.TrimStart(':').Replace(':', '/') } |
            Group-Object |
            Where-Object Count -gt 1
    )
    foreach ($duplicateInclude in $duplicateIncludes) {
        Add-Finding -Code 'OWNER_PATH_AMBIGUITY' -Severity 'P1' `
            -Subject $duplicateInclude.Name `
            -Message 'The same Gradle module is included more than once.'
    }

    $moduleDiff = Compare-Object -ReferenceObject $expectedOwnerModules -DifferenceObject $includedModules
    foreach ($difference in @($moduleDiff)) {
        $side = if ($difference.SideIndicator -eq '<=') { 'missing' } else { 'unexpected' }
        Add-Finding -Code 'MODULE_REGISTRY_DRIFT' -Severity 'P1' `
            -Subject "${side}:$($difference.InputObject)" `
            -Message "Gradle module registry has an $side module."
    }

    $sourceRoots = [System.Collections.Generic.List[object]]::new()
    foreach ($moduleName in $includedModules) {
        $moduleRoot = Join-Path $resolvedRoot $moduleName.Replace('/', '\')
        $buildPath = Join-Path $moduleRoot 'build.gradle.kts'
        if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
            Add-Finding -Code 'MODULE_BUILD_FILE_MISSING' -Severity 'P1' `
                -Subject $moduleName `
                -Message "Missing $moduleName/build.gradle.kts."
        }
        $sourceRoots.Add([pscustomobject]@{
            module = $moduleName
            path = Join-Path $moduleRoot 'src\main'
        })
    }

    for ($left = 0; $left -lt $sourceRoots.Count; $left++) {
        for ($right = $left + 1; $right -lt $sourceRoots.Count; $right++) {
            $leftRoot = $sourceRoots[$left]
            $rightRoot = $sourceRoots[$right]
            if ((Test-PathWithin -Child $leftRoot.path -Parent $rightRoot.path) -or
                (Test-PathWithin -Child $rightRoot.path -Parent $leftRoot.path)) {
                Add-Finding -Code 'OWNER_PATH_AMBIGUITY' -Severity 'P1' `
                    -Subject "$($leftRoot.module)|$($rightRoot.module)" `
                    -Message 'Module src/main roots overlap; path owner would require longest-prefix guessing.'
            }
        }
    }

    $sourceFiles = [System.Collections.Generic.List[object]]::new()
    $seenSourcePaths = @{}
    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path -LiteralPath $sourceRoot.path -PathType Container)) {
            continue
        }
        foreach ($file in Get-ChildItem -LiteralPath $sourceRoot.path -Recurse -File -Include *.kt, *.java) {
            $fullPath = [System.IO.Path]::GetFullPath($file.FullName)
            if ($seenSourcePaths.ContainsKey($fullPath)) {
                continue
            }
            $seenSourcePaths[$fullPath] = $true

            $ownerCandidates = @(
                $sourceRoots |
                    Where-Object { Test-PathWithin -Child $fullPath -Parent $_.path } |
                    ForEach-Object module |
                    Sort-Object -Unique
            )
            if ($ownerCandidates.Count -ne 1) {
                Add-Finding -Code 'OWNER_PATH_AMBIGUITY' -Severity 'P1' `
                    -Subject (Normalize-RelativePath ([System.IO.Path]::GetRelativePath($resolvedRoot, $fullPath))) `
                    -Message "Source file resolves to $($ownerCandidates.Count) module owners."
                continue
            }

            $text = Read-Utf8Text -Path $fullPath
            $packageMatch = [regex]::Match($text, '(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)')
            $packageName = if ($packageMatch.Success) { $packageMatch.Groups[1].Value } else { '' }
            $imports = [System.Collections.Generic.List[string]]::new()
            $importPattern = '(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)(\.\*)?(?:\s+as\s+[A-Za-z_][A-Za-z0-9_]*)?\s*$'
            foreach ($importMatch in [regex]::Matches($text, $importPattern)) {
                $suffix = if ($importMatch.Groups[2].Success) { '.*' } else { '' }
                $imports.Add(($importMatch.Groups[1].Value + $suffix))
            }
            $lineCount = [regex]::Matches($text, "`n").Count
            if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) {
                $lineCount++
            }
            $sourceFiles.Add([pscustomobject]@{
                module = $ownerCandidates[0]
                fullPath = $fullPath
                relativePath = Normalize-RelativePath ([System.IO.Path]::GetRelativePath($resolvedRoot, $fullPath))
                text = $text
                packageName = $packageName
                imports = @($imports)
                lineCount = $lineCount
                declarations = @(
                    [regex]::Matches(
                        $text,
                        '(?m)^(?:@\w+(?:\([^\n]*\))?\s*)*(?:(?:public|protected|private|internal|abstract|open|sealed|data|enum|annotation|value|inline|fun|suspend)\s+)*(?:class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)'
                    ) |
                        ForEach-Object { $_.Groups[1].Value }
                )
            })
        }
    }

    $typeOwners = [System.Collections.Generic.Dictionary[string, object]]::new()
    $fqcnModules = @($baseline.fqcnOwnerModules)
    foreach ($file in $sourceFiles) {
        if ($fqcnModules -notcontains $file.module -or [string]::IsNullOrEmpty($file.packageName)) {
            continue
        }
        foreach ($declaration in @($file.declarations)) {
            $fqcn = "$($file.packageName).$declaration"
            if (-not $typeOwners.ContainsKey($fqcn)) {
                $typeOwners[$fqcn] = [System.Collections.Generic.List[string]]::new()
            }
            if (-not $typeOwners[$fqcn].Contains($file.module)) {
                $typeOwners[$fqcn].Add($file.module)
            }
        }
    }

    $fqcnAmbiguities = @()
    foreach ($entry in $typeOwners.GetEnumerator()) {
        $owners = @($entry.Value | Sort-Object -Unique)
        if ($owners.Count -gt 1) {
            $fqcnAmbiguities += $entry.Key
            Add-Finding -Code 'FQCN_OWNER_AMBIGUITY' -Severity 'P1' `
                -Subject $entry.Key `
                -Message "FQCN is declared by multiple modules: $($owners -join ', ')."
        }
    }

    function Resolve-ImportOwner {
        param([Parameter(Mandatory)][string]$ImportName)

        if ($ImportName.EndsWith('.*', [System.StringComparison]::Ordinal)) {
            return @()
        }
        $segments = $ImportName.Split('.')
        $owners = [System.Collections.Generic.List[string]]::new()
        for ($segmentCount = $segments.Count; $segmentCount -ge 2; $segmentCount--) {
            $candidate = $segments[0..($segmentCount - 1)] -join '.'
            if ($typeOwners.ContainsKey($candidate)) {
                foreach ($owner in $typeOwners[$candidate]) {
                    if (-not $owners.Contains($owner)) {
                        $owners.Add($owner)
                    }
                }
            }
        }
        return @($owners | Sort-Object -Unique)
    }

    $governedModules = @($baseline.governedModules)
    $includedModuleSet = @{}
    foreach ($moduleName in $includedModules) {
        $includedModuleSet[$moduleName] = $true
    }

    $observedInternalEdges = [System.Collections.Generic.List[object]]::new()
    $observedExternalEdges = [System.Collections.Generic.List[object]]::new()
    $duplicateBuildDependencyCount = 0
    foreach ($moduleName in $governedModules) {
        $buildPath = Join-Path $resolvedRoot "$($moduleName.Replace('/', '\'))\build.gradle.kts"
        if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
            continue
        }
        $buildText = Read-Utf8Text -Path $buildPath
        foreach ($target in @(Get-ProjectTargets -Text $buildText)) {
            if (-not $includedModuleSet.ContainsKey($target)) {
                Add-Finding -Code 'PROJECT_EDGE_TARGET_UNKNOWN' -Severity 'P1' `
                    -Subject "$moduleName->$target" `
                    -Message 'Project dependency does not target a module declared in settings.gradle.kts.'
                continue
            }
            $edge = [pscustomobject]@{
                from = $moduleName
                to = $target
                key = "$moduleName->$target"
            }
            if ($governedModules -contains $target) {
                $observedInternalEdges.Add($edge)
            }
            else {
                $observedExternalEdges.Add($edge)
            }
        }
        if ($moduleName -eq 'modes/film') {
            $duplicateBuildDependencyCount = [regex]::Matches(
                $buildText,
                '(?m)^\s*(?:implementation|api)\s*\(\s*libs\.jellyfin\.core\s*\)'
            ).Count
        }
    }

    $expectedInternalKeys = @(
        $baseline.moduleEdges | ForEach-Object { "$($_.from)->$($_.to)" } | Sort-Object -Unique
    )
    $expectedExternalKeys = @(
        $baseline.externalEdges | ForEach-Object { "$($_.from)->$($_.to)" } | Sort-Object -Unique
    )
    $observedInternalKeys = @($observedInternalEdges | ForEach-Object key | Sort-Object -Unique)
    $observedExternalKeys = @($observedExternalEdges | ForEach-Object key | Sort-Object -Unique)

    foreach ($missingEdge in @(Compare-Object -ReferenceObject $expectedInternalKeys -DifferenceObject $observedInternalKeys |
            Where-Object SideIndicator -eq '<=' | ForEach-Object InputObject)) {
        Add-Finding -Code 'DIRECT_EDGE_MISSING' -Severity 'P1' `
            -Subject $missingEdge -Message 'Frozen internal direct project edge is missing.'
    }
    foreach ($unexpectedEdge in @(Compare-Object -ReferenceObject $expectedInternalKeys -DifferenceObject $observedInternalKeys |
            Where-Object SideIndicator -eq '=>' | ForEach-Object InputObject)) {
        Add-Finding -Code 'DIRECT_EDGE_UNEXPECTED' -Severity 'P1' `
            -Subject $unexpectedEdge -Message 'Unexpected internal direct project edge was declared.'
    }
    foreach ($missingEdge in @(Compare-Object -ReferenceObject $expectedExternalKeys -DifferenceObject $observedExternalKeys |
            Where-Object SideIndicator -eq '<=' | ForEach-Object InputObject)) {
        Add-Finding -Code 'DIRECT_EDGE_MISSING' -Severity 'P1' `
            -Subject $missingEdge -Message 'Frozen external direct project edge is missing.'
    }
    foreach ($unexpectedEdge in @(Compare-Object -ReferenceObject $expectedExternalKeys -DifferenceObject $observedExternalKeys |
            Where-Object SideIndicator -eq '=>' | ForEach-Object InputObject)) {
        Add-Finding -Code 'DIRECT_EDGE_UNEXPECTED' -Severity 'P1' `
            -Subject $unexpectedEdge -Message 'Unexpected external direct project edge was declared.'
    }

    $adjacency = @{}
    foreach ($edge in $observedInternalEdges) {
        if (-not $adjacency.ContainsKey($edge.from)) {
            $adjacency[$edge.from] = [System.Collections.Generic.List[string]]::new()
        }
        if (-not $adjacency[$edge.from].Contains($edge.to)) {
            $adjacency[$edge.from].Add($edge.to)
        }
    }
    $cycles = @(Find-Cycles -Adjacency $adjacency -Nodes $governedModules)
    foreach ($cycle in $cycles) {
        Add-Finding -Code 'DEPENDENCY_CYCLE' -Severity 'P1' `
            -Subject $cycle -Message 'Observed governed module graph contains a cycle.'
    }

    $rules = $baseline.rules
    $dataPattern = '^(' + ((@($rules.dataImplementationImportPrefixes) | ForEach-Object {
        [regex]::Escape($_)
    }) -join '|') + ')'
    $dataAllowlist = @($rules.dataImplementationImportAllowlist)
    $appLocalPattern = '^' + [regex]::Escape($rules.playerLocalAppPrefix)
    $musicControllerPattern = '^' + [regex]::Escape($rules.musicControllerImport) + '$'
    $musicAudioPattern = '^' + [regex]::Escape($rules.musicAudioImportPrefix)
    $filmPattern = '^' + [regex]::Escape($rules.filmPresentationImportPrefix)
    $languagePattern = '^' + [regex]::Escape($rules.languageMatcherImport) + '$'
    $surfacePredicate = { param($file) Test-SurfaceFile -File $file }

    $appDataOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'app/phone' -and (Test-SurfaceFile -File $file)
    } -Pattern $dataPattern -Allowlist $dataAllowlist)
    $playerDataOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'player/local' -and (Test-SurfaceFile -File $file)
    } -Pattern $dataPattern -Allowlist $dataAllowlist)
    $appPlayerLocalOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'app/phone'
    } -Pattern $appLocalPattern)
    $musicControllerOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'modes/music'
    } -Pattern $musicControllerPattern)
    $musicAudioOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'modes/music'
    } -Pattern $musicAudioPattern)
    $filmOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'app/phone' -and $file.relativePath -match '(^|/)presentation/film/'
    } -Pattern $filmPattern)
    $languageOccurrences = @(Get-MatchingImports -Files $sourceFiles -FilePredicate {
        param($file) $file.module -eq 'player/local'
    } -Pattern $languagePattern)

    $coreDataImportCount = 0
    $corePlayerCoreImportCount = 0
    foreach ($file in $sourceFiles | Where-Object module -eq 'core') {
        foreach ($importName in @($file.imports)) {
            $owners = @(Resolve-ImportOwner -ImportName $importName)
            if ($owners.Count -eq 1 -and $owners[0] -eq 'data') {
                $coreDataImportCount++
            }
            if ($owners.Count -eq 1 -and $owners[0] -eq 'player/core') {
                $corePlayerCoreImportCount++
            }
        }
    }

    $coreFiles = @($sourceFiles | Where-Object module -eq 'core')
    $playerLocalFiles = @($sourceFiles | Where-Object module -eq 'player/local')
    $modelsFiles = @($sourceFiles | Where-Object packageName -eq $rules.sharedPackages.models)
    $utilsFiles = @($sourceFiles | Where-Object packageName -eq $rules.sharedPackages.utils)
    $updateFiles = @($sourceFiles | Where-Object packageName -eq $rules.sharedPackages.update)

    foreach ($violation in @($baseline.violations)) {
        switch ($violation.detector) {
            'shared-models' {
                $metrics = [ordered]@{
                    coreFiles = @($modelsFiles | Where-Object module -eq 'core').Count
                    dataFiles = @($modelsFiles | Where-Object module -eq 'data').Count
                    totalFiles = $modelsFiles.Count
                }
            }
            'shared-utils' {
                $metrics = [ordered]@{
                    appFiles = @($utilsFiles | Where-Object module -eq 'app/phone').Count
                    coreFiles = @($utilsFiles | Where-Object module -eq 'core').Count
                    dataFiles = @($utilsFiles | Where-Object module -eq 'data').Count
                    totalFiles = $utilsFiles.Count
                }
            }
            'shared-update' {
                $metrics = [ordered]@{
                    coreFiles = @($updateFiles | Where-Object module -eq 'core').Count
                    dataFiles = @($updateFiles | Where-Object module -eq 'data').Count
                    totalFiles = $updateFiles.Count
                }
            }
            'app-data-impl' {
                $metrics = New-CountMetrics -Occurrences $appDataOccurrences
            }
            'core-layer-collapse' {
                $metrics = [ordered]@{
                    coreToDataImports = $coreDataImportCount
                    coreToPlayerCoreImports = $corePlayerCoreImportCount
                    coreSourceFiles = $coreFiles.Count
                    coreSourceLines = Get-SourceLineTotal -Files $coreFiles
                }
            }
            'player-local-data-impl' {
                $metrics = New-CountMetrics -Occurrences $playerDataOccurrences
                $metrics.playerLocalSourceFiles = $playerLocalFiles.Count
                $metrics.playerLocalSourceLines = Get-SourceLineTotal -Files $playerLocalFiles
            }
            'music-player-local' {
                $metrics = [ordered]@{
                    controllerImports = $musicControllerOccurrences.Count
                    controllerFiles = @($musicControllerOccurrences | Select-Object -ExpandProperty path -Unique).Count
                    audioImplementationImports = $musicAudioOccurrences.Count
                    audioFiles = @($musicAudioOccurrences | Select-Object -ExpandProperty path -Unique).Count
                    totalImports = $musicControllerOccurrences.Count + $musicAudioOccurrences.Count
                }
            }
            'app-player-local' {
                $metrics = New-CountMetrics -Occurrences $appPlayerLocalOccurrences
            }
            'film-ui-split' {
                $metrics = [ordered]@{
                    imports = $filmOccurrences.Count
                    appFiles = @($filmOccurrences | Select-Object -ExpandProperty path -Unique).Count
                }
            }
            'language-shadowing' {
                $metrics = New-CountMetrics -Occurrences $languageOccurrences
            }
            'duplicate-build-dependency' {
                $metrics = [ordered]@{
                    declarations = $duplicateBuildDependencyCount
                }
            }
            default {
                Add-Finding -Code 'DETECTOR_UNKNOWN' -Severity 'P1' `
                    -StableId $violation.id -Subject $violation.detector `
                    -Message "Unknown debt detector '$($violation.detector)'."
                continue
            }
        }
        Test-DebtLimits -Violation $violation -Metrics $metrics
    }

    $sortedFindings = @(
        $findings |
            Sort-Object code, stableId, subject |
            ForEach-Object {
                [pscustomobject][ordered]@{
                    code = $_.code
                    severity = $_.severity
                    stableId = $_.stableId
                    subject = $_.subject
                    message = $_.message
                }
            }
    )
    $exitCode = if ($sortedFindings.Count -eq 0) { 0 } else { 2 }
    $summary = [ordered]@{
        schemaVersion = '1.0'
        verdict = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
        exitCode = $exitCode
        baseline = [ordered]@{
            path = Normalize-RelativePath ([System.IO.Path]::GetRelativePath($resolvedRoot, $resolvedBaselinePath))
            sha256 = $baselineSha256
        }
        counts = [ordered]@{
            ownerModules = $includedModules.Count
            governedModules = $governedModules.Count
            sourceFiles = $sourceFiles.Count
            fqcnOwners = $typeOwners.Count
            expectedInternalEdges = $expectedInternalKeys.Count
            observedInternalEdges = $observedInternalKeys.Count
            expectedExternalEdges = $expectedExternalKeys.Count
            observedExternalEdges = $observedExternalKeys.Count
            cycles = $cycles.Count
            fqcnAmbiguities = $fqcnAmbiguities.Count
            debtRatchetFailures = @($sortedFindings | Where-Object code -eq 'DEBT_RATCHET').Count
            findings = $sortedFindings.Count
        }
        staticInputLimits = $staticInputLimits
        findings = $sortedFindings
    }
    [Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 12 -Compress))
    exit $exitCode
}
catch {
    $errorSummary = [ordered]@{
        schemaVersion = '1.0'
        verdict = 'INPUT_ERROR'
        exitCode = 3
        counts = [ordered]@{
            findings = 1
        }
        staticInputLimits = $staticInputLimits
        findings = @(
            [ordered]@{
                code = 'INPUT_INVALID'
                severity = 'P1'
                stableId = ''
                subject = ''
                message = "$($_.Exception.Message) [$($_.ScriptStackTrace)]"
            }
        )
    }
    [Console]::Out.WriteLine(($errorSummary | ConvertTo-Json -Depth 12 -Compress))
    exit 3
}
