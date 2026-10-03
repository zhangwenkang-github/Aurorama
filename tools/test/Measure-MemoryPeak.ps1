<#
.SYNOPSIS
  真机内存峰值基线：冷启动后按固定间隔采样 dumpsys meminfo，取峰值与时间序列。

.DESCRIPTION
  对应 docs/TEST_PLAN.md §2.3。采样口径为「应用 TOTAL PSS(kB)」，
  每次采样 force-stop → 冷启动 → 前台静置采样，避免拿后台进程数据。
  注意：debug 构建带调试开销，数值只用于同口径对比（不作发布口径）。

.EXAMPLE
  .\tools\test\Measure-MemoryPeak.ps1 -Seconds 60 -IntervalSeconds 2
#>
[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = 'io.github.zhangwenkang.aurorama.debug',
    [string]$Activity = 'com.zhangwenkang.cinefin.MainActivity',
    [int]$Seconds = 60,
    [double]$IntervalSeconds = 2,
    [switch]$KeepRunning,
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$script:AdbArgs = @()
if ($Serial) { $script:AdbArgs = @('-s', $Serial) }

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path $env:TEMP "cinefin-mempeak-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-Adb {
    # 保持普通函数：不能用参数属性，避免 `am start -W` 被当作 -WarningAction。
    $RemainingArgs = $args
    return (& adb @script:AdbArgs @RemainingArgs 2>&1) -join "`n"
}

function Get-TotalPssKb([string]$Dump) {
    # 依次尝试：进程明细的 `TOTAL PSS:` → App Summary 的 `TOTAL:` → 明细表 `TOTAL <n>`。
    $m = [regex]::Match($Dump, '(?m)^\s*TOTAL PSS:\s+(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($Dump, '(?m)^\s*TOTAL:\s+(\d+)') }
    if (-not $m.Success) { $m = [regex]::Match($Dump, '(?m)^\s*TOTAL\s+(\d+)') }
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return -1
}

if (-not $KeepRunning) {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell am start -W -n "$Package/$Activity" | Out-Null
}

$samples = @()
$rawLog = @()
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    $dump = Invoke-Adb shell dumpsys meminfo $Package
    $pss = Get-TotalPssKb $dump
    $samples += [ordered]@{ t = (Get-Date).ToString('HH:mm:ss'); pssKb = $pss }
    $rawLog += "=== $((Get-Date).ToString('s')) pssKb=$pss ===`n$dump"
    Write-Output ("采样 {0}: TOTAL PSS = {1} kB" -f (Get-Date -Format 'HH:mm:ss'), $pss)
    Start-Sleep -Seconds $IntervalSeconds
}

$valid = @($samples | Where-Object { $_.pssKb -gt 0 } | ForEach-Object { $_.pssKb })
$summary = [ordered]@{
    collectedAt   = (Get-Date).ToString('s')
    serial        = (Invoke-Adb get-serialno).Trim()
    model         = (Invoke-Adb shell getprop ro.product.model).Trim()
    androidRelease= (Invoke-Adb shell getprop ro.build.version.release).Trim()
    package       = $Package
    durationSec   = $Seconds
    intervalSec   = $IntervalSeconds
    samples       = $samples
    peakPssKb     = if ($valid) { ($valid | Measure-Object -Maximum).Maximum } else { -1 }
    minPssKb      = if ($valid) { ($valid | Measure-Object -Minimum).Minimum } else { -1 }
    sampleCount   = $valid.Count
    command       = "adb shell dumpsys meminfo $Package (每 $IntervalSeconds s 采样 $Seconds s；先 force-stop + am start -W)"
    note          = 'TOTAL PSS 取 dumpsys meminfo 的 App Summary TOTAL 字段；debug 口径，仅同口径对比'
}

$rawLog -join "`n" | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'mempeak-raw.txt')
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'mempeak.json')

Write-Output ("`n内存峰值 TOTAL PSS = {0} kB（{1} 个有效采样，min {2} kB）" -f `
        $summary.peakPssKb, $summary.sampleCount, $summary.minPssKb)
Write-Output "[OK] 原始输出: $OutputDir"
