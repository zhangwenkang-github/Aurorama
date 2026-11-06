[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$testsRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$contractRoot = [System.IO.Path]::GetFullPath((Join-Path $testsRoot ".."))
$repositoryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $contractRoot "..\..\..")
)
$fixturesRoot = Join-Path $testsRoot "fixtures"
$collectorPath = Join-Path $contractRoot "Invoke-G2RuntimeCollector.ps1"
$finalizerPath = Join-Path $contractRoot "Finalize-G2RuntimeReport.ps1"
$cleanupPath = Join-Path $contractRoot "Test-G2CleanupRestore.ps1"
$manifestPath = Join-Path $contractRoot "runtime-tools.manifest.json"
$contractPath = Join-Path $contractRoot "contract.json"
$authorizationPath = Join-Path $contractRoot "authorization-receipt.json"
$schemaPath = Join-Path $contractRoot "report.schema.json"

$script:passed = 0
$script:failed = 0
$script:negativeChecks = 0
$script:failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal {
    param(
        [AllowNull()]$Actual,
        [AllowNull()]$Expected,
        [Parameter(Mandatory)][string]$Message
    )
    if ($Actual -cne $Expected) {
        throw "$Message expected=[$Expected] actual=[$Actual]"
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory)][scriptblock]$Action,
        [Parameter(Mandatory)][string]$MessagePattern
    )
    $script:negativeChecks++
    $threw = $false
    try {
        & $Action
    } catch {
        $threw = $true
        Assert-True `
            -Condition ([string]$_.Exception.Message -notmatch
                "not recognized as a name of a cmdlet|Could not find a part of the path") `
            -Message "Expected behavior exception, not a missing dependency."
        Assert-True `
            -Condition ([string]$_.Exception.Message -match $MessagePattern) `
            -Message (
                "Exception mismatch expected-pattern=[{0}] actual=[{1}]" -f
                $MessagePattern,
                $_.Exception.Message
            )
    }
    Assert-True -Condition $threw -Message "Expected exception was not thrown."
}

function Invoke-Case {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    try {
        & $Action
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        $script:failures.Add("$Name :: $($_.Exception.Message)")
        Write-Output "FAIL $Name :: $($_.Exception.Message)"
    }
}

function Read-Json {
    param([Parameter(Mandatory)][string]$Path)
    return (
        Get-Content -LiteralPath $Path -Raw -Encoding utf8 |
            ConvertFrom-Json -Depth 100 -DateKind String
    )
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.
        ToLowerInvariant()
}

function New-TestTempRoot {
    $tempBase = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::GetTempPath()
    )
    $tempRoot = Join-Path `
        $tempBase `
        ("aurorama-g2-test-" + [Guid]::NewGuid().ToString("N"))
    [void][System.IO.Directory]::CreateDirectory($tempRoot)
    return [pscustomobject]@{
        Base = $tempBase
        Root = [System.IO.Path]::GetFullPath($tempRoot)
    }
}

function Remove-TestTempRoot {
    param([Parameter(Mandatory)]$Temp)
    $root = [System.IO.Path]::GetFullPath([string]$Temp.Root)
    $leaf = Split-Path -Leaf $root
    Assert-True `
        -Condition ($root.StartsWith(
            [string]$Temp.Base,
            [System.StringComparison]::OrdinalIgnoreCase
        )) `
        -Message "Unsafe temporary root."
    Assert-True `
        -Condition ($leaf.StartsWith(
            "aurorama-g2-test-",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Unexpected temporary root name."
    if ([System.IO.Directory]::Exists($root)) {
        [System.IO.Directory]::Delete($root, $true)
    }
    Assert-True `
        -Condition (-not [System.IO.Directory]::Exists($root)) `
        -Message "Temporary root was not removed."
}

function Write-TestJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    $text = ($Value | ConvertTo-Json -Depth 100).
        Replace("`r`n", "`n").
        Replace("`r", "`n")
    [System.IO.File]::WriteAllText(
        $Path,
        $text,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Copy-G2HistoricalFixture {
    param(
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$Source
    )
    [void][System.IO.Directory]::CreateDirectory($Destination)
    foreach ($fileName in @(
        "blocker.json",
        "cleanup-before.json",
        "cleanup-after.json",
        "cleanup.json",
        "device-lock.json"
    )) {
        [System.IO.File]::Copy(
            (Join-Path $Source $fileName),
            (Join-Path $Destination $fileName)
        )
    }
}

function Assert-Utf8LfNoBom {
    param([Parameter(Mandatory)][string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-True `
        -Condition (-not (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        )) `
        -Message "BOM present: $Path"
    Assert-True `
        -Condition ([Array]::IndexOf($bytes, [byte]13) -lt 0) `
        -Message "CR byte present: $Path"
    [void]$utf8Strict.GetString($bytes)
}

$toolPaths = @($collectorPath, $finalizerPath, $cleanupPath)
$toolsPresent = @($toolPaths | Where-Object { Test-Path -LiteralPath $_ }).Count -eq 3

Invoke-Case "STATIC-TOOLS-EXIST" {
    foreach ($toolPath in $toolPaths) {
        Assert-True `
            -Condition (Test-Path -LiteralPath $toolPath -PathType Leaf) `
            -Message "Missing tool: $toolPath"
    }
}

Invoke-Case "STATIC-UTF8-LF-NO-BOM" {
    foreach ($path in @($toolPaths + @(
        $manifestPath,
        (Join-Path $testsRoot "Test-G2RuntimeTools.ps1")
    ))) {
        Assert-True `
            -Condition (Test-Path -LiteralPath $path -PathType Leaf) `
            -Message "Missing file: $path"
        Assert-Utf8LfNoBom -Path $path
    }
}

if ($toolsPresent) {
    . $collectorPath
    . $finalizerPath
    . $cleanupPath
}

$contract = Read-Json $contractPath
$authorization = Read-Json $authorizationPath
$observations = Read-Json (Join-Path $fixturesRoot "observations-complete.json")
$cleanupFixture = Read-Json (Join-Path $fixturesRoot "cleanup-complete.json")

function New-FixturePassReport {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    $report.verdict = "PASS"
    $report.productVerdict = "PASS"
    $report.uncovered = @()
    $report.productFindings.P0 = @()
    $report.productFindings.P1 = @()
    $report.productFindings.P2 = @()
    $report.productFindings.P3 = @()
    return $report
}

function New-TestFinalizerBindingContext {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Observations
    )
    $root = Join-Path $Temp.Root "qa-pad-v2"
    [void][System.IO.Directory]::CreateDirectory($root)
    Write-TestJson `
        -Path (Join-Path $root "collector-registration.json") `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-collector-registration.v1"
            contractId = $Contract.contractId
            toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
            qaOwnerRole = "G2_PAD_QA_OWNER_2026_11_06"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $Contract.evidence.padRuntimeRoot
            attemptState = "REGISTERED_WINDOW_NOT_STARTED"
            lockState = "ACTIVE_COUNT_1"
            attemptNumber = 1
            windowSeconds = 600
            candidateMainSha256 = $Contract.candidate.main.sha256
            candidateFocusPeerSha256 = $Contract.candidate.focusPeer.sha256
            toolsDigest = $Contract.tools.digestSha256
            authoritativeLockState = "RELEASED_ACTIVE_0"
            recoveryBasis = [pscustomobject]@{
                outcome = "PRE_WINDOW_TOOL_BLOCK"
                historicalWindowConsumed = $false
                blockerEvidencePointer = (
                    ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/" +
                    "qa-pad-v1/blocker.json"
                )
                blockerSha256 = ("a" * 64)
                historicalDeviceLockSha256 = ("b" * 64)
                authoritativeLockState = "RELEASED_ACTIVE_0"
            }
        })
    Write-TestJson `
        -Path (Join-Path $root "collector-session.json") `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-collector-session.v1"
            contractId = $Contract.contractId
            qaOwnerRole = "G2_PAD_QA_OWNER_2026_11_06"
            evidenceRoot = $Contract.evidence.padRuntimeRoot
            attemptScope = $Contract.evidence.padRuntimeRoot
            attemptState = "WINDOW_STARTED"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptNumber = 1
            windowSeconds = 600
            extended = $false
            retry = $false
            continuous = $true
            hostLogcatProcessStopped = $true
            startedAt = $Observations.execution.startedAt
            completedAt = $Observations.execution.completedAt
            candidateMainSha256 = $Contract.candidate.main.sha256
            candidateFocusPeerSha256 = $Contract.candidate.focusPeer.sha256
            toolsDigest = $Contract.tools.digestSha256
            error = $null
        })
    Write-TestJson `
        -Path (Join-Path $root "device-lock.json") `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $Contract.evidence.padRuntimeRoot
            state = "RELEASED_ACTIVE_0"
            activeCount = 0
            attemptNumber = 1
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = ("a" * 64)
            releasedAt = "2026-11-06T12:20:00.000+08:00"
        })
    return [pscustomobject]@{
        Contract = $Contract
        Evidence = [pscustomobject]@{
            RelativePath = $Contract.evidence.padRuntimeRoot
            FullPath = $root
        }
        ToolsDigest = $Contract.tools.digestSha256
    }
}

function New-TestPadPredecessorFixture {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Observations
    )
    $padRoot = Join-Path $Temp.Root $Contract.evidence.padRuntimeRoot
    [void][System.IO.Directory]::CreateDirectory($padRoot)
    $report = New-FixturePassReport
    $reportPath = Join-Path $padRoot "report.json"
    Write-TestJson -Path $reportPath -Value $report
    $historicalBlocker =
        $Contract.attemptRecovery.historicalPreWindowBlock.evidenceFiles |
            Where-Object { [string]$_.path -ceq "blocker.json" } |
            Select-Object -First 1
    $historicalBlockerSha256 = [string]$historicalBlocker.sha256
    $lockPath = Join-Path $padRoot "device-lock.json"
    Write-TestJson `
        -Path $lockPath `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $Contract.evidence.padRuntimeRoot
            state = "RELEASED_ACTIVE_0"
            activeCount = 0
            attemptNumber = 1
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = $historicalBlockerSha256
            releasedAt = "2026-11-06T12:20:00.000+08:00"
        })
    $context = [pscustomobject]@{
        Contract = $Contract
        RepositoryRoot = $Temp.Root
    }
    $predecessor = Get-G2PadPredecessor `
        -Context $context `
        -PadRoot $padRoot
    return [pscustomobject]@{
        Temp = $Temp
        Context = $context
        PadRoot = $padRoot
        ReportPath = $reportPath
        LockPath = $lockPath
        Report = $report
        Predecessor = $predecessor
    }
}

function New-TestK60RegistrationFixture {
    param(
        [Parameter(Mandatory)]$Temp,
        [Parameter(Mandatory)]$Contract,
        [Parameter(Mandatory)]$Observations
    )
    $pad = New-TestPadPredecessorFixture `
        -Temp $Temp `
        -Contract $Contract `
        -Observations $Observations
    $k60Root = Join-Path $Temp.Root $Contract.evidence.k60RuntimeRoot
    [void][System.IO.Directory]::CreateDirectory($k60Root)
    $registration = [pscustomobject]@{
        schema = "aurorama.g2-collector-registration.v1"
        contractId = $Contract.contractId
        toolOwnerRole = "G2_RUNTIME_TOOL_OWNER_2026_11_06"
        qaOwnerRole = "G2_K60_QA_OWNER_2026_11_06"
        deviceRole = "K60"
        serial = "8e875894"
        attemptNumber = 1
        windowSeconds = 600
        attemptScope = $Contract.evidence.k60RuntimeRoot
        attemptState = "REGISTERED_WINDOW_NOT_STARTED"
        candidateMainSha256 = $Contract.candidate.main.sha256
        candidateFocusPeerSha256 = $Contract.candidate.focusPeer.sha256
        sourceCommit = $Contract.candidate.sourceCommit
        toolsDigest = $Contract.tools.digestSha256
        predecessor = $pad.Predecessor
        recoveryBasis = $null
        authoritativeLockState = "RELEASED_ACTIVE_0"
        lockState = "ACTIVE_COUNT_1"
    }
    $registrationPath = Join-Path $k60Root "collector-registration.json"
    Write-TestJson -Path $registrationPath -Value $registration
    $lockPath = Join-Path $k60Root "device-lock.json"
    Write-TestJson `
        -Path $lockPath `
        -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "K60"
            serial = "8e875894"
            attemptScope = $Contract.evidence.k60RuntimeRoot
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = $null
            releasedAt = $null
        })
    $context = [pscustomobject]@{
        Contract = $Contract
        RepositoryRoot = $Temp.Root
        Evidence = [pscustomobject]@{
            RelativePath = $Contract.evidence.k60RuntimeRoot
            FullPath = $k60Root
        }
        ToolsDigest = $Contract.tools.digestSha256
    }
    return [pscustomobject]@{
        Pad = $pad
        Context = $context
        Registration = $registration
        RegistrationPath = $registrationPath
        LockPath = $lockPath
    }
}

Invoke-Case "CONTRACT-RUNTIME-TOOLS-BOUND" {
    Assert-Equal `
        -Actual $contract.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Contract status"
    Assert-Equal `
        -Actual $contract.authorization.activePrompt `
        -Expected "none" `
        -Message "No downstream prompt while R3 repair is active"
    Assert-Equal `
        -Actual $contract.authorization.qaDispatchable `
        -Expected $false `
        -Message "QA dispatch before fresh audit"
    Assert-Equal `
        -Actual $contract.authorization.contractPackageReady `
        -Expected $false `
        -Message "R3 repair state is not ready"
    Assert-Equal `
        -Actual $contract.tools.runtimeCollector.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Collector status"
    Assert-Equal `
        -Actual $contract.tools.runtimeFinalizer.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Finalizer status"
    Assert-Equal `
        -Actual $contract.tools.cleanupVerifier.status `
        -Expected "R2_WIP_REPAIR_R3_IN_PROGRESS" `
        -Message "Cleanup status"
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        Assert-Equal `
            -Actual $contract.tools.$toolName.roleId `
            -Expected "G2_RUNTIME_TOOL_OWNER_2026_11_06" `
            -Message "$toolName owner"
        Assert-Equal `
            -Actual $contract.tools.$toolName.deviceAccess `
            -Expected "STATIC_SELF_TEST_ONLY_THIS_PHASE" `
            -Message "$toolName device access"
    }
}

Invoke-Case "CONTRACT-PREWINDOW-RECOVERY-SCOPE-BOUND" {
    $recovery = $contract.attemptRecovery
    Assert-Equal `
        -Actual $recovery.schema `
        -Expected "aurorama.g2-prewindow-recovery.v1" `
        -Message "Recovery schema"
    Assert-Equal `
        -Actual $contract.evidence.padRuntimeRoot `
        -Expected ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2" `
        -Message "Active Pad scope"
    Assert-Equal `
        -Actual $contract.evidence.k60RuntimeRoot `
        -Expected ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-k60-v2" `
        -Message "Active K60 scope"
    Assert-Equal `
        -Actual $recovery.historicalPreWindowBlock.root `
        -Expected ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1" `
        -Message "Historical Pad scope"
    Assert-Equal `
        -Actual $recovery.historicalPreWindowBlock.outcome `
        -Expected "PRE_WINDOW_TOOL_BLOCK" `
        -Message "Historical outcome"
    Assert-Equal `
        -Actual $contract.authorization.activePrompt `
        -Expected "none" `
        -Message "No downstream prompt during R3"
    Assert-Equal `
        -Actual $contract.authorization.qaDispatchable `
        -Expected $false `
        -Message "QA dispatch remains deferred"
    Assert-Equal `
        -Actual @($recovery.historicalPreWindowBlock.evidenceFiles).Count `
        -Expected 5 `
        -Message "Historical evidence count"
}

Invoke-Case "PREWINDOW-HISTORICAL-BLOCK-PROVES-NOT-STARTED" {
    $historicalRoot = Join-Path $contractRoot "qa-pad-v1"
    $proof = Assert-G2HistoricalPreWindowBlock `
        -Contract $contract `
        -HistoricalRoot $historicalRoot
    Assert-Equal `
        -Actual $proof.status `
        -Expected "BLOCKED_RUNTIME_TOOL_PATH_BINDING" `
        -Message "Historical blocker status"
    Assert-Equal `
        -Actual $proof.attemptWindow `
        -Expected "NOT_STARTED" `
        -Message "Historical window"
    Assert-Equal `
        -Actual $proof.freshInstall `
        -Expected "NOT_RUN" `
        -Message "Historical install"
    Assert-Equal `
        -Actual $proof.deviceLockState `
        -Expected "ACTIVE_COUNT_1" `
        -Message "Historical internal lock remains historical"
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-CONSUMED-BLOCKER" {
    $blocker = Read-Json (Join-Path $contractRoot "qa-pad-v1/blocker.json")
    $blocker.executed.attemptWindow = "STARTED"
    $blocker.rootCauseBoundary.windowConsumed = $true
    Assert-Throws `
        -MessagePattern "PREWINDOW|WINDOW|CONSUMED|STARTED" `
        -Action {
            Assert-G2HistoricalBlockerSemantics `
                -Contract $contract `
                -Blocker $blocker
        }
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-RETRY-ARTIFACT" {
    $temp = New-TestTempRoot
    try {
        Copy-G2HistoricalFixture `
            -Destination $temp.Root `
            -Source (Join-Path $contractRoot "qa-pad-v1")
        Write-TestJson `
            -Path (Join-Path $temp.Root "collector-registration.json") `
            -Value ([pscustomobject]@{
                schema = "aurorama.g2-collector-registration.v1"
                attemptNumber = 1
            })
        Assert-Throws `
            -MessagePattern "PREWINDOW|REGISTRATION|CONSUMED|RETRY" `
            -Action {
                Assert-G2HistoricalPreWindowBlock `
                    -Contract $contract `
                    -HistoricalRoot $temp.Root
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-MISSING-BLOCKER" {
    $temp = New-TestTempRoot
    try {
        Copy-G2HistoricalFixture `
            -Destination $temp.Root `
            -Source (Join-Path $contractRoot "qa-pad-v1")
        [System.IO.File]::Delete((Join-Path $temp.Root "blocker.json"))
        Assert-Throws `
            -MessagePattern "PREWINDOW|BLOCKER|MISSING" `
            -Action {
                Assert-G2HistoricalPreWindowBlock `
                    -Contract $contract `
                    -HistoricalRoot $temp.Root
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "PREWINDOW-HISTORICAL-REJECTS-EVIDENCE-DRIFT" {
    $temp = New-TestTempRoot
    try {
        Copy-G2HistoricalFixture `
            -Destination $temp.Root `
            -Source (Join-Path $contractRoot "qa-pad-v1")
        $path = Join-Path $temp.Root "cleanup-after.json"
        $bytes = [System.IO.File]::ReadAllBytes($path)
        $bytes[$bytes.Length - 2] = $bytes[$bytes.Length - 2] -bxor 1
        [System.IO.File]::WriteAllBytes($path, $bytes)
        Assert-Throws `
            -MessagePattern "PREWINDOW|SHA|HASH|DRIFT" `
            -Action {
                Assert-G2HistoricalPreWindowBlock `
                    -Contract $contract `
                    -HistoricalRoot $temp.Root
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-FRESH-SCOPE-ACCEPTS-PREWINDOW-HISTORY" {
    $temp = New-TestTempRoot
    try {
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "# Device Lock`n`n## 当前占用`n`n## 历史记录`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Invoke-G2DeviceRegistration `
            -Context $context `
            -DeviceRole Pad `
            -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
            -AuthoritativeLockPath $lockPath
        $registration = Read-Json (
            Join-Path $freshRoot "collector-registration.json"
        )
        $newLock = Read-Json (Join-Path $freshRoot "device-lock.json")
        $historicalLock = Read-Json (
            Join-Path $contractRoot "qa-pad-v1/device-lock.json"
        )
        Assert-Equal `
            -Actual $registration.attemptScope `
            -Expected $contract.evidence.padRuntimeRoot `
            -Message "Fresh registration scope"
        Assert-Equal `
            -Actual $registration.recoveryBasis.outcome `
            -Expected "PRE_WINDOW_TOOL_BLOCK" `
            -Message "Fresh registration lineage"
        Assert-Equal `
            -Actual $registration.recoveryBasis.authoritativeLockState `
            -Expected "RELEASED_ACTIVE_0" `
            -Message "Authoritative lock at registration"
        Assert-Equal `
            -Actual $newLock.state `
            -Expected "ACTIVE_COUNT_1" `
            -Message "Fresh instrumented lock"
        Assert-Equal `
            -Actual $historicalLock.state `
            -Expected "ACTIVE_COUNT_1" `
            -Message "Historical lock must remain unchanged"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-NEW-SCOPE-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory($freshRoot)
        Write-TestJson `
            -Path (Join-Path $freshRoot "collector-registration.json") `
            -Value ([pscustomobject]@{ attemptNumber = 1 })
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "## 当前占用`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|REGISTERED|RETRY" `
            -Action {
                Invoke-G2DeviceRegistration `
                    -Context $context `
                    -DeviceRole Pad `
                    -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
                    -AuthoritativeLockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-NEW-SCOPE-LOCK" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory($freshRoot)
        Write-TestJson `
            -Path (Join-Path $freshRoot "device-lock.json") `
            -Value ([pscustomobject]@{
                schema = "aurorama.g2-device-lock.v1"
                state = "RELEASED_ACTIVE_0"
                activeCount = 0
                attemptNumber = 1
            })
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "## 当前占用`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|REGISTERED|RETRY" `
            -Action {
                Invoke-G2DeviceRegistration `
                    -Context $context `
                    -DeviceRole Pad `
                    -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
                    -AuthoritativeLockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-HIDDEN-SCOPE-CONTENT" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory($freshRoot)
        [System.IO.File]::WriteAllText(
            (Join-Path $freshRoot ".unknown"),
            "unexpected",
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|EMPTY|CONTENT|REGISTERED" `
            -Action {
                Assert-G2NewAttemptScopeFresh `
                    -EvidenceFullPath $freshRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-UNKNOWN-SCOPE-DIRECTORY" {
    $temp = New-TestTempRoot
    try {
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        [void][System.IO.Directory]::CreateDirectory(
            (Join-Path $freshRoot "unknown-directory")
        )
        Assert-Throws `
            -MessagePattern "ATTEMPT|SCOPE|EMPTY|CONTENT|REGISTERED" `
            -Action {
                Assert-G2NewAttemptScopeFresh `
                    -EvidenceFullPath $freshRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "REGISTER-REJECTS-AUTHORITATIVE-ACTIVE-LOCK" {
    $temp = New-TestTempRoot
    try {
        $lockPath = Join-Path $temp.Root "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $lockPath,
            "## 当前占用`n`n### Pad 43af8627 — ACTIVE`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Assert-Throws `
            -MessagePattern "AUTHORITATIVE|ACTIVE|LOCK" `
            -Action {
                Invoke-G2DeviceRegistration `
                    -Context $context `
                    -DeviceRole Pad `
                    -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
                    -AuthoritativeLockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "TOOL-MANIFEST-HASHES" {
    Assert-True `
        -Condition (Test-Path -LiteralPath $manifestPath -PathType Leaf) `
        -Message "Missing runtime tools manifest."
    $manifest = Read-Json $manifestPath
    Assert-Equal `
        -Actual $manifest.schema `
        -Expected "aurorama.g2-runtime-tools.v1" `
        -Message "Manifest schema"
    Assert-Equal `
        -Actual $manifest.ownerRole `
        -Expected "G2_RUNTIME_TOOL_OWNER_2026_11_06" `
        -Message "Manifest owner"
    Assert-Equal `
        -Actual $manifest.qaV9ReuseCount `
        -Expected 0 `
        -Message "Historical reuse count"
    foreach ($entry in @($manifest.files)) {
        $entryPath = [System.IO.Path]::GetFullPath(
            (Join-Path $contractRoot $entry.path)
        )
        Assert-True `
            -Condition $entryPath.StartsWith(
                ($contractRoot + [System.IO.Path]::DirectorySeparatorChar),
                [System.StringComparison]::OrdinalIgnoreCase
            ) `
            -Message "Manifest path escaped contract root: $($entry.path)"
        Assert-True `
            -Condition (Test-Path -LiteralPath $entryPath -PathType Leaf) `
            -Message "Manifest file missing: $($entry.path)"
        Assert-Equal `
            -Actual (Get-FileSha256 $entryPath) `
            -Expected $entry.sha256 `
            -Message "Manifest hash $($entry.path)"
    }
    foreach ($toolName in @(
        "runtimeCollector",
        "runtimeFinalizer",
        "cleanupVerifier"
    )) {
        $tool = $contract.tools.$toolName
        $toolPath = [System.IO.Path]::GetFullPath(
            (Join-Path $repositoryRoot $tool.path)
        )
        Assert-Equal `
            -Actual (Get-FileSha256 $toolPath) `
            -Expected $tool.sha256 `
            -Message "Contract hash $toolName"
    }
    $digestFiles = @($toolPaths | Sort-Object {
        [System.IO.Path]::GetFileName($_)
    })
    $digestText = [System.Text.StringBuilder]::new()
    foreach ($toolPath in $digestFiles) {
        [void]$digestText.Append(
            (Get-FileSha256 $toolPath) +
                "  " +
                [System.IO.Path]::GetFileName($toolPath) +
                "`n"
        )
    }
    $digest = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            [System.Text.Encoding]::UTF8.GetBytes($digestText.ToString())
        )
    ).ToLowerInvariant()
    Assert-Equal `
        -Actual $contract.tools.digestSha256 `
        -Expected $digest `
        -Message "Tools digest"
}

Invoke-Case "NO-HISTORICAL-TOOL-REUSE" {
    foreach ($toolPath in $toolPaths) {
        $text = Get-Content -LiteralPath $toolPath -Raw -Encoding utf8
        Assert-True `
            -Condition (-not $text.Contains(
                "qa-v9",
                [System.StringComparison]::OrdinalIgnoreCase
            )) `
            -Message "Historical path reference in $toolPath"
    }
}

Invoke-Case "NO-CREDENTIAL-READS" {
    foreach ($toolPath in $toolPaths) {
        $text = Get-Content -LiteralPath $toolPath -Raw -Encoding utf8
        foreach ($forbidden in @(
            "资源.txt",
            "Authorization:",
            "password=",
            "accessToken",
            "clientSecret"
        )) {
            Assert-True `
                -Condition (-not $text.Contains(
                    $forbidden,
                    [System.StringComparison]::OrdinalIgnoreCase
                )) `
                -Message "Credential-shaped token [$forbidden] in $toolPath"
        }
    }
}

Invoke-Case "COLLECTOR-STATIC-SELF-TEST" {
    $output = @(
        & pwsh -NoProfile -File $collectorPath `
            -Action StaticSelfTest `
            -DeviceRole Pad 2>&1
    )
    Assert-Equal -Actual $LASTEXITCODE -Expected 0 -Message "Collector exit"
    Assert-True `
        -Condition (($output -join "`n").Contains("DEVICE_COMMANDS=0")) `
        -Message "Collector static mode reported device commands."
}

Invoke-Case "FINALIZER-STATIC-SELF-TEST" {
    $output = @(
        & pwsh -NoProfile -File $finalizerPath `
            -StaticSelfTest 2>&1
    )
    Assert-Equal -Actual $LASTEXITCODE -Expected 0 -Message "Finalizer exit"
    Assert-True `
        -Condition (($output -join "`n").Contains("DEVICE_COMMANDS=0")) `
        -Message "Finalizer static mode reported device commands."
}

Invoke-Case "CLEANUP-STATIC-SELF-TEST" {
    $output = @(
        & pwsh -NoProfile -File $cleanupPath `
            -StaticSelfTest `
            -DeviceRole Pad 2>&1
    )
    Assert-Equal -Actual $LASTEXITCODE -Expected 0 -Message "Cleanup exit"
    Assert-True `
        -Condition (($output -join "`n").Contains("DEVICE_COMMANDS=0")) `
        -Message "Cleanup static mode reported device commands."
}

Invoke-Case "PATH-ACCEPTS-EVIDENCE-RELATIVE-ROOT" {
    $expected = [System.IO.Path]::GetFullPath(
        (Join-Path $contractRoot "qa-pad-v1")
    )
    $resolved = Resolve-G2BoundedPath `
        -Root $contractRoot `
        -RelativePath "qa-pad-v1"
    Assert-Equal `
        -Actual $resolved `
        -Expected $expected `
        -Message "Evidence-relative path"
    Assert-True `
        -Condition (Test-Path -LiteralPath $resolved -PathType Container) `
        -Message "Pad evidence directory was not resolved."
}

Invoke-Case "PATH-REPO-RELATIVE-TOOL-VALIDATE-PASSES" {
    $output = @(
        & pwsh -NoProfile -File $collectorPath `
            -Action Validate `
            -DeviceRole Pad 2>&1
    )
    $exitCode = $LASTEXITCODE
    Assert-Equal `
        -Actual $exitCode `
        -Expected 0 `
        -Message ("Collector Validate failed: " + ($output -join " | "))
    Assert-True `
        -Condition (($output -join "`n").Contains(
            "PASS G2_RUNTIME_COLLECTOR_CONTRACT",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Collector Validate did not report contract PASS."
}

Invoke-Case "PATH-REJECTS-TRAVERSAL" {
    Assert-Throws `
        -MessagePattern "TRAVERSAL|OUTSIDE" `
        -Action {
            Resolve-G2BoundedPath `
                -Root $contractRoot `
                -RelativePath "../outside"
        }
}

Invoke-Case "PATH-REJECTS-ABSOLUTE" {
    Assert-Throws `
        -MessagePattern "ABSOLUTE|OUTSIDE" `
        -Action {
            Resolve-G2BoundedPath `
                -Root $contractRoot `
                -RelativePath "F:\outside\evidence"
        }
}

Invoke-Case "PATH-REJECTS-REPARSE-ESCAPE" {
    $temp = New-TestTempRoot
    $guardRoot = Join-Path $temp.Root "guard"
    $guardTarget = Join-Path $temp.Root "target"
    $guardLink = Join-Path $guardRoot "escape"
    try {
        [void][System.IO.Directory]::CreateDirectory($guardRoot)
        [void][System.IO.Directory]::CreateDirectory($guardTarget)
        [void](New-Item -ItemType Junction -Path $guardLink -Target $guardTarget)
        Assert-Throws `
            -MessagePattern "REPARSE|OUTSIDE" `
            -Action {
                Resolve-G2BoundedPath `
                    -Root $guardRoot `
                    -RelativePath "escape/file.txt"
            }
    } finally {
        if ([System.IO.Directory]::Exists($guardLink)) {
            [System.IO.Directory]::Delete($guardLink)
        }
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "V2-EVIDENCE-ROOT-JUNCTIONS-FAIL-CLOSED" {
    $temp = New-TestTempRoot
    $relativeRoot = ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v2"
    $parent = Join-Path $temp.Root (
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT"
    )
    $junction = Join-Path $temp.Root $relativeRoot
    $target = Join-Path $temp.Root "junction-target"
    try {
        [void][System.IO.Directory]::CreateDirectory($parent)
        [void][System.IO.Directory]::CreateDirectory($target)
        [void](New-Item -ItemType Junction -Path $junction -Target $target)
        Assert-Throws `
            -MessagePattern "REPARSE|JUNCTION" `
            -Action {
                Resolve-G2EvidenceRoot `
                    -ContractRoot $parent `
                    -DeviceRole Pad `
                    -RepositoryBase $temp.Root
            }
        Assert-Throws `
            -MessagePattern "REPARSE|JUNCTION" `
            -Action {
                Resolve-G2ReportEvidenceRoot `
                    -DeviceRole Pad `
                    -RepositoryBase $temp.Root
            }
        Assert-Throws `
            -MessagePattern "REPARSE|JUNCTION" `
            -Action {
                Resolve-G2CleanupEvidenceRoot `
                    -DeviceRole Pad `
                    -RepositoryBase $temp.Root
            }
    } finally {
        if ([System.IO.Directory]::Exists($junction)) {
            [System.IO.Directory]::Delete($junction)
        }
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "PINNED-CONTRACT-IDENTITY-REJECTS-DRIFT" {
    foreach ($mutation in @(
        "sourceCommit",
        "mainSha256",
        "focusPeerSha256",
        "schemaSha256"
    )) {
        $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
        switch ($mutation) {
            "sourceCommit" {
                $drifted.candidate.sourceCommit = ("0" * 40)
            }
            "mainSha256" {
                $drifted.candidate.main.sha256 = ("0" * 64)
            }
            "focusPeerSha256" {
                $drifted.candidate.focusPeer.sha256 = ("0" * 64)
            }
            "schemaSha256" {
                $drifted.evidence.reportSchemaSha256 = ("0" * 64)
            }
        }
        Assert-Throws `
            -MessagePattern "PINNED|IDENTITY|SHA|COMMIT|SCHEMA" `
            -Action {
                Assert-G2PinnedIdentity -Contract $drifted
            }
    }
}

Invoke-Case "AUTH-K60-REJECTS-MISSING-CONDITIONAL-SCOPE" {
    $drifted = $authorization | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.scope.k60.authorized = $false
    $drifted.scope.k60.conditional = $false
    Assert-Throws `
        -MessagePattern "AUTHORIZATION|K60|CONDITIONAL" `
        -Action {
            Assert-G2AuthorizationReceipt `
                -Contract $contract `
                -Authorization $drifted `
                -DeviceRole K60
        }
}

Invoke-Case "AUTH-K60-ACCEPTS-CURRENT-RECEIPT" {
    Assert-G2AuthorizationReceipt `
        -Contract $contract `
        -Authorization $authorization `
        -DeviceRole K60
}

Invoke-Case "FINALIZER-REJECTS-PINNED-IDENTITY-DRIFT" {
    $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.candidate.main.sha256 = ("0" * 64)
    Assert-Throws `
        -MessagePattern "PINNED|IDENTITY|SHA" `
        -Action {
            Assert-G2ReportPinnedIdentity -Contract $drifted
        }
}

Invoke-Case "FINALIZER-REJECTS-AUTH-SCOPE-DRIFT" {
    $drifted = $authorization | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.scope.k60.conditional = $false
    Assert-Throws `
        -MessagePattern "AUTHORIZATION|CONDITIONAL" `
        -Action {
            Assert-G2ReportAuthorization `
                -Contract $contract `
                -Authorization $drifted
        }
}

Invoke-Case "CLEANUP-REJECTS-PINNED-IDENTITY-DRIFT" {
    $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.evidence.reportSchemaSha256 = ("0" * 64)
    Assert-Throws `
        -MessagePattern "PINNED|IDENTITY|SCHEMA|SHA" `
        -Action {
            Assert-G2CleanupPinnedIdentity -Contract $drifted
        }
}

Invoke-Case "CLEANUP-CONTEXT-ACCEPTS-CURRENT-SCHEMA-PIN" {
    $context = Get-G2CleanupContext -DeviceRole Pad
    Assert-Equal `
        -Actual $context.Evidence.RelativePath `
        -Expected $contract.evidence.padRuntimeRoot `
        -Message "Cleanup context scope"
    Assert-Equal `
        -Actual $context.Contract.evidence.reportSchemaSha256 `
        -Expected (Get-FileSha256 $schemaPath) `
        -Message "Cleanup context schema pin"
}

Invoke-Case "CLEANUP-REJECTS-SCHEMA-FILE-HASH-DRIFT" {
    $temp = New-TestTempRoot
    try {
        $driftedSchema = Join-Path $temp.Root "report.schema.json"
        [System.IO.File]::Copy($schemaPath, $driftedSchema)
        $bytes = [System.IO.File]::ReadAllBytes($driftedSchema)
        $bytes[$bytes.Length - 1] = $bytes[$bytes.Length - 1] -bxor 1
        [System.IO.File]::WriteAllBytes($driftedSchema, $bytes)
        Assert-Throws `
            -MessagePattern "CLEANUP.*SCHEMA.*(SHA|HASH|PINNED)" `
            -Action {
                Assert-G2CleanupSchemaPin `
                    -Contract $contract `
                    -SchemaPath $driftedSchema
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CLEANUP-REJECTS-OLD-SCHEMA-PIN" {
    $drifted = $contract | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.evidence.reportSchemaSha256 =
        "8cb6d8766e773107e9493b828239ee417c61cf703a3c7756f94b684729921046"
    Assert-Throws `
        -MessagePattern "PINNED|IDENTITY|SCHEMA|SHA" `
        -Action {
            Assert-G2CleanupPinnedIdentity -Contract $drifted
        }
}

Invoke-Case "CLEANUP-REJECTS-AUTH-SCOPE-DRIFT" {
    $drifted = $authorization | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $drifted.scope.k60.authorized = $false
    Assert-Throws `
        -MessagePattern "AUTHORIZATION|K60|SCOPE" `
        -Action {
            Assert-G2CleanupAuthorization `
                -Contract $contract `
                -Authorization $drifted
        }
}

Invoke-Case "DEVICE-REJECTS-UNKNOWN-SERIAL" {
    Assert-Throws `
        -MessagePattern "SERIAL|DEVICE" `
        -Action {
            Assert-G2DevicePolicy `
                -Contract $contract `
                -DeviceRole Pad `
                -Serial "deadbeef" `
                -Predecessor $null
        }
}

Invoke-Case "K60-REQUIRES-PAD-PREDECESSOR" {
    Assert-Throws `
        -MessagePattern "K60|PREDECESSOR|PAD" `
        -Action {
            Assert-G2DevicePolicy `
                -Contract $contract `
                -DeviceRole K60 `
                -Serial "8e875894" `
                -Predecessor $null
        }
}

Invoke-Case "K60-ACCEPTS-VALID-PREDECESSOR" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        Assert-G2DevicePolicy `
            -Contract $contract `
            -Context $fixture.Context `
            -DeviceRole K60 `
            -Serial "8e875894" `
            -Predecessor $fixture.Predecessor
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-FORGED-A-B-SHA-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestK60RegistrationFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $forged = $fixture.Registration | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $forged.predecessor.reportSha256 = ("a" * 64)
        $forged.predecessor.lockSha256 = ("b" * 64)
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|SHA|REPORT|LOCK|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Registration $forged
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-REPORT-SHA-DRIFT-AFTER-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestK60RegistrationFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        [System.IO.File]::AppendAllText(
            $fixture.Pad.ReportPath,
            "`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|SHA|REPORT|LOCK|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Registration $fixture.Registration
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-LOCK-SHA-DRIFT-AFTER-REGISTRATION" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestK60RegistrationFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        [System.IO.File]::AppendAllText(
            $fixture.Pad.LockPath,
            "`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|SHA|REPORT|LOCK|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $fixture.Context `
                    -DeviceRole K60 `
                    -Registration $fixture.Registration
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-DUPLICATE-PRODUCT-GATE" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $invalidReport = $fixture.Report | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $invalidReport.productGates[1].gateId =
            $invalidReport.productGates[0].gateId
        Write-TestJson -Path $fixture.ReportPath -Value $invalidReport
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|GATE|PRODUCT|MISMATCH" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-NON-PASS-CLEANUP-REPORT" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $invalidReport = $fixture.Report | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $invalidReport.cleanupAndRestore.status = "FAIL"
        Write-TestJson -Path $fixture.ReportPath -Value $invalidReport
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|CLEANUP|PASS|MISMATCH" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-ACTIVE-PAD-LOCK" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $activeLock = Read-Json $fixture.LockPath
        $activeLock.state = "ACTIVE_COUNT_1"
        $activeLock.activeCount = 1
        $activeLock.releasedAt = $null
        Write-TestJson -Path $fixture.LockPath -Value $activeLock
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|LOCK|RELEASED|ACTIVE" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-OLD-REPORT-AND-LOCK-SCOPE" {
    $temp = New-TestTempRoot
    try {
        $fixture = New-TestPadPredecessorFixture `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $oldReport = $fixture.Report | ConvertTo-Json -Depth 100 |
            ConvertFrom-Json
        $oldReport.evidenceRoot =
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
        Write-TestJson -Path $fixture.ReportPath -Value $oldReport
        $oldLock = Read-Json $fixture.LockPath
        $oldLock.attemptScope =
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
        Write-TestJson -Path $fixture.LockPath -Value $oldLock
        Assert-Throws `
            -MessagePattern "K60|PREDECESSOR|PAD|ROOT|SCOPE|PASS|LOCK" `
            -Action {
                Get-G2PadPredecessor `
                    -Context $fixture.Context `
                    -PadRoot $fixture.PadRoot
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "K60-REJECTS-HISTORICAL-PAD-PREDECESSOR" {
    $predecessor = [pscustomobject]@{
        deviceOrder = "Pad"
        coreVerdict = "PASS"
        lockStateAfterClosure = "RELEASED_ACTIVE_0"
        productVerdict = "PASS"
        evidenceRoot = (
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
        )
        candidateMainSha256 = $contract.candidate.main.sha256
        candidateFocusPeerSha256 = $contract.candidate.focusPeer.sha256
        evidencePointer = (
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1/report.json"
        )
        reportSha256 = ("a" * 64)
        lockSha256 = ("b" * 64)
    }
    Assert-Throws `
        -MessagePattern "K60|PREDECESSOR|PAD|EVIDENCE|SCOPE" `
        -Action {
            Assert-G2DevicePolicy `
                -Contract $contract `
                -DeviceRole K60 `
                -Serial "8e875894" `
                -Predecessor $predecessor
        }
}

Invoke-Case "FINALIZER-REJECTS-OLD-PAD-EVIDENCE-ROOT" {
    $report = New-FixturePassReport
    $report.evidenceRoot = (
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1"
    )
    Assert-Throws `
        -MessagePattern "EVIDENCE|SCOPE|PAD|ROOT" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-REJECTS-ACTIVE-NEW-SCOPE-LOCK" {
    $temp = New-TestTempRoot
    try {
        $lockPath = Join-Path $temp.Root "device-lock.json"
        Write-TestJson `
            -Path $lockPath `
            -Value ([pscustomobject]@{
                schema = "aurorama.g2-device-lock.v1"
                deviceRole = "Pad"
                serial = "43af8627"
                attemptScope = $contract.evidence.padRuntimeRoot
                state = "ACTIVE_COUNT_1"
                activeCount = 1
                attemptNumber = 1
                releasedAt = $null
            })
        Assert-Throws `
            -MessagePattern "FINALIZER|LOCK|RELEASED|ACTIVE" `
            -Action {
                Assert-G2FinalizerReleasedLock `
                    -Contract $contract `
                    -DeviceRole Pad `
                    -EvidenceRoot $contract.evidence.padRuntimeRoot `
                    -LockPath $lockPath
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "FINALIZER-ATTEMPT-BINDING-ACCEPTS-FRESH-SCOPE" {
    $temp = New-TestTempRoot
    try {
        $context = New-TestFinalizerBindingContext `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $binding = Assert-G2FinalizerAttemptBinding `
            -Context $context `
            -Observations $observations
        Assert-Equal `
            -Actual $binding.evidenceRoot `
            -Expected $contract.evidence.padRuntimeRoot `
            -Message "Finalizer binding scope"
        Assert-Equal `
            -Actual $binding.attemptNumber `
            -Expected 1 `
            -Message "Finalizer binding attempt"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "FINALIZER-ATTEMPT-BINDING-REJECTS-SESSION-ATTEMPT-TWO" {
    $temp = New-TestTempRoot
    try {
        $context = New-TestFinalizerBindingContext `
            -Temp $temp `
            -Contract $contract `
            -Observations $observations
        $sessionPath = Join-Path `
            $context.Evidence.FullPath `
            "collector-session.json"
        $session = Read-Json $sessionPath
        $session.attemptNumber = 2
        Write-TestJson -Path $sessionPath -Value $session
        Assert-Throws `
            -MessagePattern "FINALIZER|SESSION|ATTEMPT|BINDING" `
            -Action {
                Assert-G2FinalizerAttemptBinding `
                    -Context $context `
                    -Observations $observations
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "WINDOW-REJECTS-RELEASED-LOCK-BEFORE-COMPLETION" {
    $temp = New-TestTempRoot
    try {
        $authoritativeLockPath = Join-Path `
            $temp.Root `
            "authoritative-device-lock.md"
        [System.IO.File]::WriteAllText(
            $authoritativeLockPath,
            "## 当前占用`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        $freshRoot = Join-Path $temp.Root "fresh-scope"
        $context = [pscustomobject]@{
            Contract = $contract
            Evidence = [pscustomobject]@{
                RelativePath = $contract.evidence.padRuntimeRoot
                FullPath = $freshRoot
            }
            ToolsDigest = $contract.tools.digestSha256
        }
        Invoke-G2DeviceRegistration `
            -Context $context `
            -DeviceRole Pad `
            -HistoricalRoot (Join-Path $contractRoot "qa-pad-v1") `
            -AuthoritativeLockPath $authoritativeLockPath
        $registration = Read-Json (
            Join-Path $freshRoot "collector-registration.json"
        )
        $lockPath = Join-Path $freshRoot "device-lock.json"
        $released = Read-Json $lockPath
        $released.state = "RELEASED_ACTIVE_0"
        $released.activeCount = 0
        $released.releasedAt = "2026-11-06T12:20:00.000+08:00"
        Write-TestJson -Path $lockPath -Value $released
        Assert-Throws `
            -MessagePattern "ACTIVE|LOCK|WINDOW|MISMATCH" `
            -Action {
                Assert-G2CollectorRegistrationState `
                    -Context $context `
                    -DeviceRole Pad `
                    -Registration $registration
            }
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "WINDOW-REJECTS-SHORT-DURATION" {
    Assert-Throws `
        -MessagePattern "WINDOW|600" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 1 `
                -WindowSeconds 599 `
                -Extended $false `
                -Retry $false
        }
}

Invoke-Case "WINDOW-REJECTS-ATTEMPT-TWO" {
    Assert-Throws `
        -MessagePattern "ATTEMPT|RETRY" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 2 `
                -WindowSeconds 600 `
                -Extended $false `
                -Retry $false
        }
}

Invoke-Case "WINDOW-REJECTS-EXTENSION" {
    Assert-Throws `
        -MessagePattern "EXTENS|RETRY" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 1 `
                -WindowSeconds 600 `
                -Extended $true `
                -Retry $false
        }
}

Invoke-Case "WINDOW-REJECTS-RETRY-TRUE" {
    Assert-Throws `
        -MessagePattern "RETRY" `
        -Action {
            Assert-G2WindowPolicy `
                -Attempt 1 `
                -WindowSeconds 600 `
                -Extended $false `
                -Retry $true
        }
}

Invoke-Case "NETSTATS-SUMS-ALL-UID-PARTITIONS" {
    $before = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "netstats-before.txt") `
        -Raw `
        -Encoding utf8
    $after = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "netstats-after.txt") `
        -Raw `
        -Encoding utf8
    $usageBefore = Get-G2NetStatsUidUsage -Text $before -Uid 10315
    $usageAfter = Get-G2NetStatsUidUsage -Text $after -Uid 10315
    Assert-True `
        -Condition ($usageBefore.PartitionCount -ge 3) `
        -Message "Not all UID partitions parsed."
    Assert-Equal `
        -Actual $usageBefore.RxBytes `
        -Expected 7000 `
        -Message "Before RX"
    Assert-Equal `
        -Actual $usageAfter.RxBytes `
        -Expected 7000 `
        -Message "After RX"
    $delta = New-G2NetStatsDelta -Before $usageBefore -After $usageAfter
    Assert-Equal -Actual $delta.WindowBytesDelta -Expected 0 -Message "Delta"
    Assert-Equal `
        -Actual $delta.AllPartitionsAccounted `
        -Expected $true `
        -Message "Partition accounting"
}

Invoke-Case "AUDIOTRACK-LOG-MAPS-GENERATION-TO-VOICE" {
    $lines = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "runtime-log-happy.log") `
        -Encoding utf8
    $events = @(Get-G2AudioTrackEvents -Lines $lines)
    Assert-Equal -Actual $events.Count -Expected 6 -Message "Event count"
    Assert-Equal `
        -Actual @($events | Where-Object voiceId -eq "zf_001").Count `
        -Expected 4 `
        -Message "zf events"
    Assert-Equal `
        -Actual @($events | Where-Object voiceId -eq "zm_010").Count `
        -Expected 2 `
        -Message "zm events"
    Assert-Equal `
        -Actual @($events | Where-Object kind -eq "START").Count `
        -Expected 3 `
        -Message "Start events"
    Assert-Equal `
        -Actual @($events | Where-Object kind -eq "COMPLETION").Count `
        -Expected 3 `
        -Message "Completion events"
}

Invoke-Case "AUDIO-GAP-CALCULATES-COMPLETION-TO-NEXT-START" {
    $lines = Get-Content `
        -LiteralPath (Join-Path $fixturesRoot "runtime-log-happy.log") `
        -Encoding utf8
    $events = @(Get-G2AudioTrackEvents -Lines $lines)
    $gap = Get-G2MaximumAudioGapSeconds -Events $events
    Assert-Equal -Actual $gap -Expected 2 -Message "Maximum gap"
}

Invoke-Case "AUDIO-GAP-REJECTS-OVER-FIFTEEN" {
    $events = @($observations.audioTrackEvents | ForEach-Object {
        $_.PSObject.Copy()
    })
    $events[3].timestamp = "2026-11-06T12:00:40.000+08:00"
    $gap = Get-G2MaximumAudioGapSeconds -Events $events
    Assert-True -Condition ($gap -gt 15) -Message "Fixture did not exceed 15s."
}

Invoke-Case "AUDIO-EVENTS-REJECT-START-WITHOUT-SENTENCE-MAP" {
    $lines = @(
        "2026-11-06 12:00:00.000  2000  2100 I KOKORO_RUNTIME: AUDIO_TRACK_STARTED owner=TTS generation=99 samples=48000 durationMillis=1000 longRun=true longRunAudioMillis=0"
    )
    Assert-Throws `
        -MessagePattern "SENTENCE|VOICE|generation" `
        -Action { Get-G2AudioTrackEvents -Lines $lines }
}

Invoke-Case "FINALIZER-MISSING-CORE-FIELD-FAILS-CLOSED" {
    $broken = $observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $broken.PSObject.Properties.Remove("audioTrackEvents")
    Assert-Throws `
        -MessagePattern "audioTrackEvents|MISSING|NOT_RUN" `
        -Action {
            New-G2RuntimeReportFromObservations `
                -Observations $broken `
                -Contract $contract `
                -Authorization $authorization `
                -ContractRoot $contractRoot `
                -FixtureOnly
        }
}

Invoke-Case "FINALIZER-NOT-RUN-CORE-IS-BLOCKED" {
    $notRun = $observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $notRun.audioFocus.status = "NOT_RUN"
    $report = New-G2RuntimeReportFromObservations `
        -Observations $notRun `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    Assert-Equal -Actual $report.verdict -Expected "BLOCKED" -Message "Verdict"
    Assert-True `
        -Condition ($report.productVerdict -cne "PASS") `
        -Message "NOT_RUN was allowed to pass."
}

Invoke-Case "FINALIZER-FIXTURE-CANNOT-PRODUCE-PASS" {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    Assert-Equal -Actual $report.verdict -Expected "BLOCKED" -Message "Verdict"
    $reportJson = $report | ConvertTo-Json -Depth 100
    Assert-True `
        -Condition ($reportJson | Test-Json -SchemaFile $schemaPath) `
        -Message "Generated fixture report did not match schema."
}

Invoke-Case "FINALIZER-FAILS-ON-PASS-GATE-WITH-NOT-RUN" {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    $report.verdict = "PASS"
    $report.productVerdict = "PASS"
    $report.productGates[0].status = "NOT_RUN"
    Assert-Throws `
        -MessagePattern "NOT_RUN|CORE|PASS" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-FAILS-ON-CLEANUP-MISMATCH" {
    $report = New-G2RuntimeReportFromObservations `
        -Observations $observations `
        -Contract $contract `
        -Authorization $authorization `
        -ContractRoot $contractRoot `
        -FixtureOnly
    $report.cleanupAndRestore.status = "FAIL"
    Assert-Throws `
        -MessagePattern "CLEANUP" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-NOT-ASSESSED-FINDINGS" {
    $report = New-FixturePassReport
    $report.productFindings.P0 = "NOT_ASSESSED"
    Assert-Throws `
        -MessagePattern "FINDINGS|NOT_ASSESSED|P0" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-SHORT-ELAPSED-WINDOW" {
    $report = New-FixturePassReport
    $report.execution.completedAt = "2026-11-06T12:09:59.000+08:00"
    Assert-Throws `
        -MessagePattern "WINDOW|ELAPSED|600" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-UNMATCHED-AUDIO-COMPLETION" {
    $report = New-FixturePassReport
    $report.audioTrackEvents[-1].trackSessionId = "TTS:unmatched"
    Assert-Throws `
        -MessagePattern "AUDIO|TRACK|COMPLETION|PAIR" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-PASS-REJECTS-K60-WITHOUT-PREDECESSOR" {
    $report = New-FixturePassReport
    $report.ownerRole = "G2_K60_QA_OWNER_2026_11_06"
    $report.evidenceRoot = $contract.evidence.k60RuntimeRoot
    $report.device.order = "K60"
    $report.device.role = "G2_K60_QA_OWNER_2026_11_06"
    $report.device.serial = "8e875894"
    $report.predecessor = $null
    Assert-Throws `
        -MessagePattern "K60|PREDECESSOR" `
        -Action {
            Test-G2ReportSemantics `
                -Report $report `
                -Contract $contract
        }
}

Invoke-Case "FINALIZER-MERGES-CLEANUP-VERIFIER-RESULT" {
    $observed = $observations | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $observed.cleanupAndRestore.status = "BLOCKED"
    $observed.cleanupAndRestore.packagesAbsent = $false
    $merged = Merge-G2CleanupIntoObservations `
        -Observations $observed `
        -Cleanup $cleanupFixture
    Assert-Equal `
        -Actual $merged.cleanupAndRestore.status `
        -Expected "PASS" `
        -Message "Merged cleanup status"
    Assert-Equal `
        -Actual $merged.cleanupAndRestore.deviceLockState `
        -Expected "RELEASED_ACTIVE_0" `
        -Message "Merged lock state"
}

Invoke-Case "CLEANUP-COMPLETE-FIXTURE-PASSES-STRUCTURE" {
    $result = New-G2CleanupResult -InputObject $cleanupFixture
    Assert-Equal -Actual $result.status -Expected "PASS" -Message "Status"
    Assert-Equal `
        -Actual @($result.failures).Count `
        -Expected 0 `
        -Message "Failures"
}

Invoke-Case "CLEANUP-REJECTS-EACH-RESIDUAL-CLASS" {
    $required = @(
        "packagesAbsent",
        "pidsAbsent",
        "serviceRecordsAbsent",
        "activeNotificationRecordsAbsent",
        "activeMediaSessionsAbsent",
        "audioFocusReleased",
        "externalDataAbsent",
        "internalDataAbsent",
        "networkRestored",
        "userSettingsRestored",
        "productionPackageMatch"
    )
    foreach ($propertyName in $required) {
        $broken = $cleanupFixture | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $broken.$propertyName = $false
        $result = New-G2CleanupResult -InputObject $broken
        Assert-Equal `
            -Actual $result.status `
            -Expected "FAIL" `
            -Message "Residual $propertyName"
    }
}

Invoke-Case "CLEANUP-REJECTS-HOST-LOG-ORPHAN" {
    $broken = $cleanupFixture | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $broken.hostLogcatOrphans = 1
    $result = New-G2CleanupResult -InputObject $broken
    Assert-Equal -Actual $result.status -Expected "FAIL" -Message "Status"
}

Invoke-Case "CLEANUP-REJECTS-LOCK-NOT-RELEASED" {
    $broken = $cleanupFixture | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $broken.deviceLockState = "ACTIVE_COUNT_1"
    $broken.deviceLockActiveCount = 1
    $result = New-G2CleanupResult -InputObject $broken
    Assert-Equal -Actual $result.status -Expected "FAIL" -Message "Status"
}

Invoke-Case "CLEANUP-LS-NOT-FOUND-IS-ABSENT" {
    $path = "/sdcard/Android/data/$mainPackage"
    Assert-True `
        -Condition (Test-G2CleanupPathAbsent `
            -ExitCode 1 `
            -Output "ls: ${path}: No such file or directory" `
            -Path $path) `
        -Message "ls not-found text was treated as a path."
}

Invoke-Case "CLEANUP-LS-CURRENT-PATH-IS-RESIDUAL" {
    $path = "/sdcard/Android/data/$mainPackage"
    Assert-True `
        -Condition (-not (Test-G2CleanupPathAbsent `
            -ExitCode 0 `
            -Output $path `
            -Path $path)) `
        -Message "Current filesystem path was treated as absent."
}

Invoke-Case "CLEANUP-AUDIO-FOCUS-IGNORES-HISTORY" {
    $dump = @"
Audio Focus stack entries (last is top of stack):

Notified focus events:
  event:GAIN source=historic clientId=$mainPackage
"@
    Assert-True `
        -Condition (-not (Test-G2CurrentAudioFocusPresent `
            -Dump $dump `
            -PackageName $mainPackage)) `
        -Message "Historical audio focus event was counted as current."
}

Invoke-Case "CLEANUP-AUDIO-FOCUS-DETECTS-CURRENT" {
    $dump = @"
Audio Focus stack entries (last is top of stack):
  source:binder clientId=$mainPackage gainRequest:3

Notified focus events:
  event:GAIN source=current clientId=$mainPackage
"@
    Assert-True `
        -Condition (Test-G2CurrentAudioFocusPresent `
            -Dump $dump `
            -PackageName $mainPackage) `
        -Message "Current audio focus owner was not detected."
}

Invoke-Case "CLEANUP-NO-SESSION-IGNORES-UNRELATED-LOGCAT" {
    $processes = @(
        [pscustomobject]@{
            ProcessId = 4321
            CommandLine = (
                "adb.exe -s other-device logcat -v threadtime,year"
            )
        }
    )
    $orphans = Get-G2HostLogcatOrphanCount `
        -Session $null `
        -Processes $processes `
        -Serial "43af8627"
    Assert-Equal `
        -Actual $orphans `
        -Expected 0 `
        -Message "Unrelated logcat process was counted as this session."
}

Invoke-Case "CLEANUP-LIVE-SESSION-LOGCAT-IS-ORPHAN" {
    $processes = @(
        [pscustomobject]@{
            ProcessId = 4321
            CommandLine = (
                "adb.exe -s 43af8627 logcat -v threadtime,year"
            )
        }
    )
    $session = [pscustomobject]@{
        hostLogcatProcessId = 4321
        hostLogcatProcessStopped = $false
    }
    $orphans = Get-G2HostLogcatOrphanCount `
        -Session $session `
        -Processes $processes `
        -Serial "43af8627"
    Assert-Equal `
        -Actual $orphans `
        -Expected 1 `
        -Message "Live session logcat process was not detected."
}

Invoke-Case "CLEANUP-SUCCESS-RELEASES-AND-REREADS-LOCK" {
    $temp = New-TestTempRoot
    $lockPath = Join-Path $temp.Root "device-lock.json"
    try {
        Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $contract.evidence.padRuntimeRoot
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = ("a" * 64)
            releasedAt = $null
        })
        $occupancy = Complete-G2CleanupDeviceLock `
            -Path $lockPath `
            -AllOtherChecksPassed $true `
            -DeviceRole Pad `
            -Serial "43af8627" `
            -AttemptScope $contract.evidence.padRuntimeRoot
        Assert-Equal `
            -Actual $occupancy.state `
            -Expected "RELEASED_ACTIVE_0" `
            -Message "Returned lock state"
        Assert-Equal `
            -Actual $occupancy.activeCount `
            -Expected 0 `
            -Message "Returned active count"
        $external = Read-Json $lockPath
        Assert-Equal `
            -Actual $external.state `
            -Expected "RELEASED_ACTIVE_0" `
            -Message "External lock state"
        Assert-Equal `
            -Actual $external.activeCount `
            -Expected 0 `
            -Message "External active count"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CLEANUP-TRUE-ACTIVE-LOCK-STILL-FAILS" {
    $temp = New-TestTempRoot
    $lockPath = Join-Path $temp.Root "device-lock.json"
    try {
        Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            attemptScope = $contract.evidence.padRuntimeRoot
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            authoritativeLockStateAtRegistration = "RELEASED_ACTIVE_0"
            historicalBlockerSha256 = ("a" * 64)
            releasedAt = $null
        })
        $occupancy = Complete-G2CleanupDeviceLock `
            -Path $lockPath `
            -AllOtherChecksPassed $false `
            -DeviceRole Pad `
            -Serial "43af8627" `
            -AttemptScope $contract.evidence.padRuntimeRoot
        $observed = $cleanupFixture |
            ConvertTo-Json -Depth 20 |
            ConvertFrom-Json
        $observed.deviceLockState = $occupancy.state
        $observed.deviceLockActiveCount = $occupancy.activeCount
        $result = New-G2CleanupResult -InputObject $observed
        Assert-Equal `
            -Actual $result.status `
            -Expected "FAIL" `
            -Message "True ACTIVE lock"
    } finally {
        Remove-TestTempRoot -Temp $temp
    }
}

Invoke-Case "CLEANUP-FILTERS-NONCANDIDATE-DUMPS" {
    $dump = @"
NotificationRecord(0x1: pkg=dev.aurorama.kokoro.harness text=candidate-state)
NotificationRecord(0x2: pkg=other.application text=unrelated-sensitive-marker)
MediaSessionRecord package=dev.aurorama.kokoro.harness ownerPid=2000
MediaSessionRecord package=other.application ownerPid=4000
AudioFocus owner=dev.aurorama.kokoro.harness state=ACTIVE
AudioFocus owner=other.application state=ACTIVE
"@
    $filtered = Select-G2CleanupCandidateText `
        -Text $dump `
        -Packages @(
            "dev.aurorama.kokoro.harness",
            "dev.aurorama.kokoro.focuspeer"
        )
    Assert-True `
        -Condition $filtered.Contains(
            "dev.aurorama.kokoro.harness",
            [System.StringComparison]::Ordinal
        ) `
        -Message "Candidate line was removed."
    Assert-True `
        -Condition (-not $filtered.Contains(
            "unrelated-sensitive-marker",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Unrelated dump content was retained."
    Assert-True `
        -Condition (-not $filtered.Contains(
            "other.application",
            [System.StringComparison]::Ordinal
        )) `
        -Message "Unrelated package line was retained."
}

Invoke-Case "MEDIA-SESSION-IGNORES-LAST-PLAYED-HISTORY" {
    $dump = @"
Media Sessions
Sessions Stack - have 0 sessions:

Audio playback (lastly played comes first)
  dev.aurorama.kokoro.harness
"@
    Assert-True `
        -Condition (Test-G2ActiveMediaSessionAbsent -Dump $dump) `
        -Message "Last-played history was counted as active."
}

Invoke-Case "MEDIA-SESSION-DETECTS-ACTIVE-SESSION" {
    $dump = @"
Media Sessions
Sessions Stack - have 1 sessions:
  MediaSessionRecord
    ownerPid=2000 ownerUid=10315 package=dev.aurorama.kokoro.harness
"@
    Assert-True `
        -Condition (-not (Test-G2ActiveMediaSessionAbsent -Dump $dump)) `
        -Message "Active session was not detected."
}

Write-Output (
    "SUMMARY PASS={0} FAIL={1} NEGATIVE_CASES={2}" -f
    $script:passed,
    $script:failed,
    $script:negativeChecks
)

if ($script:failed -gt 0) {
    Write-Output ("FAILURES=" + ($script:failures -join " | "))
    exit 1
}

Write-Output "PASS G2_RUNTIME_TOOLS"
exit 0
