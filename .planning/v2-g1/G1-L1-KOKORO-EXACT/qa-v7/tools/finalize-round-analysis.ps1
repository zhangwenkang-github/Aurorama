[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$qaRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$rawRoot = Join-Path $qaRoot 'raw'
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Json {
    param(
        [string] $Path,
        [object] $Value
    )
    $text = ($Value | ConvertTo-Json -Depth 30) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $text + "`n", $utf8)
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

function Get-SessionStackEvidence {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return [ordered]@{
            path = $Path
            lines = @()
            sessionCount = $null
            parserError = 'FILE_MISSING'
        }
    }
    $lines = @(Get-Content -LiteralPath $Path -Encoding utf8)
    $start = -1
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match 'Sessions Stack - have\s+\d+\s+sessions:') {
            $start = $index
            break
        }
    }
    if ($start -lt 0) {
        $packageMatch = @(
            $lines |
                Select-String -Pattern 'dev\.aurorama\.kokoro\.harness' -SimpleMatch:$false
        ).Count
        return [ordered]@{
            path = $Path
            lines = @()
            sessionCount = if ($packageMatch -eq 0) { 0 } else { $null }
            parserError = if ($packageMatch -eq 0) {
                'NO_PACKAGE_MATCHES_IN_ACTIVE_SESSION_DUMP'
            } else {
                'SESSION_STACK_MISSING_WITH_PACKAGE_MATCH'
            }
        }
    }
    $end = $lines.Count - 1
    for ($index = $start + 1; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match 'Sessions Stack - have\s+\d+\s+sessions:') {
            $end = $index - 1
            break
        }
        if ($lines[$index] -match '^Audio playback \(lastly played comes first\)') {
            $end = $index
            break
        }
    }
    $selected = @($lines[$start..$end])
    $countMatch = [regex]::Match(
        ($selected -join "`n"),
        'Sessions Stack - have\s+(\d+)\s+sessions:')
    return [ordered]@{
        path = $Path
        lines = $selected
        sessionCount = if ($countMatch.Success) {
            [int] $countMatch.Groups[1].Value
        } else {
            $null
        }
        parserError = $null
    }
}

$rounds = @(
    [ordered]@{
        label = 'inference-stop'
        expectedPhaseValid = $true
        expectedReleaseDeferred = $true
        expectedReleaseImmediate = $false
    },
    [ordered]@{
        label = 'playing-stop'
        expectedPhaseValid = $true
        expectedReleaseDeferred = $false
        expectedReleaseImmediate = $true
    }
)

$outputs = @()
foreach ($round in $rounds) {
    $roundRoot = Join-Path $rawRoot $round.label
    $summaryPath = Join-Path $roundRoot 'summary.json'
    $summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding utf8 |
        ConvertFrom-Json -AsHashtable

    $mediaBefore = Get-SessionStackEvidence (
        Join-Path $roundRoot 'media-session-before-filtered.txt')
    $mediaThree = Get-SessionStackEvidence (
        Join-Path $roundRoot 'media-session-after-3s-filtered.txt')
    $mediaTen = Get-SessionStackEvidence (
        Join-Path $roundRoot 'media-session-after-10s-filtered.txt')
    $mediaWait = Get-SessionStackEvidence (
        Join-Path $roundRoot 'media-session-after-wait-filtered.txt')

    $beforeStackPath = Join-Path $roundRoot 'media-session-before-stack.txt'
    $waitStackPath = Join-Path $roundRoot 'media-session-after-wait-stack.txt'
    Write-Lines $beforeStackPath $mediaBefore.lines
    Write-Lines $waitStackPath $mediaWait.lines

    $previousBefore = [int] $summary.activeMediaSessionCountBefore
    $previousThree = [int] $summary.atThreeSeconds.activeMediaSessionCount
    $previousTen = [int] $summary.atTenSeconds.activeMediaSessionCount
    $previousWait = [int] $summary.activeMediaSessionCountAfterWait

    $summary.activeMediaSessionCountBefore = $mediaBefore.sessionCount
    $summary.atThreeSeconds.activeMediaSessionCount = $mediaThree.sessionCount
    $summary.atTenSeconds.activeMediaSessionCount = $mediaTen.sessionCount
    $summary.activeMediaSessionCountAfterWait = $mediaWait.sessionCount

    $summary.resourceConvergence.serviceGoneAfter3s =
        [int] $summary.atThreeSeconds.serviceCount -eq 0
    $summary.resourceConvergence.serviceGoneAfter10s =
        [int] $summary.atTenSeconds.serviceCount -eq 0
    $summary.resourceConvergence.notificationGoneAfter10s =
        [int] $summary.atTenSeconds.notificationCount -eq 0
    $summary.resourceConvergence.activeMediaSessionGoneAfter10s =
        $mediaTen.sessionCount -eq 0
    $summary.resourceConvergence.noNewAudioStartAfterStop =
        [int] $summary.audioStartsAfterStop -eq 0
    $resourcePassed =
        $summary.resourceConvergence.serviceGoneAfter3s -and
        $summary.resourceConvergence.serviceGoneAfter10s -and
        $summary.resourceConvergence.notificationGoneAfter10s -and
        $summary.resourceConvergence.activeMediaSessionGoneAfter10s -and
        $summary.resourceConvergence.noNewAudioStartAfterStop -and
        -not $summary.serviceRetainedAfterWait -and
        [int] $summary.notificationRecordCountAfterWait -eq 0 -and
        $mediaWait.sessionCount -eq 0
    $summary.resourceConvergence.passed = $resourcePassed

    $summary.crashGate.passed =
        [int] $summary.crashGate.fDebugCount -eq 0 -and
        [int] $summary.crashGate.tombstoneCount -eq 0 -and
        [int] $summary.crashGate.currentRunApplicationExitReason5Count -eq 0 -and
        [int] $summary.crashGate.javaFatalCount -eq 0 -and
        [int] $summary.crashGate.anrCount -eq 0 -and
        [int] $summary.crashGate.inputTimeoutCount -eq 0

    $phasePassed =
        [bool] $summary.phaseValid -and
        ([bool] $summary.phaseValid -eq [bool] $round.expectedPhaseValid)
    $releasePassed = if ($round.expectedReleaseDeferred) {
        [bool] $summary.releaseDeferredSeen -and
        [bool] $summary.releaseAfterInferenceSeen
    } else {
        [bool] $summary.releaseImmediateSeen
    }
    $summary.verdict = if (
        $phasePassed -and
        [bool] $summary.processAliveAfterWait -and
        [bool] $summary.samePid -and
        [bool] $summary.crashGate.passed -and
        [bool] $summary.resourceConvergence.passed -and
        [int] $summary.audioStartsAfterStop -eq 0 -and
        $releasePassed
    ) {
        'PASS'
    } else {
        'FAIL'
    }

    $summary.analysisCorrection = [ordered]@{
        id = "QA7-EV-$($round.label.ToUpperInvariant())-MEDIA-SESSIONS"
        kind = 'MEDIA_SESSION_PARSER_FALSE_POSITIVE'
        detail = 'The first parser matched package text in the Android audio-playback history after the active session stack. The active SessionStack count is the discriminator; history alone does not prove a live MediaSession.'
        previousCounts = [ordered]@{
            before = $previousBefore
            after3s = $previousThree
            after10s = $previousTen
            afterWait = $previousWait
        }
        correctedCounts = [ordered]@{
            before = $mediaBefore.sessionCount
            after3s = $mediaThree.sessionCount
            after10s = $mediaTen.sessionCount
            afterWait = $mediaWait.sessionCount
        }
        evidence = [ordered]@{
            beforeStack = $beforeStackPath
            afterWaitStack = $waitStackPath
        }
        deviceRerunRequired = $false
        reason = 'The already-captured raw dumps contain explicit SessionStack counts; recomputing the derived assertion preserves the device execution evidence.'
    }
    $summary.evidence.mediaSessionBeforeStack = $beforeStackPath
    $summary.evidence.mediaSessionAfterWaitStack = $waitStackPath
    foreach ($key in @(
        'timeline',
        'filteredLog',
        'crashBuffer',
        'exitInfoRaw',
        'exitInfoCurrent',
        'tombstonesCurrent'
    )) {
        if ($summary.evidence.ContainsKey($key) -and
                $null -ne $summary.evidence[$key]) {
            $summary.evidence[$key] = ([string] $summary.evidence[$key]).Replace(
                'G1-L1-KOKORO-EXACT\raw\',
                'G1-L1-KOKORO-EXACT\qa-v7\raw\')
        }
    }
    Write-Json $summaryPath $summary
    $outputs += [ordered]@{
        label = $round.label
        verdict = $summary.verdict
        activeSessionCounts = $summary.analysisCorrection.correctedCounts
        summary = $summaryPath
    }
}

$outputs | ConvertTo-Json -Depth 10
if (@($outputs | Where-Object { $_.verdict -ne 'PASS' }).Count -gt 0) {
    exit 1
}
