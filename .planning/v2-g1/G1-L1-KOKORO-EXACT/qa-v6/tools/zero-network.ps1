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
$peerPackage = 'dev.aurorama.kokoro.focuspeer'
$netRoot = Join-Path $outputPath 'netstats'
$utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $netRoot | Out-Null

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Read-Setting([string] $Namespace, [string] $Key) {
    return ((Invoke-Adb @('shell', 'settings', 'get', $Namespace, $Key)).text).Trim()
}

function Read-NetworkState {
    $connectivity = (Invoke-Adb @('shell', 'dumpsys', 'connectivity')).text
    $defaultMatch = [regex]::Match(
        $connectivity,
        'Active default network:\s*([^\s]+)')
    $transport = ''
    if ($connectivity -match 'NetworkAgentInfo.*ni\{(WIFI|MOBILE|ETHERNET)') {
        $transport = $Matches[1]
    }
    [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        airplaneMode = Read-Setting 'global' 'airplane_mode_on'
        wifiOn = Read-Setting 'global' 'wifi_on'
        mobileData = Read-Setting 'global' 'mobile_data'
        activeDefaultNetwork = $defaultMatch.Groups[1].Value
        transport = $transport
    }
}

function Convert-NetStats([string] $Text, [long] $Uid) {
    $lines = $Text -split "`r?`n"
    $blocks = [Collections.Generic.List[object]]::new()
    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex] -notmatch ('^\s*ident=.*\buid=' + $Uid + '\b')) {
            continue
        }
        $identity = $lines[$lineIndex].Trim()
        $rx = [long]0
        $tx = [long]0
        for ($cursor = $lineIndex; $cursor -lt $lines.Count; $cursor++) {
            if ($cursor -gt $lineIndex -and $lines[$cursor] -match '^\s*ident=') {
                break
            }
            foreach ($match in [regex]::Matches($lines[$cursor], '\brxBytes=(\d+)')) {
                $rx += [long]$match.Groups[1].Value
            }
            foreach ($match in [regex]::Matches($lines[$cursor], '\btxBytes=(\d+)')) {
                $tx += [long]$match.Groups[1].Value
            }
        }
        $blocks.Add([ordered]@{
            line = $lineIndex + 1
            identity = $identity
            rxBytes = $rx
            txBytes = $tx
        })
    }

    $mapRows = [Collections.Generic.List[object]]::new()
    $inMap = $false
    foreach ($line in $lines) {
        if ($line -match '^\s*mAppUidStatsMap:\s*$') {
            $inMap = $true
            continue
        }
        if (-not $inMap) {
            continue
        }
        if ($line -match '^\s*uid rxBytes rxPackets txBytes txPackets\s*$') {
            continue
        }
        if ($line -match '^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$') {
            if ([long]$Matches[1] -eq $Uid) {
                $mapRows.Add([ordered]@{
                    uid = [long]$Matches[1]
                    rxBytes = [long]$Matches[2]
                    rxPackets = [long]$Matches[3]
                    txBytes = [long]$Matches[4]
                    txPackets = [long]$Matches[5]
                })
            }
            continue
        }
        if ($line -match '^\s*m[A-Za-z].*:\s*') {
            $inMap = $false
        }
    }

    $identityRx = [long](($blocks | Measure-Object -Property rxBytes -Sum).Sum)
    $identityTx = [long](($blocks | Measure-Object -Property txBytes -Sum).Sum)
    $mapRx = [long](($mapRows | Measure-Object -Property rxBytes -Sum).Sum)
    $mapTx = [long](($mapRows | Measure-Object -Property txBytes -Sum).Sum)
    [ordered]@{
        uid = $Uid
        identityBlockCount = $blocks.Count
        identityBlocks = @($blocks)
        identityRxBytes = $identityRx
        identityTxBytes = $identityTx
        appUidStatsMapRowCount = $mapRows.Count
        appUidStatsMapRows = @($mapRows)
        appUidStatsMapRxBytes = $mapRx
        appUidStatsMapTxBytes = $mapTx
    }
}

$uidLine = (Invoke-Adb @(
    'shell', 'cmd', 'package', 'list', 'packages', '-U', $package)).text.Trim()
if ($uidLine -notmatch '\buid:(\d+)') {
    throw "Main harness UID is unavailable: $uidLine"
}
$uid = [long]$Matches[1]
$originalNetwork = Read-NetworkState
$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$null = Invoke-Adb @('shell', 'am', 'force-stop', $peerPackage)

$summary = $null
try {
    $null = Invoke-Adb @(
        'shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    $null = Invoke-Adb @(
        'shell', 'cmd', 'wifi', 'set-wifi-enabled', 'disabled')
    $null = Invoke-Adb @('shell', 'svc', 'wifi', 'disable')
    $null = Invoke-Adb @(
        'shell', 'cmd', 'phone', 'data', 'disable')
    $null = Invoke-Adb @('shell', 'svc', 'data', 'disable')
    Start-Sleep -Seconds 8
    $offlineNetwork = Read-NetworkState
    if (
        $offlineNetwork.airplaneMode -ne '1' -or
        $offlineNetwork.wifiOn -ne '0' -or
        $offlineNetwork.activeDefaultNetwork -match '^\d+$'
    ) {
        throw "Network-off gate failed: $($offlineNetwork | ConvertTo-Json -Compress)"
    }

    $beforeText = (Invoke-Adb @('shell', 'dumpsys', 'netstats', 'detail')).text
    $beforePath = Join-Path $netRoot 'before.txt'
    [IO.File]::WriteAllText($beforePath, $beforeText, $utf8)
    $before = Convert-NetStats $beforeText $uid

    $logOffset = (Get-Content -LiteralPath $logPath -Encoding utf8).Count
    $launch = Invoke-Adb @(
        'shell', 'am', 'start', '-W', '--activity-single-top',
        '-n', "$package/.HarnessActivity",
        '--es', 'command', 'play_sample')
    $deadline = (Get-Date).AddSeconds(120)
    $runtimeLines = @()
    do {
        Start-Sleep -Milliseconds 300
        $runtimeLines = @(
            Get-Content -LiteralPath $logPath -Encoding utf8 |
                Select-Object -Skip $logOffset
        )
        $runtimeFailed = @(
            $runtimeLines |
                Select-String -SimpleMatch 'RUNTIME_FAILED'
        ).Count -gt 0
        $runtimeCompleted = @(
            $runtimeLines |
                Select-String -SimpleMatch 'KOKORO_RUNTIME: TTS_COMPLETED'
        ).Count -gt 0
        if ($runtimeFailed) {
            throw 'Runtime failed during offline execution'
        }
    } while (
        -not $runtimeCompleted -and
        (Get-Date) -lt $deadline
    )
    if (-not $runtimeCompleted) {
        throw 'Offline runtime did not complete within 120 seconds'
    }
    Start-Sleep -Seconds 8

    $afterText = (Invoke-Adb @('shell', 'dumpsys', 'netstats', 'detail')).text
    $afterPath = Join-Path $netRoot 'after.txt'
    [IO.File]::WriteAllText($afterPath, $afterText, $utf8)
    $after = Convert-NetStats $afterText $uid
    $summary = [ordered]@{
        schema = 'aurorama.kokoro-qa-v6.zero-network.v1'
        serial = $Serial
        package = $package
        uid = $uid
        launchExitCode = $launch.exitCode
        launchOutput = $launch.text
        networkBefore = $originalNetwork
        networkDuring = $offlineNetwork
        runtimeExcerpt = @(
            $runtimeLines |
                Select-String -Pattern @(
                    'COMMAND play_sample',
                    'TTS_SENTENCE_REQUEST',
                    'AUDIO_TRACK_STARTED owner=TTS',
                    'AUDIO_TRACK_COMPLETED owner=TTS',
                    'TTS_COMPLETED'
                ) |
                ForEach-Object Line
        )
        before = $before
        after = $after
        identityRxDeltaBytes = $after.identityRxBytes - $before.identityRxBytes
        identityTxDeltaBytes = $after.identityTxBytes - $before.identityTxBytes
        identityTotalDeltaBytes =
            ($after.identityRxBytes + $after.identityTxBytes) -
            ($before.identityRxBytes + $before.identityTxBytes)
        appUidStatsMapRxDeltaBytes =
            $after.appUidStatsMapRxBytes - $before.appUidStatsMapRxBytes
        appUidStatsMapTxDeltaBytes =
            $after.appUidStatsMapTxBytes - $before.appUidStatsMapTxBytes
        appUidStatsMapTotalDeltaBytes =
            ($after.appUidStatsMapRxBytes + $after.appUidStatsMapTxBytes) -
            ($before.appUidStatsMapRxBytes + $before.appUidStatsMapTxBytes)
        beforeNetstats = $beforePath
        afterNetstats = $afterPath
    }
} finally {
    if ($originalNetwork.airplaneMode -eq '1') {
        $null = Invoke-Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    } else {
        $null = Invoke-Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
    }
    if ($originalNetwork.wifiOn -eq '1') {
        $null = Invoke-Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', 'enabled')
        $null = Invoke-Adb @('shell', 'svc', 'wifi', 'enable')
    } else {
        $null = Invoke-Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', 'disabled')
        $null = Invoke-Adb @('shell', 'svc', 'wifi', 'disable')
    }
    if ($originalNetwork.mobileData -eq '1') {
        $null = Invoke-Adb @('shell', 'cmd', 'phone', 'data', 'enable')
        $null = Invoke-Adb @('shell', 'svc', 'data', 'enable')
    } else {
        $null = Invoke-Adb @('shell', 'cmd', 'phone', 'data', 'disable')
        $null = Invoke-Adb @('shell', 'svc', 'data', 'disable')
    }
    $restoreDeadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 2
        $restoredNetwork = Read-NetworkState
    } while (
        $originalNetwork.airplaneMode -ne '1' -and
        $restoredNetwork.activeDefaultNetwork -notmatch '^\d+$' -and
        (Get-Date) -lt $restoreDeadline
    )
    if ($null -ne $summary) {
        $summary.restoredNetwork = $restoredNetwork
        $summary.networkRestored =
            $restoredNetwork.airplaneMode -eq $originalNetwork.airplaneMode -and
            $restoredNetwork.wifiOn -eq $originalNetwork.wifiOn -and
            $restoredNetwork.mobileData -eq $originalNetwork.mobileData -and
            (
                $originalNetwork.airplaneMode -eq '1' -or
                $restoredNetwork.activeDefaultNetwork -match '^\d+$'
            )
    }
}

$mainDump = (Invoke-Adb @('shell', 'dumpsys', 'package', $package)).text
$peerDump = (Invoke-Adb @('shell', 'dumpsys', 'package', $peerPackage)).text
$summary.mainInternetPermission = $mainDump -match 'android.permission.INTERNET'
$summary.peerInternetPermission = $peerDump -match 'android.permission.INTERNET'
$summary.status =
    if (
        $summary.identityTotalDeltaBytes -eq 0 -and
        $summary.appUidStatsMapTotalDeltaBytes -eq 0 -and
        $summary.networkRestored -and
        -not $summary.mainInternetPermission -and
        -not $summary.peerInternetPermission -and
        @(
            $summary.runtimeExcerpt |
                Select-String -SimpleMatch 'TTS_COMPLETED'
        ).Count -gt 0
    ) {
        'PASS_OFFLINE_COMPLETE_INFERENCE_ZERO_UID_NETSTATS'
    } else {
        'FAIL'
    }
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'zero-network.json'),
    ($summary | ConvertTo-Json -Depth 20),
    $utf8)
$summary | ConvertTo-Json -Depth 8
if ($summary.status -eq 'FAIL') {
    exit 1
}
