[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after')]
    [string] $Prefix
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
    }
}

function Read-Setting([string] $Namespace, [string] $Key) {
    return ((Invoke-Adb @(
        'shell', 'settings', 'get', $Namespace, $Key)).text).Trim()
}

function Read-NetworkState {
    $connectivity = (Invoke-Adb @('shell', 'dumpsys', 'connectivity')).text
    $defaultMatch = [regex]::Match(
        $connectivity,
        'Active default network:\s*([^\s]+)')
    $transport = ''
    $transportMatch = [regex]::Match(
        $connectivity,
        'NetworkAgentInfo[^\r\n]*\b(WIFI|MOBILE|ETHERNET)\b')
    if ($transportMatch.Success) {
        $transport = $transportMatch.Groups[1].Value
    }
    return [ordered]@{
        airplaneMode = Read-Setting 'global' 'airplane_mode_on'
        wifiOn = Read-Setting 'global' 'wifi_on'
        mobileData = Read-Setting 'global' 'mobile_data'
        activeDefaultNetwork = $defaultMatch.Groups[1].Value
        transport = $transport
    }
}

function Read-ProductionPackages {
    $result = [Collections.Generic.List[object]]::new()
    foreach ($package in @(
        'io.github.zhangwenkang.aurorama',
        'io.github.zhangwenkang.aurorama.debug'
    )) {
        $pathResult = Invoke-Adb @('shell', 'pm', 'path', $package)
        $paths = @(
            $pathResult.text -split "`r?`n" |
                Where-Object { $_ -match '^package:' } |
                ForEach-Object { $_ -replace '^package:', '' }
        )
        $apks = [Collections.Generic.List[object]]::new()
        foreach ($path in $paths) {
            $hashResult = Invoke-Adb @('shell', 'sha256sum', $path)
            $sha256 = [regex]::Match(
                $hashResult.text,
                '\b([0-9a-f]{64})\b').Groups[1].Value
            $sizeResult = Invoke-Adb @('shell', 'stat', '-c', '%s', $path)
            $size = [regex]::Match($sizeResult.text, '^\s*(\d+)').Groups[1].Value
            $apks.Add([ordered]@{
                path = $path
                bytes = if ($size) { [long]$size } else { $null }
                sha256 = $sha256
            })
        }
        $dump = (Invoke-Adb @('shell', 'dumpsys', 'package', $package)).text
        $metadata = @(
            $dump -split "`r?`n" |
                Where-Object {
                    $_ -match 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|codePath=|primaryCpuAbi='
                }
        )
        $result.Add([ordered]@{
            package = $package
            installed = $paths.Count -gt 0
            apks = @($apks)
            metadata = $metadata
        })
    }
    return @($result)
}

$network = Read-NetworkState
$volumeText = (Invoke-Adb @(
    'shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get')).text
$volumeMatch = [regex]::Match($volumeText, 'volume is (\d+) in range \[(\d+)\.\.(\d+)\]')
$powerText = (Invoke-Adb @('shell', 'dumpsys', 'power')).text
$windowText = (Invoke-Adb @('shell', 'dumpsys', 'window', 'policy')).text
$keyguardMatch = [regex]::Match(
    $windowText,
    'mIsShowing=(true|false)|showing=(true|false)')
$keyguardText = if ($keyguardMatch.Success) {
    $keyguardMatch.Groups[1].Value + $keyguardMatch.Groups[2].Value
} else {
    $null
}
$snapshot = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.device-snapshot.v1'
    serial = $Serial
    prefix = $Prefix
    capturedAt = (Get-Date).ToString('o')
    network = $network
    musicVolume = [ordered]@{
        raw = $volumeText.Trim()
        value = if ($volumeMatch.Success) { [int]$volumeMatch.Groups[1].Value } else { $null }
        min = if ($volumeMatch.Success) { [int]$volumeMatch.Groups[2].Value } else { $null }
        max = if ($volumeMatch.Success) { [int]$volumeMatch.Groups[3].Value } else { $null }
    }
    screen = [ordered]@{
        wakefulness = [regex]::Match(
            $powerText,
            'mWakefulness=([^\s]+)').Groups[1].Value
        screenOffTimeout = Read-Setting 'system' 'screen_off_timeout'
        lockAfterTimeout = Read-Setting 'secure' 'lock_screen_lock_after_timeout'
        stayOn = Read-Setting 'global' 'stay_on_while_plugged_in'
        keyguardShowing = $keyguardText
    }
    currentUser = [ordered]@{
        current = ((Invoke-Adb @(
            'shell', 'am', 'get-current-user')).text).Trim()
        users = ((Invoke-Adb @(
            'shell', 'pm', 'list', 'users')).text).Trim()
    }
    productionPackages = Read-ProductionPackages
    harnessPackagesPresent = @(
        (Invoke-Adb @(
            'shell', 'pm', 'list', 'packages', 'dev.aurorama.kokoro')).text -split "`r?`n" |
            Where-Object { $_ -match '^package:' }
    )
}

$path = Join-Path $outputPath ("device-$Prefix.json")
[IO.File]::WriteAllText(
    $path,
    ($snapshot | ConvertTo-Json -Depth 20),
    $utf8)
$snapshot | ConvertTo-Json -Depth 10
