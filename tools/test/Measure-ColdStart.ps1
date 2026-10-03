<#
.SYNOPSIS
  真机冷启动耗时基线：force-stop → am start -W，默认跑 3 次取中位数。

.DESCRIPTION
  对应 docs/TEST_PLAN.md §2.2。指标口径：
    TotalTime = Activity 从启动到首帧完成的耗时（含进程冷启动），主指标；
    WaitTime  = am 命令等待总时长，参考指标。
  每次测量前 force-stop 并静置 -SettleSeconds 秒，保证冷启动状态。
  原始文本逐次保存，汇总 JSON 计算 min/median(中位)/max。

.EXAMPLE
  .\tools\test\Measure-ColdStart.ps1 -Runs 3
  .\tools\test\Measure-ColdStart.ps1 -Serial 43af8627 -OutputDir E:\baseline\w2
#>
[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = 'io.github.zhangwenkang.aurorama.debug',
    [string]$Activity = 'com.zhangwenkang.cinefin.MainActivity',
    [int]$Runs = 3,
    [double]$SettleSeconds = 1.0,
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$script:AdbArgs = @()
if ($Serial) { $script:AdbArgs = @('-s', $Serial) }

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path $env:TEMP "cinefin-coldstart-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-Adb {
    # 保持普通函数：不能用参数属性，避免 `am start -W` 被当作 -WarningAction。
    $RemainingArgs = $args
    return (& adb @script:AdbArgs @RemainingArgs 2>&1) -join "`n"
}

$component = "$Package/$Activity"
$rawAll = @()
$total = @()
$wait = @()

for ($i = 1; $i -le $Runs; $i++) {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds $SettleSeconds
    $raw = Invoke-Adb shell am start -W -n $component
    $rawAll += "[run $i] $component`n$raw`n"
    $t = [regex]::Match($raw, 'TotalTime:\s*(\d+)').Groups[1].Value
    $w = [regex]::Match($raw, 'WaitTime:\s*(\d+)').Groups[1].Value
    $launchState = [regex]::Match($raw, 'LaunchState:\s*(\S+)').Groups[1].Value
    if (-not $t) { Write-Warning "run $i 未解析到 TotalTime，请人工检查原始输出" }
    $total += [int]$t
    $wait += [int]$w
    Write-Output ("run {0}: TotalTime={1} ms WaitTime={2} ms LaunchState={3}" -f $i, $t, $w, $launchState)
    Start-Sleep -Seconds $SettleSeconds
}

function Get-Median([int[]]$Values) {
    $sorted = $Values | Sort-Object
    $n = $sorted.Count
    if ($n % 2 -eq 1) { return $sorted[[int](($n - 1) / 2)] }
    return [int](($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2)
}

$summary = [ordered]@{
    collectedAt   = (Get-Date).ToString('s')
    serial        = (Invoke-Adb get-serialno).Trim()
    model         = (Invoke-Adb shell getprop ro.product.model).Trim()
    androidRelease= (Invoke-Adb shell getprop ro.build.version.release).Trim()
    package       = $Package
    component     = $component
    runs          = $Runs
    totalTimeMs   = $total
    totalMedianMs = (Get-Median $total)
    totalMinMs    = ($total | Measure-Object -Minimum).Minimum
    totalMaxMs    = ($total | Measure-Object -Maximum).Maximum
    waitTimeMs    = $wait
    waitMedianMs  = (Get-Median $wait)
    command       = "adb shell am force-stop $Package; Start-Sleep $SettleSeconds; adb shell am start -W -n $component"
}

$rawAll -join "`n" | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'coldstart-raw.txt')
$summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'coldstart.json')

Write-Output ("`n冷启动 TotalTime 中位数 = {0} ms（min {1} / max {2}，{3} 次）" -f `
        $summary.totalMedianMs, $summary.totalMinMs, $summary.totalMaxMs, $Runs)
Write-Output "[OK] 原始输出: $OutputDir"
