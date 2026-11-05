function Quote-QaCommandArgument {
    param([AllowNull()][string] $Value)

    return "'" + ([string]$Value).Replace("'", "''") + "'"
}

function ConvertTo-QaProcessArgument {
    param([AllowNull()][string] $Value)

    if ([string]::IsNullOrEmpty($Value)) {
        return '""'
    }
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    $builder = [Text.StringBuilder]::new()
    $null = $builder.Append('"')
    $backslashCount = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $backslashCount++
            continue
        }
        if ($character -eq '"') {
            $null = $builder.Append('\', (2 * $backslashCount + 1))
            $null = $builder.Append('"')
            $backslashCount = 0
            continue
        }
        if ($backslashCount -gt 0) {
            $null = $builder.Append('\', $backslashCount)
            $backslashCount = 0
        }
        $null = $builder.Append($character)
    }
    if ($backslashCount -gt 0) {
        $null = $builder.Append('\', 2 * $backslashCount)
    }
    $null = $builder.Append('"')
    return $builder.ToString()
}

function Get-QaRemainingSeconds {
    [OutputType([int])]
    param(
        [AllowNull()]
        [object] $Deadline,

        [ValidateRange(1, [int]::MaxValue)]
        [int] $Maximum = 30
    )

    if ($null -eq $Deadline) {
        return 1
    }
    if ($Deadline -isnot [datetime] -and
        $Deadline -isnot [datetimeoffset]) {
        $parsedDeadline = [datetime]::MinValue
        if (-not [datetime]::TryParse(
            [string]$Deadline,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$parsedDeadline)) {
            return 1
        }
        $Deadline = $parsedDeadline
    }
    $remainingSeconds = (
        [datetimeoffset]$Deadline - [datetimeoffset](Get-Date)
    ).TotalSeconds
    if ([double]::IsNaN($remainingSeconds) -or
        [double]::IsInfinity($remainingSeconds)) {
        return 1
    }
    $bounded = [Math]::Min(
        [double]$Maximum,
        [Math]::Max(1.0, [Math]::Floor($remainingSeconds)))
    return [int]$bounded
}

function Get-QaDescendantProcessIds {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RootProcessId,

        [datetime] $StartedAfter
    )

    $descendants = [Collections.Generic.List[int]]::new()
    $visited = [Collections.Generic.HashSet[int]]::new()
    $queue = [Collections.Generic.Queue[int]]::new()
    $queue.Enqueue($RootProcessId)
    while ($queue.Count -gt 0) {
        $parentProcessId = $queue.Dequeue()
        foreach ($child in @(
            Get-CimInstance `
                -ClassName Win32_Process `
                -Filter "ParentProcessId = $parentProcessId" `
                -ErrorAction SilentlyContinue
        )) {
            $childProcessId = [int]$child.ProcessId
            if ($childProcessId -eq $PID -or
                -not $visited.Add($childProcessId)) {
                continue
            }
            if ($null -ne $StartedAfter -and
                $null -ne $child.CreationDate -and
                ([datetime]$child.CreationDate).ToUniversalTime() -lt
                    $StartedAfter.ToUniversalTime().AddSeconds(-1)) {
                continue
            }
            $descendants.Add($childProcessId)
            $queue.Enqueue($childProcessId)
        }
    }
    return @($descendants)
}

function Get-QaProtectedProcessIds {
    param(
        [int[]] $ProcessIds = @(),
        [datetime] $StartedAfter
    )

    $protected = [Collections.Generic.HashSet[int]]::new()
    foreach ($processId in $ProcessIds) {
        if ($processId -le 0 -or
            $processId -eq $PID -or
            $null -eq (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            continue
        }
        $null = $protected.Add($processId)
        foreach ($descendantId in @(
            Get-QaDescendantProcessIds `
                -RootProcessId $processId `
                -StartedAfter $StartedAfter
        )) {
            $null = $protected.Add($descendantId)
        }
    }
    return @($protected)
}

function Stop-QaProcessTree {
    param(
        [int[]] $ProcessIds = @()
    )

    $cleaned = [Collections.Generic.List[int]]::new()
    foreach ($processId in @($ProcessIds | Sort-Object -Descending -Unique)) {
        if ($processId -le 0 -or $processId -eq $PID) {
            continue
        }
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        try {
            $process.Kill($true)
        } catch {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        } finally {
            $process.Dispose()
        }
        $cleaned.Add($processId)
    }
    return @($cleaned)
}

function Wait-QaProcessesGone {
    param(
        [int[]] $ProcessIds = @(),
        [int] $TimeoutMilliseconds = 3000
    )

    $deadline = (Get-Date).AddMilliseconds($TimeoutMilliseconds)
    do {
        $remaining = @(
            $ProcessIds |
                Where-Object {
                    $null -ne (Get-Process -Id $_ -ErrorAction SilentlyContinue)
                }
        )
        if ($remaining.Count -eq 0) {
            return $true
        }
        Start-Sleep -Milliseconds 50
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Invoke-QaBoundedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,

        [string[]] $Arguments = @(),

        [Parameter(Mandatory = $true)]
        [string] $StandardOutputPath,

        [Parameter(Mandatory = $true)]
        [string] $StandardErrorPath,

        [ValidateRange(1, 7200)]
        [int] $TimeoutSeconds = 120,

        [switch] $KillResidualProcesses,

        [int[]] $AllowedResidualProcessIds = @(),

        [string] $AllowedResidualMetadataPath,

        [string] $AllowedResidualSerial,

        [string] $AllowedResidualEvidenceRoot
    )

    $startedAt = Get-Date
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $process = $null
    $rootProcessId = 0
    $rootStartedAfter = $startedAt
    $exitCode = $null
    $timedOut = $false
    $failureKind = $null
    $failureMessage = $null
    $cleanedProcessIds = [Collections.Generic.List[int]]::new()
    $residualProcessIds = [Collections.Generic.List[int]]::new()
    $protectedProcessIds = @()

    try {
        $nativeArguments = @(
            $Arguments |
                ForEach-Object { ConvertTo-QaProcessArgument -Value ([string]$_) }
        )
        $process = Start-Process `
            -FilePath $FilePath `
            -ArgumentList ($nativeArguments -join ' ') `
            -WindowStyle Hidden `
            -PassThru `
            -RedirectStandardOutput $StandardOutputPath `
            -RedirectStandardError $StandardErrorPath
        $rootProcessId = [int]$process.Id
        $rootStartedAfter = $process.StartTime.ToUniversalTime()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $timedOut = $true
            $failureKind = 'TIMEOUT'
            $failureMessage = "Process exceeded ${TimeoutSeconds}s timeout"
        } else {
            $process.WaitForExit()
            $exitCode = [int]$process.ExitCode
        }
    } catch {
        $exitCode = 1
        $failureKind = 'LAUNCH_OR_WAIT_FAILURE'
        $failureMessage = $_.Exception.Message
    } finally {
        if ($rootProcessId -gt 0) {
            $allowedProcessIds =
                [Collections.Generic.List[int]]::new()
            foreach ($allowedProcessId in $AllowedResidualProcessIds) {
                $allowedProcessIds.Add($allowedProcessId)
            }
            if (-not [string]::IsNullOrWhiteSpace($AllowedResidualMetadataPath) -and
                -not [string]::IsNullOrWhiteSpace($AllowedResidualSerial) -and
                -not [string]::IsNullOrWhiteSpace($AllowedResidualEvidenceRoot)) {
                $allowedIdentity = Test-QaOwnedProcessMetadata `
                    -MetadataPath $AllowedResidualMetadataPath `
                    -ExpectedSerial $AllowedResidualSerial `
                    -ExpectedEvidenceRoot $AllowedResidualEvidenceRoot
                if ($allowedIdentity.valid -and
                    -not $allowedIdentity.alreadyExited -and
                    [int]$allowedIdentity.processId -gt 0) {
                    $allowedProcessIds.Add([int]$allowedIdentity.processId)
                }
            }
            $protectedProcessIds = @(
                Get-QaProtectedProcessIds `
                    -ProcessIds @($allowedProcessIds) `
                    -StartedAfter $rootStartedAfter
            )
            $treeBeforeCleanup = @(
                Get-QaDescendantProcessIds `
                    -RootProcessId $rootProcessId `
                    -StartedAfter $rootStartedAfter
            )
            if ($timedOut -or
                ($null -ne $process -and -not $process.HasExited)) {
                $root = Get-Process -Id $rootProcessId -ErrorAction SilentlyContinue
                if ($null -ne $root) {
                    try {
                        Stop-Process -Id $rootProcessId -Force -ErrorAction SilentlyContinue
                    } finally {
                        $root.Dispose()
                    }
                }
            }
            if ($KillResidualProcesses) {
                $cleanupTargets = @(
                    $treeBeforeCleanup |
                        Where-Object { $_ -notin $protectedProcessIds }
                )
                foreach ($cleanedProcessId in @(
                    Stop-QaProcessTree -ProcessIds $cleanupTargets
                )) {
                    $cleanedProcessIds.Add($cleanedProcessId)
                }
            }
            $residualProcessIds.AddRange([int[]]@(
                Get-QaDescendantProcessIds `
                    -RootProcessId $rootProcessId `
                    -StartedAfter $rootStartedAfter
            ))
            $unprotectedResidual = @(
                $residualProcessIds |
                    Where-Object { $_ -notin $protectedProcessIds }
            )
            if ($KillResidualProcesses -and
                $unprotectedResidual.Count -gt 0 -and
                -not (Wait-QaProcessesGone -ProcessIds $unprotectedResidual)) {
                if ($null -eq $failureKind) {
                    $failureKind = 'RESIDUAL_PROCESS'
                    $failureMessage =
                        "Residual processes survived: $($unprotectedResidual -join ',')"
                    $exitCode = 1
                }
            }
            if ($null -ne $process) {
                $process.Dispose()
            }
        }
    }
    $stopwatch.Stop()
    if ($timedOut) {
        $exitCode = 124
    }
    return [pscustomobject][ordered]@{
        startedAt = $startedAt.ToString('o')
        finishedAt = (Get-Date).ToString('o')
        durationMs = [long]$stopwatch.Elapsed.TotalMilliseconds
        processId = $rootProcessId
        timedOut = $timedOut
        exitCode = $exitCode
        failureKind = $failureKind
        failureMessage = $failureMessage
        allowedResidualProcessIds = @(
            if ($null -ne $allowedProcessIds) { $allowedProcessIds } else {
                $AllowedResidualProcessIds
            }
        )
        protectedProcessIds = @($protectedProcessIds)
        residualProcessIds = @($residualProcessIds)
        cleanedProcessIds = @($cleanedProcessIds)
        stdoutPath = $StandardOutputPath
        stderrPath = $StandardErrorPath
    }
}

function Invoke-QaMatrixStep {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[A-Za-z0-9._-]+$')]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $ScriptPath,

        [hashtable] $Arguments = @{},

        [ValidatePattern('^[A-Za-z0-9._-]+$')]
        [string] $Kind = 'device',

        [Parameter(Mandatory = $true)]
        [string] $RawRoot,

        [Parameter(Mandatory = $true)]
        [string] $PwshPath,

        [ValidateRange(1, 7200)]
        [int] $TimeoutSeconds = 120,

        [string] $AllowedResidualMetadataPath,

        [string] $AllowedResidualSerial,

        [string] $AllowedResidualEvidenceRoot
    )

    if (-not (Test-Path -LiteralPath $ScriptPath)) {
        throw "Matrix step script is missing: $ScriptPath"
    }
    $argumentTokens = [Collections.Generic.List[string]]::new()
    foreach ($key in @($Arguments.Keys | Sort-Object)) {
        $argumentTokens.Add("-$key")
        $argumentTokens.Add([string]$Arguments[$key])
    }
    $pwshArguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $ScriptPath
    ) + @($argumentTokens)
    $displayParts = [Collections.Generic.List[string]]::new()
    $displayParts.Add((Quote-QaCommandArgument -Value $PwshPath))
    foreach ($part in $pwshArguments) {
        $displayParts.Add((Quote-QaCommandArgument -Value $part))
    }
    $stdoutPath = Join-Path $RawRoot "$Name.stdout.log"
    $stderrPath = Join-Path $RawRoot "$Name.stderr.log"
    $bounded = Invoke-QaBoundedProcess `
        -FilePath $PwshPath `
        -Arguments $pwshArguments `
        -StandardOutputPath $stdoutPath `
        -StandardErrorPath $stderrPath `
        -TimeoutSeconds $TimeoutSeconds `
        -KillResidualProcesses `
        -AllowedResidualMetadataPath $AllowedResidualMetadataPath `
        -AllowedResidualSerial $AllowedResidualSerial `
        -AllowedResidualEvidenceRoot $AllowedResidualEvidenceRoot
    $status = if ($bounded.timedOut) {
        'FAIL_TIMEOUT'
    } elseif ($bounded.exitCode -eq 0 -and
        $null -eq $bounded.failureKind) {
        'PASS'
    } else {
        'FAIL'
    }
    return [pscustomobject][ordered]@{
        name = $Name
        kind = $Kind
        command = ($displayParts -join ' ')
        startedAt = $bounded.startedAt
        finishedAt = $bounded.finishedAt
        durationMs = $bounded.durationMs
        timeoutSeconds = $TimeoutSeconds
        exitCode = $bounded.exitCode
        status = $status
        timedOut = $bounded.timedOut
        failureKind = $bounded.failureKind
        failureMessage = $bounded.failureMessage
        processId = $bounded.processId
        cleanedProcessIds = @($bounded.cleanedProcessIds)
        allowedResidualProcessIds = @($bounded.allowedResidualProcessIds)
        protectedProcessIds = @($bounded.protectedProcessIds)
        residualProcessIds = @($bounded.residualProcessIds)
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
    }
}

function Test-QaOwnedProcessMetadata {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $MetadataPath,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSerial,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedEvidenceRoot
    )

    if (-not (Test-Path -LiteralPath $MetadataPath)) {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = 'METADATA_MISSING'
            processId = $null
        }
    }
    try {
        $metadata = Get-Content `
            -LiteralPath $MetadataPath `
            -Raw `
            -Encoding utf8 |
            ConvertFrom-Json -DateKind String
    } catch {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = 'METADATA_INVALID'
            processId = $null
        }
    }
    if ([string]$metadata.serial -ne $ExpectedSerial) {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = 'METADATA_SERIAL_MISMATCH'
            processId = $metadata.hostProcessId
        }
    }
    try {
        $root = [IO.Path]::GetFullPath(
            (Resolve-Path -LiteralPath $ExpectedEvidenceRoot).Path)
    } catch {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = 'EVIDENCE_ROOT_INVALID'
            processId = $metadata.hostProcessId
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$metadata.evidenceRoot) -or
        [IO.Path]::GetFullPath([string]$metadata.evidenceRoot) -ne $root) {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = 'METADATA_EVIDENCE_ROOT_MISMATCH'
            processId = $metadata.hostProcessId
        }
    }
    $processId = 0
    if (-not [int]::TryParse([string]$metadata.hostProcessId, [ref]$processId) -or
        $processId -le 0) {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = 'PROCESS_ID_INVALID'
            processId = $null
        }
    }
    foreach ($pathName in @('stdoutPath', 'stderrPath')) {
        $pathText = [string]$metadata.$pathName
        if ([string]::IsNullOrWhiteSpace($pathText)) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'OWNER_PATH_MISSING'
                processId = $processId
            }
        }
        $fullPath = [IO.Path]::GetFullPath($pathText)
        if (-not $fullPath.StartsWith(
            $root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'OWNER_PATH_OUTSIDE_EVIDENCE_ROOT'
                processId = $processId
            }
        }
    }
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return [pscustomobject][ordered]@{
            valid = $true
            reason = 'ALREADY_EXITED'
            processId = $processId
            alreadyExited = $true
        }
    }
    try {
        $recordedStart = [datetimeoffset]::MinValue
        if (-not [datetimeoffset]::TryParse(
            [string]$metadata.hostProcessStartedAtUtc,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$recordedStart)) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'PROCESS_START_TIME_INVALID'
                processId = $processId
            }
        }
        $actualStart = [datetimeoffset]::new(
            $process.StartTime.ToUniversalTime())
        if ([Math]::Abs(($actualStart - $recordedStart).TotalSeconds) -gt 3) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'PROCESS_START_TIME_MISMATCH'
                processId = $processId
                recordedStart = $recordedStart.ToString('o')
                actualStart = $actualStart.ToString('o')
            }
        }
        $cim = Get-CimInstance `
            -ClassName Win32_Process `
            -Filter "ProcessId = $processId" `
            -ErrorAction SilentlyContinue
        $commandLine = if ($null -ne $cim) {
            [string]$cim.CommandLine
        } else {
            ''
        }
        if ([string]::IsNullOrWhiteSpace($commandLine)) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'PROCESS_QUERY_FAILED'
                processId = $processId
            }
        }
        $expectedCommandLineSha256 =
            [string]$metadata.hostProcessCommandLineSha256
        if ($expectedCommandLineSha256 -notmatch '^[0-9a-fA-F]{64}$') {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'PROCESS_COMMAND_LINE_HASH_MISSING'
                processId = $processId
            }
        }
        $actualCommandLineSha256 = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData(
                [Text.Encoding]::UTF8.GetBytes($commandLine))).
            ToLowerInvariant()
        if ($actualCommandLineSha256 -ne
            $expectedCommandLineSha256.ToLowerInvariant()) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'PROCESS_COMMAND_LINE_HASH_MISMATCH'
                processId = $processId
                commandLine = $commandLine
            }
        }
        if (
            $commandLine -notmatch '\blogcat\b' -or
            $commandLine -notmatch [regex]::Escape($ExpectedSerial) -or
            $commandLine -notmatch [regex]::Escape([string]$metadata.adbExecutable)) {
            return [pscustomobject][ordered]@{
                valid = $false
                reason = 'PROCESS_COMMAND_LINE_MISMATCH'
                processId = $processId
                commandLine = $commandLine
            }
        }
        return [pscustomobject][ordered]@{
            valid = $true
            reason = 'OWNED_PROCESS_MATCH'
            processId = $processId
            alreadyExited = $false
            commandLine = $commandLine
        }
    } finally {
        $process.Dispose()
    }
}

function Stop-QaOwnedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $MetadataPath,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSerial,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedEvidenceRoot
    )

    $identity = Test-QaOwnedProcessMetadata `
        -MetadataPath $MetadataPath `
        -ExpectedSerial $ExpectedSerial `
        -ExpectedEvidenceRoot $ExpectedEvidenceRoot
    if (-not $identity.valid) {
        return [pscustomobject][ordered]@{
            stopped = $false
            alreadyExited = $false
            reason = $identity.reason
            processId = $identity.processId
            identity = $identity
        }
    }
    if ($identity.alreadyExited) {
        return [pscustomobject][ordered]@{
            stopped = $true
            alreadyExited = $true
            reason = 'ALREADY_EXITED'
            processId = $identity.processId
            identity = $identity
        }
    }
    $process = Get-Process -Id $identity.processId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return [pscustomobject][ordered]@{
            stopped = $true
            alreadyExited = $true
            reason = 'ALREADY_EXITED'
            processId = $identity.processId
            identity = $identity
        }
    }
    try {
        $revalidated = Test-QaOwnedProcessMetadata `
            -MetadataPath $MetadataPath `
            -ExpectedSerial $ExpectedSerial `
            -ExpectedEvidenceRoot $ExpectedEvidenceRoot
        if (-not $revalidated.valid) {
            return [pscustomobject][ordered]@{
                stopped = $false
                alreadyExited = $false
                reason = $revalidated.reason
                processId = $identity.processId
                identity = $revalidated
            }
        }
        if ($revalidated.alreadyExited) {
            return [pscustomobject][ordered]@{
                stopped = $true
                alreadyExited = $true
                reason = 'ALREADY_EXITED'
                processId = $identity.processId
                identity = $revalidated
            }
        }
        try {
            $process.Kill($true)
        } catch {
            return [pscustomobject][ordered]@{
                stopped = $false
                alreadyExited = $false
                reason = 'PROCESS_KILL_FAILED'
                processId = $identity.processId
                identity = $revalidated
            }
        }
    } finally {
        $process.Dispose()
    }
    $gone = Wait-QaProcessesGone `
        -ProcessIds @($identity.processId) `
        -TimeoutMilliseconds 3000
    return [pscustomobject][ordered]@{
        stopped = $gone
        alreadyExited = $false
        reason = if ($gone) { 'STOPPED_OWNED_PROCESS' } else { 'STOP_TIMEOUT' }
        processId = $identity.processId
        identity = $identity
    }
}
