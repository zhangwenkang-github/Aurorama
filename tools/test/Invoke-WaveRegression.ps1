<#
.SYNOPSIS
  每波回归只读检查：崩溃 / ANR / MediaSession / 前台 Activity / UI 文本 / 内存。

.DESCRIPTION
  对应 docs/TEST_PLAN.md §4「每波回归清单」的自动化部分。
  本脚本只做 adb 只读命令（force-stop 与 am start 除外，用于构造验证场景），
  不触碰 Jellyfin 服务器，不做任何写白名单之外的调用。
  人工项（登录状态、播放 / 阅读流程、写白名单核对）见 TEST_PLAN §4。

.EXAMPLE
  .\tools\test\Invoke-WaveRegression.ps1 -Wave W2 -LogcatMinutes 30
#>
[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [ValidateSet('W2', 'W3', 'W4', 'W5', 'W6')][string]$Wave = 'W2',
    [string]$Package = 'io.github.zhangwenkang.aurorama.debug',
    [string]$Activity = 'com.zhangwenkang.cinefin.MainActivity',
    [int]$LogcatTailLines = 3000,
    [switch]$SkipUiDump,
    [string]$OutputRoot = 'E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\regression',
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$script:AdbArgs = @()
if ($Serial) { $script:AdbArgs = @('-s', $Serial) }

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path $OutputRoot "$Wave-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-Adb {
    # 保持普通函数：不能用参数属性，避免 `am start -W` 被当作 -WarningAction。
    $RemainingArgs = $args
    return (& adb @script:AdbArgs @RemainingArgs 2>&1) -join "`n"
}

function Save-Report([string]$Name, [string]$Text) {
    $Text | Set-Content -Encoding UTF8 (Join-Path $OutputDir "$Name.txt")
    Write-Output "[saved] $Name.txt"
}

$checks = [ordered]@{}

# 1. 崩溃 / ANR：取 logcat 尾部 N 行快照（只读，不 clear）
$log = Invoke-Adb logcat -d -v threadtime -t $LogcatTailLines
Save-Report 'logcat-crash' $log
$fatal = @($log -split "`n" | Select-String -Pattern 'FATAL EXCEPTION|ANR in |beginning of crash')
$checks.crashOrAnr = if ($fatal.Count -eq 0) { 'PASS (无命中)' } else { "FAIL ($($fatal.Count) 条命中，见 logcat-crash.txt)" }

# 2. MediaSession（音乐 / 视频会话互斥）
$ms = Invoke-Adb shell dumpsys media_session
Save-Report 'media-session' (($ms -split "`n" | Select-String -Pattern 'cinefin|state=|package=' | Select-Object -First 60) -join "`n")
$checks.mediaSession = if ($ms -match 'io\.github\.zhangwenkang\.aurorama') { 'INFO (存在 aurorama 会话)' } else { 'INFO (无 aurorama 会话)' }

# 3. 前台窗口 / Activity
$focus = Invoke-Adb shell dumpsys window displays
$focusLine = ($focus -split "`n" | Select-String -Pattern 'mCurrentFocus|mFocusedApp' | Select-Object -First 4) -join "`n"
Save-Report 'window-focus' $focusLine
$checks.foreground = $focusLine

# 4. 应用版本 / 安装信息
$pkgInfo = (Invoke-Adb shell dumpsys package $Package) -split "`n" |
    Select-String -Pattern 'versionName=|firstInstallTime=|lastUpdateTime=' | Select-Object -First 5
Save-Report 'package-info' ($pkgInfo -join "`n")

# 5. UI 文本（可选）：冷启动后 dump 当前界面文本
if (-not $SkipUiDump) {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell am start -W -n "$Package/$Activity" | Out-Null
    Start-Sleep -Seconds 5
    $dumpPath = "/sdcard/cinefin-regression-$Wave.xml"
    Invoke-Adb shell uiautomator dump $dumpPath | Out-Null
    $xml = Invoke-Adb shell cat $dumpPath
    Save-Report 'ui-dump' $xml
    $texts = @([regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ } | Select-Object -Unique)
    Save-Report 'ui-texts' ($texts -join "`n")
    $checks.uiTextCount = $texts.Count
    Invoke-Adb shell rm $dumpPath | Out-Null
}

# 6. 内存快照（回归对照基线用）
$mem = Invoke-Adb shell dumpsys meminfo $Package
$m = [regex]::Match($mem, '(?m)^\s*TOTAL PSS:\s+(\d+)')
if (-not $m.Success) { $m = [regex]::Match($mem, '(?m)^\s*TOTAL:\s+(\d+)') }
if (-not $m.Success) { $m = [regex]::Match($mem, '(?m)^\s*TOTAL\s+(\d+)') }
Save-Report 'meminfo' $mem
$checks.currentTotalPssKb = if ($m.Success) { [int]$m.Groups[1].Value } else { -1 }

$summary = [ordered]@{
    collectedAt   = (Get-Date).ToString('s')
    wave          = $Wave
    serial        = (Invoke-Adb get-serialno).Trim()
    model         = (Invoke-Adb shell getprop ro.product.model).Trim()
    package       = $Package
    logcatTailLines = $LogcatTailLines
    checks        = $checks
    outputDir     = $OutputDir
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'summary.json')
Write-Output "`n==== $Wave 回归摘要 ===="
$checks.GetEnumerator() | ForEach-Object { '{0,-20} {1}' -f $_.Key, $_.Value }
Write-Output "[OK] 原始输出: $OutputDir"
