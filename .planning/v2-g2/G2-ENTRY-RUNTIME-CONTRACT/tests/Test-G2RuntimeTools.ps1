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

Invoke-Case "CONTRACT-RUNTIME-TOOLS-BOUND" {
    Assert-Equal `
        -Actual $contract.status `
        -Expected "PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT" `
        -Message "Contract status"
    Assert-Equal `
        -Actual $contract.authorization.activePrompt `
        -Expected "G2_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT_OWNER" `
        -Message "Active prompt"
    Assert-Equal `
        -Actual $contract.authorization.qaDispatchable `
        -Expected $false `
        -Message "QA dispatch before fresh audit"
    Assert-Equal `
        -Actual $contract.tools.runtimeCollector.status `
        -Expected "READY_SELF_TESTED_STATIC_ONLY" `
        -Message "Collector status"
    Assert-Equal `
        -Actual $contract.tools.runtimeFinalizer.status `
        -Expected "READY_SELF_TESTED_STATIC_ONLY" `
        -Message "Finalizer status"
    Assert-Equal `
        -Actual $contract.tools.cleanupVerifier.status `
        -Expected "READY_SELF_TESTED_STATIC_ONLY" `
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
    $guardName = ".path-guard-" + [Guid]::NewGuid().ToString("N")
    $guardRoot = Join-Path $testsRoot $guardName
    $guardTarget = Join-Path $testsRoot ($guardName + "-target")
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
        if (Test-Path -LiteralPath $guardRoot) {
            $resolvedGuardRoot = [System.IO.Path]::GetFullPath($guardRoot)
            Assert-True `
                -Condition $resolvedGuardRoot.StartsWith(
                    ($testsRoot + [System.IO.Path]::DirectorySeparatorChar),
                    [System.StringComparison]::OrdinalIgnoreCase
                ) `
                -Message "Unsafe test cleanup root."
            Remove-Item -LiteralPath $resolvedGuardRoot -Recurse -Force
        }
        if (Test-Path -LiteralPath $guardTarget) {
            $resolvedGuardTarget = [System.IO.Path]::GetFullPath($guardTarget)
            Assert-True `
                -Condition $resolvedGuardTarget.StartsWith(
                    ($testsRoot + [System.IO.Path]::DirectorySeparatorChar),
                    [System.StringComparison]::OrdinalIgnoreCase
                ) `
                -Message "Unsafe test cleanup target."
            Remove-Item -LiteralPath $resolvedGuardTarget -Recurse -Force
        }
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
    $predecessor = [pscustomobject]@{
        deviceOrder = "Pad"
        coreVerdict = "PASS"
        lockStateAfterClosure = "RELEASED_ACTIVE_0"
        productVerdict = "PASS"
        candidateMainSha256 = $contract.candidate.main.sha256
        candidateFocusPeerSha256 = $contract.candidate.focusPeer.sha256
        evidencePointer = (
            ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/qa-pad-v1/report.json"
        )
    }
    Assert-G2DevicePolicy `
        -Contract $contract `
        -DeviceRole K60 `
        -Serial "8e875894" `
        -Predecessor $predecessor
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
    $lockPath = Join-Path `
        $testsRoot `
        (".cleanup-lock-" + [Guid]::NewGuid().ToString("N") + ".json")
    try {
        Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            releasedAt = $null
        })
        $occupancy = Complete-G2CleanupDeviceLock `
            -Path $lockPath `
            -AllOtherChecksPassed $true `
            -DeviceRole Pad `
            -Serial "43af8627"
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
        if (Test-Path -LiteralPath $lockPath) {
            Remove-Item -LiteralPath $lockPath -Force
        }
    }
}

Invoke-Case "CLEANUP-TRUE-ACTIVE-LOCK-STILL-FAILS" {
    $lockPath = Join-Path `
        $testsRoot `
        (".cleanup-lock-" + [Guid]::NewGuid().ToString("N") + ".json")
    try {
        Write-G2CleanupJson -Path $lockPath -Value ([pscustomobject]@{
            schema = "aurorama.g2-device-lock.v1"
            deviceRole = "Pad"
            serial = "43af8627"
            state = "ACTIVE_COUNT_1"
            activeCount = 1
            attemptNumber = 1
            releasedAt = $null
        })
        $occupancy = Complete-G2CleanupDeviceLock `
            -Path $lockPath `
            -AllOtherChecksPassed $false `
            -DeviceRole Pad `
            -Serial "43af8627"
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
        if (Test-Path -LiteralPath $lockPath) {
            Remove-Item -LiteralPath $lockPath -Force
        }
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
    37
)

if ($script:failed -gt 0) {
    Write-Output ("FAILURES=" + ($script:failures -join " | "))
    exit 1
}

Write-Output "PASS G2_RUNTIME_TOOLS"
exit 0
