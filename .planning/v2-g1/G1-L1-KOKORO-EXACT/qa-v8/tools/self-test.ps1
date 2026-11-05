[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$files = @(
    Get-ChildItem -LiteralPath $root -Recurse -File -Force |
        Sort-Object FullName
)
$failures = [Collections.Generic.List[string]]::new()
$strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
$astFileCount = 0
$textFileCount = 0
$bomFileCount = 0
$crFileCount = 0

foreach ($file in $files) {
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    try {
        $null = $strictUtf8.GetString($bytes)
        $textFileCount++
    } catch {
        $failures.Add("INVALID_UTF8:$($file.FullName)")
    }
    if ($bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF) {
        $bomFileCount++
        $failures.Add("UTF8_BOM:$($file.FullName)")
    }
    if ($bytes -contains 0x0D) {
        $crFileCount++
        $failures.Add("CR_LINE_ENDING:$($file.FullName)")
    }
    if ($file.Extension -eq '.ps1') {
        $tokens = $null
        $parseErrors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile(
            $file.FullName,
            [ref]$tokens,
            [ref]$parseErrors)
        if ($parseErrors.Count -gt 0) {
            foreach ($parseError in $parseErrors) {
                $failures.Add(
                    "AST_ERROR:$($file.Name):$($parseError.Extent.StartLineNumber):" +
                    $parseError.Message)
            }
        }
        $astFileCount++
    }
}

$dependencies = [ordered]@{}
foreach ($name in @(
    'pwsh.exe',
    'Get-CimInstance',
    'Get-FileHash',
    'ConvertFrom-Json',
    'adb.exe',
    'Invoke-Pester'
)) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    $dependencies[$name] = [ordered]@{
        available = $null -ne $command
        source = if ($null -ne $command) { $command.Source } else { $null }
        version = if ($null -ne $command) { [string]$command.Version } else { $null }
    }
}
if (-not $dependencies['pwsh.exe'].available) {
    $failures.Add('DEPENDENCY_MISSING:pwsh.exe')
}
if (-not $dependencies['Get-CimInstance'].available) {
    $failures.Add('DEPENDENCY_MISSING:Get-CimInstance')
}
if (-not $dependencies['Get-FileHash'].available) {
    $failures.Add('DEPENDENCY_MISSING:Get-FileHash')
}
if (-not $dependencies['ConvertFrom-Json'].available) {
    $failures.Add('DEPENDENCY_MISSING:ConvertFrom-Json')
} elseif (-not (
    (Get-Command 'ConvertFrom-Json').Parameters.Keys -contains 'DateKind'
)) {
    $failures.Add('DEPENDENCY_FEATURE_MISSING:ConvertFrom-Json -DateKind')
}

. (Join-Path $root 'qa-evidence-contract.ps1')
$digest = Get-QaToolDigest -ToolsRoot $root
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.tools-self-test.v1'
    status = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    artifactCount = $files.Count
    astFileCount = $astFileCount
    strictUtf8FileCount = $textFileCount
    bomFileCount = $bomFileCount
    crFileCount = $crFileCount
    dependencies = $dependencies
    digest = [ordered]@{
        sha256 = $digest.sha256
        entryCount = $digest.entryCount
        sortedBy = $digest.sortedBy
        selfExcluded = $digest.selfExcluded
    }
    failures = @($failures)
}
$result | ConvertTo-Json -Depth 12
if ($failures.Count -ne 0) {
    exit 1
}
