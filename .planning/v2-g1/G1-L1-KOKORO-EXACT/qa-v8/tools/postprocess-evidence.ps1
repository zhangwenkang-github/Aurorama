[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $EvidenceRoot,

    [string[]] $Packages = @(
        'dev.aurorama.kokoro.harness',
        'dev.aurorama.kokoro.focuspeer',
        'io.github.zhangwenkang.aurorama',
        'io.github.zhangwenkang.aurorama.debug'
    ),

    [string[]] $Tags = @(
        'KOKORO_RUNTIME',
        'KokoroRuntimeService'
    )
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $EvidenceRoot).Path
$utf8 = [Text.UTF8Encoding]::new($false)
$toolsPath = Join-Path $root 'tools'
$postprocessPath = Join-Path $root 'postprocess.json'
$securityScanPath = Join-Path $root 'security-scan.json'
$manifestPath = Join-Path $root 'SHA256SUMS'
$secretNamePattern =
    '^(?:authorization|cookie|set-cookie|api[_-]?key|apikey|password|passwd|secret|credential|token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token)$'
$secretValuePatterns = [ordered]@{
    authorization = '(?im)\bAuthorization\s*[:=]\s*(?!(?:Bearer\s+|Basic\s+)?\[REDACTED\])(?:Bearer\s+|Basic\s+)?[^\s,;"'']+'
    cookie = '(?im)\b(?:Cookie|Set-Cookie)\s*:\s*(?!"\[REDACTED\]")(?!\[REDACTED\])[^\r\n]+'
    apiKey = '(?im)\b(?:api[_-]?key|apikey)\s*[:=]\s*(?!"\[REDACTED\]")(?!\[REDACTED\])(?:"[^"]+"|''[^'']+''|[^\s,}]+)'
    password = '(?im)\b(?:password|passwd)\s*[:=]\s*(?!"\[REDACTED\]")(?!\[REDACTED\])(?:"[^"]+"|''[^'']+''|[^\s,}]+)'
    secret = '(?im)\bsecret\s*[:=]\s*(?!"\[REDACTED\]")(?!\[REDACTED\])(?:"[^"]+"|''[^'']+''|[^\s,}]+)'
    credential = '(?im)\bcredential\s*[:=]\s*(?!"\[REDACTED\]")(?!\[REDACTED\])(?:"[^"]+"|''[^'']+''|[^\s,}]+)'
    token = '(?im)\btoken\s*[:=]\s*(?!"\[REDACTED\]")(?!\[REDACTED\])(?:"[^"]+"|''[^'']+''|[^\s,}]+)'
    accessToken = '(?im)\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token)\s*[:=]\s*(?!"\[REDACTED\]")(?!\[REDACTED\])(?:"[^"]+"|''[^'']+''|[^\s,}]+)'
    bearer = '(?im)\bBearer\s+[A-Za-z0-9._~+/=-]{12,}'
}

function Test-EvidenceFile([IO.FileInfo] $File) {
    return -not ($File.FullName -eq $toolsPath -or
        $File.FullName.StartsWith(
            $toolsPath + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase))
}

function Get-EvidenceFiles {
    return @(
        Get-ChildItem -LiteralPath $root -Recurse -File -Force |
            Where-Object { Test-EvidenceFile $_ }
    )
}

function Get-TextFiles {
    $extensions = @('.json', '.log', '.txt', '.xml', '.jsonl')
    return @(
        Get-EvidenceFiles |
            Where-Object { $_.Extension.ToLowerInvariant() -in $extensions }
    )
}

function Protect-EvidenceValue($Value) {
    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [string] -or
        $Value -is [ValueType]) {
        return $Value
    }
    if ($Value -is [Collections.IDictionary]) {
        $copy = [ordered]@{}
        foreach ($key in $Value.Keys) {
            if ([string]$key -match $secretNamePattern) {
                $copy[$key] = '[REDACTED]'
            } else {
                $copy[$key] = Protect-EvidenceValue $Value[$key]
            }
        }
        return $copy
    }
    if ($Value -is [Collections.IEnumerable]) {
        return @($Value | ForEach-Object { Protect-EvidenceValue $_ })
    }
    $copy = [ordered]@{}
    foreach ($property in $Value.PSObject.Properties) {
        if ($property.Name -match $secretNamePattern) {
            $copy[$property.Name] = '[REDACTED]'
        } else {
            $copy[$property.Name] = Protect-EvidenceValue $property.Value
        }
    }
    return $copy
}

function Replace-PathValue($Value, [hashtable] $Replacements) {
    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [string]) {
        $result = [string]$Value
        foreach ($old in $Replacements.Keys) {
            $result = $result.Replace($old, [string]$Replacements[$old])
        }
        return $result
    }
    if ($Value -is [ValueType]) {
        return $Value
    }
    if ($Value -is [Collections.IDictionary]) {
        $copy = [ordered]@{}
        foreach ($key in $Value.Keys) {
            $copy[$key] = Replace-PathValue $Value[$key] $Replacements
        }
        return $copy
    }
    if ($Value -is [Collections.IEnumerable]) {
        return @($Value | ForEach-Object {
            Replace-PathValue $_ $Replacements
        })
    }
    $copy = [ordered]@{}
    foreach ($property in $Value.PSObject.Properties) {
        $copy[$property.Name] =
            Replace-PathValue $property.Value $Replacements
    }
    return $copy
}

function Remove-SecretValues([string] $Text) {
    $result = $Text
    $result = [regex]::Replace(
        $result,
        '(?im)(\bAuthorization\s*[:=]\s*)(?:Bearer\s+|Basic\s+)?[^\s,;"'']+',
        '$1[REDACTED]')
    $result = [regex]::Replace(
        $result,
        '(?im)(\b(?:Cookie|Set-Cookie)\s*:\s*)[^\r\n]+',
        '$1[REDACTED]')
    $result = [regex]::Replace(
        $result,
        '(?im)("(?:authorization|cookie|set-?cookie|api[_-]?key|apikey|password|passwd|secret|credential|token|access[_-]?token|refresh[_-]?token|id[_-]?token)"\s*:\s*)"[^"]*"',
        '$1"[REDACTED]"')
    $result = [regex]::Replace(
        $result,
        '(?im)\b((?:api[_-]?key|apikey|password|passwd|secret|credential|token|access[_-]?token|refresh[_-]?token|id[_-]?token)\s*[:=]\s*)(?:"[^"]+"|''[^'']+''|[^\s,;}]+)',
        '$1[REDACTED]')
    $result = [regex]::Replace(
        $result,
        '(?im)\bBearer\s+[A-Za-z0-9._~+/=-]{12,}',
        'Bearer [REDACTED]')
    return $result
}

function Write-NormalizedText([string] $Path, [string] $Text) {
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($Path, $normalized, $utf8)
}

function Get-SecretScanHits([string] $Text) {
    $hits = [Collections.Generic.List[object]]::new()
    foreach ($entry in $secretValuePatterns.GetEnumerator()) {
        $count = [regex]::Matches($Text, $entry.Value).Count
        if ($count -gt 0) {
            $hits.Add([ordered]@{
                pattern = $entry.Key
                count = $count
            })
        }
    }
    return @($hits)
}

$allInitialFiles = Get-EvidenceFiles
$packagePatterns = @(
    $Packages |
        ForEach-Object { [regex]::Escape($_) }
) -join '|'
$tagPatterns = @(
    $Tags |
        ForEach-Object { [regex]::Escape($_) }
) -join '|'
$knownPids = [Collections.Generic.HashSet[int]]::new()
$knownUids = [Collections.Generic.HashSet[long]]::new()
foreach ($file in $allInitialFiles) {
    if ($file.Extension -notin @('.json', '.log', '.txt', '.jsonl')) {
        continue
    }
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    foreach ($match in [regex]::Matches(
            $text,
            'KOKORO_RUNTIME: STATE \{[^\r\n]*?"pid":(?<pid>\d+)')) {
        $null = $knownPids.Add([int]$match.Groups['pid'].Value)
    }
    foreach ($match in [regex]::Matches(
            $text,
            '(?:SERVICE_CREATED|ownerPid=)\s*(?<pid>\d+)')) {
        $null = $knownPids.Add([int]$match.Groups['pid'].Value)
    }
    foreach ($match in [regex]::Matches(
            $text,
            '(?im)"(?:uid|ownerUid|packageUid|mainUid)"\s*:\s*(?<uid>\d+)')) {
        $null = $knownUids.Add([long]$match.Groups['uid'].Value)
    }
}

$deleted = [Collections.Generic.List[object]]::new()
$imageFiles = @(
    $allInitialFiles |
        Where-Object {
            $_.Extension.ToLowerInvariant() -in
                @('.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif')
        }
)
foreach ($file in $imageFiles) {
    $deleted.Add([ordered]@{
        path = $file.FullName.Substring($root.Length).TrimStart('\')
        reason = 'screenshots_are_forbidden'
        bytes = $file.Length
    })
    Remove-Item -LiteralPath $file.FullName -Force
}

$pathReplacements = @{}
$logFiltering = [Collections.Generic.List[object]]::new()
$hostLogs = @(
    $allInitialFiles |
        Where-Object {
            $_.Name -match '^runtime-logcat(?:\..+)?\.log$' -and
            $_.Name -notmatch 'filtered'
        }
)
foreach ($file in $hostLogs) {
    $lines = [IO.File]::ReadAllLines($file.FullName, [Text.Encoding]::UTF8)
    $retained = [Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        $keep = $false
        if ($packagePatterns -and $line -match $packagePatterns) {
            $keep = $true
        }
        if ($tagPatterns -and $line -match $tagPatterns) {
            $keep = $true
        }
        foreach ($appPid in $knownPids) {
            if ($line -match ('(?:^|\s)' + $appPid + '(?:\s|$)')) {
                $keep = $true
                break
            }
        }
        if (-not $keep) {
            foreach ($uid in $knownUids) {
                if ($line -match ('\buid\s*[=:]\s*' + $uid + '\b')) {
                    $keep = $true
                    break
                }
            }
        }
        if ($keep) {
            $retained.Add($line)
        }
    }
    $filteredPath = Join-Path $file.DirectoryName (
        [IO.Path]::GetFileNameWithoutExtension($file.Name) +
        '.package-pid-tag-uid-filtered.log')
    [IO.File]::WriteAllText(
        $filteredPath,
        (($retained -join "`n") + $(if ($retained.Count -gt 0) { "`n" } else { '' })),
        $utf8)
    $pathReplacements[$file.FullName] = $filteredPath
    $stderrPath = [IO.Path]::ChangeExtension($file.FullName, '.err')
    if (Test-Path -LiteralPath $stderrPath) {
        $pathReplacements[$stderrPath] = '[REMOVED_UNFILTERED_STDERR]'
        Remove-Item -LiteralPath $stderrPath -Force
    }
    $logFiltering.Add([ordered]@{
        source = $file.FullName.Substring($root.Length).TrimStart('\')
        filtered = $filteredPath.Substring($root.Length).TrimStart('\')
        originalLines = $lines.Count
        retainedLines = $retained.Count
    })
    Remove-Item -LiteralPath $file.FullName -Force
}

$rawNetstatsFiles = @(
    Get-EvidenceFiles |
        Where-Object {
            $_.Name -match '^final-netstats\.txt$' -or
            ($_.Extension -eq '.txt' -and $_.FullName -match '[\\/]netstats[\\/]')
        }
)
foreach ($file in $rawNetstatsFiles) {
    $deleted.Add([ordered]@{
        path = $file.FullName.Substring($root.Length).TrimStart('\')
        reason = 'unfiltered_netstats_detail'
        bytes = $file.Length
    })
    Remove-Item -LiteralPath $file.FullName -Force
}

$temporaryXml = @(
    Get-EvidenceFiles |
        Where-Object {
            $_.Extension -eq '.xml' -and
            $_.Name -notmatch
                '^(?:checkpoint-original|checkpoint-tamper-pack|checkpoint-tamper-speaker)\.xml$'
        }
)
foreach ($file in $temporaryXml) {
    $deleted.Add([ordered]@{
        path = $file.FullName.Substring($root.Length).TrimStart('\')
        reason = 'temporary_xml'
        bytes = $file.Length
    })
    Remove-Item -LiteralPath $file.FullName -Force
}

$pidFiles = @(
    Get-EvidenceFiles |
        Where-Object {
            $_.Name -match '^runtime-logcat\.pid$' -or
            $_.Name -match '^runtime-logcat\.err$'
        }
)
foreach ($file in $pidFiles) {
    $deleted.Add([ordered]@{
        path = $file.FullName.Substring($root.Length).TrimStart('\')
        reason = 'transient_logcat_pid'
        bytes = $file.Length
    })
    Remove-Item -LiteralPath $file.FullName -Force
}

$sanitizedJsonCount = 0
$sanitizedXmlCount = 0
foreach ($file in Get-TextFiles) {
    if ($file.FullName -in @($securityScanPath, $postprocessPath, $manifestPath)) {
        continue
    }
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    foreach ($old in $pathReplacements.Keys) {
        $text = $text.Replace($old, [string]$pathReplacements[$old])
    }
    if ($file.Extension -eq '.json') {
        try {
            $object = $text | ConvertFrom-Json -Depth 100
            $object = Protect-EvidenceValue $object
            $object = Replace-PathValue $object $pathReplacements
            $text = ($object | ConvertTo-Json -Depth 100) + "`n"
            $sanitizedJsonCount++
        } catch {
            $text = Remove-SecretValues $text
        }
    } elseif ($file.Extension -eq '.xml') {
        $text = Remove-SecretValues $text
        try {
            [xml] $xml = $text
            $text = $xml.OuterXml + "`n"
        } catch {
            # Keep the normalized text so the security scan can fail closed.
        }
        $sanitizedXmlCount++
    } else {
        $text = Remove-SecretValues $text
    }
    Write-NormalizedText $file.FullName $text
}

$securityHits = [Collections.Generic.List[object]]::new()
foreach ($file in Get-TextFiles) {
    if ($file.FullName -in @($securityScanPath, $postprocessPath, $manifestPath)) {
        continue
    }
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    $hits = Get-SecretScanHits $text
    if ($hits.Count -gt 0) {
        $securityHits.Add([ordered]@{
            path = $file.FullName.Substring($root.Length).TrimStart('\')
            hits = $hits
        })
    }
}
$secretScanPassed = $securityHits.Count -eq 0
$securityScan = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.secret-scan.v1'
    status = if ($secretScanPassed) { 'PASS' } else { 'FAIL' }
    passed = $secretScanPassed
    scannedFileCount = @(Get-TextFiles).Count
    hitFileCount = $securityHits.Count
    hits = @($securityHits)
    screenshotFileCount = $imageFiles.Count
}
[IO.File]::WriteAllText(
    $securityScanPath,
    (($securityScan | ConvertTo-Json -Depth 20) + "`n"),
    $utf8)
if (-not $secretScanPassed) {
    Write-Output ($securityScan | ConvertTo-Json -Depth 10)
    exit 1
}

$textFiles = Get-TextFiles
foreach ($file in $textFiles) {
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    Write-NormalizedText $file.FullName $text
}

$manifestFiles = @(
    Get-EvidenceFiles |
        Where-Object {
            $_.FullName -ne $manifestPath -and
            $_.FullName -ne $postprocessPath
        } |
        Sort-Object { $_.FullName.Substring($root.Length).TrimStart('\') }
)
$manifestLines = @(
    $manifestFiles |
        ForEach-Object {
            $relative = $_.FullName.Substring($root.Length).TrimStart('\').
                Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).
                Hash.ToLowerInvariant()
            "$hash  $relative"
        }
)
[IO.File]::WriteAllText(
    $manifestPath,
    $(if ($manifestLines.Count -gt 0) {
        (($manifestLines -join "`n") + "`n")
    } else {
        ''
    }),
    $utf8)
$manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).
    Hash.ToLowerInvariant()

$remainingTextFiles = Get-TextFiles
$bomCount = 0
$crlfCount = 0
foreach ($file in $remainingTextFiles) {
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    if ($bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bomCount++
    }
    if ($bytes -contains 0x0D) {
        $crlfCount++
    }
}
$summary = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.postprocess.v1'
    status = if (
        $secretScanPassed -and
        $bomCount -eq 0 -and
        $crlfCount -eq 0
    ) {
        'PASS_EVIDENCE_FILTERED_REDACTED_LF_NO_BOM'
    } else {
        'FAIL'
    }
    generatedAt = (Get-Date).ToString('o')
    evidenceRoot = $root
    filters = [ordered]@{
        packages = $Packages
        tags = $Tags
        pids = @($knownPids | Sort-Object)
        uids = @($knownUids | Sort-Object)
    }
    hostLogFiltering = @($logFiltering)
    deletedFiles = @($deleted)
    rawHostLogRetained = @(
        Get-EvidenceFiles |
            Where-Object {
                $_.Name -match '^runtime-logcat\.log$'
            }
    ).Count -gt 0
    unfilteredNetstatsRetained = @(
        Get-EvidenceFiles |
            Where-Object {
                $_.Name -eq 'final-netstats.txt' -or
                ($_.Extension -eq '.txt' -and $_.FullName -match '[\\/]netstats[\\/]')
            }
    ).Count -gt 0
    screenshotsRetained = @(
        Get-EvidenceFiles |
            Where-Object {
                $_.Extension.ToLowerInvariant() -in
                    @('.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif')
            }
    ).Count -gt 0
    sanitizedJsonCount = $sanitizedJsonCount
    sanitizedXmlCount = $sanitizedXmlCount
    secretScan = [ordered]@{
        passed = $secretScanPassed
        path = $securityScanPath.Substring($root.Length).TrimStart('\')
    }
    encoding = [ordered]@{
        utf8WithoutBom = $bomCount -eq 0
        lfOnly = $crlfCount -eq 0
        bomFileCount = $bomCount
        crlfFileCount = $crlfCount
    }
    manifest = [ordered]@{
        path = $manifestPath.Substring($root.Length).TrimStart('\')
        sha256 = $manifestSha256
        entryCount = $manifestLines.Count
        excludedFromItself = @('SHA256SUMS', 'postprocess.json')
    }
}
[IO.File]::WriteAllText(
    $postprocessPath,
    (($summary | ConvertTo-Json -Depth 30) + "`n"),
    $utf8)
$summary | ConvertTo-Json -Depth 10
if ($summary.status -ne 'PASS_EVIDENCE_FILTERED_REDACTED_LF_NO_BOM') {
    exit 1
}
