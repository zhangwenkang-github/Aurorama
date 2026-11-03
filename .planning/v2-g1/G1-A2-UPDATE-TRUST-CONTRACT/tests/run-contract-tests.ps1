param(
    [ValidateSet("positive", "break-hash", "break-install-confirmation")]
    [string]$Mode = "positive"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)

function Read-JsonFile {
    param([Parameter(Mandatory)][string]$Path)
    $text = [System.IO.File]::ReadAllText($Path, $utf8)
    return $text | ConvertFrom-Json -Depth 100
}

function Copy-JsonObject {
    param([Parameter(Mandatory)]$Value)
    return ($Value | ConvertTo-Json -Depth 100 -Compress) | ConvertFrom-Json -Depth 100
}

function Merge-JsonObject {
    param(
        [Parameter(Mandatory)]$Base,
        $Override
    )
    $result = Copy-JsonObject $Base
    if ($null -eq $Override) {
        return $result
    }
    foreach ($property in $Override.PSObject.Properties) {
        $existing = $result.PSObject.Properties[$property.Name]
        if (
            $property.Value -is [System.Management.Automation.PSCustomObject] -and
            $null -ne $existing -and
            $existing.Value -is [System.Management.Automation.PSCustomObject]
        ) {
            $result.($property.Name) = Merge-JsonObject $existing.Value $property.Value
        } elseif ($null -eq $existing) {
            $result | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
        } else {
            $result.($property.Name) = $property.Value
        }
    }
    return $result
}

function New-Outcome {
    param(
        [Parameter(Mandatory)][string]$State,
        [AllowNull()][object]$Failure
    )
    return [pscustomobject]@{
        State = $State
        Failure = $Failure
    }
}

function Get-VerificationOutcome {
    param([Parameter(Mandatory)]$Data)
    $c = $Data.checks
    if (-not [bool]$c.sourceTrusted) { return New-Outcome "FAILED" "UNTRUSTED_SOURCE" }
    if (-not [bool]$c.finalUrlTrusted) { return New-Outcome "FAILED" "UNTRUSTED_REDIRECT" }
    if (-not [bool]$c.channelStable) { return New-Outcome "FAILED" "UNSUPPORTED_CHANNEL" }
    if ([bool]$c.upToDate) { return New-Outcome "UP_TO_DATE" $null }
    if (-not [bool]$c.versionGreater) { return New-Outcome "FAILED" "VERSION_NOT_ADVANCING" }
    if (-not [bool]$c.assetUnique) { return New-Outcome "FAILED" "ABI_ASSET_AMBIGUOUS" }
    if (-not [bool]$c.abiSelected) { return New-Outcome "FAILED" "NO_COMPATIBLE_ABI" }
    if (-not [bool]$c.assetUrlExact) { return New-Outcome "FAILED" "UNTRUSTED_ASSET_URL" }
    if (-not [bool]$c.sizeKnown) { return New-Outcome "FAILED" "SIZE_UNKNOWN" }
    if (-not [bool]$c.digestPresent) { return New-Outcome "FAILED" "DIGEST_MISSING_OR_MALFORMED" }
    if (-not [bool]$c.sizeMatched) { return New-Outcome "FAILED" "SIZE_MISMATCH" }
    if (-not [bool]$c.hashMatched) { return New-Outcome "FAILED" "HASH_MISMATCH" }
    if (-not [bool]$c.apkReadable) { return New-Outcome "FAILED" "APK_UNREADABLE" }
    if (-not [bool]$c.packageMatched) { return New-Outcome "FAILED" "PACKAGE_MISMATCH" }
    if (-not [bool]$c.versionCodeIncremented) { return New-Outcome "FAILED" "VERSION_CODE_NOT_INCREMENTED" }
    if (-not [bool]$c.versionNameExact) { return New-Outcome "FAILED" "VERSION_TAG_MISMATCH" }
    if (-not [bool]$c.minSdkSupported) { return New-Outcome "FAILED" "MIN_SDK_UNSUPPORTED" }
    if (-not [bool]$c.abiPayloadCompatible) { return New-Outcome "FAILED" "ABI_PAYLOAD_INCOMPATIBLE" }
    if (-not [bool]$c.signerSetPresent) { return New-Outcome "FAILED" "SIGNER_MISSING" }
    if (-not [bool]$c.signerSetMatched) { return New-Outcome "FAILED" "SIGNER_MISMATCH" }
    return New-Outcome "VERIFIED" $null
}

function Get-DownloadOutcome {
    param([Parameter(Mandatory)]$Data)
    $d = $Data.download
    if ([bool]$d.cancelRequested) { return New-Outcome "CANCELLED" "USER_CANCELLED" }
    if (-not [bool]$d.sourceIdentityStable) { return New-Outcome "FAILED" "SOURCE_CHANGED" }
    if (-not [bool]$d.rangeConsistent) { return New-Outcome "FAILED" "RANGE_INVALID" }
    if (-not [bool]$d.networkReached) { return New-Outcome "FAILED" "DOWNLOAD_FAILED" }
    if ([bool]$d.downloadComplete) {
        if (-not [bool]$d.bytesMatched) { return New-Outcome "FAILED" "SIZE_MISMATCH" }
        return New-Outcome "DOWNLOADED_UNVERIFIED" $null
    }
    return New-Outcome "DOWNLOADING" $null
}

function Get-InstallLaunchOutcome {
    param([Parameter(Mandatory)]$Data)
    if (-not [bool]$Data.checks.userAuthorized) {
        return New-Outcome "VERIFIED" "INSTALL_AUTHORIZATION_REQUIRED"
    }
    $i = $Data.installer
    if ([int]$i.resolvedCount -eq 0) { return New-Outcome "FAILED" "INSTALLER_UNAVAILABLE" }
    if ([int]$i.resolvedCount -gt 1) { return New-Outcome "FAILED" "INSTALLER_AMBIGUOUS" }
    if (-not [bool]$i.trustedSystemHandler) { return New-Outcome "FAILED" "INSTALLER_UNTRUSTED" }
    if (-not [bool]$i.installPackagesPermission) { return New-Outcome "FAILED" "INSTALLER_PERMISSION_DENIED" }
    if (-not [bool]$i.uriValidated) { return New-Outcome "FAILED" "INSTALLER_URI_INVALID" }
    if (-not [bool]$i.launched) { return New-Outcome "INSTALL_AUTHORIZED" $null }
    return New-Outcome "INSTALLER_HANDOFF" $null
}

function Get-InstallReturnOutcome {
    param([Parameter(Mandatory)]$Data)
    $e = $Data.external
    if ($e.status -eq "cancelled") { return New-Outcome "CANCELLED" "USER_CANCELLED" }
    if ($e.status -eq "failed") { return New-Outcome "FAILED" "INSTALL_FAILED" }
    if ($e.status -ne "success" -or -not [bool]$e.readbackAvailable) {
        return New-Outcome "INSTALL_UNKNOWN" "INSTALL_RESULT_UNKNOWN"
    }
    $exact =
        [bool]$e.packageMatched -and
        [bool]$e.versionCodeMatched -and
        [bool]$e.versionNameMatched -and
        [bool]$e.signerMatched
    if (-not $exact) {
        return New-Outcome "INSTALL_UNKNOWN" "INSTALL_CONFIRMATION_MISMATCH"
    }
    return New-Outcome "INSTALL_CONFIRMED" $null
}

function Invoke-ContractCase {
    param(
        [Parameter(Mandatory)]$Case,
        [Parameter(Mandatory)]$Base
    )
    $data = Merge-JsonObject $Base $Case.overrides
    switch ($Case.stage) {
        "verify" { return Get-VerificationOutcome $data }
        "download" { return Get-DownloadOutcome $data }
        "install_launch" { return Get-InstallLaunchOutcome $data }
        "install_return" { return Get-InstallReturnOutcome $data }
        "restart" {
            switch ($data.atState) {
                "DOWNLOADING" {
                    if (-not [bool]$data.download.sourceIdentityStable) {
                        return New-Outcome "FAILED" "SOURCE_CHANGED"
                    }
                    return New-Outcome "DOWNLOADING" $null
                }
                "DOWNLOADED_UNVERIFIED" { return Get-VerificationOutcome $data }
                "VERIFIED" { return Get-VerificationOutcome $data }
                "INSTALL_AUTHORIZED" {
                    return New-Outcome "VERIFIED" "INSTALL_AUTHORIZATION_REQUIRED"
                }
                "INSTALLER_HANDOFF" { return Get-InstallReturnOutcome $data }
                default { throw "Unsupported restart state: $($data.atState)" }
            }
        }
        default { throw "Unsupported case stage: $($Case.stage)" }
    }
}

$contract = Read-JsonFile (Join-Path $root "trust-contract.json")
$stateMachine = Read-JsonFile (Join-Path $root "state-machine.json")
$matrix = Read-JsonFile (Join-Path $root "trust-matrix.json")
$fixture = Read-JsonFile (Join-Path $root "fixtures/contract-cases.json")

$failures = [System.Collections.Generic.List[string]]::new()
$contractAssertions = 0
$fixtureAssertions = 0
$propertyAssertions = 0

function Add-AssertionFailure {
    param([Parameter(Mandatory)][string]$Message)
    $failures.Add($Message)
}

function Assert-Condition {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not $Condition) {
        Add-AssertionFailure $Message
    }
}

if ($Mode -ne "positive") {
    $caseId = if ($Mode -eq "break-hash") { "valid_arm64_update" } else { "install_confirmed_exact" }
    $case = $fixture.cases | Where-Object id -eq $caseId
    if ($Mode -eq "break-hash") {
        $case.overrides = [pscustomobject]@{ checks = [pscustomobject]@{ hashMatched = $false } }
    } else {
        $case.overrides = [pscustomobject]@{ external = [pscustomobject]@{ readbackAvailable = $false } }
    }
    $broken = Invoke-ContractCase $case $fixture.base
    if (
        $broken.State -eq $case.expected.state -and
        [string]$broken.Failure -eq [string]$case.expected.failure
    ) {
        Write-Output "NEGATIVE_CONTROL_NOT_DETECTED mode=$Mode"
        exit 0
    }
    Write-Output "NEGATIVE_CONTROL_DETECTED mode=$Mode expected=$($case.expected.state)/$($case.expected.failure) actual=$($broken.State)/$($broken.Failure)"
    exit 1
}

$contractAssertions++
Assert-Condition ($fixture.syntheticOnly -eq $true) "fixture must be explicitly synthetic"
Assert-Condition ([bool]$contract.syntheticEvidenceOnly) "contract must identify synthetic evidence"
Assert-Condition (@($contract.checks).Count -eq 23) "expected exactly 23 ordered checks"
Assert-Condition (@($stateMachine.states).Count -eq 10) "expected exactly 10 states"
Assert-Condition (@($matrix.rows).Count -eq 10) "expected exactly 10 user-promise rows"

$orders = @($contract.checks | ForEach-Object order)
$contractAssertions++
Assert-Condition (($orders -join ",") -eq ((1..23) -join ",")) "check order must be contiguous 1..23"

$checksWithMetadata = @(
    $contract.checks | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_.authority) -and
        -not [string]::IsNullOrWhiteSpace($_.rule) -and
        $_.failureVisible.userVisible -eq $true -and
        -not [string]::IsNullOrWhiteSpace($_.failureVisible.messageKey) -and
        -not [string]::IsNullOrWhiteSpace($_.failureVisible.copy)
    }
).Count
$contractAssertions++
Assert-Condition ($checksWithMetadata -eq 23) "every check must declare authority/rule/visible failure"

$stateIds = @($stateMachine.states | ForEach-Object id)
$requiredStates = @(
    "DISCOVERED",
    "DOWNLOADING",
    "DOWNLOADED_UNVERIFIED",
    "VERIFIED",
    "INSTALL_AUTHORIZED",
    "INSTALLER_HANDOFF",
    "INSTALL_CONFIRMED",
    "INSTALL_UNKNOWN",
    "FAILED",
    "CANCELLED"
)
$contractAssertions++
Assert-Condition ((@($requiredStates | Where-Object { $_ -notin $stateIds })).Count -eq 0) "required state missing"

$invalidTransitions = @(
    $stateMachine.transitions | Where-Object { $_.from -notin $stateIds -or $_.to -notin $stateIds }
)
$contractAssertions++
Assert-Condition ($invalidTransitions.Count -eq 0) "state machine references unknown state"

$contractAssertions++
Assert-Condition ($contract.statePolicy.prohibitedShortcuts.Count -ge 4) "prohibited shortcut policy is incomplete"

$matrixInvalid = @(
    $matrix.rows | Where-Object {
        [string]::IsNullOrWhiteSpace($_.userPromise) -or
        [string]::IsNullOrWhiteSpace($_.entry) -or
        [string]::IsNullOrWhiteSpace($_.action) -or
        [string]::IsNullOrWhiteSpace($_.observableResult) -or
        @($_.authorityAndOrder).Count -eq 0 -or
        @($_.failureVisible).Count -eq 0 -or
        @($_.fixtureEvidence).Count -eq 0 -or
        @($_.uncovered).Count -eq 0
    }
)
$contractAssertions++
Assert-Condition ($matrixInvalid.Count -eq 0) "matrix row missing required evidence fields"

$caseIds = @($fixture.cases | ForEach-Object id)
$matrixFixtureRefs = @($matrix.rows | ForEach-Object { $_.fixtureEvidence } | ForEach-Object { $_ })
$missingFixtureRefs = @($matrixFixtureRefs | Where-Object { $_ -notin $caseIds } | Select-Object -Unique)
$contractAssertions++
Assert-Condition ($missingFixtureRefs.Count -eq 0) "matrix references missing fixture cases: $($missingFixtureRefs -join ',')"

$contractAssertions++
Assert-Condition (
    $contract.trustedSource.releaseApiUrl -eq "https://api.github.com/repos/zhangwenkang-github/Aurorama/releases/latest" -and
    $contract.trustedSource.acceptDraft -eq $false -and
    $contract.trustedSource.acceptPrerelease -eq $false -and
    $contract.trustedSource.serverUpdateEndpointAllowed -eq $false
) "trusted source/channel contract drifted"

$contractAssertions++
Assert-Condition (
    $contract.statePolicy.downloadCompletionState -eq "DOWNLOADED_UNVERIFIED" -and
    $contract.statePolicy.installerLaunchState -eq "INSTALLER_HANDOFF" -and
    $contract.statePolicy.unknownState -eq "INSTALL_UNKNOWN"
) "download/install state separation drifted"

foreach ($case in $fixture.cases) {
    $fixtureAssertions++
    $actual = Invoke-ContractCase $case $fixture.base
    if (
        $actual.State -ne $case.expected.state -or
        [string]$actual.Failure -ne [string]$case.expected.failure
    ) {
        Add-AssertionFailure "fixture $($case.id) expected $($case.expected.state)/$($case.expected.failure) actual $($actual.State)/$($actual.Failure)"
    }
}

$allTrue = Copy-JsonObject $fixture.base
$propertyAssertions++
$actualAllTrue = Get-VerificationOutcome $allTrue
Assert-Condition ($actualAllTrue.State -eq "VERIFIED") "all verification gates must reach VERIFIED"

foreach ($gate in $fixture.criticalVerificationGates) {
    $propertyAssertions++
    $singleFalse = Copy-JsonObject $fixture.base
    $singleFalse.checks.($gate) = $false
    $actual = Get-VerificationOutcome $singleFalse
    Assert-Condition ($actual.State -ne "VERIFIED") "PROP-EACH-GATE-BLOCKS failed for $gate"
}

$subset = @($fixture.criticalVerificationGates | Select-Object -First 8)
for ($mask = 0; $mask -lt [math]::Pow(2, $subset.Count); $mask++) {
    $propertyAssertions++
    $candidate = Copy-JsonObject $fixture.base
    $allSubsetPass = $true
    for ($index = 0; $index -lt $subset.Count; $index++) {
        $pass = (($mask -band (1 -shl $index)) -ne 0)
        $candidate.checks.($subset[$index]) = $pass
        if (-not $pass) {
            $allSubsetPass = $false
        }
    }
    $actual = Get-VerificationOutcome $candidate
    if ($allSubsetPass) {
        Assert-Condition ($actual.State -eq "VERIFIED") "PROP-VERIFIED-ONLY-ALL-GATES all-pass mask=$mask"
    } else {
        Assert-Condition ($actual.State -ne "VERIFIED") "PROP-VERIFIED-ONLY-ALL-GATES false gate mask=$mask"
    }
}

$completeDownload = Invoke-ContractCase ($fixture.cases | Where-Object id -eq "download_completed") $fixture.base
$propertyAssertions++
Assert-Condition ($completeDownload.State -eq "DOWNLOADED_UNVERIFIED") "PROP-DOWNLOAD-NOT-CONFIRMED failed"

$launchOnly = Invoke-ContractCase ($fixture.cases | Where-Object id -eq "installer_launched_without_result") $fixture.base
$propertyAssertions++
Assert-Condition ($launchOnly.State -eq "INSTALL_UNKNOWN") "PROP-HANDOFF-NOT-CONFIRMED failed"

$sourceFirst = Copy-JsonObject $fixture.base
$sourceFirst.checks.sourceTrusted = $false
$propertyAssertions++
Assert-Condition ((Get-VerificationOutcome $sourceFirst).Failure -eq "UNTRUSTED_SOURCE") "PROP-SOURCE-FIRST failed"

$prerelease = Copy-JsonObject $fixture.base
$prerelease.checks.channelStable = $false
$propertyAssertions++
Assert-Condition ((Get-VerificationOutcome $prerelease).Failure -eq "UNSUPPORTED_CHANNEL") "PROP-PRERELEASE-REJECTED failed"

foreach ($abiGate in @("assetUnique", "abiSelected", "abiPayloadCompatible")) {
    $propertyAssertions++
    $abiData = Copy-JsonObject $fixture.base
    $abiData.checks.($abiGate) = $false
    $abiFailure = (Get-VerificationOutcome $abiData).Failure
    Assert-Condition ($abiFailure -in @("ABI_ASSET_AMBIGUOUS", "NO_COMPATIBLE_ABI", "ABI_PAYLOAD_INCOMPATIBLE")) "PROP-ABI-SINGLETON failed for $abiGate"
}

$authRequired = Copy-JsonObject $fixture.base
$authRequired.checks.userAuthorized = $false
$propertyAssertions++
Assert-Condition ((Get-InstallLaunchOutcome $authRequired).Failure -eq "INSTALL_AUTHORIZATION_REQUIRED") "PROP-INSTALL-AUTH-REQUIRED failed"

$installerVariants = @(
    @{ field = "resolvedCount"; value = 0 },
    @{ field = "resolvedCount"; value = 2 },
    @{ field = "trustedSystemHandler"; value = $false },
    @{ field = "installPackagesPermission"; value = $false },
    @{ field = "uriValidated"; value = $false }
)
foreach ($variant in $installerVariants) {
    $propertyAssertions++
    $installerData = Copy-JsonObject $fixture.base
    $installerData.installer.($variant.field) = $variant.value
    $installerOutcome = Get-InstallLaunchOutcome $installerData
    Assert-Condition (
        $installerOutcome.State -eq "FAILED" -and
        $installerOutcome.Failure -ne "INSTALLER_AMBIGUOUS" -or $variant.field -eq "resolvedCount"
    ) "PROP-INSTALLER-UNIQUE-TRUSTED failed for $($variant.field)"
}

$cancelData = Copy-JsonObject $fixture.base
$cancelData.download.cancelRequested = $true
$propertyAssertions++
Assert-Condition ((Get-DownloadOutcome $cancelData).State -eq "CANCELLED") "PROP-CANCEL-EXPLICIT download failed"
$cancelData.external.status = "cancelled"
$propertyAssertions++
Assert-Condition ((Get-InstallReturnOutcome $cancelData).State -eq "CANCELLED") "PROP-CANCEL-EXPLICIT install failed"
$notCancelled = Copy-JsonObject $fixture.base
$notCancelled.external.status = "success"
$propertyAssertions++
Assert-Condition ((Get-InstallReturnOutcome $notCancelled).State -eq "INSTALL_CONFIRMED") "PROP-CANCEL-EXPLICIT positive failed"

foreach ($restartState in @("DOWNLOADED_UNVERIFIED", "VERIFIED")) {
    $propertyAssertions++
    $restartData = Copy-JsonObject $fixture.base
    $restartData | Add-Member -NotePropertyName atState -NotePropertyValue $restartState
    $restartOk = Get-VerificationOutcome $restartData
    Assert-Condition ($restartOk.State -eq "VERIFIED") "PROP-RESTART-REVERIFY positive failed for $restartState"
    $propertyAssertions++
    $restartData.checks.hashMatched = $false
    $restartBad = Get-VerificationOutcome $restartData
    Assert-Condition ($restartBad.State -eq "FAILED" -and $restartBad.Failure -eq "HASH_MISMATCH") "PROP-RESTART-REVERIFY negative failed for $restartState"
}

foreach ($gate in $fixture.criticalVerificationGates) {
    $propertyAssertions++
    $preHandoff = Copy-JsonObject $fixture.base
    $preHandoff.checks.($gate) = $false
    $preHandoffOutcome = Get-VerificationOutcome $preHandoff
    Assert-Condition ($preHandoffOutcome.State -notin @("INSTALL_CONFIRMED", "INSTALLER_HANDOFF")) "PROP-PRE-HANDOFF-NO-INSTALL failed for $gate"
}

$externalFields = @("readbackAvailable", "packageMatched", "versionCodeMatched", "versionNameMatched", "signerMatched")
for ($mask = 0; $mask -lt [math]::Pow(2, $externalFields.Count); $mask++) {
    $propertyAssertions++
    $data = Copy-JsonObject $fixture.base
    $allMatch = $true
    for ($index = 0; $index -lt $externalFields.Count; $index++) {
        $pass = (($mask -band (1 -shl $index)) -ne 0)
        $data.external.($externalFields[$index]) = $pass
        if (-not $pass) {
            $allMatch = $false
        }
    }
    $actual = Get-InstallReturnOutcome $data
    if ($allMatch) {
        Assert-Condition ($actual.State -eq "INSTALL_CONFIRMED") "PROP-CONFIRM-EXACT all-match mask=$mask"
    } else {
        Assert-Condition ($actual.State -ne "INSTALL_CONFIRMED") "PROP-CONFIRM-EXACT mismatch mask=$mask"
    }
}

$serverWriteHits = @(
    Get-ChildItem -LiteralPath $root -File -Recurse |
        Where-Object Name -ne "run-contract-tests.ps1" |
        Select-String -Pattern "https?://[^`"]*jellyfin|/System/Info|PackageInstaller.*REPLACE" -AllMatches -ErrorAction SilentlyContinue
)
$contractAssertions++
Assert-Condition ($serverWriteHits.Count -eq 0) "unexpected Jellyfin update/server-write reference"
$propertyAssertions++
Assert-Condition (
    $contract.trustedSource.serverUpdateEndpointAllowed -eq $false -and
    $serverWriteHits.Count -eq 0
) "PROP-NO-SERVER-UPDATE failed"

if ($failures.Count -gt 0) {
    Write-Output "FAIL contractAssertions=$contractAssertions fixtures=$fixtureAssertions properties=$propertyAssertions failures=$($failures.Count)"
    foreach ($failure in $failures) {
        Write-Output "FAIL_DETAIL $failure"
    }
    exit 1
}

Write-Output "PASS mode=$Mode contractAssertions=$contractAssertions fixtures=$fixtureAssertions properties=$propertyAssertions states=$($stateIds.Count) checks=$(@($contract.checks).Count) matrixRows=$(@($matrix.rows).Count)"
exit 0
