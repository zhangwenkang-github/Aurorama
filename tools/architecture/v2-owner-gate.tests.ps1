[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$guardPath = Join-Path $PSScriptRoot 'v2-owner-gate.ps1'
$baselinePath = Join-Path $PSScriptRoot 'v2-owner-baseline.json'
$pwshPath = (Get-Process -Id $PID).Path
$passed = 0
$failed = 0

function Write-Utf8 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text
    )

    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [System.IO.File]::WriteAllText(
        $Path,
        $Text,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Invoke-Guard {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Baseline
    )

    $output = & $pwshPath -NoProfile -File $guardPath -Root $Root -BaselinePath $Baseline
    $exitCode = $LASTEXITCODE
    $jsonLine = @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($jsonLine)) {
        throw "Guard produced no JSON. exit=$exitCode"
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Json = $jsonLine | ConvertFrom-Json
        Raw = $jsonLine
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-ContainsFinding {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][string]$Code
    )

    $found = @($Result.Json.findings | Where-Object code -eq $Code).Count -gt 0
    Assert-True -Condition $found -Message "Missing finding code '$Code'. $($Result.Raw)"
}

function Invoke-Test {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Body
    )

    try {
        & $Body
        $script:passed++
        Write-Output "PASS $Name"
    }
    catch {
        $script:failed++
        Write-Output "FAIL $Name :: $($_.Exception.Message)"
    }
}

function Read-JsonFile {
    param([Parameter(Mandatory)][string]$Path)
    $text = [System.IO.File]::ReadAllText(
        $Path,
        [System.Text.UTF8Encoding]::new($false, $true)
    )
    return $text | ConvertFrom-Json
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    Write-Utf8 -Path $Path -Text ($Value | ConvertTo-Json -Depth 20)
}

function New-Fixture {
    param([Parameter(Mandatory)]$Baseline)

    $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("v2-owner-gate-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null

    $settingsLines = @(
        'enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")',
        'rootProject.name = "owner-guard-fixture"',
        ''
    )
    foreach ($moduleName in @($Baseline.ownerModules)) {
        $settingsLines += "include(`":$($moduleName.Replace('/', ':'))`")"
    }
    Write-Utf8 -Path (Join-Path $fixtureRoot 'settings.gradle.kts') -Text ($settingsLines -join "`n")

    $edgesBySource = @{}
    foreach ($edge in @($Baseline.moduleEdges) + @($Baseline.externalEdges)) {
        if (-not $edgesBySource.ContainsKey($edge.from)) {
            $edgesBySource[$edge.from] = [System.Collections.Generic.List[string]]::new()
        }
        if (-not $edgesBySource[$edge.from].Contains($edge.to)) {
            $edgesBySource[$edge.from].Add($edge.to)
        }
    }

    foreach ($moduleName in @($Baseline.ownerModules)) {
        $moduleRoot = Join-Path $fixtureRoot $moduleName.Replace('/', '\')
        New-Item -ItemType Directory -Path $moduleRoot -Force | Out-Null
        $lines = @(
            'plugins {',
            '}',
            '',
            'dependencies {'
        )
        if ($edgesBySource.ContainsKey($moduleName)) {
            foreach ($target in @($edgesBySource[$moduleName] | Sort-Object)) {
                $lines += "    implementation(projects.$($target.Replace('/', '.')))"
            }
        }
        $lines += '}'
        Write-Utf8 -Path (Join-Path $moduleRoot 'build.gradle.kts') -Text ($lines -join "`n")
    }

    $fixtureBaseline = Join-Path $fixtureRoot 'baseline.json'
    Write-JsonFile -Path $fixtureBaseline -Value $Baseline
    return [pscustomobject]@{
        Root = $fixtureRoot
        BaselinePath = $fixtureBaseline
    }
}

function Remove-Fixture {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = [System.IO.Path]::GetFullPath($Path)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $leaf = Split-Path -Leaf $resolved
    if (-not $resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not $leaf.StartsWith('v2-owner-gate-', [System.StringComparison]::Ordinal)) {
        throw "Refusing to remove unexpected fixture path: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function New-SourceFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Module,
        [Parameter(Mandatory)][string]$Package,
        [Parameter(Mandatory)][string]$FileName,
        [Parameter(Mandatory)][string]$Body
    )

    $relativeDir = $Package.Replace('.', '\')
    $path = Join-Path $Root "$($Module.Replace('/', '\'))\src\main\java\$relativeDir\$FileName"
    Write-Utf8 -Path $path -Text $Body
    return $path
}

Invoke-Test -Name 'current G1-S1 baseline passes' -Body {
    $result = Invoke-Guard -Root $repoRoot -Baseline $baselinePath
    Assert-True -Condition ($result.ExitCode -eq 0) -Message "Expected exit 0, got $($result.ExitCode). $($result.Raw)"
    Assert-True -Condition ($result.Json.verdict -eq 'PASS') -Message "Expected PASS. $($result.Raw)"
}

$base = Read-JsonFile -Path $baselinePath

Invoke-Test -Name 'missing direct edge fails' -Body {
    $fixture = New-Fixture -Baseline $base
    try {
        $buildPath = Join-Path $fixture.Root 'app\phone\build.gradle.kts'
        $text = [System.IO.File]::ReadAllText($buildPath, [System.Text.UTF8Encoding]::new($false, $true))
        $text = $text.Replace("    implementation(projects.core)`n", '')
        Write-Utf8 -Path $buildPath -Text $text
        $result = Invoke-Guard -Root $fixture.Root -Baseline $fixture.BaselinePath
        Assert-True -Condition ($result.ExitCode -eq 2) -Message "Expected exit 2, got $($result.ExitCode). $($result.Raw)"
        Assert-ContainsFinding -Result $result -Code 'DIRECT_EDGE_MISSING'
    }
    finally {
        Remove-Fixture -Path $fixture.Root
    }
}

Invoke-Test -Name 'unexpected direct edge fails' -Body {
    $fixture = New-Fixture -Baseline $base
    try {
        $buildPath = Join-Path $fixture.Root 'player\core\build.gradle.kts'
        $text = [System.IO.File]::ReadAllText($buildPath, [System.Text.UTF8Encoding]::new($false, $true))
        $text = $text.Replace("dependencies {", "dependencies {`n    implementation(projects.setup)")
        Write-Utf8 -Path $buildPath -Text $text
        $result = Invoke-Guard -Root $fixture.Root -Baseline $fixture.BaselinePath
        Assert-True -Condition ($result.ExitCode -eq 2) -Message "Expected exit 2, got $($result.ExitCode). $($result.Raw)"
        Assert-ContainsFinding -Result $result -Code 'DIRECT_EDGE_UNEXPECTED'
    }
    finally {
        Remove-Fixture -Path $fixture.Root
    }
}

Invoke-Test -Name 'dependency cycle fails' -Body {
    $cycleBaseline = $base | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $cycleEdge = [pscustomobject]@{
        id = 'TEST-CYCLE'
        from = 'player/core'
        to = 'player/local'
    }
    $cycleBaseline.moduleEdges = @($cycleBaseline.moduleEdges) + $cycleEdge
    $fixture = New-Fixture -Baseline $cycleBaseline
    try {
        $result = Invoke-Guard -Root $fixture.Root -Baseline $fixture.BaselinePath
        Assert-True -Condition ($result.ExitCode -eq 2) -Message "Expected exit 2, got $($result.ExitCode). $($result.Raw)"
        Assert-ContainsFinding -Result $result -Code 'DEPENDENCY_CYCLE'
    }
    finally {
        Remove-Fixture -Path $fixture.Root
    }
}

Invoke-Test -Name 'ambiguous FQCN owner fails' -Body {
    $fixture = New-Fixture -Baseline $base
    try {
        $package = 'com.zhangwenkang.cinefin.shared'
        New-SourceFile -Root $fixture.Root -Module 'core' -Package $package -FileName 'Duplicate.kt' -Body "package $package`n`nclass Duplicate" | Out-Null
        New-SourceFile -Root $fixture.Root -Module 'data' -Package $package -FileName 'Duplicate.kt' -Body "package $package`n`nclass Duplicate" | Out-Null
        $result = Invoke-Guard -Root $fixture.Root -Baseline $fixture.BaselinePath
        Assert-True -Condition ($result.ExitCode -eq 2) -Message "Expected exit 2, got $($result.ExitCode). $($result.Raw)"
        Assert-ContainsFinding -Result $result -Code 'FQCN_OWNER_AMBIGUITY'
    }
    finally {
        Remove-Fixture -Path $fixture.Root
    }
}

Invoke-Test -Name 'new forbidden presentation import fails' -Body {
    $fixture = New-Fixture -Baseline $base
    try {
        $fixtureBaseline = Read-JsonFile -Path $fixture.BaselinePath
        $violation = @($fixtureBaseline.violations | Where-Object id -eq 'G1-S1-V-d0a87242164d')[0]
        $violation.limits.imports = 0
        $violation.limits.files = 0
        Write-JsonFile -Path $fixture.BaselinePath -Value $fixtureBaseline
        $package = 'com.zhangwenkang.cinefin.presentation.bad'
        New-SourceFile -Root $fixture.Root -Module 'app/phone' -Package $package -FileName 'BadViewModel.kt' -Body @"
package $package

import com.zhangwenkang.cinefin.database.BadDao

class BadViewModel
"@ | Out-Null
        $result = Invoke-Guard -Root $fixture.Root -Baseline $fixture.BaselinePath
        Assert-True -Condition ($result.ExitCode -eq 2) -Message "Expected exit 2, got $($result.ExitCode). $($result.Raw)"
        Assert-ContainsFinding -Result $result -Code 'DEBT_RATCHET'
    }
    finally {
        Remove-Fixture -Path $fixture.Root
    }
}

Invoke-Test -Name 'shared package debt increase fails' -Body {
    $fixture = New-Fixture -Baseline $base
    try {
        $fixtureBaseline = Read-JsonFile -Path $fixture.BaselinePath
        $violation = @($fixtureBaseline.violations | Where-Object id -eq 'G1-S1-V-f3f117c921f7')[0]
        $violation.limits.coreFiles = 0
        $violation.limits.dataFiles = 0
        $violation.limits.totalFiles = 0
        Write-JsonFile -Path $fixture.BaselinePath -Value $fixtureBaseline
        $package = 'com.zhangwenkang.cinefin.models'
        New-SourceFile -Root $fixture.Root -Module 'core' -Package $package -FileName 'NewModel.kt' -Body "package $package`n`nclass NewModel" | Out-Null
        $result = Invoke-Guard -Root $fixture.Root -Baseline $fixture.BaselinePath
        Assert-True -Condition ($result.ExitCode -eq 2) -Message "Expected exit 2, got $($result.ExitCode). $($result.Raw)"
        Assert-ContainsFinding -Result $result -Code 'DEBT_RATCHET'
    }
    finally {
        Remove-Fixture -Path $fixture.Root
    }
}

Write-Output "SUMMARY passed=$passed failed=$failed total=$($passed + $failed)"
if ($failed -ne 0) {
    exit 1
}
exit 0
