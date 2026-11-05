[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $MainApk,

    [Parameter(Mandatory = $true)]
    [string] $PeerApk,

    [ValidateRange(1, 120)]
    [int] $Minutes = 10,

    [ValidateRange(1, 7200)]
    [int] $DefaultStepTimeoutSeconds = 300,

    [ValidateRange(1, 7200)]
    [int] $FinalizeTimeoutSeconds = 600,

    [string] $RunId,

    [switch] $PreflightOnly,

    [string] $DeviceLockPath =
        'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
)

$ErrorActionPreference = 'Stop'
$outputPath = if (Test-Path -LiteralPath $OutputRoot) {
    (Resolve-Path -LiteralPath $OutputRoot).Path
} else {
    $created = New-Item -ItemType Directory -Path $OutputRoot -Force
    $created.FullName
}
$mainApkPath = (Resolve-Path -LiteralPath $MainApk).Path
$peerApkPath = (Resolve-Path -LiteralPath $PeerApk).Path
$toolsRoot = $PSScriptRoot
$rawRoot = Join-Path $outputPath 'raw'
$logsRoot = Join-Path $outputPath 'logs'
$mainInstallRoot = Join-Path $outputPath 'main-install'
$logcatPath = Join-Path $logsRoot 'runtime-logcat.log'
$installGatePath = Join-Path $outputPath 'install-gate.json'
$commandsLogPath = Join-Path $rawRoot 'commands.log'
$commandsJsonPath = Join-Path $rawRoot 'commands.json'
$preflightPath = Join-Path $rawRoot 'preflight.json'
$matrixResultPath = Join-Path $outputPath 'matrix-result.json'
$manifestPath = Join-Path $outputPath 'SHA256SUMS'
$postprocessPath = Join-Path $outputPath 'postprocess.json'
$utf8 = [Text.UTF8Encoding]::new($false)
$pwshPath = (Get-Command 'pwsh.exe' -ErrorAction Stop).Source
. (Join-Path $toolsRoot 'qa-process-lifecycle.ps1')
. (Join-Path $toolsRoot 'qa-evidence-contract.ps1')
$runId = Resolve-QaRunId -EvidenceRoot $outputPath -RequestedRunId $RunId
New-Item -ItemType Directory -Force -Path $rawRoot, $logsRoot | Out-Null

$stepResults = [Collections.Generic.List[object]]::new()
$skippedSteps = [Collections.Generic.List[object]]::new()

function Write-StepState {
    $lines = @(
        $stepResults |
            ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 20 }
    )
    [IO.File]::WriteAllText(
        $commandsLogPath,
        $(if ($lines.Count -gt 0) { ($lines -join "`n") + "`n" } else { '' }),
        $utf8)
    [IO.File]::WriteAllText(
        $commandsJsonPath,
        (ConvertTo-QaCommandsJson -Entries @($stepResults)),
        $utf8)
}

function Invoke-MatrixStep {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $ScriptPath,

        [hashtable] $Arguments = @{},

        [string] $Kind = 'device',

        [ValidateRange(1, 7200)]
        [int] $TimeoutSeconds = $DefaultStepTimeoutSeconds,

        [switch] $PreserveOwnedLogcat
    )

    $metadataPath = Join-Path $logsRoot 'logcat-start.json'
    $entry = Invoke-QaMatrixStep `
        -Name $Name `
        -ScriptPath $ScriptPath `
        -Arguments $Arguments `
        -Kind $Kind `
        -RawRoot $rawRoot `
        -PwshPath $pwshPath `
        -TimeoutSeconds $TimeoutSeconds `
        -AllowedResidualMetadataPath $(if ($PreserveOwnedLogcat) {
            $metadataPath
        } else {
            $null
        }) `
        -AllowedResidualSerial $(if ($PreserveOwnedLogcat) {
            $Serial
        } else {
            $null
        }) `
        -AllowedResidualEvidenceRoot $(if ($PreserveOwnedLogcat) {
            $outputPath
        } else {
            $null
        })
    $stepResults.Add($entry)
    Write-StepState
    return $entry
}

function Assert-DeviceLock {
    $result = Test-QaRunLock `
        -DeviceLockPath $DeviceLockPath `
        -Serial $Serial `
        -RunId $runId
    [IO.File]::WriteAllText(
        $preflightPath,
        (($result | ConvertTo-Json -Depth 10) + "`n"),
        $utf8)
    if (-not $result.passed) {
        throw (
            "Device lock gate failed: RUN_ID=$($result.runId) " +
            "ACTIVE=$($result.activeCount) " +
            "matchingRun=$($result.matchingRunCount) " +
            "reason=$($result.reason -join ',')")
    }
    return $result
}

function Write-ToolDigestManifest {
    $qaRoot = Split-Path -Parent $toolsRoot
    $canonicalPath = Join-Path $qaRoot 'tools.sha256'
    return Write-QaToolDigestSnapshot `
        -EvidenceRoot $outputPath `
        -ToolsRoot $toolsRoot `
        -CanonicalManifestPath $canonicalPath
}

$lockGate = $null
$deviceWorkStarted = $false
$priorFailure = $false
$finalizeResult = $null
$postprocessResult = $null
$topLevelError = $null
$toolDigest = $null
$commandsFreeze = $null

try {
$lockGate = Assert-DeviceLock
$toolDigest = Write-ToolDigestManifest
$requiredScripts = @(
    'qa-evidence-common.ps1',
    'qa-process-lifecycle.ps1',
    'qa-evidence-contract.ps1',
    'prepare-device.ps1',
    'resource-consistency.ps1',
    'measure-voice-samples.ps1',
    'background-longrun.ps1',
    'analyze-longrun.ps1',
    'notification-controls-v8.ps1',
    'focus-peer-test.ps1',
    'recreate-paused.ps1',
    'ui-cancel-test.ps1',
    'music-tts-arbitration.ps1',
    'checkpoint-tamper-test.ps1',
    'zero-network-v5.ps1',
    'finalize-device.ps1',
    'postprocess-evidence.ps1',
    'seal-evidence.ps1'
) | ForEach-Object { Join-Path $toolsRoot $_ }
$missingScripts = @($requiredScripts | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missingScripts.Count -gt 0) {
    throw "Required QA tools are missing for RUN_ID=${runId}: $($missingScripts -join ', ')"
}

if ($PreflightOnly) {
    $dryRunResult = [ordered]@{
        schema = 'aurorama.kokoro-qa.static-preflight.v1'
        status = 'PASS_STATIC_PREFLIGHT'
        runId = $runId
        serial = $Serial
        evidenceRoot = $outputPath
        lockGate = $lockGate
        toolDigest = $toolDigest
        deviceActionExecuted = $false
    }
    [IO.File]::WriteAllText(
        (Join-Path $rawRoot 'static-preflight.json'),
        (($dryRunResult | ConvertTo-Json -Depth 20) + "`n"),
        $utf8)
    $dryRunResult | ConvertTo-Json -Depth 20
    return
}

try {
    $deviceWorkStarted = $true
    $prepare = Invoke-MatrixStep `
        -Name 'prepare-device' `
        -ScriptPath (Join-Path $toolsRoot 'prepare-device.ps1') `
        -Arguments @{
            Serial = $Serial
            OutputRoot = $outputPath
            MainApk = $mainApkPath
            PeerApk = $peerApkPath
            RunId = $runId
            DeviceLockPath = $DeviceLockPath
        } `
        -TimeoutSeconds $DefaultStepTimeoutSeconds `
        -PreserveOwnedLogcat
    if ($prepare.exitCode -ne 0) {
        $priorFailure = $true
    }

    if (-not $priorFailure) {
        $installGate = Get-Content -LiteralPath $installGatePath -Raw -Encoding utf8 |
            ConvertFrom-Json
        if (-not [bool]$installGate.freshInstall -or
            -not [bool]$installGate.mediaPlaybackForegroundServiceTypeMatches) {
            $priorFailure = $true
        }
    }

    $steps = @(
        @{
            Name = 'resource-consistency'
            Script = 'resource-consistency.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = 'dual-voice-samples'
            Script = 'measure-voice-samples.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = "background-${Minutes}m"
            Script = 'background-longrun.ps1'
            TimeoutSeconds = $Minutes * 60 + 180
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
                Minutes = $Minutes
            }
        },
        @{
            Name = "background-${Minutes}m-analysis"
            Script = 'analyze-longrun.ps1'
            Arguments = @{
                LongRunRoot = $outputPath
                InstallAuditPath = $installGatePath
            }
        },
        @{
            Name = 'notification-four-controls'
            Script = 'notification-controls-v8.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = 'os-focus-peer'
            Script = 'focus-peer-test.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
                MainUid = if ($priorFailure) { '0' } else { [string]$installGate.main.uid }
                PeerUid = if ($priorFailure) { '0' } else { [string]$installGate.peer.uid }
            }
        },
        @{
            Name = 'paused-recreate'
            Script = 'recreate-paused.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = 'ui-cancel'
            Script = 'ui-cancel-test.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = 'music-tts-arbitration'
            Script = 'music-tts-arbitration.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = 'checkpoint-tamper'
            Script = 'checkpoint-tamper-test.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        },
        @{
            Name = 'zero-network'
            Script = 'zero-network-v5.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
            }
        }
    )

    foreach ($step in $steps) {
        if ($priorFailure) {
            $skippedSteps.Add([ordered]@{
                name = $step.Name
                reason = 'prior_matrix_step_failed_stop_gate'
            })
            continue
        }
        $result = Invoke-MatrixStep `
            -Name $step.Name `
            -ScriptPath (Join-Path $toolsRoot $step.Script) `
            -Arguments $step.Arguments `
            -TimeoutSeconds $(if ($step.ContainsKey('TimeoutSeconds')) {
                [int]$step.TimeoutSeconds
            } else {
                $DefaultStepTimeoutSeconds
            })
        if ($result.exitCode -ne 0) {
            $priorFailure = $true
        }
    }
} finally {
    if ($deviceWorkStarted) {
        try {
            $finalizeResult = Invoke-MatrixStep `
                -Name 'finalize-device' `
                -ScriptPath (Join-Path $toolsRoot 'finalize-device.ps1') `
                -Arguments @{
                    Serial = $Serial
                    OutputRoot = $outputPath
                    LogcatPath = $logcatPath
                } `
                -TimeoutSeconds $FinalizeTimeoutSeconds
            if ($finalizeResult.exitCode -ne 0) {
                $priorFailure = $true
            }
        } catch {
            $priorFailure = $true
            $stepResults.Add([ordered]@{
                name = 'finalize-device'
                kind = 'device'
                command = $null
                startedAt = (Get-Date).ToString('o')
                finishedAt = (Get-Date).ToString('o')
                durationMs = 0
                exitCode = 1
                status = 'FAIL'
                error = $_.Exception.Message
            })
            Write-StepState
        }

        try {
            $postprocessResult = Invoke-MatrixStep `
                -Name 'postprocess-evidence' `
                -ScriptPath (Join-Path $toolsRoot 'postprocess-evidence.ps1') `
                -Arguments @{
                    EvidenceRoot = $outputPath
                } `
                -Kind 'postprocess' `
                -TimeoutSeconds $DefaultStepTimeoutSeconds
            if ($postprocessResult.exitCode -ne 0) {
                $priorFailure = $true
            }
        } catch {
            $priorFailure = $true
            $stepResults.Add([ordered]@{
                name = 'postprocess-evidence'
                kind = 'postprocess'
                command = $null
                startedAt = (Get-Date).ToString('o')
                finishedAt = (Get-Date).ToString('o')
                durationMs = 0
                exitCode = 1
                status = 'FAIL'
                error = $_.Exception.Message
            })
            Write-StepState
        }
    }
}
} catch {
    $topLevelError = $_
    $priorFailure = $true
}

try {
    Write-StepState
    $commandsFreeze = Write-QaCommandsFreeze -EvidenceRoot $outputPath
} catch {
    if ($null -eq $topLevelError) {
        $topLevelError = $_
    }
    $priorFailure = $true
}

$finalStatus = if (
    $null -eq $topLevelError -and
    $lockGate.passed -and
    -not $priorFailure -and
    $skippedSteps.Count -eq 0 -and
    $null -ne $finalizeResult -and
    $finalizeResult.exitCode -eq 0 -and
    $null -ne $postprocessResult -and
    $postprocessResult.exitCode -eq 0
) {
    'PASS_PRIMARY_GATES_FINAL_SEAL_PENDING'
} else {
    'FAIL_PRIMARY_GATES'
}
$matrixResult = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.device-matrix.v1'
    status = $finalStatus
    runId = $runId
    serial = $Serial
    generatedAt = (Get-Date).ToString('o')
    strictSerial = $true
    crashGateCoverage =
        'final current-run filter from logs/logcat-start.json covers every device step and cleanup'
    topLevelError = if ($topLevelError) {
        $topLevelError.Exception.Message
    } else {
        $null
    }
    toolDigest = $toolDigest
    lockGate = $lockGate
    requestedMinutes = $Minutes
    commands = @($stepResults)
    skipped = @($skippedSteps)
    finalizeAlwaysExecuted = $null -ne $finalizeResult
    postprocessAlwaysExecuted = $null -ne $postprocessResult
    commandsFrozenBeforeFinalManifest = $null -ne $commandsFreeze
    commandsFreeze = $commandsFreeze
    finalSealRequiredAfterMatrixResult = $true
}
[IO.File]::WriteAllText(
    $matrixResultPath,
    (($matrixResult | ConvertTo-Json -Depth 30) + "`n"),
    $utf8)
$sealResult = Invoke-QaBoundedProcess `
    -FilePath $pwshPath `
    -Arguments @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', (Join-Path $toolsRoot 'seal-evidence.ps1'),
        '-EvidenceRoot', $outputPath,
        '-ToolsManifestPath', $toolDigest.verificationManifestPath
    ) `
    -StandardOutputPath (Join-Path $rawRoot 'final-seal.stdout.log') `
    -StandardErrorPath (Join-Path $rawRoot 'final-seal.stderr.log') `
    -TimeoutSeconds $DefaultStepTimeoutSeconds `
    -KillResidualProcesses
$sealExitCode = if (
    $sealResult.timedOut -or
    $null -ne $sealResult.failureKind -or
    @($sealResult.residualProcessIds).Count -gt 0
) {
    1
} else {
    [int]$sealResult.exitCode
}
$sealOutput = @(
    if (Test-Path -LiteralPath $sealResult.stdoutPath) {
        [IO.File]::ReadAllLines($sealResult.stdoutPath, [Text.Encoding]::UTF8)
    }
    if (Test-Path -LiteralPath $sealResult.stderrPath) {
        [IO.File]::ReadAllLines($sealResult.stderrPath, [Text.Encoding]::UTF8)
    }
)
$matrixResult | ConvertTo-Json -Depth 10
if ($sealOutput.Count -gt 0) {
    $sealOutput
}
if (
    $finalStatus -ne 'PASS_PRIMARY_GATES_FINAL_SEAL_PENDING' -or
    $sealExitCode -ne 0
) {
    exit 1
}
