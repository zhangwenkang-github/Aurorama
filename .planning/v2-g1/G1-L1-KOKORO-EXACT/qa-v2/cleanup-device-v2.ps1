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
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$QaRoot = $PSScriptRoot
$deviceRoot = Join-Path $QaRoot "device-$Label-$Serial"
$commandLog = Join-Path $QaRoot 'commands.log'
$package = 'dev.aurorama.kokoro.harness'
$remoteRoot = "/sdcard/Android/data/$package/files"
$beforeSnapshotPath = Join-Path $deviceRoot 'snapshot-before.json'
if (-not (Test-Path -LiteralPath $beforeSnapshotPath)) { throw 'Before snapshot missing' }
$beforeSnapshot = Get-Content -LiteralPath $beforeSnapshotPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 30

function Write-Utf8([string] $Path, [AllowNull()][object] $Value) {
    $text = if ($null -eq $Value) { '' } else { [string]$Value }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-Log([string] $Message) {
    [IO.File]::AppendAllText(
        $commandLog,
        (Get-Date).ToString('o') + ' [' + $Serial + '] ' + $Message + [Environment]::NewLine,
        $Utf8NoBom)
}

function Adb([string[]] $Arguments) {
    $output = @(& adb.exe -s $Serial @Arguments 2>&1)
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = ($output -join [Environment]::NewLine)
        Lines = $output
    }
}

function FirstMatch([string] $Text, [string] $Pattern) {
    $match = [regex]::Match($Text, $Pattern)
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return $null
}

function Setting([string] $Namespace, [string] $Key) {
    return (Adb @('shell', 'settings', 'get', $Namespace, $Key)).Text.Trim()
}

function Get-Pid {
    $text = (Adb @('shell', 'pidof', $package)).Text.Trim()
    if ($text -match '^(\d+)') { return [int]$Matches[1] }
    return $null
}

function PackageSnapshot([string] $Pkg, [string] $SafeName) {
    $dump = Adb @('shell', 'dumpsys', 'package', $Pkg)
    Write-Utf8 (Join-Path $deviceRoot "package-after-$SafeName.txt") $dump.Text
    $listed = (Adb @('shell', 'pm', 'list', 'packages', $Pkg)).Text -match [regex]::Escape("package:$Pkg")
    $pathLine = @((Adb @('shell', 'pm', 'path', $Pkg)).Text -split "`r?`n" |
        Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    $basePath = if ($pathLine) { ($pathLine -replace '^package:', '').Trim() } else { $null }
    $baseBytes = $null
    $baseSha = $null
    if ($basePath) {
        $sizeText = (Adb @('shell', 'stat', '-c', '%s', $basePath)).Text.Trim()
        if ($sizeText -match '^\d+$') { $baseBytes = [long]$sizeText }
        $hashText = (Adb @('shell', 'sha256sum', $basePath)).Text.Trim()
        if ($hashText) { $baseSha = ($hashText -split '\s+')[0].ToLowerInvariant() }
    }
    return [ordered]@{
        package = $Pkg
        installed = [bool]$listed
        versionName = FirstMatch $dump.Text 'versionName=([^\s]+)'
        versionCode = FirstMatch $dump.Text 'versionCode=(\d+)'
        codePath = FirstMatch $dump.Text 'codePath=([^\r\n]+)'
        firstInstallTime = FirstMatch $dump.Text 'firstInstallTime=([^\r\n]+)'
        lastUpdateTime = FirstMatch $dump.Text 'lastUpdateTime=([^\r\n]+)'
        dataDir = FirstMatch $dump.Text 'dataDir=([^\r\n]+)'
        primaryCpuAbi = FirstMatch $dump.Text 'primaryCpuAbi=([^\s]+)'
        basePath = $basePath
        baseBytes = $baseBytes
        baseSha256 = $baseSha
    }
}

function StateSnapshot {
    $users = (Adb @('shell', 'pm', 'list', 'users')).Text
    $power = (Adb @('shell', 'dumpsys', 'power')).Text
    $policy = (Adb @('shell', 'dumpsys', 'window', 'policy')).Text
    $connectivity = (Adb @('shell', 'dumpsys', 'connectivity')).Text
    $activeLines = @($connectivity -split "`r?`n" | Where-Object {
        $_ -match 'Active default network|NetworkAgentInfo.*(WIFI|MOBILE|ETHERNET)'
    } | Select-Object -First 30)
    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        currentUser = (Adb @('shell', 'am', 'get-current-user')).Text.Trim()
        users = $users
        wakefulness = FirstMatch $power 'mWakefulness=([^\s]+)'
        keyguardShowing = [bool]($policy -match '(?m)^\s+showing=true\s*$')
        airplaneMode = Setting 'global' 'airplane_mode_on'
        wifiOn = Setting 'global' 'wifi_on'
        mobileData = Setting 'global' 'mobile_data'
        screenOffTimeout = Setting 'system' 'screen_off_timeout'
        lockAfterTimeout = Setting 'secure' 'lock_screen_lock_after_timeout'
        stayOnWhilePluggedIn = Setting 'global' 'stay_on_while_plugged_in'
        activeNetworkLines = $activeLines
        activeDefaultNetwork = [regex]::Match(($activeLines -join "`n"), 'Active default network:\s*([^\s]+)').Groups[1].Value
    }
}

$active = @(Get-Content -LiteralPath 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md' -Encoding utf8 |
    Where-Object { $_ -match '^### .* — ACTIVE(?:\s|$)' })
if ($active.Count -ne 1 -or $active[0] -notmatch [regex]::Escape("QAV2-$Label-$Serial")) {
    throw 'Unique expected device lock missing'
}

$pidBefore = Get-Pid
$null = Adb @('shell', 'am', 'force-stop', $package)
$stopDeadline = (Get-Date).AddSeconds(12)
do {
    Start-Sleep -Milliseconds 300
    $pidAfter = Get-Pid
} while ($null -ne $pidAfter -and (Get-Date) -lt $stopDeadline)
$filesBeforeCleanup = (Adb @('shell', 'find', $remoteRoot, '-maxdepth', '2', '-type', 'f')).Text
$null = Adb @('shell', 'rm', '-rf', $remoteRoot)
$externalAbsentAfterRm = ((Adb @('shell', 'test', '-e', $remoteRoot)).ExitCode -ne 0)
$uninstall = Adb @('uninstall', $package)
Write-Log "CLEANUP force-stop=$($null -eq $pidAfter) rmExternal=$externalAbsentAfterRm uninstallExit=$($uninstall.ExitCode) output=$($uninstall.Text.Trim())"
Start-Sleep -Seconds 1
$packageVisible = (Adb @('shell', 'pm', 'list', 'packages', $package)).Text -match [regex]::Escape("package:$package")
$pidFinal = Get-Pid
$externalFinalAbsent = ((Adb @('shell', 'test', '-e', $remoteRoot)).ExitCode -ne 0)

$currentState = StateSnapshot
$beforeState = $beforeSnapshot.state
if ($currentState.airplaneMode -ne $beforeState.settings.airplaneMode) {
    if ($beforeState.settings.airplaneMode -eq '1') {
        $null = Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    } else {
        $null = Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
    }
}
if ($currentState.wifiOn -ne $beforeState.settings.wifiOn) {
    $wifiState = if ($beforeState.settings.wifiOn -eq '1') { 'enabled' } else { 'disabled' }
    $null = Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', $wifiState)
}
if ($currentState.mobileData -ne $beforeState.settings.mobileData) {
    $dataState = if ($beforeState.settings.mobileData -eq '1') { 'enable' } else { 'disable' }
    $null = Adb @('shell', 'cmd', 'phone', 'data', $dataState)
    $null = Adb @('shell', 'svc', 'data', $dataState)
}
if ($currentState.wakefulness -ne $beforeState.wakefulness) {
    $key = if ($beforeState.wakefulness -eq 'Awake') { '224' } else { '223' }
    $null = Adb @('shell', 'input', 'keyevent', $key)
}
if ($currentState.keyguardShowing) {
    $null = Adb @('shell', 'wm', 'dismiss-keyguard')
}
Start-Sleep -Seconds 6
$finalState = StateSnapshot
$release = PackageSnapshot 'io.github.zhangwenkang.aurorama' 'release'
$debug = PackageSnapshot 'io.github.zhangwenkang.aurorama.debug' 'debug'
$harness = PackageSnapshot $package 'harness'

$productionFields = @(
    'installed', 'versionName', 'versionCode', 'codePath', 'firstInstallTime',
    'lastUpdateTime', 'dataDir', 'primaryCpuAbi', 'basePath', 'baseBytes', 'baseSha256')
$differences = @()
foreach ($pair in @(@('release', $release), @('debug', $debug))) {
    $key = $pair[0]
    $current = $pair[1]
    $before = $beforeSnapshot.packages.$key
    foreach ($field in $productionFields) {
        if ($before.$field -ne $current.$field) {
            $differences += [ordered]@{
                package = $key
                field = $field
                before = $before.$field
                after = $current.$field
            }
        }
    }
}
$stateSettingsMatch = (
    $finalState.airplaneMode -eq $beforeState.settings.airplaneMode -and
    $finalState.wifiOn -eq $beforeState.settings.wifiOn -and
    $finalState.mobileData -eq $beforeState.settings.mobileData -and
    $finalState.screenOffTimeout -eq $beforeState.settings.screenOffTimeout -and
    $finalState.lockAfterTimeout -eq $beforeState.settings.lockAfterTimeout -and
    $finalState.stayOnWhilePluggedIn -eq $beforeState.settings.stayOnWhilePluggedIn)
$beforeKeyguardShowing = [bool](@($beforeState.keyguardRaw) | Where-Object { $_ -match 'showing=true' })
$screenStateMatch = (
    $finalState.wakefulness -eq $beforeState.wakefulness -and
    $finalState.keyguardShowing -eq $beforeKeyguardShowing)
$networkRestored = $beforeState.settings.airplaneMode -eq '1' -or $finalState.activeDefaultNetwork -match '^\d+$'
$result = [ordered]@{
    schema = 'aurorama.kokoro-exact.independent-qa.v2.cleanup'
    device = $Label
    serial = $Serial
    pidBefore = $pidBefore
    forceStopSucceeded = ($null -eq $pidAfter)
    pidAfter = $pidAfter
    filesBeforeCleanup = @($filesBeforeCleanup -split "`r?`n" | Where-Object { $_ })
    externalAbsentAfterRm = $externalAbsentAfterRm
    uninstallExit = $uninstall.ExitCode
    uninstallOutput = $uninstall.Text.Trim()
    packageVisibleAfterUninstall = [bool]$packageVisible
    pidFinal = $pidFinal
    externalAbsentFinal = $externalFinalAbsent
    snapshotAfter = [ordered]@{
        state = $finalState
        packages = [ordered]@{ release = $release; debug = $debug; harness = $harness }
    }
    productionPackageDifferences = $differences
    productionPackagesUnchanged = ($differences.Count -eq 0)
    stateSettingsMatch = $stateSettingsMatch
    screenStateMatch = $screenStateMatch
    screenStateNote = if ($screenStateMatch) { 'MATCH' } else { 'USER_ACTION_REQUIRED_UNLOCK_SCREEN' }
    networkRestored = $networkRestored
}
Write-Utf8 (Join-Path $deviceRoot 'snapshot-after.json') ($result.snapshotAfter | ConvertTo-Json -Depth 30)
Write-Utf8 (Join-Path $deviceRoot "cleanup-$Label.json") ($result | ConvertTo-Json -Depth 30)
Write-Log "CLEANUP VERIFY packageVisible=$packageVisible pid=$pidFinal externalAbsent=$externalFinalAbsent productionMatch=$($result.productionPackagesUnchanged) settingsMatch=$stateSettingsMatch screenMatch=$screenStateMatch networkRestored=$networkRestored"
if ($uninstall.ExitCode -ne 0 -or $packageVisible -or $null -ne $pidFinal -or -not $externalFinalAbsent -or -not $result.productionPackagesUnchanged -or -not $stateSettingsMatch) {
    throw 'Cleanup verification failed'
}
$result | ConvertTo-Json -Depth 30
