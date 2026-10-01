<#
播放页 UI 走查辅助脚本（W9-PLAYER）。

为什么需要它：播放页是 Compose + 手势层混排，控制层 3.5 秒自动淡出，
用固定坐标连点很容易点空。这里把「dump → 找节点 → 点中心」做成一条命令，
并支持「先确保控制层可见」再操作，走查结果全部用文本（uiautomator dump）核对。

用法示例：
  .\Device-Ui.ps1 -Action state
  .\Device-Ui.ps1 -Action ensure-controls
  .\Device-Ui.ps1 -Action more                 # 打开「更多」面板
  .\Device-Ui.ps1 -Action tap-text -Value 播放信息
  .\Device-Ui.ps1 -Action tap-text -Value 播放设置
  .\Device-Ui.ps1 -Action tap-desc -Value 更多
  .\Device-Ui.ps1 -Action dump                 # 打印当前所有 text/content-desc + bounds
#>
param(
    [string]$Serial = '43af8627',
    [ValidateSet('dump', 'state', 'ensure-controls', 'pause', 'more', 'tap-text', 'tap-desc')]
    [string]$Action = 'dump',
    [string]$Value = '',
    [int]$Tries = 4
)

# uiautomator 会把「theme_config 不存在」之类的告警写到 stderr，这里不能因为 stderr 就中断
$ErrorActionPreference = 'Continue'
$remoteDump = '/sdcard/w9p_ui.xml'

function Get-UiXml {
    adb -s $Serial shell uiautomator dump $remoteDump 2>&1 | Out-Null
    return (adb -s $Serial shell cat $remoteDump) -join ''
}

function Get-Bounds([string]$xml, [string]$needle, [string]$attr) {
    $pattern = "$attr=`"$needle`"[^>]*bounds=`"(\[[0-9,\]\[]+\])`""
    $match = [regex]::Match($xml, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

function Tap-Bounds([string]$bounds) {
    if ($bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = [int]((([int]$matches[1]) + ([int]$matches[3])) / 2)
        $y = [int]((([int]$matches[2]) + ([int]$matches[4])) / 2)
        adb -s $Serial shell input tap $x $y | Out-Null
        return "$x,$y"
    }
    return '<invalid>'
}

# 控制层淡出后单击画面中心唤出（视频区中心，避开竖屏下方选集区）
function Ensure-Controls {
    for ($i = 0; $i -lt $Tries; $i++) {
        $xml = Get-UiXml
        if ($xml -match 'content-desc="更多"') { return $xml }
        adb -s $Serial shell input tap 800 600 | Out-Null
        Start-Sleep -Milliseconds 600
    }
    return (Get-UiXml)
}

switch ($Action) {
    'dump' {
        $xml = Get-UiXml
        [regex]::Matches($xml, '(?:text|content-desc)="([^"]+)"[^>]*bounds="(\[[0-9,\]\[]+\])"') |
            ForEach-Object { "{0}  {1}" -f $_.Groups[1].Value, $_.Groups[2].Value } |
            Select-Object -Unique
    }
    'state' {
        $xml = Get-UiXml
        $controls = if ($xml -match 'content-desc="更多"') { 'VISIBLE' } else { 'HIDDEN' }
        if ($xml -match '播放设置|播放信息|播放队列|循环模式') { $panel = 'OPEN' } else { $panel = 'NONE' }
        "controls=$controls panel=$panel nodes=$($xml.Length)"
    }
    'ensure-controls' {
        $xml = Ensure-Controls
        if ($xml -match 'content-desc="更多"') { 'controls=VISIBLE' } else { 'controls=HIDDEN' }
    }
    'pause' {
        # 暂停后控制层不再 3.5 秒自动淡出，后续 dump / 点按都稳定（走查专用）
        adb -s $Serial shell input keyevent 127 | Out-Null
        Start-Sleep -Milliseconds 900
        $xml = Ensure-Controls
        if ($xml -match 'content-desc="更多"') { 'paused; controls=VISIBLE' } else { 'paused; controls=HIDDEN' }
    }
    'more' {
        adb -s $Serial shell input keyevent 127 | Out-Null
        Start-Sleep -Milliseconds 700
        $xml = Ensure-Controls
        $bounds = Get-Bounds $xml '更多' 'content-desc'
        if (-not $bounds) { throw 'control layer not visible; run -Action ensure-controls first' }
        $tap = Tap-Bounds $bounds
        Start-Sleep -Milliseconds 800
        $xml = Get-UiXml
        if ($xml -match '播放信息') { "more panel opened at $tap" } else { 'more panel NOT opened' }
    }
    'tap-text' {
        $xml = Get-UiXml
        $bounds = Get-Bounds $xml $Value 'text'
        if (-not $bounds) { throw "text node not found: $Value" }
        $tap = Tap-Bounds $bounds
        Start-Sleep -Milliseconds 900
        "tapped '$Value' at $tap"
    }
    'tap-desc' {
        $xml = Get-UiXml
        $bounds = Get-Bounds $xml $Value 'content-desc'
        if (-not $bounds) { throw "content-desc node not found: $Value" }
        $tap = Tap-Bounds $bounds
        Start-Sleep -Milliseconds 900
        "tapped desc '$Value' at $tap"
    }
}
