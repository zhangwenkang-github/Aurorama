[CmdletBinding()]
param(
    [ValidateSet("none", "stale-response-overwrite", "cross-scope-hit", "empty-as-error")]
    [string]$Mutation = "none",
    [switch]$GenerateManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false, $true)
$Failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param(
        [string]$Code,
        [string]$Message
    )
    $Failures.Add("${Code}: ${Message}")
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Code,
        [string]$Message
    )
    if (-not $Condition) {
        Add-Failure -Code $Code -Message $Message
    }
}

function Get-RequiredPath {
    param([string]$RelativePath)
    $FullPath = Join-Path $Root $RelativePath
    if (-not (Test-Path -LiteralPath $FullPath -PathType Leaf)) {
        throw "Missing required artifact: $RelativePath"
    }
    return $FullPath
}

function Read-Utf8Text {
    param([string]$RelativePath)
    $FullPath = Get-RequiredPath -RelativePath $RelativePath
    $Bytes = [System.IO.File]::ReadAllBytes($FullPath)
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        Add-Failure -Code "UTF8_BOM" -Message "$RelativePath must not contain a BOM"
    }
    try {
        $Text = $Utf8NoBom.GetString($Bytes)
    }
    catch {
        throw "$RelativePath is not valid UTF-8"
    }
    if ($Text.Contains("`r")) {
        Add-Failure -Code "LF_ONLY" -Message "$RelativePath contains CR; expected LF-only"
    }
    return $Text
}

function Read-Utf8Json {
    param([string]$RelativePath)
    $Text = Read-Utf8Text -RelativePath $RelativePath
    return ($Text | ConvertFrom-Json -Depth 64)
}

function Get-PropertyValue {
    param(
        [object]$Node,
        [string]$Name
    )
    if ($null -eq $Node) {
        return $null
    }
    $Property = $Node.PSObject.Properties[$Name]
    if ($null -eq $Property) {
        return $null
    }
    return $Property.Value
}

function Normalize-Query {
    param([string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    $FormKc = $Value.Normalize([System.Text.NormalizationForm]::FormKC)
    $Collapsed = [regex]::Replace($FormKc, "\s+", " ")
    return $Collapsed.Trim()
}

function ConvertTo-JsonPrimitive {
    param([object]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return ($Value | ConvertTo-Json -Compress -Depth 20)
}

function New-QueryShape {
    param(
        [object]$Matrix,
        [string]$Scope,
        [string]$Query
    )
    $Definition = $Matrix.scopeDefinitions | Where-Object { $_.scope -eq $Scope } | Select-Object -First 1
    if ($null -eq $Definition) {
        throw "Scope definition missing: $Scope"
    }
    return [ordered]@{
        schemaVersion = "g1-search-query-shape/v1"
        serverId = $Matrix.namespaceTemplate.serverId
        userId = $Matrix.namespaceTemplate.userId
        searchTerm = (Normalize-Query $Query)
        scope = $Scope
        includeItemTypes = @($Definition.includeItemTypes)
        excludeItemTypes = @()
        parentId = $null
        recursive = $true
        startIndex = 0
        limit = [int]$Matrix.baselinePaging.limit
        sortBy = @($Matrix.baselineSort.sortBy)
        sortOrder = @($Matrix.baselineSort.sortOrder)
        fields = @()
        enableUserData = $true
        imageTypes = @()
        imageTypeLimit = $null
        enableImageTypes = @()
        enableImages = $false
        enableTotalRecordCount = $true
        filters = [ordered]@{}
    }
}

function Get-QueryShapeCanonicalJson {
    param([object]$Shape)
    $Keys = @(
        "enableImages",
        "enableImageTypes",
        "enableTotalRecordCount",
        "enableUserData",
        "excludeItemTypes",
        "fields",
        "filters",
        "imageTypeLimit",
        "imageTypes",
        "includeItemTypes",
        "limit",
        "parentId",
        "recursive",
        "schemaVersion",
        "scope",
        "searchTerm",
        "serverId",
        "sortBy",
        "sortOrder",
        "startIndex",
        "userId"
    )
    $Parts = foreach ($Key in $Keys) {
        $KeyJson = ConvertTo-JsonPrimitive $Key
        $ValueJson = ConvertTo-JsonPrimitive $Shape[$Key]
        "$KeyJson`:$ValueJson"
    }
    return "{" + ($Parts -join ",") + "}"
}

function Get-QueryShapeSha256 {
    param([object]$Shape)
    $Canonical = Get-QueryShapeCanonicalJson -Shape $Shape
    $Bytes = [System.Text.Encoding]::UTF8.GetBytes($Canonical)
    return ([System.Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($Bytes)
    )).ToLowerInvariant()
}

function Classify-State {
    param([object]$Conditions)
    $Phase = Get-PropertyValue $Conditions "phase"
    $Cancelled = [bool](Get-PropertyValue $Conditions "cancelled")
    $Offline = [bool](Get-PropertyValue $Conditions "offline")
    $CacheMatch = [bool](Get-PropertyValue $Conditions "cacheMatch")
    $CachedContent = [bool](Get-PropertyValue $Conditions "cachedContent")
    $DeadlineExpired = [bool](Get-PropertyValue $Conditions "deadlineExpired")
    $HttpStatus = Get-PropertyValue $Conditions "httpStatus"
    $ItemCount = [int](Get-PropertyValue $Conditions "itemCount")
    $SourceFailures = [int](Get-PropertyValue $Conditions "sourceFailures")
    $ScopeMismatchCount = [int](Get-PropertyValue $Conditions "scopeMismatchCount")
    $ServerCompleted = [bool](Get-PropertyValue $Conditions "serverCompleted")
    $LocalCompleted = [bool](Get-PropertyValue $Conditions "localCompleted")
    $ActiveGeneration = Get-PropertyValue $Conditions "activeGeneration"
    $ResponseGeneration = Get-PropertyValue $Conditions "responseGeneration"
    $ActiveShape = Get-PropertyValue $Conditions "activeQueryShape"
    $ResponseShape = Get-PropertyValue $Conditions "responseQueryShape"

    if ($Phase -eq "IN_FLIGHT") {
        if ($CachedContent) {
            return [pscustomobject]@{
                visibleState = "LOADING_STALE"
                errorKind = "NONE"
                stale = $true
                noOp = $false
            }
        }
        return [pscustomobject]@{
            visibleState = "LOADING"
            errorKind = "NONE"
            stale = $false
            noOp = $false
        }
    }
    if ($Cancelled) {
        return [pscustomobject]@{
            visibleState = "CANCELLED"
            errorKind = "NONE"
            stale = $false
            noOp = $false
        }
    }
    if ($Offline) {
        if ($CacheMatch) {
            return [pscustomobject]@{
                visibleState = "OFFLINE_STALE"
                errorKind = "OFFLINE"
                stale = $true
                noOp = $false
            }
        }
        return [pscustomobject]@{
            visibleState = "OFFLINE_NO_CACHE"
            errorKind = "OFFLINE"
            stale = $false
            noOp = $false
        }
    }
    if (
        ($null -ne $ActiveGeneration -and $null -ne $ResponseGeneration -and [int]$ResponseGeneration -lt [int]$ActiveGeneration) -or
        ($null -ne $ActiveShape -and $null -ne $ResponseShape -and $ActiveShape -ne $ResponseShape)
    ) {
        return [pscustomobject]@{
            visibleState = "DROPPED_STALE_RESPONSE"
            errorKind = "NONE"
            stale = $true
            noOp = $true
        }
    }
    if ($DeadlineExpired) {
        return [pscustomobject]@{
            visibleState = "ERROR"
            errorKind = "TIMEOUT_DEADLINE"
            stale = $false
            noOp = $false
        }
    }
    if ($null -ne $HttpStatus) {
        switch ([int]$HttpStatus) {
            401 {
                return [pscustomobject]@{
                    visibleState = "ERROR"
                    errorKind = "AUTHENTICATION"
                    stale = $false
                    noOp = $false
                }
            }
            403 {
                return [pscustomobject]@{
                    visibleState = "ERROR"
                    errorKind = "PERMISSION"
                    stale = $false
                    noOp = $false
                }
            }
            { $_ -ge 500 } {
                return [pscustomobject]@{
                    visibleState = "ERROR"
                    errorKind = "RETRYABLE"
                    stale = $false
                    noOp = $false
                }
            }
        }
    }
    if ($ItemCount -gt 0 -and $ScopeMismatchCount -ge $ItemCount) {
        return [pscustomobject]@{
            visibleState = "PROTOCOL_ERROR"
            errorKind = "SCOPE_MISMATCH"
            stale = $false
            noOp = $false
        }
    }
    if ($ItemCount -gt 0 -and $SourceFailures -gt 0) {
        return [pscustomobject]@{
            visibleState = "PARTIAL"
            errorKind = "SOURCE_PARTIAL"
            stale = $false
            noOp = $false
        }
    }
    if ($ItemCount -gt 0) {
        return [pscustomobject]@{
            visibleState = "RESULTS"
            errorKind = "NONE"
            stale = $false
            noOp = $false
        }
    }
    if ($ServerCompleted -and $LocalCompleted -and $ItemCount -eq 0 -and $SourceFailures -eq 0) {
        return [pscustomobject]@{
            visibleState = "EMPTY"
            errorKind = "NONE"
            stale = $false
            noOp = $false
        }
    }
    if ($SourceFailures -gt 0 -or -not $ServerCompleted -or -not $LocalCompleted) {
        return [pscustomobject]@{
            visibleState = "PARTIAL"
            errorKind = "SOURCE_PARTIAL"
            stale = $false
            noOp = $false
        }
    }
    return [pscustomobject]@{
        visibleState = "LOADING"
        errorKind = "NONE"
        stale = $false
        noOp = $false
    }
}

function Get-StableIdentity {
    param([object]$Item)
    $Source = [string](Get-PropertyValue $Item "source")
    if ($Source -eq "SERVER") {
        return "server|$($Item.serverId)|$($Item.userId)|$($Item.itemType)|$($Item.itemId)"
    }
    if ($Source -eq "DOWNLOAD") {
        if ([bool](Get-PropertyValue $Item "provenanceKnown")) {
            return "server|$($Item.serverId)|$($Item.userId)|$($Item.itemType)|$($Item.itemId)"
        }
        return "download|legacy|$($Item.downloadRowId)"
    }
    if ($Source -eq "LOCAL") {
        return "local|$($Item.libraryId)|$($Item.localStableId)|$($Item.itemType)"
    }
    throw "Unsupported source: $Source"
}

function Get-TypeRank {
    param([string]$ItemType)
    switch ($ItemType) {
        "Movie" { return 0 }
        "Series" { return 1 }
        "Episode" { return 2 }
        "Video" { return 3 }
        "Audio" { return 4 }
        "Book" { return 5 }
        default { return 99 }
    }
}

function Get-OrderedIdentities {
    param([object[]]$Items)
    $Unique = [ordered]@{}
    foreach ($Item in $Items) {
        $Identity = Get-StableIdentity -Item $Item
        if (-not $Unique.Contains($Identity)) {
            $Unique[$Identity] = [pscustomobject]@{
                identity = $Identity
                sortName = [string](Get-PropertyValue $Item "sortName")
                itemType = [string](Get-PropertyValue $Item "itemType")
            }
        }
    }
    return @(
        $Unique.Values |
            Sort-Object `
                @{ Expression = { $_.sortName.ToLowerInvariant() }; Ascending = $true },
                @{ Expression = { Get-TypeRank $_.itemType }; Ascending = $true },
                @{ Expression = { $_.identity }; Ascending = $true } |
            ForEach-Object { $_.identity }
    )
}

function Get-RestoreOutcome {
    param([object]$Case)
    $SameProcess = [bool]$Case.sameProcess
    $ShapeMatches = [bool]$Case.shapeMatches
    $SelectionPresent = [bool]$Case.selectionPresent
    if (-not $SameProcess) {
        return [ordered]@{
            query = "RESTORED"
            scope = "RESTORED"
            results = "RELOAD_REQUIRED"
            scroll = "RUNTIME_REVALIDATE"
            selection = "RUNTIME_REVALIDATE"
        }
    }
    if (-not $ShapeMatches) {
        return [ordered]@{
            query = "RESTORED"
            scope = "RESTORED"
            results = "RELOAD_REQUIRED"
            scroll = "DEFERRED_UNTIL_SAME_SHAPE"
            selection = "DEFERRED_UNTIL_SAME_SHAPE"
        }
    }
    return [ordered]@{
        query = "RESTORED"
        scope = "RESTORED"
        results = "RESTORED"
        scroll = "RESTORED"
        selection = if ($SelectionPresent) { "RESTORED" } else { "CLEARED_MISSING_IDENTITY" }
    }
}

function ConvertTo-OrderedObject {
    param([object]$Value)
    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $Output = [ordered]@{}
        foreach ($Key in $Value.Keys) {
            $Output[$Key] = ConvertTo-OrderedObject $Value[$Key]
        }
        return $Output
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        return @($Value | ForEach-Object { ConvertTo-OrderedObject $_ })
    }
    if ($Value -is [pscustomobject]) {
        $Output = [ordered]@{}
        $Names = @($Value.PSObject.Properties.Name)
        [Array]::Sort($Names, [System.StringComparer]::Ordinal)
        foreach ($Name in $Names) {
            $Output[$Name] = ConvertTo-OrderedObject $Value.PSObject.Properties[$Name].Value
        }
        return $Output
    }
    return $Value
}

function Write-LfJson {
    param(
        [string]$RelativePath,
        [object]$Value
    )
    $FullPath = Join-Path $Root $RelativePath
    $Json = $Value | ConvertTo-Json -Depth 64
    $Json = $Json.Replace("`r`n", "`n").Replace("`r", "`n") + "`n"
    [System.IO.File]::WriteAllText($FullPath, $Json, $Utf8NoBom)
}

try {
    $RequiredArtifacts = @(
        "scope-query-matrix.json",
        "route-state-machine.json",
        "stable-identity-matrix.json",
        "user-commitment-matrix.json",
        "fixtures/search-contract-cases.json",
        "README.md",
        "report.json",
        "commands.log"
    )
    foreach ($Artifact in $RequiredArtifacts) {
        Get-RequiredPath -RelativePath $Artifact | Out-Null
    }

    $ScopeMatrix = Read-Utf8Json "scope-query-matrix.json"
    $StateMachine = Read-Utf8Json "route-state-machine.json"
    $IdentityMatrix = Read-Utf8Json "stable-identity-matrix.json"
    $CommitmentMatrix = Read-Utf8Json "user-commitment-matrix.json"
    $Fixtures = Read-Utf8Json "fixtures/search-contract-cases.json"
    $Report = Read-Utf8Json "report.json"

    switch ($Mutation) {
        "stale-response-overwrite" {
            $StateMachine.staleResponseCommit = "ALLOW"
        }
        "cross-scope-hit" {
            $Music = $ScopeMatrix.scopeDefinitions | Where-Object { $_.scope -eq "MUSIC" } | Select-Object -First 1
            $Music.includeItemTypes = @($Music.includeItemTypes) + @("Movie")
        }
        "empty-as-error" {
            $StateMachine.emptyClassification.visibleState = "ERROR"
        }
    }

    Assert-True ($ScopeMatrix.schemaVersion -eq "g1-scope-query-matrix/v1") "SCOPE_SCHEMA" "Unexpected scope matrix schema"
    Assert-True ($StateMachine.schemaVersion -eq "g1-route-state-machine/v1") "STATE_SCHEMA" "Unexpected state machine schema"
    Assert-True ($IdentityMatrix.schemaVersion -eq "g1-stable-identity-matrix/v1") "IDENTITY_SCHEMA" "Unexpected identity matrix schema"
    Assert-True ($CommitmentMatrix.schemaVersion -eq "g1-user-commitment-matrix/v1") "COMMITMENT_SCHEMA" "Unexpected commitment matrix schema"
    Assert-True ($Fixtures.schemaVersion -eq "g1-base001-fixtures/v1") "FIXTURE_SCHEMA" "Unexpected fixture schema"
    Assert-True ($Report.schemaVersion -eq "g1-base001-report/v1") "REPORT_SCHEMA" "Unexpected report schema"

    $ExpectedScopes = @("ALL", "VIDEO", "MUSIC", "BOOK")
    $ActualScopes = @($ScopeMatrix.scopeDefinitions | ForEach-Object { $_.scope })
    Assert-True (($ActualScopes -join ",") -eq ($ExpectedScopes -join ",")) "SCOPE_SET" "Scope order/set must be ALL,VIDEO,MUSIC,BOOK"

    $VideoSet = @("Movie", "Series", "Episode", "Video")
    $MusicSet = @("Audio")
    $BookSet = @("Book")
    $AllSet = @($VideoSet + $MusicSet + $BookSet)
    foreach ($Definition in $ScopeMatrix.scopeDefinitions) {
        $Types = @($Definition.includeItemTypes)
        switch ($Definition.scope) {
            "ALL" {
                Assert-True ((($Types -join ",") -eq ($AllSet -join ","))) "SCOPE_ALL_TYPES" "ALL types must be the ordered domain union"
            }
            "VIDEO" {
                Assert-True ((($Types -join ",") -eq ($VideoSet -join ","))) "SCOPE_VIDEO_TYPES" "VIDEO types must be Movie,Series,Episode,Video"
            }
            "MUSIC" {
                Assert-True ((($Types -join ",") -eq ($MusicSet -join ","))) "SCOPE_MUSIC_TYPES" "MUSIC types must be Audio"
            }
            "BOOK" {
                Assert-True ((($Types -join ",") -eq ($BookSet -join ","))) "SCOPE_BOOK_TYPES" "BOOK types must be Book"
            }
        }
    }
    Assert-True (@($VideoSet | Where-Object { $_ -in $MusicSet -or $_ -in $BookSet }).Count -eq 0) "SCOPE_VIDEO_DISJOINT" "VIDEO overlaps another domain"
    Assert-True (@($MusicSet | Where-Object { $_ -in $BookSet }).Count -eq 0) "SCOPE_MUSIC_DISJOINT" "MUSIC overlaps BOOK"
    Assert-True ([bool]$ScopeMatrix.queryIdentity.searchTermOnlyHashForbidden) "QUERY_TERM_ONLY_FORBIDDEN" "Query identity must include the behavior shape"

    $ShapeHashes = [ordered]@{}
    foreach ($Scope in $ExpectedScopes) {
        $Shape = New-QueryShape -Matrix $ScopeMatrix -Scope $Scope -Query $Fixtures.base.canonicalQuery
        $ShapeHashes[$Scope] = Get-QueryShapeSha256 -Shape $Shape
        Assert-True ($ShapeHashes[$Scope] -match "^[0-9a-f]{64}$") "QUERY_HASH_FORMAT" "$Scope query hash is not SHA-256 hex"
    }
    $UniqueHashes = @($ShapeHashes.Values | Select-Object -Unique)
    Assert-True ($UniqueHashes.Count -eq 4) "QUERY_SCOPE_COLLISION" "Scope query-shape hashes must be distinct"

    foreach ($Case in $Fixtures.queryNormalization) {
        $Actual = Normalize-Query -Value $Case.input
        Assert-True ($Actual -eq $Case.expected) "QUERY_NORMALIZE_$($Case.id)" "Expected '$($Case.expected)', got '$Actual'"
        Assert-True (([string]::IsNullOrWhiteSpace($Actual)) -eq [bool]$Case.blank) "QUERY_BLANK_$($Case.id)" "Blank-query classification mismatch"
    }

    foreach ($Case in $Fixtures.stateCases) {
        $Actual = Classify-State -Conditions $Case.conditions
        foreach ($Property in @("visibleState", "errorKind", "stale", "noOp")) {
            Assert-True ($Actual.$Property -eq $Case.expected.$Property) "STATE_$($Case.id)_$Property" "Expected $($Case.expected.$Property), got $($Actual.$Property)"
        }
    }

    Assert-True ($StateMachine.staleResponseCommit -eq "FORBIDDEN") "STALE_RESPONSE_COMMIT" "Late or mismatched responses must never commit"
    Assert-True ($StateMachine.emptyClassification.visibleState -eq "EMPTY") "EMPTY_NOT_ERROR" "HTTP 200 complete zero-result must remain EMPTY"
    Assert-True ($StateMachine.emptyClassification.requiresAllSourcesCompleted -eq $true) "EMPTY_COMPLETENESS" "EMPTY requires every source to settle successfully"
    Assert-True ($StateMachine.cancellation.isError -eq $false) "CANCELLED_NOT_ERROR" "Cancellation must not be classified as failure"
    Assert-True ($StateMachine.offline.isConfirmedEmpty -eq $false) "OFFLINE_NOT_EMPTY" "Offline must not become confirmed empty"

    foreach ($Case in $Fixtures.identityCases) {
        $Ordered = @(Get-OrderedIdentities -Items @($Case.items))
        $Set = @($Ordered | Sort-Object)
        if ($null -ne (Get-PropertyValue $Case "expectedIdentities")) {
            $ExpectedSet = @($Case.expectedIdentities | Sort-Object)
            Assert-True (($Set -join "|") -eq ($ExpectedSet -join "|")) "IDENTITY_SET_$($Case.id)" "Identity fold mismatch"
        }
        if ($null -ne (Get-PropertyValue $Case "expectedOrderedIdentities")) {
            $ExpectedOrdered = @($Case.expectedOrderedIdentities)
            Assert-True (($Ordered -join "|") -eq ($ExpectedOrdered -join "|")) "IDENTITY_ORDER_$($Case.id)" "Stable ordering mismatch"
        }
    }

    foreach ($Case in $Fixtures.restoreCases) {
        $Actual = Get-RestoreOutcome -Case $Case
        foreach ($Property in @("query", "scope", "results", "scroll", "selection")) {
            Assert-True ($Actual[$Property] -eq $Case.expected.$Property) "RESTORE_$($Case.id)_$Property" "Expected $($Case.expected.$Property), got $($Actual[$Property])"
        }
    }

    Assert-True (@($IdentityMatrix.rules).Count -ge 8) "IDENTITY_RULES_COUNT" "At least 8 identity rules are required"
    Assert-True (@($CommitmentMatrix.rows).Count -ge 10) "COMMITMENT_ROWS_COUNT" "At least 10 commitment rows are required"
    Assert-True ([int]$Report.contractCounts.scopes -eq @($ScopeMatrix.scopeDefinitions).Count) "REPORT_SCOPE_COUNT" "Report scope count drifted"
    Assert-True ([int]$Report.contractCounts.queryBehaviorFields -eq @($ScopeMatrix.queryShape.fields).Count) "REPORT_QUERY_FIELDS" "Report query-shape field count drifted"
    Assert-True ([int]$Report.contractCounts.routeStates -eq @($StateMachine.states).Count) "REPORT_STATE_COUNT" "Report state count drifted"
    Assert-True ([int]$Report.contractCounts.routeTransitions -eq @($StateMachine.transitions).Count) "REPORT_TRANSITION_COUNT" "Report transition count drifted"
    Assert-True ([int]$Report.contractCounts.identityRules -eq @($IdentityMatrix.rules).Count) "REPORT_IDENTITY_COUNT" "Report identity-rule count drifted"
    Assert-True ([int]$Report.contractCounts.commitmentRows -eq @($CommitmentMatrix.rows).Count) "REPORT_COMMITMENT_COUNT" "Report commitment-row count drifted"
    Assert-True ([int]$Report.contractCounts.fixtures.queryNormalization -eq @($Fixtures.queryNormalization).Count) "REPORT_FIXTURE_QUERY_COUNT" "Report query fixture count drifted"
    Assert-True ([int]$Report.contractCounts.fixtures.routeStates -eq @($Fixtures.stateCases).Count) "REPORT_FIXTURE_STATE_COUNT" "Report state fixture count drifted"
    Assert-True ([int]$Report.contractCounts.fixtures.identity -eq @($Fixtures.identityCases).Count) "REPORT_FIXTURE_IDENTITY_COUNT" "Report identity fixture count drifted"
    Assert-True ([int]$Report.contractCounts.fixtures.restore -eq @($Fixtures.restoreCases).Count) "REPORT_FIXTURE_RESTORE_COUNT" "Report restore fixture count drifted"
    $FixtureTotal = @($Fixtures.queryNormalization).Count + @($Fixtures.stateCases).Count + @($Fixtures.identityCases).Count + @($Fixtures.restoreCases).Count
    Assert-True ([int]$Report.contractCounts.fixtures.total -eq $FixtureTotal) "REPORT_FIXTURE_TOTAL" "Report total fixture count drifted"
    Assert-True ([int]$Report.contractCounts.mutations -eq @($Fixtures.mutations).Count) "REPORT_MUTATION_COUNT" "Report mutation count drifted"

    $RequiredClasses = @("A_STATIC_CONTRACT", "B_PRODUCTION_IMPLEMENTATION", "C_USER_PRODUCT_DECISION", "D_RUNTIME_DEVICE")
    foreach ($Class in $RequiredClasses) {
        Assert-True (@($CommitmentMatrix.rows | Where-Object { $_.classification -eq $Class }).Count -gt 0) "COMMITMENT_CLASS_$Class" "Required classification is absent"
    }

    $AllFiles = @(
        Get-ChildItem -LiteralPath $Root -File -Recurse |
            Where-Object { $_.Name -ne "manifest.json" } |
            Sort-Object FullName
    )
    Assert-True ($AllFiles.Count -gt 0) "ARTIFACT_EMPTY" "At least one artifact is required"

    $SensitiveRegex = "(?im)^\s*(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token)\s*[:=]\s*\S+"
    $BearerRegex = "(?im)\bbearer\s+[A-Za-z0-9._~+/=-]{16,}"
    $PrivateKeyRegex = "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
    foreach ($File in $AllFiles) {
        $Relative = [System.IO.Path]::GetRelativePath($Root, $File.FullName).Replace("\", "/")
        $Bytes = [System.IO.File]::ReadAllBytes($File.FullName)
        if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
            Add-Failure -Code "UTF8_BOM" -Message "$Relative must not contain a BOM"
        }
        try {
            $Text = $Utf8NoBom.GetString($Bytes)
        }
        catch {
            Add-Failure -Code "UTF8_INVALID" -Message "$Relative is not valid UTF-8"
            continue
        }
        if ($Text.Contains("`r")) {
            Add-Failure -Code "LF_ONLY" -Message "$Relative contains CR; expected LF-only"
        }
        if ($Relative -ne "verify-contract.ps1") {
            if ($Text -match $SensitiveRegex -or $Text -match $BearerRegex -or $Text -match $PrivateKeyRegex) {
                Add-Failure -Code "SENSITIVE_VALUE" -Message "$Relative contains a credential-like value"
            }
        }
        if ($File.Extension -eq ".json") {
            try {
                $Text | ConvertFrom-Json -Depth 64 | Out-Null
            }
            catch {
                Add-Failure -Code "JSON_PARSE" -Message "$Relative is not valid JSON"
            }
        }
    }

    if ($GenerateManifest -and $Failures.Count -eq 0) {
        $Entries = foreach ($File in $AllFiles) {
            $Relative = [System.IO.Path]::GetRelativePath($Root, $File.FullName).Replace("\", "/")
            $Bytes = [System.IO.File]::ReadAllBytes($File.FullName)
            [ordered]@{
                path = $Relative
                bytes = $Bytes.Length
                sha256 = ([System.Convert]::ToHexString(
                    [System.Security.Cryptography.SHA256]::HashData($Bytes)
                )).ToUpperInvariant()
            }
        }
        $TotalBytes = 0L
        foreach ($Entry in @($Entries)) {
            $TotalBytes += [long]$Entry["bytes"]
        }
        $Manifest = [ordered]@{
            schemaVersion = "g1-base001-manifest/v1"
            taskId = "G1-BASE-001"
            status = "STATIC_CONTRACT_COMPLETE_RUNTIME_UNVERIFIED"
            algorithm = "SHA-256"
            manifestExcludes = @("manifest.json")
            fileCount = @($Entries).Count
            totalBytes = $TotalBytes
            files = @($Entries)
        }
        Write-LfJson -RelativePath "manifest.json" -Value $Manifest
        Write-Output "MANIFEST_WRITTEN files=$($Manifest.fileCount) bytes=$($Manifest.totalBytes)"
    }

    if ((Test-Path -LiteralPath (Join-Path $Root "manifest.json") -PathType Leaf) -and -not $GenerateManifest) {
        $Manifest = Read-Utf8Json "manifest.json"
        Assert-True ($Manifest.schemaVersion -eq "g1-base001-manifest/v1") "MANIFEST_SCHEMA" "Unexpected manifest schema"
        Assert-True ($Manifest.algorithm -eq "SHA-256") "MANIFEST_ALGORITHM" "Manifest algorithm must be SHA-256"
        $Listed = @($Manifest.files | ForEach-Object { $_.path })
        $Actual = @($AllFiles | ForEach-Object { [System.IO.Path]::GetRelativePath($Root, $_.FullName).Replace("\", "/") })
        Assert-True (($Listed -join "|") -eq ($Actual -join "|")) "MANIFEST_COVERAGE" "Manifest must cover every artifact except itself"
        foreach ($Entry in $Manifest.files) {
            $FullPath = Join-Path $Root $Entry.path
            if (-not (Test-Path -LiteralPath $FullPath -PathType Leaf)) {
                Add-Failure -Code "MANIFEST_MISSING" -Message "Missing $($Entry.path)"
                continue
            }
            $Bytes = [System.IO.File]::ReadAllBytes($FullPath)
            $Hash = ([System.Convert]::ToHexString(
                [System.Security.Cryptography.SHA256]::HashData($Bytes)
            )).ToUpperInvariant()
            Assert-True ($Bytes.Length -eq [int]$Entry.bytes) "MANIFEST_BYTES" "Byte count drift for $($Entry.path)"
            Assert-True ($Hash -eq $Entry.sha256) "MANIFEST_HASH" "Hash drift for $($Entry.path)"
        }
    }

    if ($Failures.Count -gt 0) {
        foreach ($Failure in $Failures) {
            [Console]::Error.WriteLine("CONTRACT_FAIL $Failure")
        }
        Write-Output "CONTRACT_RESULT FAIL mutation=$Mutation failures=$($Failures.Count)"
        exit 1
    }

    Write-Output "CONTRACT_RESULT PASS mutation=$Mutation scopes=$($ExpectedScopes.Count) stateCases=$(@($Fixtures.stateCases).Count) identityCases=$(@($Fixtures.identityCases).Count) restoreCases=$(@($Fixtures.restoreCases).Count)"
    exit 0
}
catch {
    [Console]::Error.WriteLine("CONTRACT_FAIL HARNESS: $($_.Exception.Message)")
    [Console]::Error.WriteLine($_.InvocationInfo.PositionMessage)
    [Console]::Error.WriteLine($_.ScriptStackTrace)
    Write-Output "CONTRACT_RESULT FAIL mutation=$Mutation failures=1"
    exit 1
}
