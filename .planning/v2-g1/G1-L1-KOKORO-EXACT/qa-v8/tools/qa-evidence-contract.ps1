function Get-QaOrdinalSortedStrings {
    param([string[]] $Values = @())

    $sorted = [string[]]@($Values)
    [Array]::Sort($sorted, [StringComparer]::Ordinal)
    return @($sorted)
}

function Get-QaRelativePath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    return [IO.Path]::GetRelativePath($Root, $Path).Replace('\', '/')
}

function ConvertTo-QaCommandsJson {
    [CmdletBinding()]
    param(
        [object[]] $Entries = @()
    )

    return (
        (ConvertTo-Json `
            -InputObject @($Entries) `
            -Depth 20 `
            -AsArray) + "`n"
    )
}

function ConvertTo-QaRunId {
    param([Parameter(Mandatory = $true)][string] $RunId)

    $match = [regex]::Match(
        $RunId.Trim(),
        '^QA[-_]?V(?<version>\d+)$',
        [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) {
        throw "Invalid QA run id: $RunId"
    }
    return 'QA-V' + $match.Groups['version'].Value
}

function Resolve-QaRunId {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $EvidenceRoot,

        [string] $RequestedRunId
    )

    $fullPath = [IO.Path]::GetFullPath($EvidenceRoot)
    $derivedRunId = $null
    foreach ($segment in @($fullPath -split '[\\/]+')) {
        if ($segment -match '^QA[-_]?V\d+$') {
            $derivedRunId = ConvertTo-QaRunId -RunId $segment
        }
    }
    $resolvedRunId = if ([string]::IsNullOrWhiteSpace($RequestedRunId)) {
        if ($null -eq $derivedRunId) {
            throw "Cannot derive QA run id from evidence root: $fullPath"
        }
        $derivedRunId
    } else {
        ConvertTo-QaRunId -RunId $RequestedRunId
    }
    if ($null -ne $derivedRunId -and $derivedRunId -ne $resolvedRunId) {
        throw "QA run id $resolvedRunId does not match evidence root run $derivedRunId"
    }
    return $resolvedRunId
}

function Test-QaRunLock {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $DeviceLockPath,

        [Parameter(Mandatory = $true)]
        [string] $Serial,

        [Parameter(Mandatory = $true)]
        [string] $RunId
    )

    $resolvedRunId = ConvertTo-QaRunId -RunId $RunId
    $active = @()
    if (Test-Path -LiteralPath $DeviceLockPath) {
        $active = @(
            [IO.File]::ReadAllLines($DeviceLockPath, [Text.Encoding]::UTF8) |
                Where-Object { $_ -match '^### .* — ACTIVE\s*$' }
        )
    }
    $matching = @(
        $active |
            Where-Object {
                $runTokens = @(
                    [regex]::Matches(
                        $_,
                        '(?i)(?<![A-Za-z0-9])QA[-_]?V(?<version>\d+)(?![A-Za-z0-9])') |
                        ForEach-Object {
                            'QA-V' + $_.Groups['version'].Value
                        } |
                        Select-Object -Unique
                )
                $runTokens.Count -eq 1 -and
                $runTokens[0] -eq $resolvedRunId -and
                $_ -match (
                    '(?i)(?<![A-Za-z0-9])' +
                    [regex]::Escape($Serial) +
                    '(?![A-Za-z0-9])')
            }
    )
    $reasons = [Collections.Generic.List[string]]::new()
    if (-not (Test-Path -LiteralPath $DeviceLockPath)) {
        $reasons.Add('DEVICE_LOCK_MISSING')
    }
    if ($active.Count -ne 1) {
        $reasons.Add('ACTIVE_LOCK_COUNT_NOT_ONE')
    }
    if ($matching.Count -ne 1) {
        $reasons.Add('CURRENT_RUN_LOCK_MISSING')
    }
    return [pscustomobject][ordered]@{
        schema = 'aurorama.kokoro-qa.run-lock.v1'
        runId = $resolvedRunId
        serial = $Serial
        deviceLockPath = [IO.Path]::GetFullPath($DeviceLockPath)
        activeCount = $active.Count
        matchingRunCount = $matching.Count
        passed = $reasons.Count -eq 0
        reason = @($reasons)
        activeTitles = @($active)
    }
}

function Get-QaLongRunSegments {
    [CmdletBinding()]
    param(
        [ValidateRange(60, 86400)]
        [int] $TargetSeconds
    )

    $firstStart = 0
    $firstEnd = [Math]::Min(60, $TargetSeconds)
    $middleStart = [Math]::Max(
        60,
        [int][Math]::Floor($TargetSeconds / 2.0) - 30)
    $middleEnd = [Math]::Min($TargetSeconds, $middleStart + 60)
    $finalStart = [Math]::Max(0, $TargetSeconds - 60)
    $finalEnd = $TargetSeconds
    return [pscustomobject][ordered]@{
        targetSeconds = $TargetSeconds
        first = [pscustomobject][ordered]@{
            startSeconds = $firstStart
            endSeconds = $firstEnd
            label = "$firstStart-$firstEnd"
        }
        middle = [pscustomobject][ordered]@{
            startSeconds = $middleStart
            endSeconds = $middleEnd
            label = "$middleStart-$middleEnd"
        }
        final = [pscustomobject][ordered]@{
            startSeconds = $finalStart
            endSeconds = $finalEnd
            label = "$finalStart-$finalEnd"
        }
    }
}

function Test-QaCurrentRunCrashGate {
    [OutputType([bool])]
    param(
        [bool] $CurrentRunWindowValid,
        [bool] $SourceQueriesPassed,
        [int] $FDebugCount,
        [int] $NativeTombstoneCount,
        [int] $JavaFatalCount,
        [int] $AnrCount,
        [int] $InputTimeoutCount,
        [int] $Reason5Count
    )

    return (
        $CurrentRunWindowValid -and
        $SourceQueriesPassed -and
        $FDebugCount -eq 0 -and
        $NativeTombstoneCount -eq 0 -and
        $JavaFatalCount -eq 0 -and
        $AnrCount -eq 0 -and
        $InputTimeoutCount -eq 0 -and
        $Reason5Count -eq 0
    )
}

function Test-QaFinalizeSafetyGate {
    [OutputType([bool])]
    param(
        [bool] $Blocked,
        [bool] $CrashGatePassed,
        [bool] $CleanupArtifactsPassed,
        [bool] $RestorePassed,
        [bool] $SpeakerGatePassed,
        [bool] $UidNetworkZero
    )

    return (
        -not $Blocked -and
        $CrashGatePassed -and
        $CleanupArtifactsPassed -and
        $RestorePassed -and
        $SpeakerGatePassed -and
        $UidNetworkZero
    )
}

function Get-QaEvidencePayloadFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root
    )

    $rootPath = [IO.Path]::GetFullPath($Root)
    $toolsPath = Join-Path $rootPath 'tools'
    return @(
        Get-ChildItem -LiteralPath $rootPath -Recurse -File -Force |
            Where-Object {
                -not $_.FullName.Equals(
                    $toolsPath,
                    [StringComparison]::OrdinalIgnoreCase) -and
                -not $_.FullName.StartsWith(
                    $toolsPath + [IO.Path]::DirectorySeparatorChar,
                    [StringComparison]::OrdinalIgnoreCase)
            }
    )
}

function Get-QaManifestPayloadFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [string[]] $ExcludedRelativePaths = @(
            'SHA256SUMS',
            'final-seal.json'
        )
    )

    $rootPath = [IO.Path]::GetFullPath($Root)
    $filesByRelativePath = @{}
    foreach ($file in @(Get-QaEvidencePayloadFiles -Root $rootPath)) {
        $relative = Get-QaRelativePath -Root $rootPath -Path $file.FullName
        if ($relative -notin $ExcludedRelativePaths) {
            $filesByRelativePath[$relative] = $file
        }
    }
    $relativePaths = Get-QaOrdinalSortedStrings -Values @($filesByRelativePath.Keys)
    return @(
        $relativePaths |
            ForEach-Object {
                [pscustomobject][ordered]@{
                    relativePath = $_
                    file = $filesByRelativePath[$_]
                }
            }
    )
}

function Write-QaEvidenceManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [string[]] $ExcludedRelativePaths = @(
            'SHA256SUMS',
            'final-seal.json'
        )
    )

    $rootPath = [IO.Path]::GetFullPath($Root)
    $manifestPath = Join-Path $rootPath 'SHA256SUMS'
    $payloadFiles = @(
        Get-QaManifestPayloadFiles `
            -Root $rootPath `
            -ExcludedRelativePaths $ExcludedRelativePaths
    )
    $lines = @(
        $payloadFiles |
            ForEach-Object {
                $hash = (Get-FileHash `
                    -LiteralPath $_.file.FullName `
                    -Algorithm SHA256).
                    Hash.
                    ToLowerInvariant()
                "$hash  $($_.relativePath)"
            }
    )
    $text = if ($lines.Count -gt 0) {
        ($lines -join "`n") + "`n"
    } else {
        ''
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($text)
    [IO.File]::WriteAllBytes($manifestPath, $bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $manifestSha256 = [Convert]::ToHexString(
            $sha.ComputeHash($bytes)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    return [pscustomobject][ordered]@{
        path = $manifestPath
        sha256 = $manifestSha256
        entryCount = $lines.Count
        entries = @($payloadFiles | ForEach-Object relativePath)
        text = $text
        excludedRelativePaths = @($ExcludedRelativePaths | Sort-Object)
    }
}

function Test-QaEvidenceManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [string] $ManifestPath,

        [string[]] $ExcludedRelativePaths = @(
            'SHA256SUMS',
            'final-seal.json'
        )
    )

    $rootPath = [IO.Path]::GetFullPath($Root)
    if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
        $ManifestPath = Join-Path $rootPath 'SHA256SUMS'
    }
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        return [pscustomobject][ordered]@{
            valid = $false
            errors = @('MANIFEST_MISSING')
            entries = @()
        }
    }
    $errors = [Collections.Generic.List[string]]::new()
    $entries = [Collections.Generic.List[string]]::new()
    $hashes = @{}
    $manifestUri = [IO.Path]::GetFullPath($ManifestPath)
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines(
        $manifestUri,
        [Text.Encoding]::UTF8)) {
        $lineNumber++
        if ($line -notmatch '^([0-9a-f]{64})  ([^\r\n]+)$') {
            $errors.Add("MANIFEST_LINE_INVALID:$lineNumber")
            continue
        }
        $relative = $Matches[2]
        if ($entries.Contains($relative)) {
            $errors.Add("MANIFEST_DUPLICATE:$relative")
            continue
        }
        if ($relative -in $ExcludedRelativePaths) {
            $errors.Add("MANIFEST_INCLUDES_EXCLUDED:$relative")
            continue
        }
        $entries.Add($relative)
        $hashes[$relative] = $Matches[1]
    }
    $payloadFiles = @(
        Get-QaManifestPayloadFiles `
            -Root $rootPath `
            -ExcludedRelativePaths $ExcludedRelativePaths
    )
    $expectedEntries = @($payloadFiles | ForEach-Object relativePath)
    foreach ($expected in $expectedEntries) {
        if (-not $hashes.ContainsKey($expected)) {
            $errors.Add("MANIFEST_MISSING_ENTRY:$expected")
            continue
        }
        $actualHash = (Get-FileHash `
            -LiteralPath (Join-Path $rootPath $expected) `
            -Algorithm SHA256).
            Hash.
            ToLowerInvariant()
        if ($hashes[$expected] -ne $actualHash) {
            $errors.Add("MANIFEST_HASH_MISMATCH:$expected")
        }
    }
    foreach ($entry in $entries) {
        if ($entry -notin $expectedEntries) {
            $errors.Add("MANIFEST_UNEXPECTED_ENTRY:$entry")
        }
    }
    if ((@($entries) -join "`n") -ne (@($expectedEntries) -join "`n")) {
        $errors.Add(
            'MANIFEST_ORDER_OR_SET_MISMATCH:' +
            'entries=' + (@($entries) -join ',') +
            ';expected=' + (@($expectedEntries) -join ','))
    }
    return [pscustomobject][ordered]@{
        valid = $errors.Count -eq 0
        errors = @($errors)
        entries = @($entries)
        expectedEntries = @($expectedEntries)
        path = $manifestUri
    }
}

function Write-QaCommandsFreeze {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $EvidenceRoot
    )

    $rootPath = (Resolve-Path -LiteralPath $EvidenceRoot).Path
    $rawRoot = Join-Path $rootPath 'raw'
    $commandsJsonPath = Join-Path $rawRoot 'commands.json'
    $commandsLogPath = Join-Path $rawRoot 'commands.log'
    $freezePath = Join-Path $rawRoot 'commands-frozen.json'
    foreach ($path in @($commandsJsonPath, $commandsLogPath)) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Commands cannot be frozen; file is missing: $path"
        }
    }
    $commands = Get-Content `
        -LiteralPath $commandsJsonPath `
        -Raw `
        -Encoding utf8 |
        ConvertFrom-Json -DateKind String
    $entryCount = @($commands).Count
    $freeze = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.commands-freeze.v1'
        frozenAt = (Get-Date).ToString('o')
        entryCount = $entryCount
        commandsJson = [ordered]@{
            path = 'raw/commands.json'
            sha256 = (Get-FileHash `
                -LiteralPath $commandsJsonPath `
                -Algorithm SHA256).
                Hash.
                ToLowerInvariant()
        }
        commandsLog = [ordered]@{
            path = 'raw/commands.log'
            sha256 = (Get-FileHash `
                -LiteralPath $commandsLogPath `
                -Algorithm SHA256).
                Hash.
                ToLowerInvariant()
        }
    }
    [IO.File]::WriteAllText(
        $freezePath,
        (($freeze | ConvertTo-Json -Depth 8) + "`n"),
        [Text.UTF8Encoding]::new($false))
    return [pscustomobject]$freeze
}

function Test-QaCommandsFreeze {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $EvidenceRoot
    )

    $rootPath = (Resolve-Path -LiteralPath $EvidenceRoot).Path
    $rawRoot = Join-Path $rootPath 'raw'
    $freezePath = Join-Path $rawRoot 'commands-frozen.json'
    $reasons = [Collections.Generic.List[string]]::new()
    if (-not (Test-Path -LiteralPath $freezePath)) {
        return [pscustomobject][ordered]@{
            valid = $false
            reason = @('COMMANDS_FREEZE_MISSING')
            freezePath = $freezePath
        }
    }
    $freeze = Get-Content `
        -LiteralPath $freezePath `
        -Raw `
        -Encoding utf8 |
        ConvertFrom-Json -DateKind String
    $commandsJsonPath = Join-Path $rawRoot 'commands.json'
    $commandsLogPath = Join-Path $rawRoot 'commands.log'
    foreach ($spec in @(
        @{ Path = $commandsJsonPath; Expected = [string]$freeze.commandsJson.sha256; Name = 'COMMANDS_JSON' },
        @{ Path = $commandsLogPath; Expected = [string]$freeze.commandsLog.sha256; Name = 'COMMANDS_LOG' }
    )) {
        if (-not (Test-Path -LiteralPath $spec.Path)) {
            $reasons.Add("$($spec.Name)_MISSING")
            continue
        }
        $actual = (Get-FileHash `
            -LiteralPath $spec.Path `
            -Algorithm SHA256).
            Hash.
            ToLowerInvariant()
        if ($actual -ne $spec.Expected) {
            $reasons.Add("$($spec.Name)_HASH_MISMATCH")
        }
    }
    try {
        $commands = Get-Content `
            -LiteralPath $commandsJsonPath `
            -Raw `
            -Encoding utf8 |
            ConvertFrom-Json -DateKind String
        if (@($commands).Count -ne [int]$freeze.entryCount) {
            $reasons.Add('COMMANDS_ENTRY_COUNT_MISMATCH')
        }
    } catch {
        $reasons.Add('COMMANDS_JSON_INVALID')
    }
    return [pscustomobject][ordered]@{
        valid = $reasons.Count -eq 0
        reason = @($reasons)
        freezePath = $freezePath
        entryCount = $freeze.entryCount
    }
}

function Get-QaToolDigest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $ToolsRoot
    )

    $rootPath = (Resolve-Path -LiteralPath $ToolsRoot).Path
    $files = @(
        Get-ChildItem -LiteralPath $rootPath -Recurse -File -Force |
            Where-Object { $_.Name -ne 'tools.sha256' }
    )
    $byRelativePath = @{}
    foreach ($file in $files) {
        $relative = Get-QaRelativePath -Root $rootPath -Path $file.FullName
        $byRelativePath[$relative] = $file
    }
    $entries = Get-QaOrdinalSortedStrings -Values @($byRelativePath.Keys)
    $lines = @(
        $entries |
            ForEach-Object {
                $hash = (Get-FileHash `
                    -LiteralPath $byRelativePath[$_].FullName `
                    -Algorithm SHA256).
                    Hash.
                    ToLowerInvariant()
                "$hash  tools/$_"
            }
    )
    $text = if ($lines.Count -gt 0) {
        ($lines -join "`n") + "`n"
    } else {
        ''
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $aggregate = [Convert]::ToHexString(
            $sha.ComputeHash($bytes)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    return [pscustomobject][ordered]@{
        sha256 = $aggregate
        entryCount = $entries.Count
        entries = @($entries)
        text = $text
        sortedBy = 'relative path ordinal'
        selfExcluded = $true
    }
}

function Test-QaToolDigestManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $ToolsRoot,

        [Parameter(Mandatory = $true)]
        [string] $ManifestPath
    )

    $digest = Get-QaToolDigest -ToolsRoot $ToolsRoot
    $reasons = [Collections.Generic.List[string]]::new()
    $manifestBytes = [byte[]]@()
    $manifestText = ''
    $manifestHash = $null
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        $reasons.Add('MANIFEST_MISSING')
    } else {
        $manifestBytes = [IO.File]::ReadAllBytes($ManifestPath)
        $manifestHash = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($manifestBytes)).
            ToLowerInvariant()
        if ($manifestBytes.Length -ge 3 -and
            $manifestBytes[0] -eq 0xEF -and
            $manifestBytes[1] -eq 0xBB -and
            $manifestBytes[2] -eq 0xBF) {
            $reasons.Add('MANIFEST_UTF8_BOM')
        }
        if ($manifestBytes -contains 0x0D) {
            $reasons.Add('MANIFEST_CR_LINE_ENDING')
        }
        try {
            $manifestText = [Text.UTF8Encoding]::new($false, $true).
                GetString($manifestBytes)
        } catch {
            $reasons.Add('MANIFEST_INVALID_UTF8')
        }
        if ($manifestText.Length -eq 0 -or
            -not $manifestText.EndsWith("`n", [StringComparison]::Ordinal)) {
            $reasons.Add('MANIFEST_TRAILING_LF_MISSING')
        }
        if ($manifestText -cne $digest.text) {
            $reasons.Add('MANIFEST_DIGEST_MISMATCH')
        }
    }
    return [pscustomobject][ordered]@{
        valid = $reasons.Count -eq 0
        reason = @($reasons)
        manifestPath = [IO.Path]::GetFullPath($ManifestPath)
        manifestSha256 = $manifestHash
        entryCount = $digest.entryCount
        digestSha256 = $digest.sha256
        digestText = $digest.text
    }
}

function Write-QaToolDigestSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $EvidenceRoot,

        [Parameter(Mandatory = $true)]
        [string] $ToolsRoot,

        [Parameter(Mandatory = $true)]
        [string] $CanonicalManifestPath
    )

    $verification = Test-QaToolDigestManifest `
        -ToolsRoot $ToolsRoot `
        -ManifestPath $CanonicalManifestPath
    if (-not $verification.valid) {
        throw (
            'Canonical tool manifest verification failed: ' +
            ($verification.reason -join ', '))
    }
    $snapshotPath = Join-Path $EvidenceRoot 'tools.sha256'
    $canonicalFullPath = [IO.Path]::GetFullPath($CanonicalManifestPath)
    $snapshotFullPath = [IO.Path]::GetFullPath($snapshotPath)
    if ($canonicalFullPath.Equals(
        $snapshotFullPath,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Current-run tool snapshot cannot overwrite the canonical manifest'
    }
    [IO.File]::WriteAllText(
        $snapshotFullPath,
        $verification.digestText,
        [Text.UTF8Encoding]::new($false))
    return [pscustomobject][ordered]@{
        schema = 'aurorama.kokoro-qa.tool-digest-snapshot.v1'
        canonicalPath = $canonicalFullPath
        canonicalSha256 = $verification.manifestSha256
        verificationManifestPath = $snapshotFullPath
        aggregateSha256 = $verification.digestSha256
        entryCount = $verification.entryCount
        sortedBy = 'relative path ordinal'
        selfExcluded = $true
        runtimeWritesCanonical = $false
    }
}
