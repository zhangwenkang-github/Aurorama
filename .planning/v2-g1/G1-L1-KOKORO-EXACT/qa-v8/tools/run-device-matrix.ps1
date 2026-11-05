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

    [int] $Minutes = 30,

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
New-Item -ItemType Directory -Force -Path $rawRoot, $logsRoot | Out-Null

$stepResults = [Collections.Generic.List[object]]::new()
$skippedSteps = [Collections.Generic.List[object]]::new()

function Quote-CommandArgument([string] $Value) {
    return "'" + ([string]$Value).Replace("'", "''") + "'"
}

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
        (($stepResults | ConvertTo-Json -Depth 20) + "`n"),
        $utf8)
}

function Invoke-MatrixStep {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $ScriptPath,

        [hashtable] $Arguments = @{},

        [string] $Kind = 'device'
    )

    if (-not (Test-Path -LiteralPath $ScriptPath)) {
        throw "Matrix step script is missing: $ScriptPath"
    }
    $argumentTokens = [Collections.Generic.List[string]]::new()
    foreach ($key in $Arguments.Keys) {
        $argumentTokens.Add("-$key")
        $argumentTokens.Add([string]$Arguments[$key])
    }
    $pwshArguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $ScriptPath
    ) + @($argumentTokens)
    $displayParts = [Collections.Generic.List[string]]::new()
    $displayParts.Add((Quote-CommandArgument $pwshPath))
    foreach ($part in $pwshArguments) {
        $displayParts.Add((Quote-CommandArgument $part))
    }
    $command = $displayParts -join ' '
    $stdoutPath = Join-Path $rawRoot "$Name.stdout.log"
    $stderrPath = Join-Path $rawRoot "$Name.stderr.log"
    $startedAt = Get-Date
    $output = @(& $pwshPath @pwshArguments 2>&1)
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    $finishedAt = Get-Date
    $durationMs = [long]($finishedAt - $startedAt).TotalMilliseconds
    $outputLines = @($output | ForEach-Object { [string]$_ })
    [IO.File]::WriteAllText(
        $stdoutPath,
        $(if ($outputLines.Count -gt 0) {
            ($outputLines -join "`n") + "`n"
        } else {
            ''
        }),
        $utf8)
    [IO.File]::WriteAllText($stderrPath, '', $utf8)
    $entry = [ordered]@{
        name = $Name
        kind = $Kind
        command = $command
        startedAt = $startedAt.ToString('o')
        finishedAt = $finishedAt.ToString('o')
        durationMs = $durationMs
        exitCode = $exitCode
        status = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
    }
    $stepResults.Add($entry)
    Write-StepState
    return [pscustomobject]$entry
}

function Assert-DeviceLock {
    $lines = [IO.File]::ReadAllLines($DeviceLockPath, [Text.Encoding]::UTF8)
    $active = @(
        $lines |
            Where-Object { $_ -match '^### .* — ACTIVE\s*$' }
    )
    $matching = @(
        $active |
            Where-Object {
                $_ -match 'QA[-_]?V8' -and
                $_ -match [regex]::Escape($Serial)
            }
    )
    $result = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.matrix-preflight.v1'
        serial = $Serial
        deviceLockPath = $DeviceLockPath
        activeCount = $active.Count
        matchingQaV8Count = $matching.Count
        passed = $active.Count -eq 1 -and $matching.Count -eq 1
        activeTitles = $active
    }
    [IO.File]::WriteAllText(
        $preflightPath,
        (($result | ConvertTo-Json -Depth 10) + "`n"),
        $utf8)
    if (-not $result.passed) {
        throw "Device lock gate failed: ACTIVE=$($active.Count) matchingQA-V8=$($matching.Count)"
    }
    return $result
}

function Write-ToolDigestManifest {
    $qaRoot = Split-Path -Parent $toolsRoot
    $canonicalPath = Join-Path $qaRoot 'tools.sha256'
    $toolFiles = @(
        Get-ChildItem -LiteralPath $toolsRoot -File -Force |
            Where-Object { $_.Name -ne 'tools.sha256' } |
            Sort-Object Name
    )
    $lines = @(
        $toolFiles |
            ForEach-Object {
                $relative = 'tools/' + $_.Name
                $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).
                    Hash.ToLowerInvariant()
                "$hash  $relative"
            }
    )
    [IO.File]::WriteAllText(
        $canonicalPath,
        $(if ($lines.Count -gt 0) { ($lines -join "`n") + "`n" } else { '' }),
        $utf8)
    $aggregateSha256 =
        (Get-FileHash -LiteralPath $canonicalPath -Algorithm SHA256).
            Hash.ToLowerInvariant()
    $evidenceCopyPath = $canonicalPath
    if (
        -not [IO.Path]::GetFullPath($outputPath).Equals(
            [IO.Path]::GetFullPath($qaRoot),
            [StringComparison]::OrdinalIgnoreCase)
    ) {
        $evidenceCopyPath = Join-Path $outputPath 'tools.sha256'
        Copy-Item -LiteralPath $canonicalPath -Destination $evidenceCopyPath -Force
    }
    return [ordered]@{
        canonicalPath = $canonicalPath
        evidenceCopyPath = $evidenceCopyPath
        aggregateSha256 = $aggregateSha256
        entryCount = $lines.Count
        sortedBy = 'tools/<filename> ordinal'
        selfExcluded = $true
    }
}

$lockGate = $null
$deviceWorkStarted = $false
$priorFailure = $false
$finalizeResult = $null
$postprocessResult = $null
$topLevelError = $null
$toolDigest = $null

try {
$toolDigest = Write-ToolDigestManifest
$requiredScripts = @(
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
    throw "Required QA-v8 tools are missing: $($missingScripts -join ', ')"
}

$lockGate = Assert-DeviceLock

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
        }
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
            Name = 'background-30m'
            Script = 'background-longrun.ps1'
            Arguments = @{
                Serial = $Serial
                OutputRoot = $outputPath
                LogcatPath = $logcatPath
                Minutes = $Minutes
            }
        },
        @{
            Name = 'background-30m-analysis'
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
            -Arguments $step.Arguments
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
                }
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
                -Kind 'postprocess'
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
    finalSealRequiredAfterMatrixResult = $true
}
[IO.File]::WriteAllText(
    $matrixResultPath,
    (($matrixResult | ConvertTo-Json -Depth 30) + "`n"),
    $utf8)
$sealOutput = @(
    & $pwshPath -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $toolsRoot 'seal-evidence.ps1') `
        -EvidenceRoot $outputPath `
        -ToolsManifestPath $toolDigest.canonicalPath 2>&1
)
$sealExitCode =
    if ($null -eq $LASTEXITCODE) { 1 } else { [int]$LASTEXITCODE }
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
