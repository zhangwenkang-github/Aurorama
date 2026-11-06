[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$evidenceRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $evidenceRoot "..\..\..")
)
$contractPath = Join-Path $evidenceRoot "contract.json"
$candidatePath = Join-Path $evidenceRoot "candidate.json"
$authorizationPath = Join-Path $evidenceRoot "authorization-receipt.json"
$schemaPath = Join-Path $evidenceRoot "report.schema.json"
$runtimeToolsManifestPath = Join-Path `
    $evidenceRoot `
    "runtime-tools.manifest.json"
$aapt2 = "F:\Develop\Android\AndroidSDK\build-tools\37.0.0\aapt2.exe"
$apksigner =
    "F:\Develop\Android\AndroidSDK\build-tools\37.0.0\apksigner.bat"

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Name
    )
    if (-not $Condition) {
        throw "ASSERT-FAILED $Name"
    }
}

function Read-JsonStrict {
    param([Parameter(Mandatory)][string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-True `
        -Condition (-not (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        )) `
        -Name ("NO-BOM-" + [System.IO.Path]::GetFileName($Path))
    Assert-True `
        -Condition ([Array]::IndexOf($bytes, [byte]13) -lt 0) `
        -Name ("LF-" + [System.IO.Path]::GetFileName($Path))
    return (
        $utf8Strict.GetString($bytes) |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Get-RelativePath {
    param([Parameter(Mandatory)][string]$Path)
    return [System.IO.Path]::GetRelativePath(
        $repositoryRoot,
        $Path
    ).Replace("\", "/")
}

function Resolve-EvidencePath {
    param([Parameter(Mandatory)][string]$RelativePath)
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $repositoryRoot $RelativePath)
    )
    $rootPrefix = $evidenceRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    Assert-True `
        -Condition $fullPath.StartsWith(
            $rootPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        ) `
        -Name "EVIDENCE-PATH-INSIDE-ROOT"
    return $fullPath
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function Resolve-ContractRelativePath {
    param([Parameter(Mandatory)][string]$RelativePath)
    if (
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath -match "^[A-Za-z]:" -or
        $RelativePath.StartsWith("\\", [System.StringComparison]::Ordinal)
    ) {
        throw "ASSERT-FAILED MANIFEST-PATH-ABSOLUTE"
    }
    if (@($RelativePath -split "[\\/]") -contains "..") {
        throw "ASSERT-FAILED MANIFEST-PATH-TRAVERSAL"
    }
    $fullPath = [System.IO.Path]::GetFullPath(
        (Join-Path $evidenceRoot $RelativePath)
    )
    $rootPrefix = $evidenceRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    Assert-True `
        -Condition $fullPath.StartsWith(
            $rootPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        ) `
        -Name "MANIFEST-PATH-INSIDE-ROOT"
    return $fullPath
}

foreach ($requiredPath in @(
    $contractPath,
    $candidatePath,
    $authorizationPath,
    $schemaPath,
    $runtimeToolsManifestPath,
    $aapt2,
    $apksigner
)) {
    Assert-True `
        -Condition (Test-Path -LiteralPath $requiredPath) `
        -Name ("EXISTS-" + [System.IO.Path]::GetFileName($requiredPath))
}

$contract = Read-JsonStrict $contractPath
$candidate = Read-JsonStrict $candidatePath
$authorization = Read-JsonStrict $authorizationPath
[void](Read-JsonStrict $schemaPath)
$runtimeToolsManifest = Read-JsonStrict $runtimeToolsManifestPath

Assert-True `
    -Condition ($contract.schema -ceq "aurorama.g2-entry-runtime-contract.v1") `
    -Name "CONTRACT-SCHEMA"
Assert-True `
    -Condition (
        $contract.status -ceq
        "PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT"
    ) `
    -Name "CONTRACT-STATUS"
Assert-True `
    -Condition (
        $contract.owner.roleId -ceq
        "G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06"
    ) `
    -Name "OWNER-IDENTITY"
Assert-True `
    -Condition ($contract.owner.freshness -ceq "ONE_TIME_ONLY") `
    -Name "OWNER-FRESHNESS"
Assert-True `
    -Condition (
        $contract.evidence.root -ceq
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT"
    ) `
    -Name "EVIDENCE-ROOT"
Assert-True `
    -Condition (
        $contract.authorization.qaDispatchable -eq $false -and
        $contract.authorization.activePrompt -ceq
            "G2_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT_OWNER" -and
        $contract.authorization.deviceExecution -ceq
            "AUTHORIZED_PAD_FIRST_K60_GATED"
    ) `
    -Name "RUNTIME-AUTHORIZATION-OPEN"
Assert-True `
    -Condition (
        $authorization.schema -ceq
            "aurorama.g2-device-execution-authorization.v1" -and
        $authorization.receiptId -ceq
            "G2-DEVICE-AUTH-2026-11-06-PAD-K60" -and
        $authorization.decisionId -ceq "V2-D210" -and
        $authorization.contractId -ceq $contract.contractId -and
        $authorization.authorizedBy -ceq "USER" -and
        $authorization.scope.runtimeQa -eq $true -and
        $authorization.scope.deviceInstall -eq $true -and
        $authorization.scope.newRuntimeToolCreation -eq $true -and
        $authorization.scope.pad.authorized -eq $true -and
        $authorization.scope.k60.authorized -eq $true -and
        $authorization.scope.k60.conditional -eq $true -and
        $authorization.scope.k60.condition -ceq
            "PAD_FULL_CORE_PASS_AND_RELEASED_ACTIVE_0" -and
        $authorization.scope.g2Implementation -eq $false -and
        $authorization.scope.release -eq $false -and
        $authorization.scope.serverWrite -eq $false -and
        $authorization.scope.q03Close -eq $false -and
        $authorization.scope.profileVoiceFreeze -eq $false -and
        $authorization.scope.padP2Acceptance -eq $false -and
        $authorization.scope.l2L3Unlock -eq $false -and
        $authorization.candidate.sourceCommit -ceq
            $candidate.sourceCommit -and
        $authorization.candidate.mainSha256 -ceq
            $candidate.main.sha256 -and
        $authorization.candidate.focusPeerSha256 -ceq
            $candidate.focusPeer.sha256
    ) `
    -Name "DEVICE-AUTHORIZATION-RECEIPT"
Assert-True `
    -Condition (
        $contract.devicePolicy.deviceOrder -ceq "Pad->K60" -and
        $contract.devicePolicy.deviceWindowMinutes -eq 10 -and
        $contract.devicePolicy.attemptsPerDevice -eq 1 -and
        $contract.devicePolicy.k60Locked -eq $true -and
        $contract.devicePolicy.pad.serial -ceq "43af8627" -and
        $contract.devicePolicy.k60.serial -ceq "8e875894"
    ) `
    -Name "DEVICE-POLICY"
Assert-True `
    -Condition (
        $contract.executionPolicy.padRequiresFullCorePassBeforeK60 -eq $true -and
        $contract.executionPolicy.padLockMustBeReleasedBeforeK60 -eq $true -and
        $contract.executionPolicy.windowExtensionAllowed -eq $false -and
        $contract.executionPolicy.retryAllowed -eq $false
    ) `
    -Name "EXECUTION-POLICY"
Assert-True `
    -Condition (
        @($contract.productGates).Count -eq 13 -and
        @($contract.stopConditions).Count -ge 10
    ) `
    -Name "GATE-COVERAGE"
Assert-True `
    -Condition (
        $contract.tools.runtimeCollector.roleId -ceq
            "G2_RUNTIME_TOOL_OWNER_2026_11_06" -and
        $contract.tools.runtimeFinalizer.roleId -ceq
            "G2_RUNTIME_TOOL_OWNER_2026_11_06" -and
        $contract.tools.cleanupVerifier.roleId -ceq
            "G2_RUNTIME_TOOL_OWNER_2026_11_06" -and
        $contract.tools.runtimeCollector.status -ceq
            "READY_SELF_TESTED_STATIC_ONLY" -and
        $contract.tools.runtimeFinalizer.status -ceq
            "READY_SELF_TESTED_STATIC_ONLY" -and
        $contract.tools.cleanupVerifier.status -ceq
            "READY_SELF_TESTED_STATIC_ONLY" -and
        $contract.tools.runtimeCollector.deviceAccess -ceq
            "STATIC_SELF_TEST_ONLY_THIS_PHASE" -and
        $contract.tools.runtimeFinalizer.deviceAccess -ceq
            "STATIC_SELF_TEST_ONLY_THIS_PHASE" -and
        $contract.tools.cleanupVerifier.deviceAccess -ceq
            "STATIC_SELF_TEST_ONLY_THIS_PHASE" -and
        $contract.tools.runtimeCollector.networkAccess -ceq "FORBIDDEN" -and
        $contract.tools.runtimeFinalizer.networkAccess -ceq "FORBIDDEN" -and
        $contract.tools.cleanupVerifier.networkAccess -ceq "FORBIDDEN"
    ) `
    -Name "RUNTIME-TOOLS-READY-STATIC-ONLY"
Assert-True `
    -Condition (
        $runtimeToolsManifest.schema -ceq
            "aurorama.g2-runtime-tools.v1" -and
        $runtimeToolsManifest.contractId -ceq $contract.contractId -and
        $runtimeToolsManifest.ownerRole -ceq
            "G2_RUNTIME_TOOL_OWNER_2026_11_06" -and
        $runtimeToolsManifest.status -ceq
            "READY_SELF_TESTED_STATIC_ONLY" -and
        [int]$runtimeToolsManifest.qaV9ReuseCount -eq 0 -and
        [int]$runtimeToolsManifest.deviceCommandsExecutedInSelfTest -eq 0 -and
        [int]$runtimeToolsManifest.adbCallsInSelfTest -eq 0 -and
        [int]$runtimeToolsManifest.networkCallsInSelfTest -eq 0 -and
        [int]$runtimeToolsManifest.gradleCallsInSelfTest -eq 0 -and
        $runtimeToolsManifest.fixtureModeCannotProducePass -eq $true
    ) `
    -Name "RUNTIME-TOOLS-MANIFEST-IDENTITY"
Assert-True `
    -Condition (
        @($runtimeToolsManifest.devicePolicy.allowedSerials).Count -eq 2 -and
        @($runtimeToolsManifest.devicePolicy.allowedSerials) -contains
            "43af8627" -and
        @($runtimeToolsManifest.devicePolicy.allowedSerials) -contains
            "8e875894" -and
        $runtimeToolsManifest.devicePolicy.padFirst -eq $true -and
        $runtimeToolsManifest.devicePolicy.
            k60RequiresPadFullPassAndReleasedLock -eq $true -and
        [int]$runtimeToolsManifest.devicePolicy.attemptsPerDevice -eq 1 -and
        [int]$runtimeToolsManifest.devicePolicy.windowSeconds -eq 600 -and
        $runtimeToolsManifest.devicePolicy.extensionAllowed -eq $false -and
        $runtimeToolsManifest.devicePolicy.retryAllowed -eq $false
    ) `
    -Name "RUNTIME-TOOLS-DEVICE-POLICY"
$runtimeToolFiles = [System.Collections.Generic.List[string]]::new()
$runtimeToolPathSet = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
foreach ($manifestEntry in @($runtimeToolsManifest.files)) {
    $manifestEntryPath = Resolve-ContractRelativePath `
        ([string]$manifestEntry.path)
    Assert-True `
        -Condition (Test-Path -LiteralPath $manifestEntryPath -PathType Leaf) `
        -Name ("RUNTIME-TOOL-FILE-EXISTS-" + $manifestEntry.path)
    Assert-True `
        -Condition $runtimeToolPathSet.Add([string]$manifestEntry.path) `
        -Name ("RUNTIME-TOOL-FILE-DUPLICATE-" + $manifestEntry.path)
    Assert-True `
        -Condition (
            (Get-FileSha256 $manifestEntryPath) -ceq
            [string]$manifestEntry.sha256
        ) `
        -Name ("RUNTIME-TOOL-FILE-SHA-" + $manifestEntry.path)
    $runtimeToolFiles.Add([string]$manifestEntry.path)
}
foreach ($requiredRuntimeTool in @(
    "Invoke-G2RuntimeCollector.ps1",
    "Finalize-G2RuntimeReport.ps1",
    "Test-G2CleanupRestore.ps1",
    "tests/Test-G2RuntimeTools.ps1"
)) {
    Assert-True `
        -Condition $runtimeToolPathSet.Contains($requiredRuntimeTool) `
        -Name ("RUNTIME-TOOL-MANIFEST-MISSING-" + $requiredRuntimeTool)
}
$runtimeToolDigestBuilder = [System.Text.StringBuilder]::new()
foreach ($fileName in @(
    "Finalize-G2RuntimeReport.ps1",
    "Invoke-G2RuntimeCollector.ps1",
    "Test-G2CleanupRestore.ps1"
) | Sort-Object) {
    $runtimeToolPath = Join-Path $evidenceRoot $fileName
    [void]$runtimeToolDigestBuilder.Append(
        (Get-FileSha256 $runtimeToolPath) + "  " + $fileName + "`n"
    )
}
$runtimeToolsDigest = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData(
        [System.Text.Encoding]::UTF8.GetBytes(
            $runtimeToolDigestBuilder.ToString()
        )
    )
).ToLowerInvariant()
Assert-True `
    -Condition (
        $runtimeToolsDigest -ceq $contract.tools.digestSha256 -and
        $runtimeToolsManifest.toolsDigestSha256 -ceq
            $contract.tools.digestSha256
    ) `
    -Name "RUNTIME-TOOLS-DIGEST"
foreach ($fileName in @(
    "Invoke-G2RuntimeCollector.ps1",
    "Finalize-G2RuntimeReport.ps1",
    "Test-G2CleanupRestore.ps1"
)) {
    $runtimeToolPath = Join-Path $evidenceRoot $fileName
    $runtimeToolText = [System.IO.File]::ReadAllText(
        $runtimeToolPath,
        $utf8Strict
    )
    Assert-True `
        -Condition (-not $runtimeToolText.Contains(
            "qa-v9",
            [System.StringComparison]::OrdinalIgnoreCase
        )) `
        -Name ("RUNTIME-TOOL-HISTORICAL-REFERENCE-" + $fileName)
    foreach ($forbiddenToken in @(
        "资源.txt",
        "Authorization:",
        "password=",
        "accessToken",
        "clientSecret"
    )) {
        Assert-True `
            -Condition (-not $runtimeToolText.Contains(
                $forbiddenToken,
                [System.StringComparison]::OrdinalIgnoreCase
            )) `
            -Name (
                "RUNTIME-TOOL-SENSITIVE-TOKEN-" +
                $fileName +
                "-" +
                $forbiddenToken
            )
    }
}
$builderPath = Resolve-EvidencePath $contract.tools.candidateBuilder.path
$validatorPath = Resolve-EvidencePath $contract.tools.contractValidator.path
$schemaContractPath = Resolve-EvidencePath $contract.evidence.reportSchemaPath
$runtimeManifestPath = Resolve-EvidencePath `
    $contract.tools.runtimeManifest.path
Assert-True `
    -Condition (
        (Get-FileSha256 $builderPath) -ceq
        $contract.tools.candidateBuilder.sha256 -and
        (Get-FileSha256 $validatorPath) -ceq
        $contract.tools.contractValidator.sha256 -and
        (Get-FileSha256 $runtimeManifestPath) -ceq
            $contract.tools.runtimeManifest.sha256 -and
        (Get-FileSha256 $schemaContractPath) -ceq
        $contract.evidence.reportSchemaSha256
    ) `
    -Name "TOOL-AND-SCHEMA-BINDING"
Assert-True `
    -Condition (
        $contract.reusePolicy.qaV9OwnerReusable -eq $false -and
        $contract.reusePolicy.qaV9ToolsReusable -eq $false -and
        $contract.reusePolicy.qaV9EvidenceReusable -eq $false -and
        $contract.reusePolicy.qaV9PromptReusable -eq $false
    ) `
    -Name "QA-V9-NO-REUSE"
Assert-True `
    -Condition (
        $contract.stateLocks.q03 -ceq "OPEN" -and
        $contract.stateLocks.exactProfileVoice -ceq "OPEN" -and
        $contract.stateLocks.padP2 -ceq "UNACCEPTED" -and
        $contract.stateLocks.g1 -ceq "NOT_PASSED" -and
        $contract.stateLocks.runtime -ceq "UNVERIFIED" -and
        $contract.stateLocks.l2L3 -ceq "LOCKED" -and
        $contract.stateLocks.g2Implementation -ceq "NOT_AUTHORIZED"
    ) `
    -Name "STATE-LOCKS"

Assert-True `
    -Condition (
        $candidate.schema -ceq "aurorama.g2-entry-candidate.v1" -and
        $candidate.qaExecuted -eq $false -and
        $candidate.deviceTouched -eq $false -and
        $candidate.networkUsed -eq $false
    ) `
    -Name "CANDIDATE-PROVENANCE"
Assert-True `
    -Condition (
        $candidate.main.sha256 -ceq $contract.candidate.main.sha256 -and
        $candidate.focusPeer.sha256 -ceq $contract.candidate.focusPeer.sha256
    ) `
    -Name "CONTRACT-CANDIDATE-BINDING"

foreach ($artifact in @(
    [pscustomobject]@{
        Name = "main"
        Expected = $candidate.main
    },
    [pscustomobject]@{
        Name = "focusPeer"
        Expected = $candidate.focusPeer
    }
)) {
    $artifactPath = Resolve-EvidencePath $artifact.Expected.path
    Assert-True `
        -Condition (Test-Path -LiteralPath $artifactPath -PathType Leaf) `
        -Name ("ARTIFACT-EXISTS-" + $artifact.Name)
    Assert-True `
        -Condition (
            (Get-Item -LiteralPath $artifactPath).Length -eq
            [long]$artifact.Expected.bytes
        ) `
        -Name ("ARTIFACT-BYTES-" + $artifact.Name)
    Assert-True `
        -Condition (
            (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).
                Hash.
                ToLowerInvariant() -ceq $artifact.Expected.sha256
        ) `
        -Name ("ARTIFACT-SHA256-" + $artifact.Name)

    $badging = @(& $aapt2 dump badging $artifactPath 2>&1)
    Assert-True `
        -Condition ($LASTEXITCODE -eq 0) `
        -Name ("BADGING-EXIT-" + $artifact.Name)
    $badgingText = $badging -join "`n"
    Assert-True `
        -Condition $badgingText.Contains(
            "package: name='$($artifact.Expected.package)' " +
            "versionCode='$($artifact.Expected.versionCode)' " +
            "versionName='$($artifact.Expected.versionName)'",
            [System.StringComparison]::Ordinal
        ) `
        -Name ("BADGING-IDENTITY-" + $artifact.Name)
    Assert-True `
        -Condition (-not $badgingText.Contains(
            "uses-permission: name='android.permission.INTERNET'",
            [System.StringComparison]::Ordinal
        )) `
        -Name ("NO-INTERNET-PERMISSION-" + $artifact.Name)

    & $apksigner verify --verbose --print-certs $artifactPath 2>&1 |
        Out-Null
    Assert-True `
        -Condition ($LASTEXITCODE -eq 0) `
        -Name ("SIGNATURE-" + $artifact.Name)
}

Assert-True `
    -Condition (
        (Get-RelativePath $evidenceRoot) -ceq
        $contract.evidence.root
    ) `
    -Name "EVIDENCE-ROOT-MATCH"

Write-Output "PASS G2_ENTRY_RUNTIME_CONTRACT"
Write-Output "MAIN_SHA256=$($candidate.main.sha256)"
Write-Output "PEER_SHA256=$($candidate.focusPeer.sha256)"
Write-Output "RUNTIME_AUTHORIZATION=PAD_FIRST_K60_GATED"
