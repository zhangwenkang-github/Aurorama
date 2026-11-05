[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$qaRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$utf8 = [Text.UTF8Encoding]::new($false)
$textExtensions = @(
    '.json',
    '.jsonl',
    '.txt',
    '.log',
    '.md',
    '.xml',
    '.ps1'
)

function Write-Json {
    param(
        [string] $Path,
        [object] $Value
    )
    $text = ($Value | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $text + "`n", $utf8)
}

$files = @(
    Get-ChildItem -LiteralPath $qaRoot -Recurse -File |
        Where-Object {
            $_.FullName -notmatch '[\\/]raw[\\/]\.tmp[\\/]' -and
            $_.Name -notin @('SHA256SUMS', 'integrity-receipt.json')
        }
)

$normalized = 0
$strictDecodeErrors = @()
$bomFiles = @()
$crlfFiles = @()
foreach ($file in $files) {
    $isText =
        $file.Extension.ToLowerInvariant() -in $textExtensions -or
        $file.Name -in @('.gitattributes', '.gitignore')
    if (-not $isText) {
        continue
    }
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    if ($bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF) {
        $bomFiles += $file.FullName
    }
    try {
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    } catch {
        $strictDecodeErrors += $file.FullName
        continue
    }
    if ($text.Contains("`r")) {
        $crlfFiles += $file.FullName
    }
    $normalizedText =
        $text.TrimStart([char] 0xFEFF) -replace "`r`n", "`n" -replace "`r", "`n"
    $normalizedText = $normalizedText -replace '(?m)[ \t]+$', ''
    if (-not $normalizedText.EndsWith("`n")) {
        $normalizedText += "`n"
    }
    [IO.File]::WriteAllText($file.FullName, $normalizedText, $utf8)
    $normalized++
}

$files = @(
    Get-ChildItem -LiteralPath $qaRoot -Recurse -File |
        Where-Object {
            $_.FullName -notmatch '[\\/]raw[\\/]\.tmp[\\/]' -and
            $_.Name -notin @(
                'SHA256SUMS',
                'integrity-receipt.json',
                'sensitive-scan.json'
            )
        }
)

$patterns = [ordered]@{}
$patterns['bearer'] = '(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{12,}'
$patterns['jwt'] = '\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b'
$patterns['accessTokenValue'] =
    '(?i)\bAccessToken\b\s*[:=]\s*["'']?[A-Za-z0-9._~+/=-]{12,}'
$patterns[('wifi' + 'NetworkKey')] = '(?i)\bwifiNetworkKey\b'
$patterns['ssidValue'] = '(?im)^\s*(?:ssid|wifi_ssid)\s*[:=]\s*\S+'
$patterns['passwordValue'] = '(?im)^\s*(?:password|passwd|pwd)\s*[:=]\s*\S+'
$patterns['authorizationHeader'] =
    '(?i)\bAuthorization\s*:\s*(?:Basic|Bearer)\s+\S+'

$scanHits = [ordered]@{}
foreach ($name in $patterns.Keys) {
    $scanHits[$name] = @()
}
$urlHits = @()
foreach ($file in $files) {
    $isText =
        $file.Extension.ToLowerInvariant() -in $textExtensions -or
        $file.Name -in @('.gitattributes', '.gitignore')
    if (-not $isText) {
        continue
    }
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    foreach ($name in $patterns.Keys) {
        $count = [regex]::Matches($text, $patterns[$name]).Count
        if ($count -gt 0) {
            $scanHits[$name] += [ordered]@{
                path = [IO.Path]::GetRelativePath($qaRoot, $file.FullName).Replace('\', '/')
                count = $count
            }
        }
    }
    foreach ($match in [regex]::Matches($text, 'https?://[^\s"''<>]+')) {
        $urlHits += [ordered]@{
            path = [IO.Path]::GetRelativePath($qaRoot, $file.FullName).Replace('\', '/')
            host = ([uri] $match.Value).Host
        }
    }
}

$secretHitCount = 0
foreach ($name in $patterns.Keys) {
    $secretHitCount += @($scanHits[$name]).Count
}
$unexpectedUrlHits = @(
    $urlHits |
        Where-Object {
            $_.host -notin @(
                'schemas.android.com',
                'www.w3.org'
            )
        }
)
$unfilteredLogNames = @(
    $files |
        Where-Object {
            $_.Extension -in @('.log', '.txt') -and
            $_.Name -match '(?i)unfiltered|raw-host'
        } |
        ForEach-Object {
            [IO.Path]::GetRelativePath($qaRoot, $_.FullName).Replace('\', '/')
        }
)

$sensitiveScan = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.sensitive-scan.v1'
    generatedAt = (Get-Date).ToString('o')
    scannedFileCount = $files.Count
    strictDecodeErrorCount = $strictDecodeErrors.Count
    strictDecodeErrors = $strictDecodeErrors
    bomFileCountBeforeNormalization = $bomFiles.Count
    crFileCountBeforeNormalization = $crlfFiles.Count
    normalizedTextFileCount = $normalized
    secretPatternHitCount = $secretHitCount
    secretPatternHits = $scanHits
    urlHitCount = $urlHits.Count
    unexpectedUrlHits = $unexpectedUrlHits
    unfilteredHostLogCount = $unfilteredLogNames.Count
    unfilteredHostLogs = $unfilteredLogNames
    notificationXmlRetained = [bool] (@(
        $files |
            Where-Object { $_.Name -match '(?i)notification.*\.xml$' }
    ).Count)
    status = if (
        $strictDecodeErrors.Count -eq 0 -and
        $secretHitCount -eq 0 -and
        $unexpectedUrlHits.Count -eq 0 -and
        $unfilteredLogNames.Count -eq 0
    ) {
        'PASS'
    } else {
        'FAIL'
    }
}
$scanPath = Join-Path $qaRoot 'sensitive-scan.json'
Write-Json $scanPath $sensitiveScan

$files = @(
    Get-ChildItem -LiteralPath $qaRoot -Recurse -File |
        Where-Object {
            $_.FullName -notmatch '[\\/]raw[\\/]\.tmp[\\/]' -and
            $_.Name -notin @('SHA256SUMS', 'integrity-receipt.json')
        } |
        Sort-Object FullName
)
$manifestLines = @(
    $files |
        ForEach-Object {
            $relative = [IO.Path]::GetRelativePath($qaRoot, $_.FullName).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relative"
        }
)
$manifestPath = Join-Path $qaRoot 'SHA256SUMS'
[IO.File]::WriteAllText(
    $manifestPath,
    ((@($manifestLines) -join "`n") + "`n"),
    $utf8
)
$manifestSha = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$reportSha = (Get-FileHash -LiteralPath (Join-Path $qaRoot 'report.json') -Algorithm SHA256).Hash.ToLowerInvariant()
$receipt = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.integrity-receipt.v1'
    generatedAt = (Get-Date).ToString('o')
    manifest = 'SHA256SUMS'
    manifestSha256 = $manifestSha
    manifestEntryCount = $manifestLines.Count
    report = 'report.json'
    reportSha256 = $reportSha
    sensitiveScan = 'sensitive-scan.json'
    sensitiveScanStatus = $sensitiveScan.status
    excludesSelf = $true
    excludesReceipt = $true
}
$receiptPath = Join-Path $qaRoot 'integrity-receipt.json'
Write-Json $receiptPath $receipt

$receipt | ConvertTo-Json -Depth 8
if ($sensitiveScan.status -ne 'PASS') {
    exit 1
}
