<#
.SYNOPSIS
  采集 Cinefin 真机性能基线所需的设备与环境信息（只读）。

.DESCRIPTION
  对应 docs/TEST_PLAN.md §2「性能基线方法」。输出分为人类可读文本与
  info.json（供基线汇总使用），原始输出保存到 -OutputDir。

.EXAMPLE
  .\tools\test\Get-DeviceInfo.ps1 -Serial 43af8627
#>
[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = 'com.zhangwenkang.cinefin.debug',
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$script:AdbArgs = @()
if ($Serial) { $script:AdbArgs = @('-s', $Serial) }

function Invoke-Adb {
    # 注意：不要给本函数加 [Parameter()] / [CmdletBinding()]，否则会变成“高级函数”，
    # 把 `am start -W` 里的 -W 抢去绑定 -WarningAction 导致歧义报错。
    $RemainingArgs = $args
    $raw = & adb @script:AdbArgs @RemainingArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ("adb {0} 退出码 {1}: {2}" -f ($RemainingArgs -join ' '), $LASTEXITCODE, ($raw -join ' '))
    }
    return ($raw -join "`n")
}

function Get-Prop([string]$Name) {
    return ((Invoke-Adb shell getprop $Name)).Trim()
}

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path $env:TEMP "cinefin-deviceinfo-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$model = Get-Prop 'ro.product.model'
$device = Get-Prop 'ro.product.device'
$android = Get-Prop 'ro.build.version.release'
$sdk = Get-Prop 'ro.build.version.sdk'
$abis = Get-Prop 'ro.product.cpu.abilist'
$fingerprint = Get-Prop 'ro.build.fingerprint'
$memTotalKb = ((Invoke-Adb shell cat /proc/meminfo) -split "`n" |
    Select-String '^MemTotal:' | Select-Object -First 1) -replace '\D', ''
$dfLine = ($(Invoke-Adb shell df -k /data) -split "`n" | Select-Object -Last 1)
$wmSize = (Invoke-Adb shell wm size) -replace "`n", ' '
$wmDensity = (Invoke-Adb shell wm density) -replace "`n", ' '
$appVersion = ((Invoke-Adb shell dumpsys package $Package) -split "`n" |
    Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
$appFirstInstall = ((Invoke-Adb shell dumpsys package $Package) -split "`n" |
    Select-String 'firstInstallTime=' | Select-Object -First 1).ToString().Trim()

$info = [ordered]@{
    collectedAt       = (Get-Date).ToString('s')
    serial            = (Invoke-Adb get-serialno).Trim()
    model             = $model
    device            = $device
    androidRelease    = $android
    sdk               = $sdk
    abiList           = $abis
    buildFingerprint  = $fingerprint
    totalRamKb        = [int]$memTotalKb
    dataFreeKb        = if ($dfLine -match '\s(\d+)\s+\d+%\s*/data') { [int]$Matches[1] } else { -1 }
    wmSize            = $wmSize.Trim()
    wmDensity         = $wmDensity.Trim()
    package           = $Package
    appVersion        = $appVersion
    appFirstInstall   = $appFirstInstall
}

$info | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'info.json')
$info.GetEnumerator() | ForEach-Object { '{0,-18} {1}' -f $_.Key, $_.Value } |
    Set-Content -Encoding UTF8 (Join-Path $OutputDir 'info.txt')
$info.GetEnumerator() | ForEach-Object { '{0,-18} {1}' -f $_.Key, $_.Value }
Write-Output "`n[OK] 原始输出: $OutputDir"
