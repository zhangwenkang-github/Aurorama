[CmdletBinding()]
param(
    [string] $Serial = '43af8627',

    [ValidateSet('inference', 'playing')]
    [Parameter(Mandatory = $true)]
    [string] $StopPhase,

    [Parameter(Mandatory = $true)]
    [string] $Label,

    [int] $WaitSecondsAfterStop = 45
)

$ErrorActionPreference = 'Stop'
$package = 'dev.aurorama.kokoro.harness'
$expectedSha256 = '252a3a15f637b05e874d9b2ad2aa0c48076d2babb83f66c5ec18683dc533e161'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = Join-Path $taskRoot "raw\$Label"
$tempRoot = Join-Path $taskRoot 'raw\.tmp'
$null = New-Item -ItemType Directory -Path $evidenceRoot, $tempRoot -Force
$utf8 = [Text.UTF8Encoding]::new($false)
$remoteXml = "/sdcard/qa_v7_$Label.xml"
$localXml = Join-Path $tempRoot "$Label-notification.xml"

function Invoke-Adb {
    param(
        [string[]] $Arguments,
        [switch] $AllowFailure
    )
    $output = @(& adb.exe -s $Serial @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb failed: adb -s $Serial $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Write-Lines {
    param(
        [string] $Path,
        [object[]] $Lines
    )
    $text = if ($null -eq $Lines -or $Lines.Count -eq 0) {
        ''
    } else {
        (@($Lines | ForEach-Object { $_.ToString() }) -join "`n") + "`n"
    }
    [IO.File]::WriteAllText($Path, ($text -replace "`r`n", "`n"), $utf8)
}

function Write-Json {
    param(
        [string] $Path,
        [object] $Value
    )
    $text = ($Value | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $text + "`n", $utf8)
}

function Get-PackageSha256 {
    $devicePath = ((Invoke-Adb @('shell', "pm path $package")) |
        Where-Object { $_ -match '^package:' } |
        Select-Object -First 1) -replace '^package:', ''
    if ([string]::IsNullOrWhiteSpace($devicePath)) {
        throw 'Harness package is not installed'
    }
    $devicePath = $devicePath.Trim()
    $shaLine = (Invoke-Adb @('shell', "sha256sum $devicePath") | Select-Object -First 1)
    return [ordered]@{
        path = $devicePath
        sha256 = ($shaLine -split '\s+')[0].ToLowerInvariant()
    }
}

function Get-RuntimePid {
    return ((Invoke-Adb @('shell', "pidof $package") -AllowFailure) -join "`n").Trim()
}

function Get-NotificationText {
    return @(Invoke-Adb @('shell', 'dumpsys notification --noredact'))
}

function Get-MediaText {
    return @(Invoke-Adb @('shell', 'dumpsys media_session'))
}

function Get-ServiceText {
    return @(Invoke-Adb @('shell', "dumpsys activity services $package"))
}

function Get-NotificationMatches {
    param([object[]] $Lines)
    return @(
        $Lines |
            Select-String -Pattern 'NotificationRecord.*dev\.aurorama\.kokoro\.harness' |
            ForEach-Object { $_.Line }
    )
}

function Get-MediaMatches {
    param([object[]] $Lines)
    $text = $Lines -join "`n"
    $stackStart = $text.IndexOf('Sessions Stack - have')
    if ($stackStart -lt 0) {
        return @()
    }
    $historyStart = $text.IndexOf(
        'Audio playback (lastly played comes first)',
        $stackStart)
    $stackEnd = if ($historyStart -ge 0) { $historyStart } else { $text.Length }
    return @(($text.Substring($stackStart, $stackEnd - $stackStart) -split "`n"))
}

function Get-ActiveMediaSessionCount {
    param([object[]] $Lines)
    $stackLines = Get-MediaMatches $Lines
    $stackText = $stackLines -join "`n"
    $countMatch = [regex]::Match($stackText, 'Sessions Stack - have\s+(\d+)\s+sessions:')
    if (-not $countMatch.Success) {
        return $null
    }
    return [int] $countMatch.Groups[1].Value
}

function Get-ServiceMatches {
    param([object[]] $Lines)
    return @(
        $Lines |
            Select-String -Pattern 'ServiceRecord.*dev\.aurorama\.kokoro\.harness|dev\.aurorama\.kokoro\.harness.*ServiceRecord' |
            ForEach-Object { $_.Line }
    )
}

function Assert-HealthyProcessAndObject {
    $windowText = @(Invoke-Adb @('shell', 'dumpsys window')) -join "`n"
    $policyText = @(Invoke-Adb @('shell', 'dumpsys window policy')) -join "`n"
    $keyguardValues = @(
        [regex]::Matches(
            "$windowText`n$policyText",
            '(?m)^\s*(?:isKeyguardShowing|mKeyguardShowing|KeyguardShowing)=(true|false)') |
            ForEach-Object { $_.Groups[1].Value }
    )
    if ($keyguardValues -contains 'true' -or $keyguardValues -notcontains 'false') {
        throw 'USER_UNLOCK_REQUIRED: keyguard must be demonstrably false'
    }
    $identity = Get-PackageSha256
    if ($identity.sha256 -ne $expectedSha256) {
        throw "APK identity drift: $($identity.sha256)"
    }
    return [ordered]@{
        keyguardShowing = $false
        deviceApk = $identity
    }
}

function Read-UiDocument {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        $text = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
        if ([string]::IsNullOrWhiteSpace($text)) {
            return $null
        }
        return [xml] $text
    } catch {
        return $null
    }
}

function Find-Node {
    param(
        [xml] $Document,
        [string] $Pattern
    )
    if ($null -eq $Document) {
        return $null
    }
    return $Document.SelectNodes('//*[@text or @resource-id or @content-desc]') |
        Where-Object {
            $_.text -match $Pattern -or
            $_.'resource-id' -eq "$package`:id/action_stop" -or
            $_.'content-desc' -match $Pattern
        } |
        Select-Object -First 1
}

function Get-NodeClickPoint {
    param([object] $Node)
    $bounds = [string] $Node.bounds
    $match = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $match.Success) {
        throw "Invalid notification node bounds: $bounds"
    }
    return [ordered]@{
        bounds = $bounds
        x = [int] ((
            [int] $match.Groups[1].Value +
            [int] $match.Groups[3].Value
        ) / 2)
        y = [int] ((
            [int] $match.Groups[2].Value +
            [int] $match.Groups[4].Value
        ) / 2)
    }
}

function Dump-NotificationDocument {
    param([string] $AttemptLabel)
    $attemptLocal = Join-Path $tempRoot "$Label-$AttemptLabel.xml"
    Remove-Item -LiteralPath $attemptLocal, $remoteXml -Force -ErrorAction SilentlyContinue
    $dump = @(Invoke-Adb @('shell', "uiautomator dump --compressed $remoteXml") -AllowFailure)
    if ($LASTEXITCODE -ne 0) {
        return [ordered]@{ document = $null; dump = $dump; localPath = $null }
    }
    $pull = @(Invoke-Adb @('pull', $remoteXml, $attemptLocal) -AllowFailure)
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $attemptLocal)) {
        return [ordered]@{ document = $null; dump = $dump; pull = $pull; localPath = $null }
    }
    return [ordered]@{
        document = Read-UiDocument $attemptLocal
        dump = $dump
        pull = $pull
        localPath = $attemptLocal
    }
}

function Find-And-Tap-StopControl {
    $uiDumpResults = @()
    $document = $null
    $stopNode = $null
    for ($attempt = 1; $attempt -le 3 -and $null -eq $stopNode; $attempt++) {
        $result = Dump-NotificationDocument "attempt-$attempt"
        $uiDumpResults += [ordered]@{
            attempt = $attempt
            dump = $result.dump
            pull = $result.pull
            parsed = $null -ne $result.document
        }
        $document = $result.document
        $stopNode = Find-Node $document '^停止$'
        if ($null -eq $stopNode -and $null -ne $document) {
            $moreNode = Find-Node $document '更多通知|展开|更多'
            if ($null -ne $moreNode) {
                $morePoint = Get-NodeClickPoint $moreNode
                $null = Invoke-Adb @('shell', 'input', 'tap', [string] $morePoint.x, [string] $morePoint.y)
                Start-Sleep -Milliseconds 300
                $expanded = Dump-NotificationDocument "attempt-$attempt-expanded"
                $uiDumpResults += [ordered]@{
                    attempt = "$attempt-expanded"
                    dump = $expanded.dump
                    pull = $expanded.pull
                    parsed = $null -ne $expanded.document
                }
                $document = $expanded.document
                $stopNode = Find-Node $document '^停止$'
            }
        }
    }
    if ($null -eq $stopNode) {
        throw 'NOTIFICATION_STOP_NOT_FOUND'
    }
    $point = Get-NodeClickPoint $stopNode
    $tapAt = (Get-Date).ToString('o')
    $tapOutput = @(
        Invoke-Adb @(
            'shell', 'input', 'tap', [string] $point.x, [string] $point.y
        )
    )
    return [ordered]@{
        control = [ordered]@{
            text = [string] $stopNode.text
            resourceId = [string] $stopNode.'resource-id'
            contentDescription = [string] $stopNode.'content-desc'
            bounds = $point.bounds
            x = $point.x
            y = $point.y
            enabled = [string] $stopNode.enabled
            clickable = [string] $stopNode.clickable
        }
        tapAt = $tapAt
        tapOutput = $tapOutput
        uiDumpResults = $uiDumpResults
    }
}

function Get-LogcatLines {
    $all = @(Invoke-Adb @('logcat', '-d', '-b', 'all', '-v', 'threadtime'))
    $pids = @(
        $all |
            ForEach-Object {
                if ($_ -match ' KOKORO_RUNTIME:') {
                    $parts = $_ -split '\s+'
                    if ($parts.Count -gt 2) {
                        $parts[2]
                    }
                }
            } |
            Where-Object { $_ -match '^\d+$' } |
            Select-Object -Unique
    )
    $pidPattern = if ($pids.Count -gt 0) {
        '(?:\s(?:' + ($pids -join '|') + ')\s)'
    } else {
        '(?!)'
    }
    return @(
        $all |
            Where-Object {
                $_ -match [regex]::Escape($package) -or
                $_ -match $pidPattern -or
                $_ -cmatch '\sF DEBUG\s*:'
            }
    )
}

function Get-ExitInfoText {
    return @(Invoke-Adb @(
        'shell', 'dumpsys', 'activity', 'exit-info', $package
    )) -join "`n"
}

function Get-CurrentExitBlocks {
    param(
        [string] $ExitInfoText,
        [string] $StartClock
    )
    return @(
        [regex]::Matches(
            $ExitInfoText,
            '(?ms)^\s*timestamp=([^\r\n]+).*?(?=^\s*timestamp=|\z)') |
            Where-Object { $_.Groups[1].Value.Trim() -ge $StartClock }
    )
}

function Get-CurrentTombstones {
    param(
        [string] $DropboxText,
        [string] $StartClock
    )
    return @(
        [regex]::Matches(
            $DropboxText,
            '(?ms)^(?<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) SYSTEM_TOMBSTONE.*?(?=^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} SYSTEM_TOMBSTONE|\z)') |
            Where-Object {
                $_.Groups['timestamp'].Value -ge $StartClock -and
                $_.Value -match [regex]::Escape($package)
            }
    )
}

Remove-Item -LiteralPath $remoteXml, $localXml -Force -ErrorAction SilentlyContinue
$gate = Assert-HealthyProcessAndObject

& adb.exe -s $Serial shell pm grant $package android.permission.POST_NOTIFICATIONS 2>$null |
    Out-Null
& adb.exe -s $Serial shell am force-stop $package | Out-Null
& adb.exe -s $Serial shell am force-stop 'dev.aurorama.kokoro.focuspeer' | Out-Null
Start-Sleep -Milliseconds 250

$priorCrash = @(Invoke-Adb @('logcat', '-d', '-b', 'crash', '-v', 'threadtime'))
$priorCrashPackage = @(
    $priorCrash |
        Where-Object {
            $_ -match [regex]::Escape($package) -or
            $_ -cmatch '\sF DEBUG\s*:'
        }
)
Write-Lines (Join-Path $evidenceRoot 'crash-buffer-before-clear-package-filtered.txt') $priorCrashPackage
$null = Invoke-Adb @('logcat', '-b', 'all', '-c')

$startAt = (Get-Date).ToString('o')
$startClock = ([datetime] $startAt).ToString('yyyy-MM-dd HH:mm:ss')
$launch = @(
    Invoke-Adb @(
        'shell', 'am', 'start', '-W',
        '-n', "$package/.HarnessActivity",
        '--es', 'command', 'play_sample'
    )
)
Start-Sleep -Milliseconds 180
$null = Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME')

$runtimeLog = ''
$requestObservedAt = $null
$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline) {
    $runtimeLog = (@(Invoke-Adb @(
        'logcat', '-d', '-v', 'threadtime', '-s', 'KOKORO_RUNTIME'
    )) -join "`n")
    if ($runtimeLog -match 'TTS_SENTENCE_REQUEST') {
        $requestObservedAt = (Get-Date).ToString('o')
        break
    }
    Start-Sleep -Milliseconds 100
}
if ($null -eq $requestObservedAt) {
    throw 'STOP_PRECONDITION_FAILED: no TTS_SENTENCE_REQUEST before deadline'
}

$runtimePidBefore = Get-RuntimePid
if ([string]::IsNullOrWhiteSpace($runtimePidBefore)) {
    throw 'Runtime PID missing before notification Stop'
}
$serviceBefore = Get-ServiceText
$notificationBefore = Get-NotificationText
$mediaBefore = Get-MediaText
$notificationBeforeMatches = Get-NotificationMatches $notificationBefore
$mediaBeforeMatches = Get-MediaMatches $mediaBefore
$mediaSessionCountBefore = Get-ActiveMediaSessionCount $mediaBefore
$serviceBeforeMatches = Get-ServiceMatches $serviceBefore
Write-Lines (Join-Path $evidenceRoot 'service-before.txt') $serviceBeforeMatches
Write-Lines (Join-Path $evidenceRoot 'notification-before-filtered.txt') $notificationBeforeMatches
Write-Lines (Join-Path $evidenceRoot 'media-session-before-filtered.txt') $mediaBeforeMatches

$preconditionAt = $null
if ($StopPhase -eq 'inference') {
    $preconditionAt = $requestObservedAt
    Start-Sleep -Milliseconds 120
} else {
    $audioDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $audioDeadline) {
        $runtimeLog = (@(Invoke-Adb @(
            'logcat', '-d', '-v', 'threadtime', '-s', 'KOKORO_RUNTIME'
        )) -join "`n")
        if ($runtimeLog -match 'AUDIO_TRACK_STARTED owner=TTS') {
            $preconditionAt = (Get-Date).ToString('o')
            break
        }
        Start-Sleep -Milliseconds 75
    }
    if ($null -eq $preconditionAt) {
        throw 'PLAYING_STOP_PRECONDITION_FAILED: no TTS AudioTrack start before deadline'
    }
}

$null = Invoke-Adb @('shell', 'cmd', 'statusbar', 'expand-notifications')
Start-Sleep -Milliseconds 450
$notificationClick = Find-And-Tap-StopControl
$stopAt = [datetime] $notificationClick.tapAt

$checks = @{}
foreach ($delay in @(3, 10)) {
    Start-Sleep -Seconds ($(if ($delay -eq 3) { 3 } else { 7 }))
    $service = Get-ServiceText
    $notification = Get-NotificationText
    $media = Get-MediaText
    $serviceMatches = Get-ServiceMatches $service
    $notificationMatches = Get-NotificationMatches $notification
    $mediaMatches = Get-MediaMatches $media
    Write-Lines (Join-Path $evidenceRoot "service-after-$delay`s.txt") $serviceMatches
    Write-Lines (Join-Path $evidenceRoot "notification-after-$delay`s-filtered.txt") $notificationMatches
    Write-Lines (Join-Path $evidenceRoot "media-session-after-$delay`s-filtered.txt") $mediaMatches
    $checks[$delay] = [ordered]@{
        runtimePid = Get-RuntimePid
        serviceCount = $serviceMatches.Count
        notificationCount = $notificationMatches.Count
        activeMediaSessionCount = Get-ActiveMediaSessionCount $media
    }
}

$remainingWait = [Math]::Max(0, $WaitSecondsAfterStop - 10)
if ($remainingWait -gt 0) {
    Start-Sleep -Seconds $remainingWait
}

$runtimePidAfter = Get-RuntimePid
$serviceAfter = Get-ServiceText
$notificationAfter = Get-NotificationText
$mediaAfter = Get-MediaText
$serviceAfterMatches = Get-ServiceMatches $serviceAfter
$notificationAfterMatches = Get-NotificationMatches $notificationAfter
$mediaAfterMatches = Get-MediaMatches $mediaAfter
$mediaSessionCountAfter = Get-ActiveMediaSessionCount $mediaAfter
Write-Lines (Join-Path $evidenceRoot 'service-after-wait.txt') $serviceAfterMatches
Write-Lines (Join-Path $evidenceRoot 'notification-after-wait-filtered.txt') $notificationAfterMatches
Write-Lines (Join-Path $evidenceRoot 'media-session-after-wait-filtered.txt') $mediaAfterMatches

$filteredLog = Get-LogcatLines
$timeline = @(
    $filteredLog |
        Where-Object {
            $_ -match 'TTS_SENTENCE_REQUEST|TTS_LOADED|TTS_INFERENCE_FINISHED|TTS_RELEASE_DEFERRED|TTS_RELEASED|TTS_RELEASE_SKIPPED|TTS_STALE_RESULT_DISCARDED|AUDIO_TRACK_STARTED owner=TTS|AUDIO_TRACK_COMPLETED owner=TTS|COMMAND stop|STATE |TTS_STOPPED|SERVICE_DESTROYED|Process .* has died|Force finishing activity' -or
            $_ -cmatch '\sF DEBUG\s*:' -or
            $_ -match '\bpid:'
        }
)
Write-Lines (Join-Path $evidenceRoot 'runtime-logcat-package-filtered.log') $filteredLog
Write-Lines (Join-Path $evidenceRoot 'stop-timeline-filtered.log') $timeline

$crashBuffer = @(Invoke-Adb @('logcat', '-d', '-b', 'crash', '-v', 'threadtime'))
$pidPattern = "(?:\s$([regex]::Escape($runtimePidBefore))\s)"
$crashBufferMatches = @(
    $crashBuffer |
        Where-Object {
            $_ -match [regex]::Escape($package) -or
            $_ -match $pidPattern -or
            $_ -cmatch '\sF DEBUG\s*:' -or
            $_ -match 'signal \d+|Abort message|Cmdline:'
        }
)
Write-Lines (Join-Path $evidenceRoot 'crash-buffer-package-filtered.txt') $crashBufferMatches

$exitInfoText = Get-ExitInfoText
Write-Lines (Join-Path $evidenceRoot 'exit-info-package-raw.txt') ($exitInfoText -split "`n")
$currentExitBlocks = Get-CurrentExitBlocks $exitInfoText $startClock
Write-Lines (Join-Path $evidenceRoot 'exit-info-current-run.txt') @(
    $currentExitBlocks | ForEach-Object { $_.Value }
)
$currentNativeCrashes = @(
    $currentExitBlocks |
        Where-Object { $_.Value -match 'reason=5 \(APP CRASH\(NATIVE\)\)' }
)

$dropboxText = @(Invoke-Adb @(
    'shell', 'dumpsys', 'dropbox', '--print', 'SYSTEM_TOMBSTONE'
)) -join "`n"
$currentTombstones = Get-CurrentTombstones $dropboxText $startClock
Write-Lines (Join-Path $evidenceRoot 'tombs-current-run.txt') @(
    $currentTombstones | ForEach-Object { $_.Value }
)

$stopLine = $timeline |
    Where-Object { $_ -match ' KOKORO_RUNTIME: COMMAND stop$' } |
    Select-Object -Last 1
$stopLogTime = if ($null -ne $stopLine -and
        $stopLine -match '^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)') {
    $matches[1]
} else {
    $null
}
$isAfterStop = {
    param([string] $Line)
    if ($null -eq $stopLogTime -or
            $Line -notmatch '^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)') {
        return $false
    }
    return $matches[1] -gt $stopLogTime
}
$audioStartsAfterStop = @(
    $timeline |
        Where-Object {
            (& $isAfterStop $_) -and $_ -match 'AUDIO_TRACK_STARTED owner=TTS'
        }
).Count
$audioStartsBeforeStop = @(
    $timeline |
        Where-Object {
            $_ -match '^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)' -and
            $matches[1] -lt $stopLogTime -and
            $_ -match 'AUDIO_TRACK_STARTED owner=TTS'
        }
)
$audioCompletionsBeforeStop = @(
    $timeline |
        Where-Object {
            $_ -match '^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)' -and
            $matches[1] -lt $stopLogTime -and
            $_ -match 'AUDIO_TRACK_COMPLETED owner=TTS'
        }
)

$javaFatal = @($filteredLog | Where-Object { $_ -cmatch 'FATAL EXCEPTION' })
$anr = @($filteredLog | Where-Object { $_ -cmatch 'ANR in' })
$inputTimeout = @(
    $filteredLog |
        Where-Object { $_ -cmatch 'Input dispatching timed out' }
)
$fDebug = @($filteredLog | Where-Object { $_ -cmatch '\sF DEBUG\s*:' })
$tombstonesForRun = @(
    $currentTombstones |
        Where-Object { $_.Value -match [regex]::Escape($package) }
)
$processAlive = -not [string]::IsNullOrWhiteSpace($runtimePidAfter)
$samePid = $runtimePidAfter -eq $runtimePidBefore
$playingStopValid =
    $StopPhase -eq 'playing' -and
    $audioStartsBeforeStop.Count -gt 0 -and
    $audioCompletionsBeforeStop.Count -eq 0
$inferenceStopValid =
    $StopPhase -eq 'inference' -and
    $audioStartsBeforeStop.Count -eq 0
$crashGatePassed =
    $fDebug.Count -eq 0 -and
    $tombstonesForRun.Count -eq 0 -and
    $currentNativeCrashes.Count -eq 0 -and
    $javaFatal.Count -eq 0 -and
    $anr.Count -eq 0 -and
    $inputTimeout.Count -eq 0
$resourceConverged =
    $checks[3].serviceCount -eq 0 -and
    $checks[10].serviceCount -eq 0 -and
    $checks[10].notificationCount -eq 0 -and
    $checks[10].activeMediaSessionCount -eq 0 -and
    $serviceAfterMatches.Count -eq 0 -and
    $notificationAfterMatches.Count -eq 0 -and
    $mediaSessionCountAfter -eq 0
$phaseValid = if ($StopPhase -eq 'inference') {
    $inferenceStopValid
} else {
    $playingStopValid
}
$verdict = if (
    $phaseValid -and
    $processAlive -and
    $samePid -and
    $crashGatePassed -and
    $resourceConverged -and
    $audioStartsAfterStop -eq 0
) {
    'PASS'
} else {
    'FAIL'
}

$summary = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.stop-round.v1'
    serial = $Serial
    package = $package
    label = $Label
    stopPhase = $StopPhase
    startAt = $startAt
    startClock = $startClock
    gate = $gate
    launch = $launch
    requestObservedAt = $requestObservedAt
    preconditionAt = $preconditionAt
    runtimePidBefore = $runtimePidBefore
    runtimePidAfter = $runtimePidAfter
    samePid = $samePid
    processAliveAfterWait = $processAlive
    serviceBefore = [ordered]@{
        recordCount = $serviceBeforeMatches.Count
        foreground =
            [bool] (@($serviceBefore | Where-Object { $_ -match 'isForeground=true' }).Count)
    }
    notificationRecordCountBefore = $notificationBeforeMatches.Count
    activeMediaSessionCountBefore = $mediaSessionCountBefore
    notificationControl = $notificationClick.control
    notificationUiDump = $notificationClick.uiDumpResults
    tapAt = $notificationClick.tapAt
    stopLogTime = $stopLogTime
    atThreeSeconds = $checks[3]
    atTenSeconds = $checks[10]
    serviceRetainedAfterWait = $serviceAfterMatches.Count -gt 0
    notificationRecordCountAfterWait = $notificationAfterMatches.Count
    activeMediaSessionCountAfterWait = $mediaSessionCountAfter
    phaseValid = $phaseValid
    inferenceStopValid = $inferenceStopValid
    playingStopValid = $playingStopValid
    audioStartsBeforeStop = $audioStartsBeforeStop.Count
    audioCompletionsBeforeStop = $audioCompletionsBeforeStop.Count
    audioStartsAfterStop = $audioStartsAfterStop
    releaseDeferredSeen = [bool] (@(
        $filteredLog |
            Where-Object { $_ -match 'TTS_RELEASE_DEFERRED' }
    ).Count)
    releaseAfterInferenceSeen = [bool] (@(
        $filteredLog |
            Where-Object { $_ -match 'TTS_RELEASED reason=deferred_until_inference_finished' }
    ).Count)
    releaseImmediateSeen = [bool] (@(
        $filteredLog |
            Where-Object { $_ -match 'TTS_RELEASED reason=no_inference_in_flight' }
    ).Count)
    crashGate = [ordered]@{
        passed = $crashGatePassed
        fDebugCount = $fDebug.Count
        tombstoneCount = $tombstonesForRun.Count
        currentRunApplicationExitReason5Count = $currentNativeCrashes.Count
        currentRunApplicationExitBlockCount = $currentExitBlocks.Count
        javaFatalCount = $javaFatal.Count
        anrCount = $anr.Count
        inputTimeoutCount = $inputTimeout.Count
        tombstoneDirectory = 'PERMISSION_DENIED; SYSTEM_TOMBSTONE filtered by current start time and package'
    }
    resourceConvergence = [ordered]@{
        passed = $resourceConverged
        serviceGoneAfter3s = $checks[3].serviceCount -eq 0
        serviceGoneAfter10s = $checks[10].serviceCount -eq 0
        notificationGoneAfter10s = $checks[10].notificationCount -eq 0
        activeMediaSessionGoneAfter10s = $checks[10].activeMediaSessionCount -eq 0
        noNewAudioStartAfterStop = $audioStartsAfterStop -eq 0
    }
    verdict = $verdict
    evidence = [ordered]@{
        timeline = (Join-Path $evidenceRoot 'stop-timeline-filtered.log')
        filteredLog = (Join-Path $evidenceRoot 'runtime-logcat-package-filtered.log')
        crashBuffer = (Join-Path $evidenceRoot 'crash-buffer-package-filtered.txt')
        exitInfoRaw = (Join-Path $evidenceRoot 'exit-info-package-raw.txt')
        exitInfoCurrent = (Join-Path $evidenceRoot 'exit-info-current-run.txt')
        tombstonesCurrent = (Join-Path $evidenceRoot 'tombs-current-run.txt')
    }
}

Write-Json (Join-Path $evidenceRoot 'summary.json') $summary

Remove-Item -LiteralPath $remoteXml, $localXml -Force -ErrorAction SilentlyContinue
if ($notificationClick.uiDumpResults) {
    foreach ($result in $notificationClick.uiDumpResults) {
        if ($result.attempt) {
            $candidate = Join-Path $tempRoot "$Label-attempt-$($result.attempt).xml"
            Remove-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        }
    }
}

$summary | ConvertTo-Json -Depth 12
if ($verdict -ne 'PASS') {
    exit 1
}
