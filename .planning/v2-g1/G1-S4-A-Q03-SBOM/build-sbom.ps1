param(
    [string]$RepoRoot = "",
    [string]$ApkPath = "F:\Develop\code_data\work_tree\Aurorama\w90a2-release-candidate-20261102\Aurorama\app\phone\build\outputs\apk\libre\release\phone-libre-arm64-v8a-release.apk"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$EvidenceRoot = $PSScriptRoot

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-JsonFile([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    [System.IO.File]::WriteAllText($Path, $normalized, [System.Text.UTF8Encoding]::new($false))
}

function New-Property([string]$Name, [string]$Value) {
    return [ordered]@{
        name = $Name
        value = [string]$Value
    }
}

function Get-NamedPropertyValue($Object, [string]$Name) {
    foreach ($property in @($Object.properties)) {
        if ($null -eq $property) {
            continue
        }
        if ($property.name -eq $Name) {
            return [string]$property.value
        }
    }
    return $null
}

function Get-MavenPurl([string]$Group, [string]$Artifact, [string]$Version) {
    $escapedGroup = [System.Uri]::EscapeDataString($Group)
    $escapedArtifact = [System.Uri]::EscapeDataString($Artifact)
    $escapedVersion = [System.Uri]::EscapeDataString($Version)
    return "pkg:maven/$escapedGroup/$escapedArtifact@$escapedVersion"
}

function Assert-Equal($Actual, $Expected, [string]$Name) {
    if ($Actual -ne $Expected) {
        throw "$Name mismatch: expected=$Expected actual=$Actual"
    }
}

$S4aRoot = Join-Path $RepoRoot ".planning/v2-g1/G1-S4-A"
$RuntimePath = Join-Path $S4aRoot "runtime-closure.json"
$LicensePath = Join-Path $S4aRoot "license-matrix.json"
$ReportPath = Join-Path $S4aRoot "report.json"
$GradleBuildPath = Join-Path $RepoRoot "app/phone/build.gradle.kts"
$GradleCatalogPath = Join-Path $RepoRoot "gradle/libs.versions.toml"
$JspecifyPomPath = "C:\Users\zhangwenkang\.gradle\caches\modules-2\files-2.1\org.jspecify\jspecify\1.0.0\fae4a252aeee8a649653b2f239067bcead8f80cb\jspecify-1.0.0.pom"
$LicenseContractPath = Join-Path $EvidenceRoot "license-evidence-contract.json"

$expectedInputs = [ordered]@{
    "runtime-closure.json" = "4266cc5af2367444afd81a14a6d7663e9c0c4ecfef3d6095da1c5e89e406731e"
    "license-matrix.json" = "e9a2a1874a1f9076e7ac1de20715ac9a0cac96e492697ca11b335288a7b49773"
    "report.json" = "fa1e466351eb0f88d88f77c87b6ca4659c6380bdb18a335244666d69162de6ad"
    "jspecify-1.0.0.pom" = "cdab929a3b95211f43d2090c5e2d0dfe8465960e378bc32b35841dab324433a6"
    "license-evidence-contract.json" = "5e4a5336089cce2500686b35465916505b66ee8c6ac957e7521785b3369b5651"
}

foreach ($entry in $expectedInputs.GetEnumerator()) {
    $path = switch ($entry.Key) {
        "runtime-closure.json" { $RuntimePath }
        "license-matrix.json" { $LicensePath }
        "report.json" { $ReportPath }
        "jspecify-1.0.0.pom" { $JspecifyPomPath }
        "license-evidence-contract.json" { $LicenseContractPath }
    }
    if (-not (Test-Path -LiteralPath $path)) {
        throw "INPUT_NOT_FOUND: $path"
    }
    Assert-Equal (Get-Sha256 $path) $entry.Value "input SHA-256 ($($entry.Key))"
}

$runtime = Read-JsonFile $RuntimePath
$license = Read-JsonFile $LicensePath
$s4aReport = Read-JsonFile $ReportPath
$licenseContract = Read-JsonFile $LicenseContractPath

Assert-Equal ([string]$licenseContract.contractId) "aurorama-license-evidence-v1" "license evidence contract ID"
$rawToPrimary = @{}
foreach ($property in $licenseContract.rawStateToPrimary.PSObject.Properties) {
    $rawToPrimary[[string]$property.Name] = [string]$property.Value
}

$allExternal = @($runtime.externalResolvedClosure)
$directExternal = @($runtime.directExternal)
$directProjects = @($runtime.directProject)

Assert-Equal $allExternal.Count 296 "external component count"
Assert-Equal $directExternal.Count 35 "direct external count"
Assert-Equal $directProjects.Count 9 "direct project count"
Assert-Equal @($allExternal | Sort-Object -Unique).Count 296 "unique external count"
Assert-Equal @($directExternal | Sort-Object -Unique).Count 35 "unique direct external count"
Assert-Equal @($directExternal | Where-Object { $allExternal -notcontains $_ }).Count 0 "direct subset gap"
Assert-Equal @($runtime.unresolved).Count 0 "unresolved marker count"

$invalidCoordinates = @($allExternal | Where-Object { ($_ -split ":", 3).Count -ne 3 })
Assert-Equal $invalidCoordinates.Count 0 "invalid Maven coordinate count"

$directLicenseByCoordinate = @{}
foreach ($licenseEntry in $license.directByLicense.PSObject.Properties) {
    foreach ($coordinate in @($licenseEntry.Value.coordinates)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$coordinate)) {
            $directLicenseByCoordinate[[string]$coordinate] = [string]$licenseEntry.Name
        }
    }
}
Assert-Equal $directLicenseByCoordinate.Count 35 "direct exact license mapping count"

$familyRules = [System.Collections.Generic.List[object]]::new()
foreach ($row in @($license.transitiveFamilyMatrix)) {
    foreach ($pattern in @($row.coordinatePatterns)) {
        $normalizedPattern = [string]$pattern
        if (($normalizedPattern -split ":").Count -eq 2) {
            $normalizedPattern = "${normalizedPattern}:*"
        }
        $familyRules.Add([ordered]@{
            pattern = $normalizedPattern
            license = [string]$row.license
            confidence = [string]$row.spdxConfidence
        })
    }
}

$metadataOnly = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($coordinate in @($s4aReport.closure.metadataOnlyCandidates)) {
    [void]$metadataOnly.Add([string]$coordinate)
}

$externalComponents = [System.Collections.Generic.List[object]]::new()
$externalCounts = [ordered]@{
    direct = 0
    transitive = 0
    metadataOnly = 0
}
$licenseCounts = [ordered]@{}

foreach ($coordinate in ($allExternal | Sort-Object)) {
    $parts = $coordinate -split ":", 3
    $group = [string]$parts[0]
    $artifact = [string]$parts[1]
    $version = [string]$parts[2]
    $purl = Get-MavenPurl $group $artifact $version
    $relationship = if ($directExternal -contains $coordinate) { "direct" } else { "transitive" }
    $externalCounts[$relationship]++

    $licenseIdentifier = $null
    $licenseName = $null
    $licenseEvidence = $null
    $licenseSource = $null

    if ($directLicenseByCoordinate.ContainsKey($coordinate)) {
        $licenseIdentifier = [string]$directLicenseByCoordinate[$coordinate]
        $licenseEvidence = "DIRECT_POM_EXACT"
        $licenseSource = ".planning/v2-g1/G1-S4-A/license-matrix.json#/directByLicense/$licenseIdentifier/coordinates"
    } elseif ($coordinate -eq "org.jspecify:jspecify:1.0.0") {
        [xml]$jspecifyPom = Get-Content -LiteralPath $JspecifyPomPath -Raw -Encoding utf8
        Assert-Equal ([string]$jspecifyPom.project.licenses.license.name) "The Apache License, Version 2.0" "JSpecify license name"
        $licenseIdentifier = "Apache-2.0"
        $licenseEvidence = "LOCAL_POM_EXACT"
        $licenseSource = "$JspecifyPomPath#project.licenses"
    } else {
        $rule = $null
        foreach ($candidateRule in $familyRules) {
            if ($coordinate -like $candidateRule.pattern) {
                $rule = $candidateRule
                break
            }
        }
        if ($null -eq $rule) {
            throw "LICENSE_RULE_NOT_FOUND: $coordinate"
        }

        $licenseIdentifier = [string]$rule.license
        $licenseEvidence = [string]$rule.confidence
        $licenseSource = ".planning/v2-g1/G1-S4-A/license-matrix.json#/transitiveFamilyMatrix/$licenseIdentifier"

        if ($group -eq "org.bouncycastle") {
            $licenseIdentifier = $null
            $licenseName = "Bouncy Castle Licence"
            $licenseEvidence = "FAMILY_WITH_EXPLICIT_NAME_EXCEPTION"
            $licenseSource = ".planning/v2-g1/G1-S4-A/license-matrix.json#/transitiveFamilyMatrix/MIT/exceptions"
        } elseif ($coordinate -eq "com.google.guava:guava:33.3.1-android") {
            $licenseEvidence = "FAMILY_EXCEPTION_POM_LICENSE_MISSING"
            $licenseSource = ".planning/v2-g1/G1-S4-A/license-matrix.json#/unknownsAndPotentialConflicts/LIC-META-01"
        } elseif ($coordinate -eq "org.slf4j:slf4j-api:2.0.19") {
            $licenseEvidence = "FAMILY_EXCEPTION_POM_LICENSE_MISSING"
            $licenseSource = ".planning/v2-g1/G1-S4-A/license-matrix.json#/unknownsAndPotentialConflicts/LIC-META-02"
        }
    }

    if (-not $rawToPrimary.ContainsKey($licenseEvidence)) {
        throw "UNMAPPED_RAW_LICENSE_STATE: $licenseEvidence"
    }
    $primaryLicenseGrade = [string]$rawToPrimary[$licenseEvidence]

    $displayLicense = if ($licenseIdentifier) { $licenseIdentifier } else { $licenseName }
    if ($licenseCounts.Contains($displayLicense)) {
        $licenseCounts[$displayLicense]++
    } else {
        $licenseCounts[$displayLicense] = 1
    }

    $licenseObject = if ($licenseIdentifier) {
        [ordered]@{ license = [ordered]@{ id = $licenseIdentifier } }
    } else {
        [ordered]@{ license = [ordered]@{ name = $licenseName } }
    }

    $isMetadataOnly = $metadataOnly.Contains($coordinate)
    if ($isMetadataOnly) {
        $externalCounts.metadataOnly++
    }

    $properties = [System.Collections.Generic.List[object]]::new()
    $properties.Add((New-Property "aurorama:gradle.relationship" $relationship))
    $properties.Add((New-Property "aurorama:license.evidence" $licenseEvidence))
    $properties.Add((New-Property "aurorama:license.evidence.primary" $primaryLicenseGrade))
    $properties.Add((New-Property "aurorama:license.evidence.contract" ([string]$licenseContract.contractId)))
    $properties.Add((New-Property "aurorama:license.source" $licenseSource))
    $properties.Add((New-Property "aurorama:runtime.artifact" (-not $isMetadataOnly).ToString().ToLowerInvariant()))
    $properties.Add((New-Property "aurorama:source.graph" ".planning/v2-g1/G1-S4-A/runtime-closure.json"))

    $externalComponents.Add([ordered]@{
        type = "library"
        "bom-ref" = $purl
        group = $group
        name = $artifact
        version = $version
        purl = $purl
        scope = "required"
        licenses = @($licenseObject)
        properties = @($properties)
    })
}

Assert-Equal $externalComponents.Count 296 "serialized external component count"
Assert-Equal $externalCounts.direct 35 "serialized direct external count"
Assert-Equal $externalCounts.transitive 261 "serialized transitive external count"

$rawStateCounts = [ordered]@{}
$primaryGradeCounts = [ordered]@{}
foreach ($rawState in $rawToPrimary.Keys) {
    $rawStateCounts[$rawState] = 0
}
foreach ($grade in @($licenseContract.primaryGrades)) {
    $primaryGradeCounts[[string]$grade] = 0
}
foreach ($component in $externalComponents) {
    $rawState = Get-NamedPropertyValue $component "aurorama:license.evidence"
    $primaryGrade = Get-NamedPropertyValue $component "aurorama:license.evidence.primary"
    if (-not $rawToPrimary.ContainsKey($rawState)) {
        throw "UNMAPPED_SERIALIZED_RAW_LICENSE_STATE: $rawState"
    }
    Assert-Equal $primaryGrade $rawToPrimary[$rawState] "primary projection ($($component.'bom-ref'))"
    $rawStateCounts[$rawState]++
    $primaryGradeCounts[$primaryGrade]++
}
$rawStateSum = [int64]0
foreach ($count in $rawStateCounts.Values) {
    $rawStateSum += [int64]$count
}
$primaryGradeSum = [int64]0
foreach ($count in $primaryGradeCounts.Values) {
    $primaryGradeSum += [int64]$count
}
Assert-Equal $rawStateSum 296 "raw license state sum"
Assert-Equal $primaryGradeSum 296 "primary license grade sum"
foreach ($property in $licenseContract.snapshotInvariants.rawStateCounts.PSObject.Properties) {
    Assert-Equal ([int64]$rawStateCounts[$property.Name]) ([int64]$property.Value) "raw license state count ($($property.Name))"
}
foreach ($property in $licenseContract.snapshotInvariants.primaryGradeCounts.PSObject.Properties) {
    Assert-Equal ([int64]$primaryGradeCounts[$property.Name]) ([int64]$property.Value) "primary license grade count ($($property.Name))"
}

$projectComponents = [System.Collections.Generic.List[object]]::new()
foreach ($projectPath in ($directProjects | Sort-Object)) {
    $projectRef = "gradle-project:$($projectPath.TrimStart(':'))"
    $properties = @(
        (New-Property "aurorama:gradle.relationship" "direct"),
        (New-Property "aurorama:license.evidence" "PROJECT_LICENSE"),
        (New-Property "aurorama:license.source" ".planning/v2-g1/G1-S4-A/license-matrix.json#/projectLicense"),
        (New-Property "aurorama:source.graph" ".planning/v2-g1/G1-S4-A/runtime-closure.json")
    )
    $projectComponents.Add([ordered]@{
        type = "library"
        "bom-ref" = $projectRef
        name = $projectPath
        scope = "required"
        licenses = @(
            [ordered]@{ license = [ordered]@{ id = "GPL-3.0-only" } }
        )
        properties = $properties
    })
}

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK_NOT_FOUND: $ApkPath"
}
$apkItem = Get-Item -LiteralPath $ApkPath
$apkSha256 = Get-Sha256 $ApkPath
Assert-Equal $apkItem.Length 79729350 "APK size"
Assert-Equal $apkSha256 "acb1648d9e9c5265bc54dd13b24eea7ada3d16e397d3e10c6a72e23d2d2ff5bb" "APK SHA-256"

$rootRef = "pkg:apk/io.github.zhangwenkang.aurorama@1.4.0?arch=arm64-v8a"
$rootComponent = [ordered]@{
    type = "application"
    "bom-ref" = $rootRef
    group = "io.github.zhangwenkang"
    name = "aurorama"
    version = "1.4.0"
    description = "S4-A derived 1.4.0 arm64 runtime inventory; not the canonical 2.0 release SBOM"
    purl = $rootRef
    licenses = @(
        [ordered]@{ license = [ordered]@{ id = "GPL-3.0-only" } }
    )
    hashes = @(
        [ordered]@{ alg = "SHA-256"; content = $apkSha256 }
    )
    properties = @(
        (New-Property "aurorama:package" "io.github.zhangwenkang.aurorama"),
        (New-Property "aurorama:version.code" "5"),
        (New-Property "aurorama:artifact.role" "frozen-1.4-snapshot"),
        (New-Property "aurorama:canonical.2.0.sbom" "false"),
        (New-Property "aurorama:gradle.graph.completeness" "flat_inventory_no_edge_topology")
    )
}

$sbom = [ordered]@{
    bomFormat = "CycloneDX"
    specVersion = "1.6"
    version = 1
    metadata = [ordered]@{
        component = $rootComponent
        properties = @(
            (New-Property "aurorama:evidence.task" "G1-S4-A-Q03-SBOM"),
            (New-Property "aurorama:evidence.head" "db6d176e55007a9d50c59cf53f84858ce7b9876f"),
            (New-Property "aurorama:source.s4a.head" "cecc7d1013efd04a1ad25905b9f1832d2c5ccac9"),
            (New-Property "aurorama:source.runtime" ".planning/v2-g1/G1-S4-A/runtime-closure.json"),
            (New-Property "aurorama:source.license" ".planning/v2-g1/G1-S4-A/license-matrix.json"),
            (New-Property "aurorama:source.apk.sha256" $apkSha256),
            (New-Property "aurorama:license.evidence.contract" ([string]$licenseContract.contractId)),
            (New-Property "aurorama:license.evidence.primary-rule" ([string]$licenseContract.primaryRule))
        )
        tools = [ordered]@{
            components = @(
                [ordered]@{
                    type = "application"
                    name = "Aurorama S4-A derived SBOM builder"
                    version = "1.0.0"
                }
            )
        }
    }
    components = @($projectComponents) + @($externalComponents)
    compositions = @(
        [ordered]@{
            aggregate = "incomplete"
            assemblies = @($rootRef)
            dependencies = @($rootRef)
        }
    )
}

$sbomPath = Join-Path $EvidenceRoot "sbom.cdx.json"
$sbomJson = $sbom | ConvertTo-Json -Depth 100
Write-Utf8NoBom $sbomPath $sbomJson

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
try {
    $nativeEntries = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -notlike "lib/arm64-v8a/*") {
            continue
        }
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $stream = $entry.Open()
            try {
                $hashBytes = $sha.ComputeHash($stream)
            } finally {
                $stream.Dispose()
            }
        } finally {
            $sha.Dispose()
        }
        $entrySha256 = ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
        $fileName = [System.IO.Path]::GetFileName($entry.FullName)

        $originCoordinates = switch ($fileName) {
            "libffmpegJNI.so" {
                @("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
                break
            }
            "libass.so" {
                @("io.github.peerless2012:ass-kt:0.5.1")
                break
            }
            "libasskt.so" {
                @("io.github.peerless2012:ass-kt:0.5.1")
                break
            }
            "libandroidx.graphics.path.so" {
                @("androidx.graphics:graphics-path:1.0.1")
                break
            }
            "libmpv.so" {
                @("dev.jdtech.mpv:libmpv:1.0.0")
                break
            }
            "libplayer.so" {
                @("dev.jdtech.mpv:libmpv:1.0.0")
                break
            }
            "libc++_shared.so" {
                @("dev.jdtech.mpv:libmpv:1.0.0", "io.github.peerless2012:ass-kt:0.5.1")
                break
            }
            default {
                if ($fileName -like "libav*.so") {
                    @("dev.jdtech.mpv:libmpv:1.0.0")
                } else {
                    @()
                }
            }
        }

        $licenseStatus = switch ($fileName) {
            "libffmpegJNI.so" { "PARTIAL_PARENT_GPL_NATIVE_NOTICE_INCOMPLETE"; break }
            "libavcodec.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libavdevice.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libavfilter.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libavformat.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libavutil.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libswresample.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libswscale.so" { "BLOCKED_FFMPEG_BUILD_LICENSE_UNRESOLVED"; break }
            "libass.so" { "PARTIAL_NOTICE_ONLY_ISC"; break }
            "libasskt.so" { "PARTIAL_PARENT_MIT_NATIVE_NOTICE_INCOMPLETE"; break }
            "libc++_shared.so" { "BLOCKED_NDK_RUNTIME_NOTICE_UNRESOLVED"; break }
            "libandroidx.graphics.path.so" { "PARTIAL_PARENT_FAMILY_LICENSE_ONLY"; break }
            "libmpv.so" { "PARTIAL_WRAPPER_MIT_LIBMPV_BODY_NOTICE_ONLY"; break }
            "libplayer.so" { "PARTIAL_PARENT_MIT_NATIVE_NOTICE_INCOMPLETE"; break }
            default { "UNRESOLVED"; break }
        }

        $nativeEntries.Add([ordered]@{
            path = $entry.FullName
            abi = "arm64-v8a"
            bytes = [int64]$entry.Length
            compressedBytes = [int64]$entry.CompressedLength
            stored = ($entry.Length -eq $entry.CompressedLength)
            sha256 = $entrySha256
            mavenOriginCoordinates = @($originCoordinates)
            automaticGradleSbomExpression = $false
            packagedFileEvidence = "PROVEN"
            nativeLicenseStatus = $licenseStatus
        })
    }

    Assert-Equal $nativeEntries.Count 14 "native entry count"
    $nativeBytes = [int64]0
    foreach ($nativeEntry in $nativeEntries) {
        $nativeBytes += [int64]$nativeEntry.bytes
    }
    Assert-Equal $nativeBytes 29482904 "native entry bytes"

    $nativeInventory = [ordered]@{
        schemaVersion = 1
        task = "G1-S4-A-Q03-SBOM"
        artifact = [ordered]@{
            path = $ApkPath
            package = "io.github.zhangwenkang.aurorama"
            versionName = "1.4.0"
            versionCode = 5
            abi = "arm64-v8a"
            bytes = [int64]$apkItem.Length
            sha256 = $apkSha256
        }
        summary = [ordered]@{
            nativeEntryCount = $nativeEntries.Count
            nativeEntryBytes = $nativeBytes
            allEntriesStored = (@($nativeEntries | Where-Object { -not $_.stored }).Count -eq 0)
            automaticallyExpressibleByGradleDependencyGraph = 0
            provenanceBlockedCount = @($nativeEntries | Where-Object { $_.nativeLicenseStatus -like "BLOCKED*" }).Count
            provenancePartialCount = @($nativeEntries | Where-Object { $_.nativeLicenseStatus -like "PARTIAL*" }).Count
        }
        entries = @($nativeEntries | Sort-Object path)
        absentCandidates = @(
            [ordered]@{
                component = "PDFium"
                presentInFrozenRuntime = $false
                gradleExpression = "ABSENT_NOT_EXPRESSIBLE_UNTIL_SELECTED_AND_ADDED"
            },
            [ordered]@{
                component = "Sherpa-ONNX / Piper / TTS voice assets"
                presentInFrozenRuntime = $false
                gradleExpression = "ABSENT_OR_MIXED_MAVEN_AND_DATA_ASSETS"
            }
        )
        limitations = @(
            "Gradle dependency graph lists Maven/AAR components, not nested native files.",
            "This inventory is the frozen 1.4 arm64 APK snapshot, not a future 2.0 candidate APK.",
            "Presence and SHA-256 do not prove native license text, build flags, source offer, ABI compatibility, or runtime behavior."
        )
    }
    $nativePath = Join-Path $EvidenceRoot "native-inventory.json"
    Write-Utf8NoBom $nativePath (($nativeInventory | ConvertTo-Json -Depth 100))
} finally {
    $zip.Dispose()
}

$files = @(
    Get-ChildItem -LiteralPath $EvidenceRoot -File |
        Where-Object { $_.Name -ne "manifest.json" } |
        Sort-Object Name
)
$fileRecords = foreach ($file in $files) {
    [ordered]@{
        name = $file.Name
        bytes = [int64]$file.Length
        sha256 = Get-Sha256 $file.FullName
    }
}
$outputTotalBytes = [int64]0
foreach ($fileRecord in $fileRecords) {
    $outputTotalBytes += [int64]$fileRecord.bytes
}

$inputRecords = @(
    [ordered]@{
        role = "s4a-runtime-closure"
        path = ".planning/v2-g1/G1-S4-A/runtime-closure.json"
        bytes = (Get-Item -LiteralPath $RuntimePath).Length
        sha256 = Get-Sha256 $RuntimePath
    },
    [ordered]@{
        role = "s4a-license-matrix"
        path = ".planning/v2-g1/G1-S4-A/license-matrix.json"
        bytes = (Get-Item -LiteralPath $LicensePath).Length
        sha256 = Get-Sha256 $LicensePath
    },
    [ordered]@{
        role = "s4a-report"
        path = ".planning/v2-g1/G1-S4-A/report.json"
        bytes = (Get-Item -LiteralPath $ReportPath).Length
        sha256 = Get-Sha256 $ReportPath
    },
    [ordered]@{
        role = "frozen-arm64-apk"
        path = $ApkPath
        bytes = $apkItem.Length
        sha256 = $apkSha256
    },
    [ordered]@{
        role = "release-gradle-build"
        path = "app/phone/build.gradle.kts"
        bytes = (Get-Item -LiteralPath $GradleBuildPath).Length
        sha256 = Get-Sha256 $GradleBuildPath
    },
    [ordered]@{
        role = "version-catalog"
        path = "gradle/libs.versions.toml"
        bytes = (Get-Item -LiteralPath $GradleCatalogPath).Length
        sha256 = Get-Sha256 $GradleCatalogPath
    },
    [ordered]@{
        role = "jspecify-pom-exact-license"
        path = $JspecifyPomPath
        bytes = (Get-Item -LiteralPath $JspecifyPomPath).Length
        sha256 = Get-Sha256 $JspecifyPomPath
    }
)

$manifest = [ordered]@{
    schemaVersion = 1
    task = "G1-S4-A-Q03-SBOM"
    status = "PARTIAL"
    evidenceHead = "db6d176e55007a9d50c59cf53f84858ce7b9876f"
    branch = "codex/v2-integration"
    sourceHead = "cecc7d1013efd04a1ad25905b9f1832d2c5ccac9"
    inputs = $inputRecords
    counts = [ordered]@{
        projectComponents = 9
        externalComponents = 296
        directExternal = 35
        transitiveExternal = 261
        nativeEntries = 14
        nativeEntryBytes = 29482904
    }
    licenseEvidence = [ordered]@{
        contractId = [string]$licenseContract.contractId
        primaryRule = [string]$licenseContract.primaryRule
        rawStateCounts = $rawStateCounts
        primaryGradeCounts = $primaryGradeCounts
    }
    licenseCounts = $licenseCounts
    files = [ordered]@{
        count = @($fileRecords).Count
        totalBytes = $outputTotalBytes
        entries = @($fileRecords)
    }
    limitations = @(
        "No parent-child topology exists in the frozen runtime closure; direct/transitive is a classification, not a reconstructed dependency graph.",
        "This is a derived CycloneDX inventory for the frozen S4-A snapshot, not a canonical 2.0 candidate release SBOM.",
        "Native license and runtime compatibility remain open."
    )
}

$manifestPath = Join-Path $EvidenceRoot "manifest.json"
Write-Utf8NoBom $manifestPath (($manifest | ConvertTo-Json -Depth 100))

[ordered]@{
    status = "BUILT"
    sbom = $sbomPath
    nativeInventory = (Join-Path $EvidenceRoot "native-inventory.json")
    manifest = $manifestPath
    externalComponents = 296
    projectComponents = 9
    nativeEntries = 14
    licenseRawStateCounts = $rawStateCounts
    licensePrimaryGradeCounts = $primaryGradeCounts
} | ConvertTo-Json -Compress
