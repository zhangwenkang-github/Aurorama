[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = Join-Path $taskRoot 'runtime-closure-v2\device-K60-8e875894'
$logcatPath = Join-Path $evidenceRoot 'logcat-final.log'
$package = 'dev.aurorama.kokoro.harness'
$remoteUi = '/sdcard/kokoro_notification_controls.xml'
$step = 0

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Arguments)
    & adb -s $Serial @Arguments
}

function Save-Ui {
    $script:step++
    Invoke-Adb shell cmd statusbar expand-notifications | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell uiautomator dump $remoteUi | Out-Null
    $localPath = Join-Path $evidenceRoot ("notification-controls-$($script:step).xml")
    Invoke-Adb pull $remoteUi $localPath | Out-Null
    return [xml](Get-Content -LiteralPath $localPath -Raw -Encoding utf8)
}

function Find-Control {
    param(
        [xml] $Ui,
        [string] $Control
    )
    $targetId = "$package`:id/$Control"
    return $Ui.SelectNodes('//*[@resource-id]') |
        Where-Object { $_.'resource-id' -eq $targetId } |
        Select-Object -First 1
}

function Reveal-AggregateIfNeeded {
    param(
        [xml] $Ui,
        [string] $Control
    )
    $node = Find-Control -Ui $Ui -Control $Control
    if ($null -ne $node) {
        return $Ui
    }
    $aggregate = $Ui.SelectNodes('//*[@resource-id]') |
        Where-Object {
            $_.'resource-id' -eq 'com.android.systemui:id/custom_aggregate_notification'
        } |
        Select-Object -First 1
    if ($null -eq $aggregate) {
        throw "Notification aggregate not found while revealing $Control"
    }
    $match = [regex]::Match($aggregate.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $match.Success) {
        throw "Invalid aggregate bounds: $($aggregate.bounds)"
    }
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
    Start-Sleep -Milliseconds 800
    return Save-Ui
}

function Tap-Control {
    param([string] $Control)
    $ui = Save-Ui
    $ui = Reveal-AggregateIfNeeded -Ui $ui -Control $Control
    $node = Find-Control -Ui $ui -Control $Control
    if ($null -eq $node) {
        throw "Notification control not visible: $Control"
    }
    if ($node.enabled -ne 'true' -or $node.clickable -ne 'true') {
        throw "Notification control not actionable: $Control $($node.bounds)"
    }
    $match = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $match.Success) {
        throw "Invalid control bounds: $Control $($node.bounds)"
    }
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
    return [ordered]@{
        control = $Control
        text = $node.text
        bounds = $node.bounds
        tap = "$([int]$x),$([int]$y)"
    }
}

$observed = [Collections.Generic.List[object]]::new()
Invoke-Adb shell input keyevent 3 | Out-Null
Start-Sleep -Seconds 1

$observed.Add((Tap-Control -Control 'action_play_pause'))
Start-Sleep -Seconds 8
$observed.Add((Tap-Control -Control 'action_next'))
Start-Sleep -Seconds 5
$observed.Add((Tap-Control -Control 'action_play_pause'))
Start-Sleep -Seconds 2
$observed.Add((Tap-Control -Control 'action_previous'))
Start-Sleep -Seconds 2
$observed.Add((Tap-Control -Control 'action_play_pause'))
Start-Sleep -Seconds 8
$observed.Add((Tap-Control -Control 'action_stop'))
Start-Sleep -Seconds 3

$logText = Get-Content -LiteralPath $logcatPath -Raw -Encoding utf8
$commandLines =
    $logText -split "`r?`n" |
        Select-String -Pattern 'KOKORO_RUNTIME: COMMAND (resume|next|pause|previous|stop)' |
        ForEach-Object Line
$navigateLines =
    $logText -split "`r?`n" |
        Select-String -Pattern 'KOKORO_RUNTIME: TTS_NAVIGATE' |
        ForEach-Object Line
$serviceText =
    Invoke-Adb shell dumpsys activity services $package | Out-String
$notificationRecords =
    Invoke-Adb shell dumpsys notification --noredact |
        Select-String -Pattern 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness' |
        ForEach-Object Line

$result = [ordered]@{
    status =
        if ($observed.Count -eq 6 -and
            $serviceText -notmatch 'KokoroRuntimeService' -and
            $notificationRecords.Count -eq 0) {
            'NOTIFICATION_CONTROLS_SEMANTICS_PASS'
        } else {
            'FAIL'
        }
    observedTaps = $observed
    commandLines = $commandLines
    navigateLines = $navigateLines
    servicePresent = $serviceText -match 'KokoroRuntimeService'
    foregroundPresent = $serviceText -match 'isForeground=true'
    notificationRecordPresent = $notificationRecords.Count -ne 0
    runtimePid = (Invoke-Adb shell pidof $package).Trim()
}
$result | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $evidenceRoot 'notification-controls-via-systemui.json') `
        -Encoding utf8
$result | ConvertTo-Json -Depth 8
