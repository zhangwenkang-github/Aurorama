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
$utf8 = [Text.UTF8Encoding]::new($false)
$resultPath = Join-Path $outputPath 'resource-consistency.json'
$remoteUiXml = '/sdcard/qa_v8_resource_ui.xml'
$remoteNotificationXml = '/sdcard/qa_v8_resource_notification.xml'
$localUiXml = Join-Path $outputPath 'resource-ui-temporary.xml'
$localNotificationXml = Join-Path $outputPath 'resource-notification-temporary.xml'
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Get-LogLines([int] $Offset = 0) {
    @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $Offset
    )
}

function Wait-ForAudioStart([int] $Offset, [int] $TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 300
        $line = Get-LogLines $Offset |
            Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
            Select-Object -First 1
    } while ($null -eq $line -and (Get-Date) -lt $deadline)
    return $line
}

function Wait-ForPlayingState([int] $Offset) {
    $deadline = (Get-Date).AddSeconds(15)
    $state = $null
    do {
        Start-Sleep -Milliseconds 250
        $state = Get-LatestRuntimeState (Get-LogLines $Offset)
    } while (
        (
            $null -eq $state -or
            $state.status -ne 'PLAYING' -or
            -not [bool]$state.audioTrackPlaying
        ) -and
        (Get-Date) -lt $deadline
    )
    return $state
}

function Get-SanitizedUiSnapshot(
    [string] $RemotePath,
    [string] $LocalPath,
    [string] $Name
) {
    $null = Invoke-Adb @('shell', 'rm', '-f', $RemotePath)
    $dump = Invoke-Adb @(
        'shell', 'uiautomator', 'dump', '--compressed', $RemotePath)
    if (
        $dump.exitCode -ne 0 -or
        [string]::IsNullOrWhiteSpace($dump.text)
    ) {
        throw "uiautomator dump failed for ${Name}: $($dump.text)"
    }
    $pull = Invoke-Adb @('pull', $RemotePath, $LocalPath)
    if (
        $pull.exitCode -ne 0 -or
        -not (Test-Path -LiteralPath $LocalPath) -or
        (Get-Item -LiteralPath $LocalPath).Length -eq 0 -or
        [string]::IsNullOrWhiteSpace($pull.text)
    ) {
        throw "Could not pull non-empty ${Name} UI XML: $($pull.text)"
    }
    try {
        [xml] $document = [IO.File]::ReadAllText(
            $LocalPath,
            [Text.Encoding]::UTF8)
    } finally {
        Remove-Item -LiteralPath $LocalPath -Force -ErrorAction SilentlyContinue
        $null = Invoke-Adb @('shell', 'rm', '-f', $RemotePath)
    }

    $texts = @(
        $document.SelectNodes('//*[@text]') |
            ForEach-Object { [string]$_.text } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Unique
    )
    $controls = @(
        $document.SelectNodes('//*[@resource-id]') |
            Where-Object {
                $_.'resource-id' -like "$package`:id/notification_*" -or
                $_.'resource-id' -like "$package`:id/action_*" -or
                $_.'resource-id' -eq
                    'com.android.systemui:id/custom_aggregate_notification'
            } |
            ForEach-Object {
                [ordered]@{
                    resourceId = [string]$_.'resource-id'
                    text = [string]$_.text
                    contentDescription = [string]$_.'content-desc'
                    bounds = [string]$_.bounds
                    clickable = [string]$_.clickable
                    enabled = [string]$_.enabled
                }
            }
    )
    $sanitizedPath = Join-Path $outputPath "$Name-sanitized.json"
    [IO.File]::WriteAllText(
        $sanitizedPath,
        (([ordered]@{
            schema = 'aurorama.kokoro-qa-v8.sanitized-ui.v1'
            name = $Name
            texts = $texts
            controls = $controls
        } | ConvertTo-Json -Depth 10) + "`n"),
        $utf8)
    return [pscustomobject]@{
        sanitizedPath = $sanitizedPath
        texts = $texts
        combinedText = $texts -join "`n"
        controls = $controls
    }
}

$failure = $null
try {
    $installGatePath = Join-Path $outputPath 'install-gate.json'
    $installGate = Get-Content -LiteralPath $installGatePath -Raw -Encoding utf8 |
        ConvertFrom-Json
    $null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse')
    $null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
    $lineOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
    $launch = Invoke-Adb @(
        'shell', 'am', 'start', '-W', '--activity-single-top',
        '-n', "$package/.HarnessActivity",
        '--es', 'command', 'play_full')
    if ($launch.exitCode -ne 0) {
        throw "play_full launch failed: $($launch.text)"
    }
    $audioStartLine = Wait-ForAudioStart $lineOffset
    if ($null -eq $audioStartLine) {
        throw 'play_full did not create a real TTS AudioTrack'
    }
    $stateBeforeStop = Wait-ForPlayingState $lineOffset
    if (
        $null -eq $stateBeforeStop -or
        $stateBeforeStop.status -ne 'PLAYING' -or
        -not [bool]$stateBeforeStop.audioTrackPlaying
    ) {
        throw 'Runtime did not reach a real PLAYING state after AudioTrack start'
    }

    $serviceBefore = (Invoke-Adb @(
        'shell', 'dumpsys', 'activity', 'services', $package)).text
    $foregroundBefore = Get-LiveForegroundServiceEvidence $serviceBefore
    $mainUi = Get-SanitizedUiSnapshot `
        -RemotePath $remoteUiXml `
        -LocalPath $localUiXml `
        -Name 'resource-main-ui'

    $null = Invoke-Adb @(
        'shell', 'cmd', 'statusbar', 'expand-notifications')
    Start-Sleep -Seconds 1
    $notificationUi = Get-SanitizedUiSnapshot `
        -RemotePath $remoteNotificationXml `
        -LocalPath $localNotificationXml `
        -Name 'resource-notification-ui'
    $initialActionNodes = @(
        $notificationUi.controls |
            Where-Object { $_.resourceId -like "$package`:id/action_*" }
    )
    if ($initialActionNodes.Count -eq 0) {
        $aggregateNode = $notificationUi.controls |
            Where-Object {
                $_.resourceId -eq
                    'com.android.systemui:id/custom_aggregate_notification'
            } |
            Select-Object -First 1
        if ($null -eq $aggregateNode) {
            throw 'Notification aggregate is unavailable before the four-action check'
        }
        $aggregateBounds = [regex]::Match(
            $aggregateNode.bounds,
            '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $aggregateBounds.Success) {
            throw "Invalid notification aggregate bounds: $($aggregateNode.bounds)"
        }
        $aggregateX = [int]((
            [int]$aggregateBounds.Groups[1].Value +
            [int]$aggregateBounds.Groups[3].Value
        ) / 2)
        $aggregateY = [int]((
            [int]$aggregateBounds.Groups[2].Value +
            [int]$aggregateBounds.Groups[4].Value
        ) / 2)
        $null = Invoke-Adb @(
            'shell', 'input', 'tap', "$aggregateX", "$aggregateY")
        Start-Sleep -Milliseconds 700
        $notificationUi = Get-SanitizedUiSnapshot `
            -RemotePath $remoteNotificationXml `
            -LocalPath $localNotificationXml `
            -Name 'resource-notification-ui-expanded'
    }
    $null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse')

    $notificationText = (Invoke-Adb @(
        'shell', 'dumpsys', 'notification', '--noredact')).text
    $notificationRecordLines = @(
        ($notificationText -split "`r?`n") |
            Where-Object {
                $_ -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
            }
    )
    $notificationRecordActions = [regex]::Matches(
        ($notificationRecordLines -join "`n"),
        '\bactions=(\d+)') |
        ForEach-Object { [int]$_.Groups[1].Value } |
        Select-Object -First 1

    $mediaText = (Invoke-Adb @(
        'shell', 'dumpsys', 'media_session')).text
    $mediaBeforeStop = Get-MediaSessionEvidence `
        -Text $mediaText `
        -Packages @($package)
    $activeMainSessionsBeforeStop = @(
        $mediaBeforeStop.activeSessions |
            Where-Object package -eq $package
    )
    $activeMediaSession = if ($activeMainSessionsBeforeStop.Count -eq 1) {
        $activeMainSessionsBeforeStop[0]
    } else {
        $null
    }

    $controlNodes = @($notificationUi.controls)
    $fourActionIds = @(
        "$package`:id/action_stop",
        "$package`:id/action_play_pause",
        "$package`:id/action_next",
        "$package`:id/action_previous"
    )
    $observedActionIds = @(
        $controlNodes |
            Where-Object { $_.resourceId -like "$package`:id/action_*" } |
            ForEach-Object resourceId |
            Select-Object -Unique
    )
    $notificationTitleNode = $controlNodes |
        Where-Object resourceId -eq "$package`:id/notification_title" |
        Select-Object -First 1
    $notificationBodyNode = $controlNodes |
        Where-Object resourceId -eq "$package`:id/notification_text" |
        Select-Object -First 1
    $playPauseNode = $controlNodes |
        Where-Object resourceId -eq "$package`:id/action_play_pause" |
        Select-Object -First 1
    $expectedSpeakerText = "Speaker: $($stateBeforeStop.speaker) sid=$($stateBeforeStop.sid)"
    $expectedPackText = "Pack: $($stateBeforeStop.packId)"
    $expectedNotificationLine =
        "Notification: $($stateBeforeStop.notificationTitle) · $($stateBeforeStop.notificationText)"

    $checksBeforeStop = [ordered]@{
        stateOwnerIsTts = $stateBeforeStop.activeAudioOwner -eq 'TTS'
        stateAudioTrackPlaying = [bool]$stateBeforeStop.audioTrackPlaying
        liveServiceForeground =
            [bool]$foregroundBefore.servicePresent -and
            [bool]$foregroundBefore.isForeground
        installedManifestMediaPlayback =
            [bool]$installGate.mediaPlaybackForegroundServiceTypeMatches
        uiHasSpeakerSid = $mainUi.combinedText.Contains($expectedSpeakerText)
        uiHasPack = $mainUi.combinedText.Contains($expectedPackText)
        uiHasNotificationSnapshot =
            $mainUi.combinedText.Contains($expectedNotificationLine)
        notificationTitleMatches =
            $null -ne $notificationTitleNode -and
            $notificationTitleNode.text -eq $stateBeforeStop.notificationTitle
        notificationBodyMatches =
            $null -ne $notificationBodyNode -and
            $notificationBodyNode.text -eq $stateBeforeStop.notificationText
        notificationHasFourActions =
            @($fourActionIds | Where-Object { $_ -in $observedActionIds }).Count -eq 4
        notificationRecordHasFourActions =
            $notificationRecordLines.Count -ge 1 -and
            @($notificationRecordActions | Where-Object { $_ -eq 4 }).Count -eq 1
        playPauseLabelMatches =
            $null -ne $playPauseNode -and
            $playPauseNode.text -eq '暂停'
        stateActionModelHasFour =
            @($stateBeforeStop.notificationRecordActions).Count -eq 4
        singleActiveMainMediaSession =
            $activeMainSessionsBeforeStop.Count -eq 1
        mediaSessionActive =
            $null -ne $activeMediaSession -and
            [bool]$activeMediaSession.active
        mediaSessionTitleMatches =
            $null -ne $activeMediaSession -and
            $activeMediaSession.metadataTitle -eq $stateBeforeStop.notificationTitle
        mediaSessionSpeakerMatches =
            $null -ne $activeMediaSession -and
            $activeMediaSession.metadataArtist -eq
                "Kokoro isolated runtime · $($stateBeforeStop.speaker)"
        mediaSessionPackMatches =
            $null -ne $activeMediaSession -and
            $activeMediaSession.metadataAlbum -eq $stateBeforeStop.packId
    }

    $stopOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
    $stopResult = Invoke-Adb @(
        'shell', 'am', 'start', '-W', '--activity-single-top',
        '-n', "$package/.HarnessActivity",
        '--es', 'command', 'stop')
    if ($stopResult.exitCode -ne 0) {
        throw "stop command failed: $($stopResult.text)"
    }
    Start-Sleep -Milliseconds 350
    $null = Invoke-Adb @('shell', 'input', 'keyevent', '3')

    $convergenceDeadline = (Get-Date).AddSeconds(20)
    $converged = $false
    $finalServiceText = ''
    $finalNotificationText = ''
    $finalMediaText = ''
    do {
        Start-Sleep -Seconds 1
        $finalServiceText = (Invoke-Adb @(
            'shell', 'dumpsys', 'activity', 'services', $package)).text
        $finalNotificationText = (Invoke-Adb @(
            'shell', 'dumpsys', 'notification', '--noredact')).text
        $finalMediaText = (Invoke-Adb @(
            'shell', 'dumpsys', 'media_session')).text
        $finalMediaEvidence = Get-MediaSessionEvidence `
            -Text $finalMediaText `
            -Packages @($package)
        $activeAfterStop = @(
            $finalMediaEvidence.activeSessions |
                Where-Object package -eq $package
        )
        $serviceAfterStop = Get-LiveForegroundServiceEvidence $finalServiceText
        $notificationAfterStop =
            $finalNotificationText -match
                'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
        $converged =
            -not $serviceAfterStop.servicePresent -and
            -not $serviceAfterStop.isForeground -and
            -not $notificationAfterStop -and
            $activeAfterStop.Count -eq 0
    } while (-not $converged -and (Get-Date) -lt $convergenceDeadline)

    $postStopLines = Get-LogLines $stopOffset
    $stateAfterStop = Get-LatestRuntimeState $postStopLines
    $newAudioStartsAfterStop = @(
        $postStopLines |
            Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS'
    )
    $checksAfterStop = [ordered]@{
        stopCommandExitZero = $stopResult.exitCode -eq 0
        stoppedStateObserved =
            $null -ne $stateAfterStop -and
            $stateAfterStop.status -in @('STOPPED', 'COMPLETED', 'IDLE')
        audioTrackNotPlaying =
            $null -eq $stateAfterStop -or
            $stateAfterStop.audioTrackPlaying -eq $false
        noNewTtsAudioTrackAfterStop = $newAudioStartsAfterStop.Count -eq 0
        serviceForegroundConverged = -not $serviceAfterStop.servicePresent
        notificationConverged =
            $finalNotificationText -notmatch
                'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
        activeMediaSessionConverged = $activeAfterStop.Count -eq 0
    }
    $checks =
        [ordered]@{ beforeStop = $checksBeforeStop; afterStop = $checksAfterStop }
    $allChecks = @($checksBeforeStop.Values) + @($checksAfterStop.Values)
    $passed = @($allChecks | Where-Object { $_ -ne $true }).Count -eq 0
    $result = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.resource-consistency.v1'
        status = if ($passed) {
            'PASS_STATE_UI_NOTIFICATION_MEDIASESSION_CONSISTENT_AND_STOP_CONVERGED'
        } else {
            'FAIL'
        }
        serial = $Serial
        capturedAt = (Get-Date).ToString('o')
        lineOffset = $lineOffset
        audioStartLine = $audioStartLine.Line
        stateBeforeStop = $stateBeforeStop
        stateAfterStop = $stateAfterStop
        stopOffset = $stopOffset
        foregroundBeforeStop = $foregroundBefore
        foregroundAfterStop = $serviceAfterStop
        notificationRecordLines = $notificationRecordLines
        notificationRecordActionCount = $notificationRecordActions
        notificationUi = [ordered]@{
            sanitizedPath = $notificationUi.sanitizedPath
            actionIds = $observedActionIds
            title = if ($notificationTitleNode) {
                $notificationTitleNode.text
            } else {
                $null
            }
            text = if ($notificationBodyNode) {
                $notificationBodyNode.text
            } else {
                $null
            }
            playPauseLabel = if ($playPauseNode) {
                $playPauseNode.text
            } else {
                $null
            }
        }
        mainUiSanitizedPath = $mainUi.sanitizedPath
        mediaSessionBeforeStop = $mediaBeforeStop
        mediaSessionAfterStop = $finalMediaEvidence
        newTtsAudioTracksAfterStop =
            @($newAudioStartsAfterStop | ForEach-Object Line)
        checks = $checks
    }
    [IO.File]::WriteAllText(
        $resultPath,
        (($result | ConvertTo-Json -Depth 30) + "`n"),
        $utf8)
    $result | ConvertTo-Json -Depth 10
    if (-not $passed) {
        exit 1
    }
} catch {
    $failure = $_
    $errorResult = [ordered]@{
        schema = 'aurorama.kokoro-qa-v8.resource-consistency.v1'
        status = 'FAIL'
        serial = $Serial
        capturedAt = (Get-Date).ToString('o')
        error = $_.Exception.Message
    }
    [IO.File]::WriteAllText(
        $resultPath,
        (($errorResult | ConvertTo-Json -Depth 10) + "`n"),
        $utf8)
    throw
} finally {
    $null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'collapse')
    $null = Invoke-Adb @('shell', 'rm', '-f', $remoteUiXml)
    $null = Invoke-Adb @('shell', 'rm', '-f', $remoteNotificationXml)
    Remove-Item -LiteralPath $localUiXml, $localNotificationXml `
        -Force -ErrorAction SilentlyContinue
}
