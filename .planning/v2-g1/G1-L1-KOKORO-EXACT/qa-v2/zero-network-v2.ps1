[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('8e875894', '43af8627')]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet('K60', 'Pad5')]
    [string] $Label
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$QaRoot = $PSScriptRoot
$deviceRoot = Join-Path $QaRoot "device-$Label-$Serial"
$netRoot = Join-Path $QaRoot 'netstats'
$null = New-Item -ItemType Directory -Path $deviceRoot, $netRoot -Force
$commandLog = Join-Path $QaRoot 'commands.log'
$package = 'dev.aurorama.kokoro.harness'

function Write-Utf8([string] $Path, [AllowNull()][object] $Value) {
    $text = if ($null -eq $Value) { '' } else { [string]$Value }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-Log([string] $Message) {
    $line = (Get-Date).ToString('o') + ' [' + $Serial + '] ' + $Message + [Environment]::NewLine
    [IO.File]::AppendAllText($commandLog, $line, $Utf8NoBom)
}

function Adb([string[]] $Arguments) {
    $output = @(& adb.exe -s $Serial @Arguments 2>&1)
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = ($output -join [Environment]::NewLine)
        Lines = $output
    }
}

function Setting([string] $Namespace, [string] $Key) {
    return (Adb @('shell', 'settings', 'get', $Namespace, $Key)).Text.Trim()
}

function State {
    $connectivity = (Adb @('shell', 'dumpsys', 'connectivity')).Text
    $activeLines = @($connectivity -split "`r?`n" | Where-Object {
        $_ -match 'Active default network|NetworkAgentInfo.*(WIFI|MOBILE|ETHERNET)'
    } | Select-Object -First 20)
    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        airplaneMode = Setting 'global' 'airplane_mode_on'
        wifiOn = Setting 'global' 'wifi_on'
        mobileData = Setting 'global' 'mobile_data'
        wifiStatus = (Adb @('shell', 'svc', 'wifi', 'status')).Text.Trim()
        dataStatus = (Adb @('shell', 'svc', 'data', 'status')).Text.Trim()
        activeNetworkLines = $activeLines
        activeDefaultNetwork = [regex]::Match(($activeLines -join "`n"), 'Active default network:\s*([^\s]+)').Groups[1].Value
    }
}

function Parse-NetStats([string] $Text, [long] $Uid) {
    $lines = $Text -split "`r?`n"
    $blocks = [Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match ('^\s*ident=.*\buid=' + $Uid + '\b')) {
            $identityText = $lines[$i].Trim()
            $rx = [long]0
            $tx = [long]0
            for ($j = $i + 1; $j -lt [Math]::Min($lines.Count, $i + 5); $j++) {
                if ($lines[$j] -match '^\s*ident=') { break }
                foreach ($match in [regex]::Matches($lines[$j], '\brxBytes=(\d+)')) {
                    $rx += [long]$match.Groups[1].Value
                }
                foreach ($match in [regex]::Matches($lines[$j], '\btxBytes=(\d+)')) {
                    $tx += [long]$match.Groups[1].Value
                }
            }
            $blocks.Add([ordered]@{line=$i+1;identity=$identityText;rxBytes=$rx;txBytes=$tx})
        }
    }

    $appMapRows = [Collections.Generic.List[object]]::new()
    $inAppMap = $false
    foreach ($line in $lines) {
        if ($line -match '^\s*mAppUidStatsMap:\s*$') {
            $inAppMap = $true
            continue
        }
        if (-not $inAppMap) { continue }
        if ($line -match '^\s*uid rxBytes rxPackets txBytes txPackets\s*$') { continue }
        if ($line -match '^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$') {
            if ([long]$Matches[1] -eq $Uid) {
                $appMapRows.Add([ordered]@{
                    uid=[long]$Matches[1]
                    rxBytes=[long]$Matches[2]
                    rxPackets=[long]$Matches[3]
                    txBytes=[long]$Matches[4]
                    txPackets=[long]$Matches[5]
                })
            }
            continue
        }
        if ($line -match '^\s*m[A-Za-z].*:\s*') { $inAppMap = $false }
    }

    $identityRx = if ($blocks.Count) { ($blocks | Measure-Object rxBytes -Sum).Sum } else { [long]0 }
    $identityTx = if ($blocks.Count) { ($blocks | Measure-Object txBytes -Sum).Sum } else { [long]0 }
    $mapRx = if ($appMapRows.Count) { ($appMapRows | Measure-Object rxBytes -Sum).Sum } else { [long]0 }
    $mapTx = if ($appMapRows.Count) { ($appMapRows | Measure-Object txBytes -Sum).Sum } else { [long]0 }
    return [ordered]@{
        uid = $Uid
        identityBlockCount = $blocks.Count
        identityBlocks = @($blocks)
        identityRxBytes = [long]$identityRx
        identityTxBytes = [long]$identityTx
        appUidStatsMapRowCount = $appMapRows.Count
        appUidStatsMapRows = @($appMapRows)
        appUidStatsMapRxBytes = [long]$mapRx
        appUidStatsMapTxBytes = [long]$mapTx
    }
}

$lockPath = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
$active = @(Get-Content -LiteralPath $lockPath -Encoding utf8 |
    Where-Object { $_ -match '^### .* — ACTIVE(?:\s|$)' })
if ($active.Count -ne 1 -or $active[0] -notmatch [regex]::Escape("QAV2-$Label-$Serial")) {
    throw 'Unique expected device lock missing'
}

$uidLine = (Adb @('shell', 'cmd', 'package', 'list', 'packages', '-U', $package)).Text.Trim()
if ($uidLine -notmatch '\buid:(\d+)') { throw 'Harness UID missing' }
$uid = [long]$Matches[1]
$beforeNetwork = State
Write-Utf8 (Join-Path $deviceRoot "network-$Label-before-zero.json") ($beforeNetwork | ConvertTo-Json -Depth 12)
Write-Log "ZERO_NETWORK begin uid=$uid airplaneBefore=$($beforeNetwork.airplaneMode) wifiBefore=$($beforeNetwork.wifiOn) dataBefore=$($beforeNetwork.mobileData)"

$runOutput = $null
$summary = $null
try {
    $null = Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    $null = Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', 'disabled')
    $phoneData = Adb @('shell', 'cmd', 'phone', 'data', 'disable')
    $null = Adb @('shell', 'svc', 'wifi', 'disable')
    $null = Adb @('shell', 'svc', 'data', 'disable')
    Start-Sleep -Seconds 8
    $offNetwork = State
    $offNetwork['phoneDataDisableExit'] = $phoneData.ExitCode
    $offNetwork['phoneDataDisableOutput'] = $phoneData.Text.Trim()
    Write-Utf8 (Join-Path $deviceRoot "network-$Label-during-zero.json") ($offNetwork | ConvertTo-Json -Depth 12)
    $offText = $offNetwork | ConvertTo-Json -Depth 12 -Compress
    if ($offNetwork.airplaneMode -ne '1' -or $offNetwork.wifiOn -ne '0' -or $offNetwork.activeDefaultNetwork -match '^\d+$') {
        throw "Network-off gate failed: $offText"
    }

    $beforeStats = (Adb @('shell', 'dumpsys', 'netstats', 'detail')).Text
    $beforePath = Join-Path $netRoot "$Label-zero-network-before.txt"
    $afterPath = Join-Path $netRoot "$Label-zero-network-after.txt"
    Write-Utf8 $beforePath $beforeStats
    $parsedBefore = Parse-NetStats $beforeStats $uid
    Write-Log "NETSTATS before identityBlocks=$($parsedBefore.identityBlockCount) appMapRows=$($parsedBefore.appUidStatsMapRowCount)"

    $generationScript = Join-Path $QaRoot 'run-generation-v2.ps1'
    $runOutput = (& pwsh -NoProfile -File $generationScript `
        -Serial $Serial -Label $Label -Mode 'warm-zf' -Phase 'zero-network' -TimeoutSeconds 220 |
        Out-String)
    Write-Utf8 (Join-Path $deviceRoot "zero-network-$Label-run-output.json") $runOutput
    $runResultPath = Join-Path $deviceRoot "run-$Label-zero-network.json"
    if (-not (Test-Path -LiteralPath $runResultPath)) { throw 'Zero-network generation result missing' }
    $runResult = Get-Content -LiteralPath $runResultPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 30
    Start-Sleep -Seconds 8
    $afterStats = (Adb @('shell', 'dumpsys', 'netstats', 'detail')).Text
    Write-Utf8 $afterPath $afterStats
    $parsedAfter = Parse-NetStats $afterStats $uid
    $summary = [ordered]@{
        schema = 'aurorama.kokoro-exact.independent-qa.v2.zero-network'
        serial = $Serial
        label = $Label
        uid = $uid
        networkBefore = $beforeNetwork
        networkDuring = $offNetwork
        parsedBefore = $parsedBefore
        parsedAfter = $parsedAfter
        identityRxDeltaBytes = $parsedAfter.identityRxBytes - $parsedBefore.identityRxBytes
        identityTxDeltaBytes = $parsedAfter.identityTxBytes - $parsedBefore.identityTxBytes
        identityTotalDeltaBytes = ($parsedAfter.identityRxBytes + $parsedAfter.identityTxBytes) - ($parsedBefore.identityRxBytes + $parsedBefore.identityTxBytes)
        appUidStatsMapRxDeltaBytes = $parsedAfter.appUidStatsMapRxBytes - $parsedBefore.appUidStatsMapRxBytes
        appUidStatsMapTxDeltaBytes = $parsedAfter.appUidStatsMapTxBytes - $parsedBefore.appUidStatsMapTxBytes
        appUidStatsMapTotalDeltaBytes = ($parsedAfter.appUidStatsMapRxBytes + $parsedAfter.appUidStatsMapTxBytes) - ($parsedBefore.appUidStatsMapRxBytes + $parsedBefore.appUidStatsMapTxBytes)
        generation = $runResult
        beforeNetstats = $beforePath
        afterNetstats = $afterPath
    }
} finally {
    if ($beforeNetwork.airplaneMode -eq '1') {
        $null = Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    } else {
        $null = Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
    }
    if ($beforeNetwork.wifiOn -eq '1') {
        $null = Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', 'enabled')
        $null = Adb @('shell', 'svc', 'wifi', 'enable')
    } else {
        $null = Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', 'disabled')
        $null = Adb @('shell', 'svc', 'wifi', 'disable')
    }
    if ($beforeNetwork.mobileData -eq '1') {
        $null = Adb @('shell', 'cmd', 'phone', 'data', 'enable')
        $null = Adb @('shell', 'svc', 'data', 'enable')
    } else {
        $null = Adb @('shell', 'cmd', 'phone', 'data', 'disable')
        $null = Adb @('shell', 'svc', 'data', 'disable')
    }
    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Seconds 2
        $restored = State
    } while (($beforeNetwork.airplaneMode -ne '1') -and ($restored.activeDefaultNetwork -notmatch '^\d+$') -and (Get-Date) -lt $deadline)
    Write-Utf8 (Join-Path $deviceRoot "network-$Label-restored-zero.json") ($restored | ConvertTo-Json -Depth 12)
    Write-Log "ZERO_NETWORK restore airplane=$($restored.airplaneMode) wifi=$($restored.wifiOn) data=$($restored.mobileData) active=$($restored.activeDefaultNetwork)"
    if ($null -ne $summary) {
        $summary.restoredNetwork = $restored
        $summary.networkRestoredMatch = (
            $restored.airplaneMode -eq $beforeNetwork.airplaneMode -and
            $restored.wifiOn -eq $beforeNetwork.wifiOn -and
            $restored.mobileData -eq $beforeNetwork.mobileData -and
            ($beforeNetwork.airplaneMode -eq '1' -or $restored.activeDefaultNetwork -match '^\d+$'))
        $summaryPath = Join-Path $deviceRoot "zero-network-$Label-summary.json"
        Write-Utf8 $summaryPath ($summary | ConvertTo-Json -Depth 30)
        Write-Log "ZERO_NETWORK end identityDelta=$($summary.identityTotalDeltaBytes) appMapDelta=$($summary.appUidStatsMapTotalDeltaBytes) networkRestored=$($summary.networkRestoredMatch)"
    }
}

if ($null -eq $summary) { throw 'Zero-network summary unavailable' }
$summary | ConvertTo-Json -Depth 30
