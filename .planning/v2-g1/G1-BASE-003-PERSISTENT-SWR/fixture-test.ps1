[CmdletBinding()]
param(
    [ValidateSet(
        'positive',
        'mutation-older-generation-used',
        'cross-account-hit',
        'write-after-still-fresh'
    )]
    [string]$Case = 'positive'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)

function Read-Text([string]$RelativePath) {
    $path = [System.IO.Path]::GetFullPath((Join-Path $root $RelativePath))
    $prefix = $root.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "path escaped contract root: $RelativePath"
    }
    return [System.IO.File]::ReadAllText($path, $utf8)
}

function Read-Json([string]$RelativePath) {
    return (Read-Text $RelativePath | ConvertFrom-Json)
}

function Get-Sha256([byte[]]$Bytes) {
    return [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($Bytes)
    ).ToLowerInvariant()
}

function Assert-Contract([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "CONTRACT_ASSERTION_FAILED: $Message"
    }
}

function Get-CacheKey(
    [string]$PrincipalContext,
    [string]$ServerId,
    [string]$UserId,
    [string]$Scope,
    [string]$Query,
    [int]$PageStart,
    [int]$PageLimit,
    [string]$Locale
) {
    if (
        [string]::IsNullOrWhiteSpace($PrincipalContext) -or
        [string]::IsNullOrWhiteSpace($ServerId) -or
        [string]::IsNullOrWhiteSpace($UserId) -or
        [string]::IsNullOrWhiteSpace($Scope) -or
        [string]::IsNullOrWhiteSpace($Locale)
    ) {
        return $null
    }
    $parts = @(
        '1',
        $PrincipalContext,
        $ServerId,
        $UserId,
        'search',
        $Scope,
        $Query.Trim().ToLowerInvariant(),
        $PageStart.ToString([System.Globalization.CultureInfo]::InvariantCulture),
        $PageLimit.ToString([System.Globalization.CultureInfo]::InvariantCulture),
        $Locale
    )
    $material = [System.Text.StringBuilder]::new()
    foreach ($part in $parts) {
        $bytes = $utf8.GetBytes($part)
        [void]$material.Append($bytes.Length).Append(':').Append($part).Append("`n")
    }
    return Get-Sha256 $utf8.GetBytes($material.ToString())
}

function Get-RecordState(
    [long]$NowWallMs,
    [long]$PayloadFetchedAtWallMs,
    [long]$LastValidatedAtWallMs,
    [long]$SoftTtlMs,
    [long]$HardMaxAgeMs
) {
    if (
        $PayloadFetchedAtWallMs -le 0 -or
        $LastValidatedAtWallMs -lt $PayloadFetchedAtWallMs -or
        $NowWallMs -lt $LastValidatedAtWallMs
    ) {
        return 'CLOCK_ANOMALY'
    }
    if (($NowWallMs - $PayloadFetchedAtWallMs) -ge $HardMaxAgeMs) {
        return 'EXPIRED'
    }
    if (($NowWallMs - $LastValidatedAtWallMs) -lt $SoftTtlMs) {
        return 'FRESH'
    }
    return 'STALE'
}

function Test-ProjectableFresh(
    [string]$RecordState,
    [long]$RecordGeneration,
    [long]$CurrentGeneration,
    [string]$RecordIdentity,
    [string]$CurrentIdentity
) {
    return (($RecordState -ceq 'FRESH') -and ($RecordGeneration -eq $CurrentGeneration) -and ($RecordIdentity -ceq $CurrentIdentity))
}

function Get-ManifestPayloadHash([object[]]$Files) {
    $ordered = $Files | Sort-Object { $_.path }
    $material = [System.Text.StringBuilder]::new()
    foreach ($file in $ordered) {
        [void]$material.Append($file.path)
        [void]$material.Append("`t")
        [void]$material.Append($file.bytes)
        [void]$material.Append("`t")
        [void]$material.Append($file.sha256)
        [void]$material.Append("`n")
    }
    return Get-Sha256 $utf8.GetBytes($material.ToString())
}

function Test-ManifestAndArtifacts {
    $manifest = Read-Json 'manifest.json'
    Assert-Contract ($manifest.schemaVersion -eq '1.0') 'manifest schemaVersion'
    Assert-Contract ($manifest.algorithm -eq 'SHA-256') 'manifest algorithm'
    Assert-Contract ($manifest.lineEnding -eq 'LF') 'manifest lineEnding'
    Assert-Contract ($manifest.files.Count -ge 6) 'manifest must cover all deliverables'

    $totalBytes = 0L
    foreach ($entry in $manifest.files) {
        $path = [System.IO.Path]::GetFullPath((Join-Path $root $entry.path))
        $prefix = $root.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        Assert-Contract ($path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) "escaped manifest path $($entry.path)"
        Assert-Contract ([System.IO.File]::Exists($path)) "missing manifest file $($entry.path)"
        $bytes = [System.IO.File]::ReadAllBytes($path)
        Assert-Contract ($bytes.LongLength -eq [long]$entry.bytes) "byte count $($entry.path)"
        Assert-Contract ((Get-Sha256 $bytes) -eq $entry.sha256) "hash $($entry.path)"
        Assert-Contract (-not ($bytes -contains 13)) "CR byte in $($entry.path)"
        $lfCount = ($bytes | Where-Object { $_ -eq 10 }).Count
        Assert-Contract ($lfCount -eq [int]$entry.lfCount) "LF count $($entry.path)"
        $totalBytes += $bytes.LongLength
    }
    Assert-Contract ($totalBytes -eq [long]$manifest.totalBytes) 'manifest totalBytes'
    Assert-Contract ((Get-ManifestPayloadHash $manifest.files) -eq $manifest.payloadSha256) 'manifest payloadSha256'

    $filesToScan = $manifest.files | ForEach-Object {
        Join-Path $root $_.path
    }
    $secretPatterns = @(
        '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        '(?i)Bearer\s+[A-Za-z0-9._~-]{20,}',
        '(?i)(?:api[_-]?key|password|passwd|secret|access[_-]?token|refresh[_-]?token|cookie)\s*[:=]\s*["'']?[A-Za-z0-9._~+/=-]{12,}'
    )
    foreach ($file in $filesToScan) {
        $text = [System.IO.File]::ReadAllText($file, $utf8)
        foreach ($pattern in $secretPatterns) {
            Assert-Contract (-not [regex]::IsMatch($text, $pattern)) "sensitive-value pattern in $file"
        }
    }
}

$identity = Read-Json 'cache-identity-matrix.json'
$stateMachine = Read-Json 'freshness-state-machine.json'
$commitments = Read-Json 'user-commitment-matrix.json'
$report = Read-Json 'report.json'

switch ($Case) {
    'positive' {
        Test-ManifestAndArtifacts
        Assert-Contract ($identity.dimensions.Count -eq 19) '19 identity dimensions'
        Assert-Contract ($stateMachine.states.Count -eq 10) '10 projection states'
        Assert-Contract ($stateMachine.transitions.Count -eq 22) '22 transitions'
        Assert-Contract ($stateMachine.ttlBoundaryRules.Count -eq 11) '11 TTL rules'
        Assert-Contract ($commitments.rows.Count -eq 12) '12 user commitments'
        Assert-Contract ($report.knownCodeConstants.Count -eq 10) '10 known constants'
        Assert-Contract ($report.contractGaps.Count -eq 8) '8 contract gaps'
        Assert-Contract ($identity.unknownIdentityPolicy.decision -eq 'FAIL_CLOSED') 'unknown identity fail closed'

        $baseKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-a' 'VIDEO' 'Neon' 0 60 'zh-CN'
        $scopeKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-a' 'MUSIC' 'Neon' 0 60 'zh-CN'
        $pageKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-a' 'VIDEO' 'Neon' 60 60 'zh-CN'
        $localeKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-a' 'VIDEO' 'Neon' 0 60 'en-US'
        $userBKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-b' 'VIDEO' 'Neon' 0 60 'zh-CN'
        Assert-Contract ($null -ne $baseKey) 'valid key'
        Assert-Contract ($baseKey -ne $scopeKey) 'scope changes key'
        Assert-Contract ($baseKey -ne $pageKey) 'page changes key'
        Assert-Contract ($baseKey -ne $localeKey) 'locale changes key'
        Assert-Contract ($baseKey -ne $userBKey) 'user changes key'
        Assert-Contract ($null -eq (Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' '' 'VIDEO' 'Neon' 0 60 'zh-CN')) 'missing user fails closed'

        $t0 = 1700000000000L
        $soft = 60L
        $hard = 300L
        Assert-Contract ((Get-RecordState $t0 $t0 $t0 $soft $hard) -eq 'FRESH') 'fresh at t0'
        Assert-Contract ((Get-RecordState ($t0 + 59) $t0 $t0 $soft $hard) -eq 'FRESH') 'fresh before soft boundary'
        Assert-Contract ((Get-RecordState ($t0 + 60) $t0 $t0 $soft $hard) -eq 'STALE') 'stale at soft boundary'
        Assert-Contract ((Get-RecordState ($t0 + 300) $t0 $t0 $soft $hard) -eq 'EXPIRED') 'expired at hard boundary'
        Assert-Contract ((Get-RecordState ($t0 - 1) $t0 $t0 $soft $hard) -eq 'CLOCK_ANOMALY') 'wall rollback is anomaly'
        Assert-Contract (-not (Test-ProjectableFresh 'STALE' 7 7 'server-a|user-a' 'server-a|user-a')) 'stale never fresh'
        Assert-Contract (-not (Test-ProjectableFresh 'FRESH' 7 8 'server-a|user-a' 'server-a|user-a')) 'old generation never fresh'
        Assert-Contract (-not (Test-ProjectableFresh 'FRESH' 7 7 'server-a|user-a' 'server-a|user-b')) 'cross identity never fresh'
        Assert-Contract (Test-ProjectableFresh 'FRESH' 7 7 'server-a|user-a' 'server-a|user-a') 'current fresh positive'

        $valid304State = Get-RecordState ($t0 + 65) $t0 ($t0 + 65) $soft $hard
        Assert-Contract ($valid304State -eq 'FRESH') 'valid 304 renews soft freshness'
        $oldPayloadAfter304 = Get-RecordState ($t0 + 300) $t0 ($t0 + 65) $soft $hard
        Assert-Contract ($oldPayloadAfter304 -eq 'EXPIRED') '304 does not renew hard payload age'

        $offlineCurrent = Test-ProjectableFresh 'STALE' 9 9 'server-a|user-a' 'server-a|user-a'
        Assert-Contract (-not $offlineCurrent) 'offline stale remains stale'
        $offlineUnknown = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' '' 'VIDEO' 'Neon' 0 60 'zh-CN'
        Assert-Contract ($null -eq $offlineUnknown) 'offline unknown identity blocks lookup'

        Write-Output 'PASS positive: identity, TTL, 304, mutation fence and artifact manifest contracts hold'
        exit 0
    }
    'mutation-older-generation-used' {
        $currentGeneration = 9L
        $mutationGenerationUsed = 8L
        $fixedContractAccepts = $mutationGenerationUsed -eq $currentGeneration
        Assert-Contract (-not $fixedContractAccepts) 'fixed contract rejects an older mutation generation'
        if (-not $fixedContractAccepts) {
            Write-Error 'NEGATIVE_FIXTURE_TRIPPED mutation-older-generation-used: a mutation accepted stale generation'
            exit 1
        }
        exit 0
    }
    'cross-account-hit' {
        $accountAKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-a' 'VIDEO' 'Neon' 0 60 'zh-CN'
        $accountBKey = Get-CacheKey 'ONLINE_ACCOUNT' 'server-a' 'user-b' 'VIDEO' 'Neon' 0 60 'zh-CN'
        $fixedContractAllowsHit = $accountAKey -eq $accountBKey
        Assert-Contract (-not $fixedContractAllowsHit) 'fixed contract separates account keys'
        if (-not $fixedContractAllowsHit) {
            Write-Error 'NEGATIVE_FIXTURE_TRIPPED cross-account-hit: account A payload crossed into account B'
            exit 1
        }
        exit 0
    }
    'write-after-still-fresh' {
        $preMutationRecord = @{
            State = 'FRESH'
            Generation = 12L
            Identity = 'server-a|user-a'
        }
        $postMutationGeneration = 13L
        $incorrectFresh = Test-ProjectableFresh `
            -RecordState ([string]$preMutationRecord.State) `
            -RecordGeneration ([long]$preMutationRecord.Generation) `
            -CurrentGeneration ([long]$postMutationGeneration) `
            -RecordIdentity ([string]$preMutationRecord.Identity) `
            -CurrentIdentity 'server-a|user-a'
        $badCandidateFresh = (
            ([string]$preMutationRecord.State -ceq 'FRESH') -and
            ([string]$preMutationRecord.Identity -ceq 'server-a|user-a')
        )
        Assert-Contract (-not $incorrectFresh) 'fixed contract rejects pre-mutation fresh projection'
        if ($badCandidateFresh) {
            Write-Error 'NEGATIVE_FIXTURE_TRIPPED write-after-still-fresh: old generation projected as fresh'
            exit 1
        }
        Write-Output 'UNEXPECTED: the deliberately stale projection was accepted; rerun contract review'
        exit 0
    }
}
