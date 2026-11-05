[CmdletBinding()]
param(
    [string]$RepoRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 is required."
}

$repositoryRoot = if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
}
else {
    [System.IO.Path]::GetFullPath($RepoRoot)
}

$contractPath = Join-Path $PSScriptRoot "HANDOFF_BOOTSTRAP_V2.json"
$preflightPath = Join-Path $PSScriptRoot "Invoke-HandoffPreflight.ps1"
$testPath = $PSCommandPath
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8Output
$OutputEncoding = $utf8Output

$testFailures = [System.Collections.Generic.List[string]]::new()
$testResults = [System.Collections.Generic.List[object]]::new()

function Assert-Condition {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Name
    )

    if (-not $Condition) {
        $testFailures.Add($Name)
        throw "ASSERT-FAILED $Name"
    }
}

function Assert-StrictUtf8Lf {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    Assert-Condition `
        -Condition (Test-Path -LiteralPath $Path -PathType Leaf) `
        -Name "$Name-EXISTS"
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-Condition `
        -Condition (-not (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        )) `
        -Name "$Name-NO-BOM"
    Assert-Condition `
        -Condition ([Array]::IndexOf($bytes, [byte]13) -lt 0) `
        -Name "$Name-LF"
    try {
        [void]$utf8Strict.GetString($bytes)
    }
    catch {
        Assert-Condition -Condition $false -Name "$Name-UTF8"
    }
}

function Write-LfJson {
    param(
        [Parameter(Mandatory)][object]$Value,
        [Parameter(Mandatory)][string]$Path
    )

    $text = $Value | ConvertTo-Json -Depth 100
    $text = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $text.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $text += "`n"
    }
    [System.IO.File]::WriteAllText($Path, $text, $utf8Output)
}

function Invoke-Preflight {
    param(
        [string]$FixturePath,
        [ValidateSet("next", "padQa")]
        [string]$PromptName = "next"
    )

    $arguments = @(
        "-NoProfile",
        "-File",
        $preflightPath,
        "-RepoRoot",
        $repositoryRoot
    )
    if (-not [string]::IsNullOrWhiteSpace($FixturePath)) {
        $arguments += @("-ContractPath", $FixturePath)
    }
    if ($PromptName -ne "next") {
        $arguments += @("-Prompt", $PromptName)
    }

    $output = @(& pwsh @arguments 2>&1)
    return [pscustomobject]@{
        exitCode = $LASTEXITCODE
        output = $output
        firstLine = if ($output.Count -gt 0) { [string]$output[0] } else { "" }
    }
}

function Add-CaseResult {
    param(
        [Parameter(Mandatory)][string]$Case,
        [Parameter(Mandatory)][int]$ExpectedExit,
        [Parameter(Mandatory)][object]$Result
    )

    $passed = $Result.exitCode -eq $ExpectedExit
    if ($ExpectedExit -eq 0) {
        $passed = $passed -and $Result.firstLine.StartsWith(
            "PASS ",
            [System.StringComparison]::Ordinal
        )
    }
    else {
        $passed = $passed -and $Result.firstLine.StartsWith(
            "FAIL_CLOSED ",
            [System.StringComparison]::Ordinal
        )
    }
    Assert-Condition -Condition $passed -Name "CASE-$Case"
    $testResults.Add([pscustomobject]@{
        case = $Case
        expected = $ExpectedExit
        actual = $Result.exitCode
        result = "PASS"
    })
    Write-Output (
        "CASE {0} expected={1} actual={2} result=PASS" -f
        $Case,
        $ExpectedExit,
        $Result.exitCode
    )
}

function Get-Gate {
    param(
        [Parameter(Mandatory)][object]$Contract,
        [Parameter(Mandatory)][string]$GateId
    )

    return @(
        $Contract.requiredGates.lifecycle |
            Where-Object { $_.id -ceq $GateId }
    )[0]
}

function Get-ToolsMtimeSnapshot {
    param(
        [Parameter(Mandatory)][string]$ToolsRoot,
        [Parameter(Mandatory)][string]$ManifestPath
    )

    $rootItem = Get-Item -LiteralPath $ToolsRoot
    $manifestItem = Get-Item -LiteralPath $ManifestPath
    $files = @(
        Get-ChildItem -LiteralPath $ToolsRoot -File -Recurse -Force |
            Sort-Object -Property FullName
    )
    $fileSnapshot = @(
        foreach ($file in $files) {
            "{0}|{1}|{2}" -f
            $file.FullName,
            $file.Length,
            $file.LastWriteTimeUtc.ToString("o")
        }
    )
    return (
        @(
            $rootItem.LastWriteTimeUtc.ToString("o"),
            $manifestItem.Length,
            $manifestItem.LastWriteTimeUtc.ToString("o")
        ) + $fileSnapshot
    ) -join "`n"
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $tempBase ("aurorama-b5-preflight-" + [guid]::NewGuid().ToString("N")))
)
if (-not $tempDirectory.StartsWith(
    $tempBase,
    [System.StringComparison]::OrdinalIgnoreCase
)) {
    throw "Unsafe temporary directory."
}

$qaToolsRoot = Join-Path $repositoryRoot ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools"
$qaManifestPath = Join-Path $repositoryRoot ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools.sha256"
$toolsMtimeBefore = Get-ToolsMtimeSnapshot `
    -ToolsRoot $qaToolsRoot `
    -ManifestPath $qaManifestPath

try {
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    $baseContractText = [System.IO.File]::ReadAllText(
        $contractPath,
        $utf8Strict
    )
    $baseContract = $baseContractText | ConvertFrom-Json -Depth 100

    Add-CaseResult `
        -Case "current-contract" `
        -ExpectedExit 0 `
        -Result (Invoke-Preflight)

    $negativeCases = @(
        @{
            name = "audit-pass-without-allowed-phase"
            mutate = {
                param($contract)
                $contract.qaRoundState.preHandoffAudit = "PASS"
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").status = "PASS"
            }
        },
        @{
            name = "audit-owner-drift"
            mutate = {
                param($contract)
                (Get-Gate $contract "PRE_HANDOFF_AUDIT_PASS").owner = "PAD_QA_OWNER"
            }
        },
        @{
            name = "review-requiredBefore-missing"
            mutate = {
                param($contract)
                $gate = Get-Gate $contract "INDEPENDENT_INTEGRATION_REVIEW_PASS"
                [void]$gate.PSObject.Properties.Remove("requiredBefore")
            }
        },
        @{
            name = "first-action-arbitrary-path"
            mutate = {
                param($contract)
                $badPath = "C:\Windows\Temp\arbitrary.ps1"
                $command = @($contract.prompts.toolRepair.command)
                $command[3] = $badPath
                $contract.prompts.toolRepair.command = $command
                $badAction = $contract.prompts.toolRepair.firstAction.Replace(
                    $preflightPath,
                    $badPath
                )
                $contract.prompts.toolRepair.firstAction = $badAction
                $contract.firstReadOnlyActions.toolRepair[0] = $badAction
                $contract.prompts.toolRepair.textLines[2] = (
                    "第一条只读动作必须实际执行：" + $badAction +
                    "。若非零或 FAIL_CLOSED，立即停止并只报压缩失败。"
                )
            }
        },
        @{
            name = "first-action-unknown-argument"
            mutate = {
                param($contract)
                $command = @($contract.prompts.toolRepair.command)
                $contract.prompts.toolRepair.command = @($command + "-Unknown")
                $badAction = $contract.prompts.toolRepair.firstAction + " -Unknown"
                $contract.prompts.toolRepair.firstAction = $badAction
                $contract.firstReadOnlyActions.toolRepair[0] = $badAction
                $contract.prompts.toolRepair.textLines[2] = (
                    "第一条只读动作必须实际执行：" + $badAction +
                    "。若非零或 FAIL_CLOSED，立即停止并只报压缩失败。"
                )
            }
        },
        @{
            name = "gate-order-swapped"
            mutate = {
                param($contract)
                $gates = @($contract.requiredGates.lifecycle)
                $contract.requiredGates.lifecycle = @(
                    $gates[0],
                    $gates[1],
                    $gates[2],
                    $gates[4],
                    $gates[3],
                    $gates[5]
                )
            }
        },
        @{
            name = "gate-duplicate"
            mutate = {
                param($contract)
                $gates = @($contract.requiredGates.lifecycle)
                $contract.requiredGates.lifecycle = @(
                    $gates[0],
                    $gates[1],
                    $gates[2],
                    $gates[3],
                    $gates[3],
                    $gates[5]
                )
            }
        },
        @{
            name = "gate-missing"
            mutate = {
                param($contract)
                $gates = @($contract.requiredGates.lifecycle)
                $contract.requiredGates.lifecycle = @(
                    $gates[0],
                    $gates[1],
                    $gates[2],
                    $gates[3],
                    $gates[4]
                )
            }
        },
        @{
            name = "qa-pass-before-audit"
            mutate = {
                param($contract)
                $contract.qaRoundState.padQaPromptDispatch = "PASS"
                (Get-Gate $contract "QA_DISPATCHABLE").status = "PASS"
            }
        },
        @{
            name = "top-level-field-missing"
            mutate = {
                param($contract)
                [void]$contract.PSObject.Properties.Remove("readOrder")
            }
        },
        @{
            name = "role-table-incomplete"
            mutate = {
                param($contract)
                $contract.roles = @(
                    $contract.roles |
                        Where-Object { $_.id -cne "PRE_HANDOFF_AUDIT_OWNER" }
                )
            }
        },
        @{
            name = "window-30-minutes"
            mutate = {
                param($contract)
                $contract.devicePolicy.DEVICE_WINDOW_MINUTES = 30
            }
        },
        @{
            name = "k60-before-pad"
            mutate = {
                param($contract)
                $devices = @($contract.devicePolicy.devices)
                $contract.devicePolicy.DEVICE_ORDER = "K60->Pad"
                $contract.devicePolicy.devices = @($devices[1], $devices[0])
            }
        },
        @{
            name = "retired-round3-prompt-owner"
            mutate = {
                param($contract)
                $contract.prompts.toolRepair.roleId =
                    "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER"
            }
        }
    )

    foreach ($case in $negativeCases) {
        $fixture = $baseContractText | ConvertFrom-Json -Depth 100
        & $case.mutate $fixture
        $fixturePath = Join-Path $tempDirectory ($case.name + ".json")
        Write-LfJson -Value $fixture -Path $fixturePath
        Add-CaseResult `
            -Case $case.name `
            -ExpectedExit 2 `
            -Result (Invoke-Preflight -FixturePath $fixturePath)
    }

    Add-CaseResult `
        -Case "direct-padQa-request" `
        -ExpectedExit 2 `
        -Result (Invoke-Preflight -PromptName "padQa")

    foreach ($scriptFile in @($preflightPath, $testPath)) {
        $tokens = $null
        $parseErrors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile(
            $scriptFile,
            [ref]$tokens,
            [ref]$parseErrors
        )
        Assert-Condition `
            -Condition ($parseErrors.Count -eq 0) `
            -Name ("AST-" + [System.IO.Path]::GetFileName($scriptFile))
    }
    Write-Output "STATIC AST=2/2 PASS"

    $strictFiles = @(
        $contractPath,
        $preflightPath,
        $testPath,
        (Join-Path $repositoryRoot "AGENTS.md"),
        (Join-Path $repositoryRoot "docs/PROJECT_RULES.md"),
        (Join-Path $repositoryRoot "docs/PROJECT_PLAN.md"),
        (Join-Path $repositoryRoot "docs/V2_PLAN.md"),
        (Join-Path $repositoryRoot "docs/TASKS.md"),
        (Join-Path $repositoryRoot "docs/LESSONS.md")
    )
    foreach ($strictFile in $strictFiles) {
        Assert-StrictUtf8Lf `
            -Path $strictFile `
            -Name ("UTF8-" + [System.IO.Path]::GetFileName($strictFile))
    }
    [void](Get-Content -LiteralPath $contractPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100)
    Write-Output "STATIC JSON=PASS UTF8_NO_BOM_LF=$($strictFiles.Count)/$($strictFiles.Count)"

    $activeText = Get-Content -LiteralPath (Join-Path $repositoryRoot "docs/V2_PLAN.md") -Raw -Encoding utf8
    $activeCount = [regex]::Matches($activeText, "(?m)^### ACTIVE").Count
    Assert-Condition -Condition ($activeCount -eq 1) -Name "ACTIVE-COUNT-1"

    $tasksLines = @(
        Get-Content -LiteralPath (Join-Path $repositoryRoot "docs/TASKS.md") -Encoding utf8
    )
    $inProgressIndex = [Array]::IndexOf(
        [string[]]$tasksLines,
        "## In Progress"
    )
    Assert-Condition -Condition ($inProgressIndex -ge 0) -Name "TASKS-HEADING"
    $tasksEnd = $tasksLines.Count
    for ($lineIndex = $inProgressIndex + 1; $lineIndex -lt $tasksLines.Count; $lineIndex++) {
        if ($tasksLines[$lineIndex].StartsWith("## ", [System.StringComparison]::Ordinal)) {
            $tasksEnd = $lineIndex
            break
        }
    }
    $taskCount = @(
        for ($lineIndex = $inProgressIndex + 1; $lineIndex -lt $tasksEnd; $lineIndex++) {
            if ($tasksLines[$lineIndex] -match '^\| `V2-[^`]+`') {
                $tasksLines[$lineIndex]
            }
        }
    ).Count
    Assert-Condition -Condition ($taskCount -eq 3) -Name "TASKS-COUNT-3"
    Write-Output "STATIC ACTIVE=1 TASKS=3 PASS"

    $toolFiles = @(
        Get-ChildItem -LiteralPath $qaToolsRoot -File -Recurse -Force |
            Where-Object { $_.Name -ne "tools.sha256" }
    )
    $toolByPath = @{}
    foreach ($toolFile in $toolFiles) {
        $relativePath = [System.IO.Path]::GetRelativePath(
            $qaToolsRoot,
            $toolFile.FullName
        ).Replace("\", "/")
        $toolByPath[$relativePath] = $toolFile.FullName
    }
    $relativePaths = [string[]]@($toolByPath.Keys)
    [Array]::Sort($relativePaths, [System.StringComparer]::Ordinal)
    $manifestLines = @(
        foreach ($relativePath in $relativePaths) {
            $sha = (Get-FileHash `
                -LiteralPath $toolByPath[$relativePath] `
                -Algorithm SHA256).Hash.ToLowerInvariant()
            "$sha  tools/$relativePath"
        }
    )
    $manifestText = ($manifestLines -join "`n") + "`n"
    $manifestBytes = $utf8Output.GetBytes($manifestText)
    $digest = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($manifestBytes)
    ).ToLowerInvariant()
    $expectedDigest = "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4"
    Assert-Condition -Condition ($relativePaths.Count -eq 31) -Name "TOOLS-COUNT-31"
    Assert-Condition -Condition ($manifestBytes.Length -eq 2984) -Name "TOOLS-MANIFEST-2984"
    Assert-Condition -Condition ($digest -ceq $expectedDigest) -Name "TOOLS-DIGEST"
    Assert-Condition `
        -Condition ((Get-FileHash -LiteralPath $qaManifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $expectedDigest) `
        -Name "TOOLS-MANIFEST-SHA"
    $canonicalLines = @($baseContract.qaTools.canonicalManifestLines)
    Assert-Condition `
        -Condition (($manifestLines -join "`n") -ceq ($canonicalLines -join "`n")) `
        -Name "TOOLS-31-OF-31"
    Write-Output "STATIC TOOLS=31/31 MANIFEST=2984 DIGEST=$digest PASS"

    $toolsMtimeAfter = Get-ToolsMtimeSnapshot `
        -ToolsRoot $qaToolsRoot `
        -ManifestPath $qaManifestPath
    Assert-Condition `
        -Condition ($toolsMtimeAfter -ceq $toolsMtimeBefore) `
        -Name "QA-TOOLS-MTIME-UNCHANGED"
    Write-Output "STATIC QA_TOOLS_MTIME=UNCHANGED PASS"

    $diffCheck = @(& git -C $repositoryRoot diff --check 2>&1)
    Assert-Condition `
        -Condition ($LASTEXITCODE -eq 0) `
        -Name "GIT-DIFF-CHECK"
    Write-Output "STATIC GIT_DIFF_CHECK=PASS"

    $stagedDiff = @(& git -C $repositoryRoot diff --cached --name-only 2>&1)
    Assert-Condition `
        -Condition ($LASTEXITCODE -eq 0 -and $stagedDiff.Count -eq 0) `
        -Name "NO-STAGED-CHANGES"
    Write-Output "STATIC STAGED=0 PASS"

    Assert-Condition `
        -Condition (-not (
            @(& git -C $repositoryRoot status --short -- .gitignore 2>&1).Count -gt 0
        )) `
        -Name "GITIGNORE-UNCHANGED"
    Write-Output "STATIC GITIGNORE=UNCHANGED PASS"
}
catch {
    Write-Output ("SUMMARY passed={0} failed={1}" -f $testResults.Count, $testFailures.Count)
    Write-Output ("TEST_FAILURE " + $_.Exception.Message)
    exit 2
}
finally {
    if ([System.IO.Directory]::Exists($tempDirectory)) {
        [System.IO.Directory]::Delete($tempDirectory, $true)
    }
    if ([System.IO.Directory]::Exists($tempDirectory)) {
        Write-Output "TEMP_CLEANUP=FAILED"
        exit 2
    }
}

if ($testFailures.Count -gt 0) {
    Write-Output ("SUMMARY passed={0} failed={1}" -f $testResults.Count, $testFailures.Count)
    exit 2
}

Write-Output ("SUMMARY passed={0} failed=0 TEMP_CLEANUP=PASS" -f $testResults.Count)
exit 0
