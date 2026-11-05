[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $LogcatPath
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$logPath = (Resolve-Path -LiteralPath $LogcatPath).Path
$package = 'dev.aurorama.kokoro.harness'
$remoteXml = '/sdcard/qa_v8_notification_controls.xml'
$temporaryXml = Join-Path $outputPath 'notification-controls-current.xml'
$utf8 = [Text.UTF8Encoding]::new($false)
$lineOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Read-LatestState([int] $Offset = $lineOffset) {
    $tail = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $Offset
    )
    return Get-LatestRuntimeState $tail
}

function Save-SanitizedUi([int] $Step) {
    $null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'expand-notifications')
    Start-Sleep -Milliseconds 700
    $null = Invoke-Adb @('shell', 'rm', '-f', $remoteXml)
    $dump = Invoke-Adb @('shell', 'uiautomator', 'dump', $remoteXml)
    if (
        $dump.exitCode -ne 0 -or
        [string]::IsNullOrWhiteSpace($dump.text)
    ) {
        throw "uiautomator dump failed at notification step ${Step}: $($dump.text)"
    }
    $pull = Invoke-Adb @('pull', $remoteXml, $temporaryXml)
    if (
        $pull.exitCode -ne 0 -or
        -not (Test-Path -LiteralPath $temporaryXml) -or
        (Get-Item -LiteralPath $temporaryXml).Length -eq 0 -or
        [string]::IsNullOrWhiteSpace($pull.text)
    ) {
        throw "Could not pull non-empty notification UI at step ${Step}: $($pull.text)"
    }
    try {
        $xmlText = [IO.File]::ReadAllText($temporaryXml, [Text.Encoding]::UTF8)
        [xml] $document = $xmlText
    } finally {
        Remove-Item -LiteralPath $temporaryXml -Force -ErrorAction SilentlyContinue
    }

    $controlNodes = @(
        $document.SelectNodes('//*[@resource-id]') |
            Where-Object {
                $_.'resource-id' -like "$package`:id/action_*" -or
                $_.'resource-id' -eq 'com.android.systemui:id/custom_aggregate_notification'
            } |
            ForEach-Object {
                [ordered]@{
                    resourceId = [string]$_.'resource-id'
                    text = [string]$_.text
                    bounds = [string]$_.bounds
                    clickable = [string]$_.clickable
                    enabled = [string]$_.enabled
                }
            }
    )
    $sanitizedPath = Join-Path $outputPath "notification-ui-step-$Step.json"
    [IO.File]::WriteAllText(
        $sanitizedPath,
        ($controlNodes | ConvertTo-Json -Depth 5),
        $utf8)
    return [pscustomobject]@{
        Document = $document
        SanitizedPath = $sanitizedPath
        Nodes = $controlNodes
    }
}

function Find-Control($Ui, [string] $Control) {
    $targetId = "$package`:id/$Control"
    return $Ui.Document.SelectNodes('//*[@resource-id]') |
        Where-Object { $_.'resource-id' -eq $targetId } |
        Select-Object -First 1
}

function Reveal-Aggregate($Ui, [string] $Control) {
    $node = Find-Control $Ui $Control
    if ($null -ne $node) {
        return $Ui
    }
    $aggregate = $Ui.Document.SelectNodes('//*[@resource-id]') |
        Where-Object {
            $_.'resource-id' -eq 'com.android.systemui:id/custom_aggregate_notification'
        } |
        Select-Object -First 1
    if ($null -eq $aggregate) {
        throw "Notification aggregate is unavailable for $Control"
    }
    $bounds = [regex]::Match($aggregate.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $bounds.Success) {
        throw "Invalid aggregate bounds: $($aggregate.bounds)"
    }
    $x = [int]((
        [int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value
    ) / 2)
    $y = [int]((
        [int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value
    ) / 2)
    $null = Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
    Start-Sleep -Milliseconds 700
    return Save-SanitizedUi -Step (100 + $script:step)
}

function Tap-Control([string] $Control, [int] $WaitSeconds, [string] $ExpectedCommand) {
    $script:step++
    $ui = Save-SanitizedUi -Step $script:step
    $ui = Reveal-Aggregate $ui $Control
    $node = Find-Control $ui $Control
    if ($null -eq $node) {
        throw "Notification control is not visible: $Control"
    }
    if ($node.clickable -ne 'true' -or $node.enabled -ne 'true') {
        throw "Notification control is not actionable: $Control $($node.bounds)"
    }
    $bounds = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $bounds.Success) {
        throw "Invalid notification control bounds: $($node.bounds)"
    }
    $x = [int]((
        [int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value
    ) / 2)
    $y = [int]((
        [int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value
    ) / 2)
    $tapOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
    $preTapLineCount = [Math]::Max(0, $tapOffset - $lineOffset)
    $preTapLines = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $lineOffset -First $preTapLineCount
    )
    $preState = Get-LatestRuntimeState $preTapLines
    $tap = Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
    Start-Sleep -Seconds $WaitSeconds
    $state = Read-LatestState $tapOffset
    $tail = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $tapOffset
    )
    $audioStartLines = @(
        $tail |
            Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
            ForEach-Object Line
    )
    if ($ExpectedCommand -in @('resume', 'previous')) {
        $audioDeadline = (Get-Date).AddSeconds(35)
        do {
            if ($audioStartLines.Count -gt 0 -and
                $null -ne $state -and
                $state.status -eq 'PLAYING' -and
                [bool]$state.audioTrackPlaying) {
                break
            }
            Start-Sleep -Milliseconds 300
            $tail = @(
                Get-Content -LiteralPath $logPath -Encoding utf8 |
                    Select-Object -Skip $tapOffset
            )
            $audioStartLines = @(
                $tail |
                    Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
                    ForEach-Object Line
            )
            $state = Get-LatestRuntimeState $tail
        } while ((Get-Date) -lt $audioDeadline)
    }
    $commandLines = @(
        $tail |
            Select-String -SimpleMatch "KOKORO_RUNTIME: COMMAND $ExpectedCommand" |
            ForEach-Object Line
    )
    return [ordered]@{
        control = $Control
        tappedAt = (Get-Date).ToString('o')
        text = [string]$node.text
        bounds = [string]$node.bounds
        tap = "$x,$y"
        tapExitCode = $tap.exitCode
        expectedCommand = $ExpectedCommand
        logOffset = $tapOffset
        preState = $preState
        commandObserved = $commandLines.Count -gt 0
        commandLines = $commandLines
        state = $state
        audioStartLines = $audioStartLines
        sanitizedUi = $ui.SanitizedPath
    }
}

$observed = [Collections.Generic.List[object]]::new()
$null = Invoke-Adb @('shell', 'input', 'keyevent', '3')
Start-Sleep -Milliseconds 900
$before = Read-LatestState
$observed.Add((Tap-Control -Control 'action_play_pause' -WaitSeconds 2 -ExpectedCommand 'pause'))
$observed.Add((Tap-Control -Control 'action_next' -WaitSeconds 1 -ExpectedCommand 'next'))
$observed.Add((Tap-Control -Control 'action_play_pause' -WaitSeconds 12 -ExpectedCommand 'resume'))
$observed.Add((Tap-Control -Control 'action_previous' -WaitSeconds 12 -ExpectedCommand 'previous'))
$observed.Add((Tap-Control -Control 'action_stop' -WaitSeconds 5 -ExpectedCommand 'stop'))
$null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse')
Start-Sleep -Seconds 3
$null = Invoke-Adb @('shell', 'rm', '-f', $remoteXml)

$serviceText = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $package)).text
$notificationText = (Invoke-Adb @('shell', 'dumpsys', 'notification', '--noredact')).text
$mediaText = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
$mediaEvidence = Get-MediaSessionEvidence `
    -Text $mediaText `
    -Packages @($package)
$activeMainSessions = @(
    $mediaEvidence.activeSessions |
        Where-Object package -eq $package
)
$pidText = ((Invoke-Adb @('shell', 'pidof', $package)).text).Trim()
$tail = @(Get-Content -LiteralPath $logPath -Encoding utf8 | Select-Object -Skip $lineOffset)
$fatalLines = @(
    $tail |
        Select-String -Pattern 'FATAL EXCEPTION|ANR in dev\.aurorama\.kokoro\.harness|InputTimeout|Input dispatching timed out'
)
$previousActionLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip ([int]$observed[3].logOffset)
)
$previousNavigateLines = @(
    $previousActionLines |
        Select-String -Pattern 'TTS_NAVIGATE from=(\d+) to=(\d+) wasActive=true' |
        Where-Object {
            [int]$_.Matches[0].Groups[2].Value -lt
            [int]$_.Matches[0].Groups[1].Value
        } |
        ForEach-Object Line
)
$nextActionLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip ([int]$observed[1].logOffset)
)
$nextNavigateLines = @(
    $nextActionLines |
        Select-String -Pattern 'TTS_NAVIGATE from=(\d+) to=(\d+) wasActive=(true|false)' |
        Where-Object {
            [int]$_.Matches[0].Groups[2].Value -eq
                ([int]$_.Matches[0].Groups[1].Value + 1)
        } |
        ForEach-Object Line
)
$previousDirectionValid = @(
    $previousNavigateLines |
        ForEach-Object {
            [regex]::Match(
                $_,
                'TTS_NAVIGATE from=(\d+) to=(\d+)')
        } |
        Where-Object {
            $_.Success -and
            [int]$_.Groups[2].Value -eq
                ([int]$_.Groups[1].Value - 1)
        }
).Count -gt 0
$nextDirectionValid = $nextNavigateLines.Count -gt 0
$checks = [ordered]@{
    allControlsTapped = $observed.Count -eq 5
    allCommandsObserved = @($observed | Where-Object { -not $_.commandObserved }).Count -eq 0
    pausedAfterFirst = $observed[0].state.status -eq 'PAUSED'
    nextMovedWhilePaused =
        $observed[1].preState.status -eq 'PAUSED' -and
        [int]$observed[1].state.stableIndex -ne
            [int]$observed[1].preState.stableIndex
    resumeReturnedPlaying = $observed[2].state.status -in @('PLAYING', 'PREPARING')
    previousNavigateObserved = $previousDirectionValid
    nextNavigateDirectionIsPlusOne = $nextDirectionValid
    previousRestartedRealAudio = @($observed[3].audioStartLines).Count -gt 0
    stopStartedFromRealPlaying =
        $observed[4].preState.status -eq 'PLAYING' -and
        [bool]$observed[4].preState.audioTrackPlaying
    stopCommandObserved = $observed[4].commandObserved
    serviceGoneAfterStop = $serviceText -notmatch 'KokoroRuntimeService'
    notificationGoneAfterStop = $notificationText -notmatch 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
    activeMediaSessionGoneAfterStop = $activeMainSessions.Count -eq 0
    noFatalAnrInputTimeout = $fatalLines.Count -eq 0
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.notification-controls.v1'
    status = if ($passed) { 'PASS_FOUR_SYSTEMUI_CONTROLS' } else { 'FAIL' }
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    before = $before
    observedTaps = @($observed)
    checks = $checks
    previousNavigateLines = $previousNavigateLines
    nextNavigateLines = $nextNavigateLines
    final = [ordered]@{
        servicePresent = $serviceText -match 'KokoroRuntimeService'
        notificationRecordPresent = $notificationText -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
        activeMediaSessionCount = $activeMainSessions.Count
        mediaSessionExcerpt = $mediaEvidence.excerpt
        runtimePid = if ($pidText) { $pidText } else { $null }
        activityProcessMayRemainAfterServiceStop = -not [string]::IsNullOrWhiteSpace($pidText)
        fatalAnrInputTimeoutLines = @($fatalLines | ForEach-Object Line)
    }
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'notification-controls.json'),
    ($result | ConvertTo-Json -Depth 20),
    $utf8)
$result | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
