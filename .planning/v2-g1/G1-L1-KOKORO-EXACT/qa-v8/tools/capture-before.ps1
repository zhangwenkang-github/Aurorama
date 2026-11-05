[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$utf8 = [Text.UTF8Encoding]::new($false)
$mainPackage = 'dev.aurorama.kokoro.harness'
$peerPackage = 'dev.aurorama.kokoro.focuspeer'

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Read-Setting([string] $Namespace, [string] $Key) {
    return ((Invoke-Adb @(
        'shell', 'settings', 'get', $Namespace, $Key)).text).Trim()
}

function Read-ProductionPackages {
    $result = @()
    foreach ($package in @(
        'io.github.zhangwenkang.aurorama',
        'io.github.zhangwenkang.aurorama.debug'
    )) {
        $pathResult = Invoke-Adb @('shell', 'pm', 'path', $package)
        $pathText = $pathResult.text.Trim()
        $basePath = (
            $pathText -split "`r?`n" |
                Where-Object { $_ -match '^package:' } |
                Select-Object -First 1
        ) -replace '^package:', ''
        $stat = $null
        $sha = $null
        $versionLine = @()
        if ($basePath) {
            $stat = Invoke-Adb @('shell', 'stat', '-c', '%s', $basePath)
            $sha = Invoke-Adb @('shell', 'sha256sum', $basePath)
            $packageDump = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
            $versionLine = @(
                ($packageDump.text -split "`r?`n") |
                    Where-Object {
                        $_ -match 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|codePath=|primaryCpuAbi='
                    }
            )
        }
        $result += [ordered]@{
            package = $package
            pathText = $pathText
            basePath = $basePath
            stat = $stat
            sha256 = $sha
            versionLine = $versionLine
        }
    }
    return @($result)
}

$powerText = (Invoke-Adb @('shell', 'dumpsys', 'power')).text
$windowText = (Invoke-Adb @('shell', 'dumpsys', 'window', 'policy')).text
$connectivityText = (Invoke-Adb @('shell', 'dumpsys', 'connectivity')).text
$volumeCommand = Invoke-Adb @(
    'shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get')
$currentUser = (Invoke-Adb @(
    'shell', 'am', 'get-current-user')).text.Trim()
$keyguardValue = [regex]::Match(
    $windowText,
    'mIsShowing=(true|false)').Groups[1].Value
if (-not $keyguardValue) {
    $keyguardValue = [regex]::Match(
        $windowText,
        'showing=(true|false)').Groups[1].Value
}
if ($keyguardValue -ne 'false') {
    throw "USER_UNLOCK_REQUIRED: keyguard value=$keyguardValue"
}

$harnessPackages = (Invoke-Adb @(
    'shell', 'pm', 'list', 'packages', 'dev.aurorama.kokoro')).text.Trim()
$serviceMain = (Invoke-Adb @(
    'shell', 'dumpsys', 'activity', 'services', $mainPackage)).text
$notification = (Invoke-Adb @(
    'shell', 'dumpsys', 'notification', '--noredact')).text
$volumeMatch = [regex]::Match(
    $volumeCommand.text,
    'volume is (\d+) in range \[(\d+)\.\.(\d+)\]')
$snapshot = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.before-device.v1'
    capturedAt = (Get-Date).ToString('o')
    serial = $Serial
    model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model')).text.Trim()
    sdk = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')).text.Trim()
    abi = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abilist')).text.Trim()
    fingerprint = (Invoke-Adb @(
        'shell', 'getprop', 'ro.build.fingerprint')).text.Trim()
    powerLines = @(
        ($powerText -split "`r?`n") |
            Where-Object { $_ -match 'mWakefulness=|mStayOn=' }
    )
    wakefulness = [regex]::Match(
        $powerText,
        'mWakefulness=([^\s]+)').Groups[1].Value
    keyguardLines = @(
        ($windowText -split "`r?`n") |
            Where-Object {
                $_ -match 'KeyguardStateMonitor|mIsShowing=|showing=|deviceHasKeyguard|mKeyguard'
            } |
            Select-Object -First 20
    )
    keyguardShowing = $keyguardValue
    currentUser = $currentUser
    users = (Invoke-Adb @('shell', 'pm', 'list', 'users')).text.Trim()
    harnessPackages = $harnessPackages
    harnessServicePresent = $serviceMain -match 'KokoroRuntimeService'
    harnessNotificationRecordPresent = @(
        ($notification -split "`r?`n") |
            Where-Object {
                $_ -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.(harness|focuspeer)'
            }
    )
    airplaneMode = Read-Setting 'global' 'airplane_mode_on'
    wifiOn = Read-Setting 'global' 'wifi_on'
    mobileData = Read-Setting 'global' 'mobile_data'
    activeNetworkLines = @(
        ($connectivityText -split "`r?`n") |
            Where-Object { $_ -match 'Active default network:' } |
            Select-Object -First 3
    )
    musicVolumeCommand = [ordered]@{
        exitCode = $volumeCommand.exitCode
        text = $volumeCommand.text
    }
    musicVolumeSetting = if ($volumeMatch.Success) {
        [int]$volumeMatch.Groups[1].Value
    } else {
        $null
    }
    musicVolumeRange = if ($volumeMatch.Success) {
        "$($volumeMatch.Groups[2].Value)..$($volumeMatch.Groups[3].Value)"
    } else {
        $null
    }
    screenOffTimeout = Read-Setting 'system' 'screen_off_timeout'
    lockAfterTimeout = Read-Setting 'secure' 'lock_screen_lock_after_timeout'
    stayOn = Read-Setting 'global' 'stay_on_while_plugged_in'
    productionPackages = Read-ProductionPackages
}

$path = Join-Path $outputPath 'before.json'
[IO.File]::WriteAllText(
    $path,
    (($snapshot | ConvertTo-Json -Depth 20) + "`n"),
    $utf8)
$snapshot | ConvertTo-Json -Depth 10
