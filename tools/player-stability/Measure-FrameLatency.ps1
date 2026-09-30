<#
.SYNOPSIS
  PLAYER-STAB：前台播放的帧节奏测量（SurfaceFlinger --latency）。

.DESCRIPTION
  视频输出是 TextureView，`dumpsys gfxinfo` 统计不到视频帧（实测 Total frames=0）；
  这里改用 SurfaceFlinger 的 layer present 时间戳采样应用窗口（ff592e ... PlayerActivity#...），
  统计相邻上屏帧的间隔 P50 / P95 / P99、平均 FPS 与 jank 帧占比。

  jank 口径：帧间隔 > max(1.8 × P50, 1.5 × 屏幕刷新周期) 记为一次掉帧
  （P50 代表视频本身的帧率，避免 24/30fps 视频把所有帧都算成 jank）。

  用法：前台打开要测的视频 → 运行脚本 -Seconds 30；同一视频分别测 mpv / ExoPlayer 两个内核。

.EXAMPLE
  .\tools\player-stability\Measure-FrameLatency.ps1 -Serial 43af8627 -Seconds 30
#>
[CmdletBinding()]
param(
    [string]$Serial = '43af8627',
    [string]$Package = 'com.zhangwenkang.cinefin.debug',
    [int]$Seconds = 30,
    [int]$SampleIntervalSeconds = 4,
    [string]$OutputJson = ''
)

$ErrorActionPreference = 'Stop'
$adbArgs = @('-s', $Serial)

function Invoke-Adb {
    $remaining = $args
    return (& adb @adbArgs @remaining 2>&1) -join "`n"
}

function Get-PlayerLayer {
    $pattern = [regex]::Escape($Package) + '.*PlayerActivity#\d+$'
    $lines = (Invoke-Adb shell dumpsys SurfaceFlinger --list) -split "`n"
    $match = $lines | Where-Object { $_ -match $pattern } | Select-Object -Last 1
    if (-not $match) { throw "未找到 $Package 的 PlayerActivity layer；确认播放页在前台" }
    return $match.Trim()
}

function Get-Percentile {
    param([double[]]$SortedValues, [double]$Percentile)
    if ($SortedValues.Count -eq 0) { return [double]::NaN }
    $index = [math]::Floor(($SortedValues.Count - 1) * $Percentile)
    return [math]::Round($SortedValues[$index], 2)
}

$layer = Get-PlayerLayer
Write-Output "layer = $layer"

$presentTimes = [System.Collections.Generic.SortedSet[double]]::new()
$readyTimes = [System.Collections.Generic.SortedSet[double]]::new()
$refreshMs = 0.0
$samples = 0
$deadline = (Get-Date).AddSeconds($Seconds)

while ((Get-Date) -lt $deadline) {
    $raw = Invoke-Adb shell dumpsys SurfaceFlinger --latency "$layer"
    $lines = $raw -split "`n"
    if ($lines.Count -lt 2) { continue }
    if ($refreshMs -le 0) { $refreshMs = [double]$lines[0] / 1e6 }
    foreach ($line in $lines[1..($lines.Count - 1)]) {
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -ge 3 -and $parts[1] -ne '0') {
            # SurfaceFlinger 用 INT64_MAX 表示尚未 present 的 pending 帧，必须剔除
            $timestampNs = [double]$parts[1]
            if ($timestampNs -gt 0 -and $timestampNs -lt 1e18) {
                [void]$presentTimes.Add($timestampNs)
            }
            # frameReady 去重后是"真正提交了新 buffer 的帧"，可过滤同一帧被重复合成的假节奏
            $readyNs = [double]$parts[2]
            if ($readyNs -gt 0 -and $readyNs -lt 1e18) {
                [void]$readyTimes.Add($readyNs)
            }
        }
    }
    $samples++
    Start-Sleep -Seconds $SampleIntervalSeconds
}

function Get-IntervalStats {
    param([double[]]$Times, [double]$RefreshMs)
    $sorted = @($Times | Sort-Object)
    if ($sorted.Count -lt 2) {
        return [pscustomobject]@{
            frames = $sorted.Count; fps = 0.0; p50_ms = 0.0; p95_ms = 0.0; p99_ms = 0.0
            max_ms = 0.0; jank_threshold_ms = 0.0; jank_frames = 0; jank_pct = 0.0
        }
    }
    $deltas = @(for ($i = 1; $i -lt $sorted.Count; $i++) { ($sorted[$i] - $sorted[$i - 1]) / 1e6 })
    $deltasSorted = @($deltas | Sort-Object)
    $p50 = Get-Percentile -SortedValues $deltasSorted -Percentile 0.50
    $p95 = Get-Percentile -SortedValues $deltasSorted -Percentile 0.95
    $p99 = Get-Percentile -SortedValues $deltasSorted -Percentile 0.99
    $avg = [double](($deltas | Measure-Object -Average).Average)
    $max = [math]::Round(($deltas | Measure-Object -Maximum).Maximum, 2)
    $threshold = [math]::Max(1.8 * $p50, 1.5 * $RefreshMs)
    $jank = @($deltas | Where-Object { $_ -gt $threshold })
    return [pscustomobject]@{
        frames            = $sorted.Count
        fps               = [math]::Round(1000.0 / $avg, 1)
        p50_ms            = $p50
        p95_ms            = $p95
        p99_ms            = $p99
        max_ms            = $max
        jank_threshold_ms = [math]::Round($threshold, 2)
        jank_frames       = $jank.Count
        jank_pct          = [math]::Round(100.0 * $jank.Count / $deltas.Count, 2)
    }
}

if ($presentTimes.Count -lt 5) {
    throw "只采到 $($presentTimes.Count) 帧：确认播放页在前台、视频正在渲染（不是锁屏 / 后台）"
}

$presentStats = Get-IntervalStats -Times @($presentTimes) -RefreshMs $refreshMs
$readyStats = Get-IntervalStats -Times @($readyTimes) -RefreshMs $refreshMs

$result = [pscustomobject]@{
    serial         = $Serial
    layer          = $layer
    window_seconds = $Seconds
    samples        = $samples
    refresh_ms     = [math]::Round($refreshMs, 2)
    # present：SurfaceFlinger 合成节奏（可能重复合成同一帧）
    present_frames = $presentStats.frames
    present_fps    = $presentStats.fps
    present_p50_ms = $presentStats.p50_ms
    present_p95_ms = $presentStats.p95_ms
    present_p99_ms = $presentStats.p99_ms
    present_jank_pct = $presentStats.jank_pct
    # ready：去重后的真实新 buffer 帧节奏（视频帧）
    ready_frames   = $readyStats.frames
    ready_fps      = $readyStats.fps
    ready_p50_ms   = $readyStats.p50_ms
    ready_p95_ms   = $readyStats.p95_ms
    ready_p99_ms   = $readyStats.p99_ms
    ready_jank_pct = $readyStats.jank_pct
}

$result | Format-List | Out-String | Write-Output
if ($OutputJson) {
    $result | ConvertTo-Json | Set-Content -Path $OutputJson -Encoding utf8
    Write-Output "json = $OutputJson"
}
