[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputPath
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$productionPackage = 'io.github.zhangwenkang.aurorama'
$testPackages = @(
    'dev.aurorama.kokoro.harness',
    'dev.aurorama.kokoro.focuspeer'
)

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

function First-Match {
    param(
        [string[]] $Lines,
        [string] $Pattern
    )
    $match = ($Lines | Select-String -Pattern $Pattern | Select-Object -First 1)
    if ($null -eq $match) {
        return $null
    }
    return $match.Matches[0].Groups[1].Value
}

function Get-PackageIdentity {
    param([string] $Package)
    $pathLines = @(Invoke-Adb @('shell', "pm path $Package") -AllowFailure)
    $basePath = ($pathLines | Where-Object { $_ -match '^package:' } |
        Select-Object -First 1) -replace '^package:', ''
    if ([string]::IsNullOrWhiteSpace($basePath)) {
        return [ordered]@{
            installed = $false
            basePath = $null
            bytes = $null
            sha256 = $null
            versionName = $null
            versionCode = $null
        }
    }
    $basePath = $basePath.Trim()
    $sizeText = (Invoke-Adb @('shell', "stat -c %s $basePath") | Select-Object -First 1)
    $shaLine = (Invoke-Adb @('shell', "sha256sum $basePath") | Select-Object -First 1)
    $packageDump = @(Invoke-Adb @('shell', "dumpsys package $Package"))
    $versionNameLine = $packageDump |
        Where-Object { $_ -match 'versionName=' } |
        Select-Object -First 1
    $versionCodeLine = $packageDump |
        Where-Object { $_ -match 'versionCode=' } |
        Select-Object -First 1
    return [ordered]@{
        installed = $true
        basePath = $basePath
        bytes = [int64] $sizeText
        sha256 = ($shaLine -split '\s+')[0].ToLowerInvariant()
        versionName = if ($versionNameLine -match 'versionName=([^\s]+)') {
            $matches[1]
        } else {
            $null
        }
        versionCode = if ($versionCodeLine -match 'versionCode=(\d+)') {
            [int] $matches[1]
        } else {
            $null
        }
    }
}

$startedAt = (Get-Date).ToString('o')
$windowText = @(Invoke-Adb @('shell', 'dumpsys window')) -join "`n"
$policyText = @(Invoke-Adb @('shell', 'dumpsys window policy')) -join "`n"
$powerText = @(Invoke-Adb @('shell', 'dumpsys power')) -join "`n"
$audioText = @(Invoke-Adb @('shell', 'dumpsys audio')) -join "`n"
$connectivityText = @(Invoke-Adb @('shell', 'dumpsys connectivity')) -join "`n"
$packageList = @(Invoke-Adb @('shell', 'pm list packages -f'))
$notificationText = @(Invoke-Adb @('shell', 'dumpsys notification --noredact')) -join "`n"
$mediaText = @(Invoke-Adb @('shell', 'dumpsys media_session')) -join "`n"
$serviceText = @(Invoke-Adb @('shell', 'dumpsys activity services'))

$keyguardStates = @(
    [regex]::Matches(
        "$windowText`n$policyText",
        '(?m)^\s*(?:isKeyguardShowing|mKeyguardShowing|KeyguardShowing)=(true|false)') |
        ForEach-Object { $_.Groups[1].Value }
)
$keyguardShowing = if ($keyguardStates -contains 'true') {
    $true
} elseif ($keyguardStates -contains 'false') {
    $false
} else {
    $null
}

$screenLines = @(Invoke-Adb @('shell', 'settings get system screen_off_timeout'))
$stayOnLines = @(Invoke-Adb @('shell', 'settings get global stay_on_while_plugged_in'))
$lockAfterLines = @(Invoke-Adb @('shell', 'settings get secure lock_after_timeout'))
$currentUserLines = @(Invoke-Adb @('shell', 'am get-current-user'))
$airplaneLines = @(Invoke-Adb @('shell', 'settings get global airplane_mode_on'))
$wifiLines = @(Invoke-Adb @('shell', 'settings get global wifi_on'))
$mobileDataLines = @(Invoke-Adb @('shell', 'settings get global mobile_data'))

$packageIdentities = [ordered]@{}
foreach ($package in $testPackages) {
    $packageIdentities[$package] = Get-PackageIdentity $package
}
$productionIdentity = Get-PackageIdentity $productionPackage

$testPathMatches = @(
    $packageList |
        Where-Object {
            $_ -match 'dev\.aurorama\.kokoro\.(?:harness|focuspeer)'
        }
)
$productionPathMatches = @(
    $packageList |
        Where-Object { $_ -match [regex]::Escape($productionPackage) }
)

$musicVolumeMatch = [regex]::Match(
    $audioText,
    '(?ms)^- STREAM_MUSIC:\s*.*?^\s*streamVolume:(\d+)\s*$')
$snapshot = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.device-snapshot.v1'
    capturedAt = $startedAt
    serial = $Serial
    state = ((Invoke-Adb @('get-state')) -join "`n").Trim()
    sdk = [int] ((Invoke-Adb @('shell', 'getprop ro.build.version.sdk')) -join "`n").Trim()
    abi = ((Invoke-Adb @('shell', 'getprop ro.product.cpu.abi')) -join "`n").Trim()
    device = ((Invoke-Adb @('shell', 'getprop ro.product.device')) -join "`n").Trim()
    keyguardStateSamples = $keyguardStates
    keyguardShowing = $keyguardShowing
    wakefulness = First-Match @($powerText -split "`n") 'mWakefulness=(\w+)'
    currentUser = [int] (($currentUserLines -join "`n").Trim())
    screenOffTimeout = (($screenLines -join "`n").Trim())
    stayOnWhilePluggedIn = (($stayOnLines -join "`n").Trim())
    lockAfterTimeout = (($lockAfterLines -join "`n").Trim())
    musicVolume = if ($musicVolumeMatch.Success) {
        [int] $musicVolumeMatch.Groups[1].Value
    } else {
        $null
    }
    airplaneMode = (($airplaneLines -join "`n").Trim())
    wifiEnabled = (($wifiLines -join "`n").Trim())
    mobileData = (($mobileDataLines -join "`n").Trim())
    activeDefaultNetworkLine = @(
        $connectivityText -split "`n" |
            Select-String -Pattern 'Active default network:' |
            Select-Object -First 1 |
            ForEach-Object { $_.Line.Trim() }
    )
    productionPackage = $productionIdentity
    productionPackagePathMatches = $productionPathMatches
    testPackagePaths = @($testPathMatches)
    testPackageIdentities = $packageIdentities
    notificationRecordCount = [int] (
        [regex]::Matches(
            $notificationText,
            'NotificationRecord\(.*?dev\.aurorama\.kokoro\.(?:harness|focuspeer)').Count
    )
    activeMediaSessionCount = [int] (
        [regex]::Matches(
            $mediaText,
            '(?m)^\s*MediaSessionRecord.*dev\.aurorama\.kokoro\.(?:harness|focuspeer)').Count
    )
    testServiceRecordCount = [int] (
        @(
            $serviceText |
                Select-String -Pattern 'ServiceRecord.*dev\.aurorama\.kokoro\.(?:harness|focuspeer)'
        ).Count
    )
}

$parent = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
$null = New-Item -ItemType Directory -Path $parent -Force
[IO.File]::WriteAllText(
    ([IO.Path]::GetFullPath($OutputPath)),
    ($snapshot | ConvertTo-Json -Depth 10),
    $utf8
)
$snapshot | ConvertTo-Json -Depth 10
