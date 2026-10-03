<#
.SYNOPSIS
  PLAYER-STAB：ANR 场景复现脚本（整季补片 + 连续点击）。

.DESCRIPTION
  复现 2026-10-01 02:01 的 ANR：PlayerActivity（mpv 内核，多集剧集）起播后
  fillQueueInBackground 逐集补片；旧实现会在主线程同步执行 mpv 的 loadfile 命令，
  主线程连续阻塞后，点击输入 5 秒得不到处理 → "Waited 5000ms for MotionEvent"。

  脚本在起播后的补片窗口内连续注入 tap，随后检查 logcat 是否出现
    "Input dispatching timed out" / "ANR in" / "Waited 5000ms"，
  并统计 MIUI PerfMonitor 的主线程最大 latency（latency=NNNms）。
  修复后期望：以上超时日志计数为 0，且 latency 峰值明显下降。

.EXAMPLE
  .\tools\player-stability\Invoke-AnrRepro.ps1 -ItemId 6718656d-4740-f3b0-0ad0-f2a1ca2fef51
#>
[CmdletBinding()]
param(
    [string]$Serial = '43af8627',
    [Parameter(Mandatory = $true)][string]$ItemId,
    [string]$ItemKind = 'Episode',
    [int]$TapCount = 30,
    [int]$TapX = 800,
    [int]$TapY = 1280,
    [int]$WarmupSeconds = 2,
    [int]$WaitAfterTapsSeconds = 8,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$Package = 'io.github.zhangwenkang.aurorama.debug'
$Activity = 'com.zhangwenkang.cinefin.PlayerActivity'
$adbArgs = @('-s', $Serial)

function Invoke-Adb {
    $remaining = $args
    return (& adb @adbArgs @remaining 2>&1) -join "`n"
}

Invoke-Adb logcat -c | Out-Null
Invoke-Adb shell am force-stop $Package | Out-Null
Start-Sleep -Seconds 1

$start = Invoke-Adb shell am start -n "$Package/$Activity" --es itemId $ItemId --es itemKind $ItemKind
if (-not $Quiet) { Write-Output ($start -split "`n" | Select-Object -Last 1) }
Start-Sleep -Seconds $WarmupSeconds

$tapStart = Get-Date
for ($i = 0; $i -lt $TapCount; $i++) {
    Invoke-Adb shell input tap $TapX $TapY | Out-Null
}
$tapSeconds = [math]::Round(((Get-Date) - $tapStart).TotalSeconds, 1)
Start-Sleep -Seconds $WaitAfterTapsSeconds

$log = Invoke-Adb logcat -d -t 6000
$inputTimeout = @([regex]::Matches($log, 'Input dispatching timed out')).Count
$anrIn = @([regex]::Matches($log, 'ANR in ')).Count
$waited = @([regex]::Matches($log, 'Waited \d+ms for MotionEvent')).Count
$latencyMatches = [regex]::Matches($log, 'PerfMonitor looperActivity[^\n]*latency=(\d+)ms')
$maxLatency = 0
foreach ($m in $latencyMatches) {
    $v = [int]$m.Groups[1].Value
    if ($v -gt $maxLatency) { $maxLatency = $v }
}

if (-not $Quiet) {
    Write-Output ("taps={0} tap_window={1}s wait={2}s" -f $TapCount, $tapSeconds, $WaitAfterTapsSeconds)
    Write-Output ("Input dispatching timed out = {0}" -f $inputTimeout)
    Write-Output ("ANR in = {0}" -f $anrIn)
    Write-Output ("Waited Nms for MotionEvent = {0}" -f $waited)
    Write-Output ("PerfMonitor max main-thread latency = {0} ms" -f $maxLatency)
}

[pscustomobject]@{
    serial                  = $Serial
    item_id                 = $ItemId
    tap_count               = $TapCount
    input_dispatch_timeouts = $inputTimeout
    anr_in                  = $anrIn
    waited_motion_events    = $waited
    perf_max_latency_ms     = $maxLatency
    anr_reproduced          = ($inputTimeout -gt 0 -or $anrIn -gt 0)
}
