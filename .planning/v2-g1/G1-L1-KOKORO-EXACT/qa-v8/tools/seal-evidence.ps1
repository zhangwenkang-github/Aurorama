[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $EvidenceRoot,

    [string] $ToolsManifestPath
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $EvidenceRoot).Path
$toolsRoot = Join-Path $root 'tools'
$manifestPath = Join-Path $root 'SHA256SUMS'
$finalSealPath = Join-Path $root 'final-seal.json'
$finalScanPath = Join-Path $root 'security-scan-final.json'
$utf8 = [Text.UTF8Encoding]::new($false)
$secretPatterns = [ordered]@{
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

function Test-InToolsDirectory([IO.FileInfo] $File) {
    return $File.FullName.Equals(
            $toolsRoot,
            [StringComparison]::OrdinalIgnoreCase) -or
        $File.FullName.StartsWith(
            $toolsRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)
}

function Get-EvidencePayloadFiles {
    return @(
        Get-ChildItem -LiteralPath $root -Recurse -File -Force |
            Where-Object { -not (Test-InToolsDirectory $_) }
    )
}

function Get-SecretHits([string] $Text) {
    $hits = [Collections.Generic.List[object]]::new()
    foreach ($entry in $secretPatterns.GetEnumerator()) {
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

function Normalize-TextFile([IO.FileInfo] $File) {
    if ($File.Extension.ToLowerInvariant() -notin
        @('.json', '.log', '.txt', '.xml', '.jsonl')) {
        return
    }
    $text = [IO.File]::ReadAllText($File.FullName, [Text.Encoding]::UTF8)
    $normalized = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($File.FullName, $normalized, $utf8)
}

$excludedFromManifest = @(
    $manifestPath,
    $finalSealPath
)
$allPayloadFiles = Get-EvidencePayloadFiles
foreach ($file in $allPayloadFiles) {
    Normalize-TextFile $file
}

$scanHits = [Collections.Generic.List[object]]::new()
$scannedPaths = [Collections.Generic.List[string]]::new()
foreach ($file in Get-EvidencePayloadFiles) {
    if ($file.FullName -in @($finalScanPath, $finalSealPath)) {
        continue
    }
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $hits = Get-SecretHits $text
    if ($hits.Count -gt 0) {
        $scanHits.Add([ordered]@{
            path = $file.FullName.
                Substring($root.Length).
                TrimStart('\').
                Replace('\', '/')
            hits = $hits
        })
    }
    $scannedPaths.Add(
        $file.FullName.
            Substring($root.Length).
            TrimStart('\').
            Replace('\', '/'))
}

$toolsManifestDetails = $null
if (-not [string]::IsNullOrWhiteSpace($ToolsManifestPath) -and
    (Test-Path -LiteralPath $ToolsManifestPath)) {
    $toolsManifestDetails = [ordered]@{
        path = (Resolve-Path -LiteralPath $ToolsManifestPath).Path
        sha256 = (Get-FileHash -LiteralPath $ToolsManifestPath -Algorithm SHA256).
            Hash.ToLowerInvariant()
        lineCount = @(
            Get-Content -LiteralPath $ToolsManifestPath -Encoding utf8
        ).Count
    }
}

$matrixResultScanned = $scannedPaths -contains 'matrix-result.json'
$postprocessStdoutScanned =
    $scannedPaths -contains 'raw/postprocess-evidence.stdout.log'
$requiredPostRunFilesScanned =
    $matrixResultScanned -and $postprocessStdoutScanned
$finalScan = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.final-secret-scan.v1'
    status = if ($scanHits.Count -eq 0 -and $requiredPostRunFilesScanned) {
        'PASS'
    } else {
        'FAIL'
    }
    passed = $scanHits.Count -eq 0 -and $requiredPostRunFilesScanned
    scannedFileCount = $scannedPaths.Count
    scannedFiles = @($scannedPaths)
    hitFileCount = $scanHits.Count
    hits = @($scanHits)
    toolsDirectoryExcludedFromPayload = $true
    toolsManifest = $toolsManifestDetails
    requiredPostRunFilesScanned = [ordered]@{
        matrixResult = $matrixResultScanned
        postprocessStdout = $postprocessStdoutScanned
    }
}
$finalScanText = ($finalScan | ConvertTo-Json -Depth 20) + "`n"
$finalScanHits = Get-SecretHits $finalScanText
if ($finalScanHits.Count -gt 0) {
    $finalScan.status = 'FAIL'
    $finalScan.passed = $false
    $finalScan.metadataScanHits = @($finalScanHits)
    $finalScanText = ($finalScan | ConvertTo-Json -Depth 20) + "`n"
}
[IO.File]::WriteAllText($finalScanPath, $finalScanText, $utf8)
if (-not [bool]$finalScan.passed) {
    $finalScan | ConvertTo-Json -Depth 10
    exit 1
}

$manifestFiles = @(
    Get-EvidencePayloadFiles |
        Where-Object {
            $_.FullName -notin $excludedFromManifest
        } |
        Sort-Object { $_.FullName.Substring($root.Length).TrimStart('\') }
)
$manifestLines = @(
    $manifestFiles |
        ForEach-Object {
            $relative = $_.FullName.
                Substring($root.Length).
                TrimStart('\').
                Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).
                Hash.ToLowerInvariant()
            "$hash  $relative"
        }
)
$manifestText = if ($manifestLines.Count -gt 0) {
    ($manifestLines -join "`n") + "`n"
} else {
    ''
}
$manifestScanHits = Get-SecretHits $manifestText
if ($manifestScanHits.Count -gt 0) {
    $failedScan = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.final-secret-scan.v1'
        status = 'FAIL'
        passed = $false
        manifestScanHits = @($manifestScanHits)
    }
    [IO.File]::WriteAllText(
        $finalScanPath,
        (($failedScan | ConvertTo-Json -Depth 10) + "`n"),
        $utf8)
    $failedScan | ConvertTo-Json -Depth 10
    exit 1
}
[IO.File]::WriteAllText($manifestPath, $manifestText, $utf8)
$manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).
    Hash.ToLowerInvariant()

$finalSeal = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.final-seal.v1'
    status = 'PASS_FINAL_FILES_SCANNED_AND_SEALED'
    generatedAt = (Get-Date).ToString('o')
    evidenceRoot = $root
    finalSecretScan = [ordered]@{
        path = $finalScanPath.Substring($root.Length).TrimStart('\')
        passed = $true
        scannedFileCount = $scannedPaths.Count
        contentScannedBeforeWrite = $true
    }
    manifest = [ordered]@{
        path = $manifestPath.Substring($root.Length).TrimStart('\')
        sha256 = $manifestSha256
        entryCount = $manifestLines.Count
        excludedFromItself = @('SHA256SUMS', 'final-seal.json')
        contentScannedBeforeWrite = $true
    }
    toolsManifest = $toolsManifestDetails
    postManifestFiles = [ordered]@{
        matrixResultScanned = $matrixResultScanned
        postprocessStdoutScanned = $postprocessStdoutScanned
        finalSealContentScannedBeforeWrite = $true
    }
}
$finalSealText = ($finalSeal | ConvertTo-Json -Depth 20) + "`n"
$finalSealHits = Get-SecretHits $finalSealText
if ($finalSealHits.Count -gt 0) {
    $finalSeal.status = 'FAIL'
    $finalSeal.secretHits = @($finalSealHits)
    $finalSealText = ($finalSeal | ConvertTo-Json -Depth 20) + "`n"
}
[IO.File]::WriteAllText($finalSealPath, $finalSealText, $utf8)
$finalSeal | ConvertTo-Json -Depth 10
if ($finalSeal.status -ne 'PASS_FINAL_FILES_SCANNED_AND_SEALED') {
    exit 1
}
