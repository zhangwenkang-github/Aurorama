param(
    [string]$EvidenceRoot = $PSScriptRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Equal($Actual, $Expected, [string]$Name) {
    if ($Actual -ne $Expected) {
        throw "$Name mismatch: expected=$Expected actual=$Actual"
    }
}

function Get-PropertyValue($Object, [string]$Name) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-NamedPropertyValue($Object, [string]$Name) {
    foreach ($property in @((Get-PropertyValue $Object "properties"))) {
        if ($null -eq $property) {
            continue
        }
        if ((Get-PropertyValue $property "name") -eq $Name) {
            return (Get-PropertyValue $property "value")
        }
    }
    return $null
}

function Test-MavenComponent($Component) {
    return ((Get-PropertyValue $Component "purl") -like "pkg:maven/*")
}

$sbomPath = Join-Path $EvidenceRoot "sbom.cdx.json"
$nativePath = Join-Path $EvidenceRoot "native-inventory.json"
$manifestPath = Join-Path $EvidenceRoot "manifest.json"
$contractPath = Join-Path $EvidenceRoot "license-evidence-contract.json"

Assert-Equal (Test-Path -LiteralPath $sbomPath) $true "SBOM exists"
Assert-Equal (Test-Path -LiteralPath $nativePath) $true "native inventory exists"
Assert-Equal (Test-Path -LiteralPath $manifestPath) $true "manifest exists"
Assert-Equal (Test-Path -LiteralPath $contractPath) $true "license evidence contract exists"

$sbom = Get-Content -LiteralPath $sbomPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
$native = Get-Content -LiteralPath $nativePath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
$contract = Get-Content -LiteralPath $contractPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100

$rawToPrimary = @{}
foreach ($property in $contract.rawStateToPrimary.PSObject.Properties) {
    $rawToPrimary[$property.Name] = [string]$property.Value
}
$allowedPrimaryGrades = @($contract.primaryGrades | ForEach-Object { [string]$_ })
Assert-Equal @($allowedPrimaryGrades | Sort-Object -Unique).Count $allowedPrimaryGrades.Count "unique primary grades"
foreach ($property in $contract.rawStateToPrimary.PSObject.Properties) {
    if ($allowedPrimaryGrades -notcontains [string]$property.Value) {
        throw "INVALID_PRIMARY_GRADE: $($property.Value)"
    }
}

Assert-Equal $sbom.bomFormat "CycloneDX" "bomFormat"
Assert-Equal $sbom.specVersion "1.6" "specVersion"
Assert-Equal @($sbom.components).Count 305 "SBOM component count"
Assert-Equal @($sbom.components | Where-Object { Test-MavenComponent $_ }).Count 296 "Maven component count"
Assert-Equal @($sbom.components | Where-Object { $_.'bom-ref' -like "gradle-project:*" }).Count 9 "project component count"
Assert-Equal @($sbom.components | Where-Object { (Get-NamedPropertyValue $_ "aurorama:gradle.relationship") -eq "direct" }).Count 44 "direct external plus project count"
Assert-Equal @($sbom.components | Where-Object { (Test-MavenComponent $_) -and ((Get-NamedPropertyValue $_ "aurorama:gradle.relationship") -eq "direct") }).Count 35 "direct external count"
Assert-Equal @($sbom.components | Where-Object { (Test-MavenComponent $_) -and ((Get-NamedPropertyValue $_ "aurorama:gradle.relationship") -eq "transitive") }).Count 261 "transitive external count"
Assert-Equal @($sbom.components | Where-Object { (Test-MavenComponent $_) -and (@($_.licenses).Count -eq 0) }).Count 0 "external license identifier coverage"
Assert-Equal @($sbom.components | ForEach-Object { $_.'bom-ref' } | Sort-Object -Unique).Count 305 "unique component bom-ref count"
Assert-Equal @($sbom.components | Where-Object { Test-MavenComponent $_ } | ForEach-Object { Get-PropertyValue $_ "purl" } | Sort-Object -Unique).Count 296 "unique Maven PURL count"

foreach ($component in @($sbom.components | Where-Object { Test-MavenComponent $_ })) {
    if ($component.purl -notmatch '^pkg:maven/[^/]+/[^@]+@[^/]+$') {
        throw "INVALID_PURL: $($component.'bom-ref')"
    }
}

$missingRawState = 0
$missingPrimaryGrade = 0
$invalidPrimaryProjection = 0
$rawStateCounts = [ordered]@{}
$primaryGradeCounts = [ordered]@{}
foreach ($grade in @($contract.primaryGrades)) {
    $primaryGradeCounts[[string]$grade] = 0
}
foreach ($rawState in $rawToPrimary.Keys) {
    $rawStateCounts[[string]$rawState] = 0
}

foreach ($component in @($sbom.components | Where-Object { Test-MavenComponent $_ })) {
    $rawProperties = @($component.properties | Where-Object { $_.name -eq "aurorama:license.evidence" })
    $primaryProperties = @($component.properties | Where-Object { $_.name -eq "aurorama:license.evidence.primary" })
    Assert-Equal $rawProperties.Count 1 "raw license property cardinality ($($component.'bom-ref'))"
    Assert-Equal $primaryProperties.Count 1 "primary license property cardinality ($($component.'bom-ref'))"
    $rawState = [string](Get-NamedPropertyValue $component "aurorama:license.evidence")
    $primaryGrade = [string](Get-NamedPropertyValue $component "aurorama:license.evidence.primary")
    if ([string]::IsNullOrWhiteSpace($rawState)) {
        $missingRawState++
        continue
    }
    if ([string]::IsNullOrWhiteSpace($primaryGrade)) {
        $missingPrimaryGrade++
        continue
    }
    if (-not $rawToPrimary.ContainsKey($rawState)) {
        throw "UNMAPPED_RAW_LICENSE_STATE: $rawState"
    }
    if ($primaryGrade -ne $rawToPrimary[$rawState]) {
        $invalidPrimaryProjection++
        continue
    }
    $rawStateCounts[$rawState]++
    $primaryGradeCounts[$primaryGrade]++
}

Assert-Equal $missingRawState 0 "missing raw license state"
Assert-Equal $missingPrimaryGrade 0 "missing primary license grade"
Assert-Equal $invalidPrimaryProjection 0 "invalid primary license projection"
$classifiedComponents = [int64]0
foreach ($count in $rawStateCounts.Values) {
    $classifiedComponents += [int64]$count
}
Assert-Equal $classifiedComponents 296 "classified external count"

$expectedRawCounts = $contract.snapshotInvariants.rawStateCounts
foreach ($property in $expectedRawCounts.PSObject.Properties) {
    Assert-Equal ([int64]$rawStateCounts[$property.Name]) ([int64]$property.Value) "raw state count ($($property.Name))"
}
$expectedPrimaryCounts = $contract.snapshotInvariants.primaryGradeCounts
foreach ($property in $expectedPrimaryCounts.PSObject.Properties) {
    Assert-Equal ([int64]$primaryGradeCounts[$property.Name]) ([int64]$property.Value) "primary grade count ($($property.Name))"
}
Assert-Equal (@($rawStateCounts.Values | Measure-Object -Sum).Sum) 296 "raw state sum"
Assert-Equal (@($primaryGradeCounts.Values | Measure-Object -Sum).Sum) 296 "primary grade sum"

Assert-Equal @($native.entries).Count 14 "native entry count"
$actualNativeBytes = [int64]0
foreach ($nativeEntry in @($native.entries)) {
    $actualNativeBytes += [int64]$nativeEntry.bytes
}
Assert-Equal $actualNativeBytes 29482904 "native bytes"
Assert-Equal @($native.entries | ForEach-Object { $_.path } | Sort-Object -Unique).Count 14 "unique native path count"
Assert-Equal @($native.entries | Where-Object { $_.automaticGradleSbomExpression -ne $false }).Count 0 "automatic native expression count"
foreach ($entry in @($native.entries)) {
    if ($entry.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "INVALID_NATIVE_SHA256: $($entry.path)"
    }
}

$manifestFileCount = [int64]$manifest.files.count
$manifestTotalBytes = [int64]$manifest.files.totalBytes
$actualFileBytes = [int64]0
foreach ($record in @($manifest.files.entries)) {
    $path = Join-Path $EvidenceRoot $record.name
    if (-not (Test-Path -LiteralPath $path)) {
        throw "MANIFEST_FILE_MISSING: $($record.name)"
    }
    $item = Get-Item -LiteralPath $path
    Assert-Equal ([int64]$item.Length) ([int64]$record.bytes) "manifest bytes ($($record.name))"
    Assert-Equal (Get-Sha256 $path) $record.sha256 "manifest SHA-256 ($($record.name))"
    $actualFileBytes += [int64]$item.Length
}
Assert-Equal @($manifest.files.entries).Count $manifestFileCount "manifest file count"
Assert-Equal $actualFileBytes $manifestTotalBytes "manifest total bytes"
Assert-Equal $manifestFileCount 10 "expected evidence file count before manifest"

[ordered]@{
    status = "PASS"
    components = 305
    external = 296
    projects = 9
    directExternal = 35
    transitiveExternal = 261
    licensePrimary = [ordered]@{
        exactPom = $primaryGradeCounts["EXACT_POM"]
        familyOnly = $primaryGradeCounts["FAMILY_ONLY"]
        mixedExactFamily = $primaryGradeCounts["MIXED_EXACT_FAMILY"]
        unknownBlocked = $primaryGradeCounts["UNKNOWN_BLOCKED"]
    }
    licenseRawStates = $rawStateCounts
    nativeEntries = 14
    nativeBytes = 29482904
    manifestFilesBeforeManifest = $manifestFileCount
    manifestBytesBeforeManifest = $manifestTotalBytes
} | ConvertTo-Json -Compress
