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
$remoteXml = '/sdcard/qa_v8_ui_cancel.xml'
$temporaryXml = Join-Path $outputPath 'ui-cancel-current.xml'
$utf8 = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Read-LatestState([string[]] $Lines) {
    $matches = @($Lines | Select-String -Pattern 'KOKORO_RUNTIME: STATE (\{[^\r\n]+\})')
    if ($matches.Count -eq 0) {
        return $null
    }
    try {
        return $matches[$matches.Count - 1].Matches[0].Groups[1].Value |
            ConvertFrom-Json -Depth 20
    } catch {
        return $null
    }
}

function Find-CancelNode {
    $null = Invoke-Adb @('shell', 'rm', '-f', $remoteXml)
    $dump = Invoke-Adb @('shell', 'uiautomator', 'dump', $remoteXml)
    if (
        $dump.exitCode -ne 0 -or
        [string]::IsNullOrWhiteSpace($dump.text)
    ) {
        throw "UI dump failed before cancel: $($dump.text)"
    }
    $pull = Invoke-Adb @('pull', $remoteXml, $temporaryXml)
    if (
        $pull.exitCode -ne 0 -or
        -not (Test-Path -LiteralPath $temporaryXml) -or
        (Get-Item -LiteralPath $temporaryXml).Length -eq 0 -or
        [string]::IsNullOrWhiteSpace($pull.text)
    ) {
        throw "Could not pull non-empty UI XML before cancel: $($pull.text)"
    }
    try {
        [xml] $document = [IO.File]::ReadAllText(
            $temporaryXml,
            [Text.Encoding]::UTF8)
    } finally {
        Remove-Item -LiteralPath $temporaryXml -Force -ErrorAction SilentlyContinue
    }
    return $document.SelectNodes('//*[@content-desc]') |
        Where-Object {
            $_.'content-desc' -eq 'runtime_command:cancel' -and
            $_.enabled -eq 'true'
        } |
        Select-Object -First 1
}

$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$lineOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$launch = Invoke-Adb @(
    'shell', 'am', 'start', '-W', '--activity-single-top',
    '-n', "$package/.HarnessActivity",
    '--es', 'command', 'play_full')
if ($launch.exitCode -ne 0) {
    throw "UI cancel playback launch failed: $($launch.text)"
}

$deadline = (Get-Date).AddSeconds(60)
$firstAudioLine = $null
do {
    Start-Sleep -Milliseconds 300
    $lines = @(
        Get-Content -LiteralPath $logPath -Encoding utf8 |
            Select-Object -Skip $lineOffset
    )
    $firstAudioLine = $lines |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS' |
        Select-Object -First 1
} while ($null -eq $firstAudioLine -and (Get-Date) -lt $deadline)
if ($null -eq $firstAudioLine) {
    throw 'No real AudioTrack before UI cancel'
}

$cancelNode = $null
for ($attempt = 0; $attempt -lt 6 -and $null -eq $cancelNode; $attempt++) {
    $cancelNode = Find-CancelNode
    if ($null -eq $cancelNode) {
        $null = Invoke-Adb @(
            'shell', 'input', 'swipe', '540', '2050', '540', '650', '450')
        Start-Sleep -Milliseconds 500
    }
}
if ($null -eq $cancelNode) {
    throw 'UI cancel command node was not found after bounded scrolling'
}

$bounds = [regex]::Match($cancelNode.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
if (-not $bounds.Success) {
    throw "Invalid cancel bounds: $($cancelNode.bounds)"
}
$x = [int]((
    [int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value
) / 2)
$y = [int]((
    [int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value
) / 2)
$cancelTapOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
$cancelTapAt = Get-Date
$tap = Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
Start-Sleep -Seconds 5

$lines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $lineOffset
)
$postTapLines = @(
    Get-Content -LiteralPath $logPath -Encoding utf8 |
        Select-Object -Skip $cancelTapOffset
)
$newAudioStartsAfterCancelTap = @(
    $postTapLines |
        Select-String -SimpleMatch 'AUDIO_TRACK_STARTED owner=TTS'
)
$cancelConverged = $lines |
    Select-String -SimpleMatch 'KOKORO_RUNTIME: CANCEL_CONVERGED' |
    Select-Object -First 1
$cancelExit = $lines |
    Select-String -SimpleMatch 'KOKORO_RUNTIME: CANCEL_PROCESS_EXIT' |
    Select-Object -First 1
$pidAfter = ((Invoke-Adb @('shell', 'pidof', $package)).text).Trim()
$serviceAfter = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $package)).text
$notificationAfter = (Invoke-Adb @('shell', 'dumpsys', 'notification', '--noredact')).text
$mediaAfter = (Invoke-Adb @('shell', 'dumpsys', 'media_session')).text
$mediaEvidence = Get-MediaSessionEvidence `
    -Text $mediaAfter `
    -Packages @($package)
$activeMainSessions = @(
    $mediaEvidence.activeSessions |
        Where-Object package -eq $package
)
$externalAfter = (Invoke-Adb @('shell', 'run-as', $package, 'find', 'files', '-maxdepth', '2', '-type', 'f')).text
$fatalLines = @(
    $lines |
        Select-String -Pattern 'FATAL EXCEPTION|ANR in dev\.aurorama\.kokoro\.harness|InputTimeout|Input dispatching timed out'
)

$checks = [ordered]@{
    realAudioBeforeCancel = $null -ne $firstAudioLine
    cancelUiNodeClicked =
        $tap.exitCode -eq 0 -and
        $cancelNode.'content-desc' -eq 'runtime_command:cancel'
    cancelConvergedLog = $null -ne $cancelConverged
    processExitLog = $null -ne $cancelExit
    noNewTtsAudioTrackAfterCancelTap =
        $newAudioStartsAfterCancelTap.Count -eq 0
    processGone = [string]::IsNullOrWhiteSpace($pidAfter)
    serviceGone = $serviceAfter -notmatch 'KokoroRuntimeService'
    notificationGone =
        $notificationAfter -notmatch 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
    activeMediaSessionGone = $activeMainSessions.Count -eq 0
    noTemporaryRuntimeFile =
        $externalAfter -notmatch 'runtime_state\.json|checkpoint'
    noFatalAnrInputTimeout = $fatalLines.Count -eq 0
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.ui-cancel.v1'
    status = if ($passed) { 'PASS_UI_CANCEL_CONVERGED' } else { 'FAIL' }
    serial = $Serial
    capturedAt = (Get-Date).ToString('o')
    firstAudioLine = $firstAudioLine.Line
    cancelNode = [ordered]@{
        contentDescription = [string]$cancelNode.'content-desc'
        text = [string]$cancelNode.text
        bounds = [string]$cancelNode.bounds
        tap = "$x,$y"
        tapExitCode = $tap.exitCode
        tapAt = $cancelTapAt.ToString('o')
        logOffset = $cancelTapOffset
    }
    newAudioTracksAfterCancelTap =
        @($newAudioStartsAfterCancelTap | ForEach-Object Line)
    cancelConvergedLine =
        if ($cancelConverged) { $cancelConverged.Line } else { $null }
    processExitLine = if ($cancelExit) { $cancelExit.Line } else { $null }
    checks = $checks
    final = [ordered]@{
        pid = if ($pidAfter) { $pidAfter } else { $null }
        servicePresent = $serviceAfter -match 'KokoroRuntimeService'
        notificationPresent =
            $notificationAfter -match 'NotificationRecord\(.*pkg=dev\.aurorama\.kokoro\.harness'
        activeMediaSessionCount = $activeMainSessions.Count
        mediaSessionExcerpt = $mediaEvidence.excerpt
        externalFind = if ($externalAfter) { $externalAfter } else { '' }
        fatalAnrInputTimeoutLines = @($fatalLines | ForEach-Object Line)
    }
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'ui-cancel.json'),
    ($result | ConvertTo-Json -Depth 20),
    $utf8)
$null = Invoke-Adb @('shell', 'rm', '-f', $remoteXml)
$result | ConvertTo-Json -Depth 8
if (-not $passed) {
    exit 1
}
