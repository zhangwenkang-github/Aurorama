[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$ownerRole = "G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06"
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8Output
$OutputEncoding = $utf8Output

$evidenceRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $evidenceRoot "..\..\..")
)
$sourceTaskRoot = Join-Path `
    $repositoryRoot `
    ".planning/v2-g1/G1-L1-KOKORO-EXACT"
$sourceHarnessRoot = Join-Path $sourceTaskRoot "harness"
$androidSdk = "F:\Develop\Android\AndroidSDK"
$aapt2 = Join-Path $androidSdk "build-tools/37.0.0/aapt2.exe"
$apksigner = Join-Path $androidSdk "build-tools/37.0.0/apksigner.bat"

foreach ($requiredPath in @(
    $sourceHarnessRoot,
    (Join-Path $sourceTaskRoot "extracted"),
    (Join-Path $sourceTaskRoot "assets"),
    $aapt2,
    $apksigner
)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required local build input is missing: $requiredPath"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function Get-Utf8LfSha256 {
    param([Parameter(Mandatory)][string]$TextValue)
    $normalized = $TextValue.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    return [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            $utf8Output.GetBytes($normalized)
        )
    ).ToLowerInvariant()
}

function Write-LfText {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text
    )
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8Output)
}

$tempBase = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::GetTempPath()
)
$tempRoot = Join-Path `
    $tempBase `
    ("aurorama-g2-candidate-" + [Guid]::NewGuid().ToString("N"))
$tempTaskRoot = Join-Path $tempRoot "G1-L1-KOKORO-EXACT"
$tempHarnessRoot = Join-Path $tempTaskRoot "harness"

try {
    [void][System.IO.Directory]::CreateDirectory($tempHarnessRoot)

    foreach ($directoryName in @("app", "contract", "peer")) {
        Copy-Item `
            -LiteralPath (Join-Path $sourceHarnessRoot $directoryName) `
            -Destination $tempHarnessRoot `
            -Recurse
    }
    foreach ($fileName in @(
        "build.ps1",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "install-harness.ps1",
        "finalize-report.ps1"
    )) {
        Copy-Item `
            -LiteralPath (Join-Path $sourceHarnessRoot $fileName) `
            -Destination $tempHarnessRoot
    }

    [void](New-Item `
        -ItemType Junction `
        -Path (Join-Path $tempTaskRoot "extracted") `
        -Target (Join-Path $sourceTaskRoot "extracted"))
    [void](New-Item `
        -ItemType Junction `
        -Path (Join-Path $tempTaskRoot "assets") `
        -Target (Join-Path $sourceTaskRoot "assets"))

    $tempBuildScript = Join-Path $tempHarnessRoot "build.ps1"
    $buildScript = [System.IO.File]::ReadAllText(
        $tempBuildScript,
        $utf8Strict
    )
    $replacements = [ordered]@{
        "--version-code 4 --version-name '2.0-kokoro-runtime-v5'" =
            "--version-code 5 --version-name '2.0-g2-runtime-contract-v6'"
        "--version-code 1 --version-name '1.0-focus-peer'" =
            "--version-code 2 --version-name '1.1-g2-focus-peer'"
        "versionCode = 4" = "versionCode = 5"
        "versionName = '2.0-kokoro-runtime-v5'" =
            "versionName = '2.0-g2-runtime-contract-v6'"
        "versionCode = 1" = "versionCode = 2"
        "versionName = '1.0-focus-peer'" =
            "versionName = '1.1-g2-focus-peer'"
    }
    foreach ($entry in $replacements.GetEnumerator()) {
        if (-not $buildScript.Contains(
            $entry.Key,
            [System.StringComparison]::Ordinal
        )) {
            throw "Expected build-script token is missing: $($entry.Key)"
        }
        $buildScript = $buildScript.Replace(
            $entry.Key,
            $entry.Value,
            [System.StringComparison]::Ordinal
        )
    }
    Write-LfText -Path $tempBuildScript -Text $buildScript

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $env:JAVA_HOME = "F:\Develop\Android\Android Studio\jbr"
    }
    $buildOutput = @(
        & pwsh -NoProfile -File $tempBuildScript 2>&1
    )
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -ne 0) {
        throw (
            "Candidate build failed with exit {0}. Tail: {1}" -f
            $buildExitCode,
            (@($buildOutput | Select-Object -Last 20) -join " | ")
        )
    }

    $tempMainApk = Join-Path `
        $tempHarnessRoot `
        "build/outputs/kokoro-isolated-harness-debug.apk"
    $tempPeerApk = Join-Path `
        $tempHarnessRoot `
        "build/outputs/kokoro-focus-peer-debug.apk"
    foreach ($artifactPath in @($tempMainApk, $tempPeerApk)) {
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
            throw "Expected built artifact is missing: $artifactPath"
        }
    }

    $mainApk = Join-Path $evidenceRoot "candidate-main-v6.apk"
    $peerApk = Join-Path $evidenceRoot "candidate-focus-peer-v2.apk"
    Copy-Item -LiteralPath $tempMainApk -Destination $mainApk -Force
    Copy-Item -LiteralPath $tempPeerApk -Destination $peerApk -Force

    $mainBadging = @(& $aapt2 dump badging $mainApk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Main candidate badging inspection failed."
    }
    $peerBadging = @(& $aapt2 dump badging $peerApk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Focus peer badging inspection failed."
    }
    $mainSignature = @(
        & $apksigner verify --verbose --print-certs $mainApk 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Main candidate signature verification failed."
    }
    $peerSignature = @(
        & $apksigner verify --verbose --print-certs $peerApk 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Focus peer signature verification failed."
    }

    $packagePattern = [regex]::new(
        "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
    )
    $mainPackageMatch = $mainBadging |
        ForEach-Object { $packagePattern.Match([string]$_) } |
        Where-Object Success |
        Select-Object -First 1
    $peerPackageMatch = $peerBadging |
        ForEach-Object { $packagePattern.Match([string]$_) } |
        Where-Object Success |
        Select-Object -First 1
    if ($null -eq $mainPackageMatch -or $null -eq $peerPackageMatch) {
        throw "Package identity could not be parsed from badging output."
    }

    $mainPermissions = @(
        $mainBadging |
            ForEach-Object {
                if ([string]$_ -match "^uses-permission: name='([^']+)'") {
                    $Matches[1]
                }
            }
    )
    $peerPermissions = @(
        $peerBadging |
            ForEach-Object {
                if ([string]$_ -match "^uses-permission: name='([^']+)'") {
                    $Matches[1]
                }
            }
    )
    $mainVersionCode = [int]$mainPackageMatch.Groups[2].Value
    $peerVersionCode = [int]$peerPackageMatch.Groups[2].Value
    if (
        $mainPackageMatch.Groups[1].Value -cne "dev.aurorama.kokoro.harness" -or
        $mainVersionCode -ne 5 -or
        $mainPackageMatch.Groups[3].Value -cne "2.0-g2-runtime-contract-v6" -or
        $peerPackageMatch.Groups[1].Value -cne "dev.aurorama.kokoro.focuspeer" -or
        $peerVersionCode -ne 2 -or
        $peerPackageMatch.Groups[3].Value -cne "1.1-g2-focus-peer" -or
        $mainPermissions -contains "android.permission.INTERNET" -or
        $peerPermissions -contains "android.permission.INTERNET" -or
        -not (($mainBadging -join "`n").Contains(
            "native-code: 'arm64-v8a'",
            [System.StringComparison]::Ordinal
        ))
    ) {
        throw "Candidate identity or product permission gate failed."
    }

    $sourceFiles = @(
        Get-ChildItem `
            -LiteralPath (Join-Path $sourceHarnessRoot "contract/src/main") `
            -Recurse -File
        Get-ChildItem `
            -LiteralPath (Join-Path $sourceHarnessRoot "contract/src/test") `
            -Recurse -File
        Get-ChildItem `
            -LiteralPath (Join-Path $sourceHarnessRoot "app/src/main") `
            -Recurse -File
        Get-ChildItem `
            -LiteralPath (Join-Path $sourceHarnessRoot "peer/src/main") `
            -Recurse -File
        Get-Item -LiteralPath (Join-Path $sourceHarnessRoot "build.ps1")
    ) | Sort-Object FullName
    $sourceDigestLines = foreach ($sourceFile in $sourceFiles) {
        $relativePath = [System.IO.Path]::GetRelativePath(
            $repositoryRoot,
            $sourceFile.FullName
        ).Replace("\", "/")
        "{0}  {1}" -f (Get-FileSha256 $sourceFile.FullName), $relativePath
    }
    $sourceInputsDigest = Get-Utf8LfSha256 (
        $sourceDigestLines -join "`n"
    )

    $modelRoot = Join-Path `
        $sourceTaskRoot `
        "extracted/kokoro-multi-lang-v1_1"
    $aarPath = Join-Path `
        $sourceTaskRoot `
        "assets/sherpa-onnx-2.0.1.aar"
    $runtimeInputs = [ordered]@{
        modelOnnxSha256 = Get-FileSha256 (Join-Path $modelRoot "model.onnx")
        voicesSha256 = Get-FileSha256 (Join-Path $modelRoot "voices.bin")
        tokensSha256 = Get-FileSha256 (Join-Path $modelRoot "tokens.txt")
        lexiconSha256 = Get-FileSha256 (Join-Path $modelRoot "lexicon-zh.txt")
        sherpaAarSha256 = Get-FileSha256 $aarPath
    }

    $signerDigestPattern = [regex]::new(
        "certificate SHA-256 digest: ([0-9a-f]+)",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    $mainSignerMatch = $mainSignature |
        ForEach-Object { $signerDigestPattern.Match([string]$_) } |
        Where-Object Success |
        Select-Object -First 1
    $peerSignerMatch = $peerSignature |
        ForEach-Object { $signerDigestPattern.Match([string]$_) } |
        Where-Object Success |
        Select-Object -First 1
    if ($null -eq $mainSignerMatch -or $null -eq $peerSignerMatch) {
        throw "Signer digest could not be parsed."
    }

    $sourceCommit = ([string](
        & git -C $repositoryRoot rev-parse HEAD 2>$null
    )).Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch "^[0-9a-f]{40}$") {
        throw "Source commit could not be resolved."
    }
    $worktreeStatus = @(
        & git -C $repositoryRoot status --porcelain=v1 2>$null
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Worktree status could not be read."
    }

    $candidate = [ordered]@{
        schema = "aurorama.g2-entry-candidate.v1"
        ownerRole = $ownerRole
        builtAt = (Get-Date).ToString("o")
        sourceCommit = $sourceCommit
        sourceInputsDigestSha256 = $sourceInputsDigest
        buildMode = "OFFLINE_LOCAL_TOOLCHAIN_VERSION_ONLY_DELTA"
        qaExecuted = $false
        deviceTouched = $false
        networkUsed = $false
        main = [ordered]@{
            path = ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/candidate-main-v6.apk"
            bytes = (Get-Item -LiteralPath $mainApk).Length
            sha256 = Get-FileSha256 $mainApk
            package = $mainPackageMatch.Groups[1].Value
            versionCode = $mainVersionCode
            versionName = $mainPackageMatch.Groups[3].Value
            abi = "arm64-v8a"
            internetPermission = $false
            permissions = $mainPermissions
            signerCertificateSha256 = $mainSignerMatch.Groups[1].Value
        }
        focusPeer = [ordered]@{
            path = ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/candidate-focus-peer-v2.apk"
            bytes = (Get-Item -LiteralPath $peerApk).Length
            sha256 = Get-FileSha256 $peerApk
            package = $peerPackageMatch.Groups[1].Value
            versionCode = $peerVersionCode
            versionName = $peerPackageMatch.Groups[3].Value
            internetPermission = $false
            permissions = $peerPermissions
            signerCertificateSha256 = $peerSignerMatch.Groups[1].Value
        }
        runtimeInputs = $runtimeInputs
        worktreeStatusAtBuild = $worktreeStatus
    }
    $candidatePath = Join-Path $evidenceRoot "candidate.json"
    $candidateText = $candidate | ConvertTo-Json -Depth 12
    Write-LfText -Path $candidatePath -Text $candidateText
    Write-LfText `
        -Path (Join-Path $evidenceRoot "candidate-main-badging.txt") `
        -Text ($mainBadging -join "`n")
    Write-LfText `
        -Path (Join-Path $evidenceRoot "candidate-focus-peer-badging.txt") `
        -Text ($peerBadging -join "`n")

    Write-Output "STATUS=PASS"
    Write-Output "MAIN_BYTES=$($candidate.main.bytes)"
    Write-Output "MAIN_SHA256=$($candidate.main.sha256)"
    Write-Output "PEER_BYTES=$($candidate.focusPeer.bytes)"
    Write-Output "PEER_SHA256=$($candidate.focusPeer.sha256)"
    Write-Output "SOURCE_INPUTS_DIGEST=$sourceInputsDigest"
}
finally {
    if (
        (Test-Path -LiteralPath $tempRoot) -and
        $tempRoot.StartsWith(
            $tempBase,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -and
        ([System.IO.Path]::GetFileName($tempRoot)).StartsWith(
            "aurorama-g2-candidate-",
            [System.StringComparison]::Ordinal
        )
    ) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
