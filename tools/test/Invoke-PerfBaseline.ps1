<#
.SYNOPSIS
  一键跑完 Cinefin 性能基线：设备信息 + 冷启动 ×3 中位 + 内存峰值 + APK 体积。

.DESCRIPTION
  对应 docs/TEST_PLAN.md §2。原始输出默认写到仓库外本地目录（不入库）：
    E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\baseline\<时间戳>-<设备>
  该目录下的 summary.json 是 TEST_PLAN §2.5 基线表的取证来源。

.EXAMPLE
  .\tools\test\Invoke-PerfBaseline.ps1
  .\tools\test\Invoke-PerfBaseline.ps1 -Serial 43af8627 -ColdStartRuns 3 -MemorySeconds 60
#>
[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = 'com.zhangwenkang.cinefin.debug',
    [string]$Activity = 'com.zhangwenkang.cinefin.MainActivity',
    [int]$ColdStartRuns = 3,
    [int]$MemorySeconds = 60,
    [double]$MemoryIntervalSeconds = 2,
    [switch]$Rebuild,
    [string]$OutputRoot = 'E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\baseline',
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $device = if ($Serial) { $Serial } else { 'auto' }
    $OutputDir = Join-Path $OutputRoot "$stamp-$device"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$common = @{ Serial = $Serial; Package = $Package }

& "$here\Get-DeviceInfo.ps1" @common -OutputDir $OutputDir | Out-Null
$device = (Get-Content (Join-Path $OutputDir 'info.json') -Raw | ConvertFrom-Json)

if ($Rebuild) {
    $env:JAVA_HOME = 'D:\Android\Android Studio\jbr'
    & .\gradlew.bat :app:phone:assembleDebug --console=plain | Select-Object -Last 5
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug 失败（退出码 $LASTEXITCODE）" }
}

& "$here\Measure-ApkSize.ps1" -OutputDir $OutputDir
& "$here\Measure-ColdStart.ps1" @common -Activity $Activity -Runs $ColdStartRuns -OutputDir $OutputDir
& "$here\Measure-MemoryPeak.ps1" @common -Activity $Activity -Seconds $MemorySeconds `
    -IntervalSeconds $MemoryIntervalSeconds -OutputDir $OutputDir

$cold = Get-Content (Join-Path $OutputDir 'coldstart.json') -Raw | ConvertFrom-Json
$mem = Get-Content (Join-Path $OutputDir 'mempeak.json') -Raw | ConvertFrom-Json
$apk = Get-Content (Join-Path $OutputDir 'apksize.json') -Raw | ConvertFrom-Json
$arm64 = $apk.apks | Where-Object { $_.abi -eq 'arm64-v8a' } | Select-Object -First 1

$summary = [ordered]@{
    collectedAt        = (Get-Date).ToString('s')
    gitCommit          = (& git rev-parse --short HEAD).Trim()
    device             = $device.model
    deviceSerial       = $device.serial
    androidRelease     = $device.androidRelease
    package            = $Package
    coldStartMedianMs  = $cold.totalMedianMs
    coldStartRunsMs    = $cold.totalTimeMs
    memoryPeakPssKb    = $mem.peakPssKb
    memorySamples      = $mem.sampleCount
    apkArm64Bytes      = if ($arm64) { $arm64.bytes } else { -1 }
    apkArm64Mib        = if ($arm64) { $arm64.mib } else { -1 }
    outputDir          = $OutputDir
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'summary.json')
Write-Output "`n==== 基线汇总 ===="
$summary.GetEnumerator() | ForEach-Object {
    $v = if ($_.Value -is [array]) { $_.Value -join ', ' } else { $_.Value }
    '{0,-20} {1}' -f $_.Key, $v
}
Write-Output "[OK] 全部原始输出: $OutputDir"
