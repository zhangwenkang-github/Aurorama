[CmdletBinding()]
param(
    [string] $Serial = '43af8627',

    [Parameter(Mandatory = $true)]
    [string] $BeforeSnapshot,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot
)

$ErrorActionPreference = 'Stop'
$outputPath = [IO.Path]::GetFullPath($OutputRoot)
$null = New-Item -ItemType Directory -Path $outputPath -Force
$utf8 = [Text.UTF8Encoding]::new($false)
$testPackages = @(
    'dev.aurorama.kokoro.harness',
    'dev.aurorama.kokoro.focuspeer'
)
$before = Get-Content -LiteralPath $BeforeSnapshot -Raw -Encoding utf8 |
    ConvertFrom-Json

function Invoke-Adb {
    param(
        [string[]] $Arguments,
        [switch] $AllowFailure
    )
    $output = @(& adb.exe -s $Serial @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb failed: adb -s $Serial $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Write-Json {
    param(
        [string] $Path,
        [object] $Value
    )
    $text = ($Value | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $text + "`n", $utf8)
}

function Get-SessionStackCount {
    param([object[]] $Lines)
    $match = ($Lines -join "`n") |
        Select-String -Pattern 'Sessions Stack - have\s+(\d+)\s+sessions:' |
        Select-Object -First 1
    if ($null -eq $match) {
        return $null
    }
    return [int] $match.Matches[0].Groups[1].Value
}

foreach ($package in $testPackages) {
    $null = Invoke-Adb @('shell', 'am', 'force-stop', $package) -AllowFailure
}
$null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse') -AllowFailure
$null = Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure

$uninstallResults = [ordered]@{}
foreach ($package in $testPackages) {
    $result = @(
        Invoke-Adb @('uninstall', '--user', '0', $package) -AllowFailure
    )
    $uninstallResults[$package] = [ordered]@{
        output = $result
        success = [bool] (($result -join "`n") -match '(?m)^Success\s*$')
    }
}

foreach ($package in $testPackages) {
    foreach ($path in @(
        "/sdcard/Android/data/$package",
        "/sdcard/Android/obb/$package",
        "/sdcard/Android/media/$package"
    )) {
        $null = Invoke-Adb @('shell', 'rm', '-rf', $path) -AllowFailure
    }
}
$null = Invoke-Adb @('shell', 'rm', '-f', '/sdcard/qa_v7_*.xml') -AllowFailure
$null = Invoke-Adb @('shell', 'rm', '-rf', '/data/local/tmp/qa_v7_*') -AllowFailure

$packagePaths = [ordered]@{}
$externalPathsRemain = [ordered]@{}
foreach ($package in $testPackages) {
    $packagePaths[$package] = @(
        Invoke-Adb @('shell', 'pm', 'path', $package) -AllowFailure
    )
    foreach ($path in @(
        "/sdcard/Android/data/$package",
        "/sdcard/Android/obb/$package",
        "/sdcard/Android/media/$package"
    )) {
        $listing = @(
            Invoke-Adb @('shell', 'ls', '-ld', $path) -AllowFailure
        )
        $externalPathsRemain[$path] = [bool] (
            ($listing -join "`n") -notmatch 'No such file|Permission denied'
        )
    }
}

$serviceText = @(Invoke-Adb @('shell', 'dumpsys activity services'))
$notificationText = @(Invoke-Adb @('shell', 'dumpsys notification --noredact'))
$mediaText = @(Invoke-Adb @('shell', 'dumpsys media_session'))
$runtimePids = [ordered]@{}
foreach ($package in $testPackages) {
    $runtimePids[$package] =
        ((Invoke-Adb @('shell', 'pidof', $package) -AllowFailure) -join "`n").Trim()
}
$serviceMatches = @(
    $serviceText |
        Where-Object {
            $_ -match 'dev\.aurorama\.kokoro\.(?:harness|focuspeer)'
        }
)
$notificationMatches = @(
    $notificationText |
        Where-Object {
            $_ -match 'NotificationRecord.*dev\.aurorama\.kokoro\.(?:harness|focuspeer)'
        }
)
$activeMediaSessionCount = Get-SessionStackCount $mediaText
$remoteHelperEntries = @(
    (Invoke-Adb @('shell', 'ls', '-1', '/sdcard') -AllowFailure) |
        Where-Object { $_ -match '^qa_v7_' }
)

$snapshotScript = Join-Path $PSScriptRoot 'device-snapshot.ps1'
$afterSnapshotPath = Join-Path $outputPath 'device-after.json'
& pwsh -NoProfile -ExecutionPolicy Bypass -File $snapshotScript `
    -Serial $Serial `
    -OutputPath $afterSnapshotPath |
    Out-Null
$after = Get-Content -LiteralPath $afterSnapshotPath -Raw -Encoding utf8 |
    ConvertFrom-Json

$productionMatch =
    $before.productionPackage.bytes -eq $after.productionPackage.bytes -and
    $before.productionPackage.sha256 -eq $after.productionPackage.sha256 -and
    $before.productionPackage.basePath -eq $after.productionPackage.basePath -and
    $before.productionPackage.versionCode -eq $after.productionPackage.versionCode -and
    $before.productionPackage.versionName -eq $after.productionPackage.versionName
$packagesAbsent = @(
    $packagePaths.Values |
        Where-Object { @($_ | Where-Object { $_ -match '^package:' }).Count -gt 0 }
).Count -eq 0
$testsAbsent =
    $packagesAbsent -and
    @($runtimePids.Values | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 0 -and
    $serviceMatches.Count -eq 0 -and
    $notificationMatches.Count -eq 0 -and
    $activeMediaSessionCount -eq 0 -and
    @($externalPathsRemain.Values | Where-Object { $_ }).Count -eq 0 -and
    $remoteHelperEntries.Count -eq 0
$restoreMatch =
    $after.keyguardShowing -eq $false -and
    $after.currentUser -eq $before.currentUser -and
    $after.musicVolume -eq $before.musicVolume -and
    $after.airplaneMode -eq $before.airplaneMode -and
    $after.wifiEnabled -eq $before.wifiEnabled -and
    $after.mobileData -eq $before.mobileData -and
    $after.screenOffTimeout -eq $before.screenOffTimeout -and
    $after.stayOnWhilePluggedIn -eq $before.stayOnWhilePluggedIn -and
    $after.lockAfterTimeout -eq $before.lockAfterTimeout -and
    $after.wakefulness -eq $before.wakefulness -and
    @($after.activeDefaultNetworkLine).Count -gt 0

$cleanup = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.cleanup.v1'
    serial = $Serial
    completedAt = (Get-Date).ToString('o')
    uninstall = $uninstallResults
    packagePaths = $packagePaths
    runtimePids = $runtimePids
    serviceRecordCount = $serviceMatches.Count
    notificationRecordCount = $notificationMatches.Count
    activeMediaSessionCount = $activeMediaSessionCount
    externalPathsRemain = $externalPathsRemain
    remoteHelperEntries = $remoteHelperEntries
    beforeSnapshot = [IO.Path]::GetFullPath($BeforeSnapshot)
    afterSnapshot = $afterSnapshotPath
    productionMatch = $productionMatch
    testsAbsent = $testsAbsent
    restoreMatch = $restoreMatch
    productionBefore = $before.productionPackage
    productionAfter = $after.productionPackage
    restored = [ordered]@{
        keyguardShowing = $after.keyguardShowing
        wakefulness = $after.wakefulness
        currentUser = $after.currentUser
        musicVolume = $after.musicVolume
        airplaneMode = $after.airplaneMode
        wifiEnabled = $after.wifiEnabled
        mobileData = $after.mobileData
        activeDefaultNetworkLine = $after.activeDefaultNetworkLine
        screenOffTimeout = $after.screenOffTimeout
        stayOnWhilePluggedIn = $after.stayOnWhilePluggedIn
        lockAfterTimeout = $after.lockAfterTimeout
    }
    passed = $productionMatch -and $testsAbsent -and $restoreMatch
}

$cleanupPath = Join-Path $outputPath 'cleanup.json'
Write-Json $cleanupPath $cleanup
$cleanup | ConvertTo-Json -Depth 12
if (-not $cleanup.passed) {
    exit 1
}
