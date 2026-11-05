[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $MainApk,

    [Parameter(Mandatory = $true)]
    [string] $PeerApk
)

$ErrorActionPreference = 'Stop'
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$toolsRoot = $PSScriptRoot
$mainApkPath = (Resolve-Path -LiteralPath $MainApk).Path
$peerApkPath = (Resolve-Path -LiteralPath $PeerApk).Path
$mainPackage = 'dev.aurorama.kokoro.harness'
$peerPackage = 'dev.aurorama.kokoro.focuspeer'
$utf8 = [Text.UTF8Encoding]::new($false)
$lockPath = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
$artifactAuditPath = (
    Resolve-Path -LiteralPath (
        Join-Path $toolsRoot '..\..\runtime-closure-v3\artifacts\artifact-audit.json')
).Path
$artifactAudit = Get-Content -LiteralPath $artifactAuditPath -Raw -Encoding utf8 |
    ConvertFrom-Json
$pinnedMainSha = '252a3a15f637b05e874d9b2ad2aa0c48076d2babb83f66c5ec18683dc533e161'
$pinnedPeerSha = '61c79aa1b56b9fc921ce23199394d342f4badad31389b842f38c1d5c3a4bebab'
$expectedMainPermissions = @(
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.WAKE_LOCK'
)
$expectedPeerPermissions = @()
$mainInstallRoot = Join-Path $outputPath 'main-install'
$peerInstallRoot = Join-Path $outputPath 'peer-install'
New-Item -ItemType Directory -Force -Path $mainInstallRoot, $peerInstallRoot | Out-Null
. (Join-Path $PSScriptRoot 'qa-evidence-common.ps1')

function Invoke-Adb([string[]] $AdbArgs) {
    $captured = @(& adb.exe -s $Serial @AdbArgs 2>&1)
    [ordered]@{
        exitCode = $LASTEXITCODE
        text = ($captured -join "`n")
        lines = $captured
    }
}

function Assert-DeviceLock {
    $lines = [IO.File]::ReadAllLines($lockPath, [Text.Encoding]::UTF8)
    $matchingPattern =
        '^### .*QA[-_]?V8.*' +
        [regex]::Escape($Serial) +
        '.* — ACTIVE\s*$'
    $active = @(
        $lines |
            Where-Object { $_ -match $matchingPattern }
    )
    $allActive = @(
        $lines |
            Where-Object { $_ -match '^### .* — ACTIVE\s*$' }
    )
    if ($active.Count -ne 1 -or $allActive.Count -ne 1) {
        throw "Device lock gate failed: matching=$($active.Count) allActive=$($allActive.Count)"
    }
}

function Read-PackageIdentity([string] $Package) {
    $pathText = (Invoke-Adb @('shell', 'pm', 'path', $Package)).text.Trim()
    $basePath = (
        $pathText -split "`r?`n" |
            Where-Object { $_ -match '^package:' } |
            Select-Object -First 1
    ) -replace '^package:', ''
    if (-not $basePath) {
        throw "Package path missing for $Package"
    }
    $sha = (Invoke-Adb @('shell', 'sha256sum', $basePath)).text.Trim()
    $size = (Invoke-Adb @('shell', 'stat', '-c', '%s', $basePath)).text.Trim()
    $dump = (Invoke-Adb @('shell', 'dumpsys', 'package', $Package)).text
    $uidLine = (Invoke-Adb @(
        'shell', 'cmd', 'package', 'list', 'packages', '-U', $Package)).text.Trim()
    $permissionLines = @(
        ($dump -split "`r?`n") |
            Where-Object {
                $_ -match 'versionCode=|versionName=|primaryCpuAbi=|codePath=|firstInstallTime=|lastUpdateTime=|android\.permission\.(FOREGROUND_SERVICE|FOREGROUND_SERVICE_MEDIA_PLAYBACK|POST_NOTIFICATIONS|WAKE_LOCK|INTERNET)(:|$)'
            }
    )
    [ordered]@{
        package = $Package
        pathText = $pathText
        basePath = $basePath
        bytes = if ($size -match '^\d+$') { [long]$size } else { $null }
        sha256 = [regex]::Match($sha, '\b([0-9a-f]{64})\b').Groups[1].Value
        uidLine = $uidLine
        uid = if ($uidLine -match '\buid:(\d+)') { [int]$Matches[1] } else { $null }
        versionCode = if ($dump -match 'versionCode=(\d+)') { [int]$Matches[1] } else { $null }
        versionName = if ($dump -match 'versionName=([^\s]+)') { $Matches[1] } else { $null }
        primaryCpuAbi = if ($dump -match 'primaryCpuAbi=([^\s]+)') { $Matches[1] } else { $null }
        firstInstallTime =
            [regex]::Match($dump, 'firstInstallTime=([^\r\n]+)').Groups[1].Value.Trim()
        lastUpdateTime =
            [regex]::Match($dump, 'lastUpdateTime=([^\r\n]+)').Groups[1].Value.Trim()
        permissionLines = $permissionLines
        internetPermission = $dump -match 'android\.permission\.INTERNET'
        serviceLines = @(
            ($dump -split "`r?`n") |
                Where-Object {
                    $_ -match 'KokoroRuntimeService|FocusPeerActivity|foregroundServiceType|mediaPlayback'
                } |
                Select-Object -First 20
        )
    }
}

Assert-DeviceLock
$keyguard = [regex]::Match(
    (Invoke-Adb @('shell', 'dumpsys', 'window', 'policy')).text,
    'mIsShowing=(true|false)').Groups[1].Value
if (-not $keyguard) {
    $keyguard = [regex]::Match(
        (Invoke-Adb @('shell', 'dumpsys', 'window', 'policy')).text,
        'showing=(true|false)').Groups[1].Value
}
if ($keyguard -ne 'false') {
    throw "USER_UNLOCK_REQUIRED: keyguard=$keyguard"
}

if (-not (Test-Path -LiteralPath (Join-Path $outputPath 'before.json'))) {
    & pwsh -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $toolsRoot 'capture-before.ps1') `
        -Serial $Serial `
        -OutputRoot $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Baseline snapshot failed'
    }
}

$freshInstallStartedAt = Get-Date
$freshInstallDeviceEpochRaw = (
    Invoke-Adb @('shell', 'date', '+%s')).text.Trim()
$freshInstallDeviceEpochText = [regex]::Match(
    $freshInstallDeviceEpochRaw,
    '^\s*(\d{10,})\s*$').Groups[1].Value
if (-not $freshInstallDeviceEpochText) {
    throw "Invalid device epoch baseline: $freshInstallDeviceEpochRaw"
}
$freshInstallDeviceEpochSeconds =
    [long]$freshInstallDeviceEpochText
$freshInstallDeviceBaseline =
    [DateTimeOffset]::FromUnixTimeSeconds(
        $freshInstallDeviceEpochSeconds).ToLocalTime().DateTime
$mainPathBefore = Invoke-Adb @('shell', 'pm', 'path', $mainPackage)
$peerPathBefore = Invoke-Adb @('shell', 'pm', 'path', $peerPackage)
$mainPreUninstall = Invoke-Adb @('uninstall', '--user', '0', $mainPackage)
$peerPreUninstall = Invoke-Adb @('uninstall', '--user', '0', $peerPackage)
$mainPathAfterUninstall = Invoke-Adb @('shell', 'pm', 'path', $mainPackage)
$peerPathAfterUninstall = Invoke-Adb @('shell', 'pm', 'path', $peerPackage)

& pwsh -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $toolsRoot 'install-with-miui-confirm.ps1') `
    -Serial $Serial `
    -Apk $mainApkPath `
    -OutputRoot $mainInstallRoot
if ($LASTEXITCODE -ne 0) {
    throw 'Fresh main APK install failed'
}
& pwsh -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $toolsRoot 'install-with-miui-confirm.ps1') `
    -Serial $Serial `
    -Apk $peerApkPath `
    -OutputRoot $peerInstallRoot
if ($LASTEXITCODE -ne 0) {
    throw 'Fresh peer APK install failed'
}

$grantResult = Invoke-Adb @(
    'shell', 'pm', 'grant', $mainPackage, 'android.permission.POST_NOTIFICATIONS')
$mainIdentity = Read-PackageIdentity $mainPackage
$peerIdentity = Read-PackageIdentity $peerPackage
$mainInstallWrapper = Get-Content -LiteralPath (
    Join-Path $mainInstallRoot 'miui-install-wrapper.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$peerInstallWrapper = Get-Content -LiteralPath (
    Join-Path $peerInstallRoot 'miui-install-wrapper.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$mainInstalledManifest = Get-InstalledApkManifestForegroundEvidence `
    -Serial $Serial `
    -Package $mainPackage `
    -HostApkPath $mainApkPath
$mainPermissions = Get-ApkPermissionEvidence `
    -ApkPath $mainApkPath `
    -ExpectedPermissions $expectedMainPermissions `
    -Aapt2Path $mainInstalledManifest.manifest.aapt2Path
$peerPermissions = Get-ApkPermissionEvidence `
    -ApkPath $peerApkPath `
    -ExpectedPermissions $expectedPeerPermissions `
    -Aapt2Path $mainInstalledManifest.manifest.aapt2Path
$expectedMainSha = [string]$artifactAudit.main.sha256
$expectedPeerSha = [string]$artifactAudit.peer.sha256
$artifactAuditSha256 = (Get-FileHash -LiteralPath $artifactAuditPath -Algorithm SHA256).
    Hash.ToLowerInvariant()
$hostMainSha = (Get-FileHash -LiteralPath $mainApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$hostPeerSha = (Get-FileHash -LiteralPath $peerApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$postGranted = @(
    $mainIdentity.permissionLines |
        Where-Object { $_ -match 'POST_NOTIFICATIONS: granted=true' }
).Count -gt 0
$mainFirstInstallAt = if ($mainIdentity.firstInstallTime) {
    $parsed = [datetime]::MinValue
    $ok = [datetime]::TryParse(
        $mainIdentity.firstInstallTime,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeLocal,
        [ref]$parsed)
    if ($ok) { $parsed } else { $null }
} else {
    $null
}
$peerFirstInstallAt = if ($peerIdentity.firstInstallTime) {
    $parsed = [datetime]::MinValue
    $ok = [datetime]::TryParse(
        $peerIdentity.firstInstallTime,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeLocal,
        [ref]$parsed)
    if ($ok) { $parsed } else { $null }
} else {
    $null
}
$mainFreshInstallProof =
    $mainPathAfterUninstall.text -notmatch '^package:' -and
    $mainIdentity.basePath.StartsWith('/data/app/', [StringComparison]::Ordinal) -and
    $null -ne $mainFirstInstallAt -and
    $mainFirstInstallAt -ge $freshInstallDeviceBaseline.AddSeconds(-2)
$peerFreshInstallProof =
    $peerPathAfterUninstall.text -notmatch '^package:' -and
    $peerIdentity.basePath.StartsWith('/data/app/', [StringComparison]::Ordinal) -and
    $null -ne $peerFirstInstallAt -and
    $peerFirstInstallAt -ge $freshInstallDeviceBaseline.AddSeconds(-2)
$checks = [ordered]@{
    mainFreshInstall =
        [bool]$mainInstallWrapper.freshInstall -and $mainFreshInstallProof
    peerFreshInstall =
        [bool]$peerInstallWrapper.freshInstall -and $peerFreshInstallProof
    artifactAuditMainShaPinned = $expectedMainSha -eq $pinnedMainSha
    artifactAuditPeerShaPinned = $expectedPeerSha -eq $pinnedPeerSha
    hostMainShaMatchesArtifact = $hostMainSha -eq $expectedMainSha
    hostPeerShaMatchesArtifact = $hostPeerSha -eq $expectedPeerSha
    hostMainBytesMatchArtifact =
        (Get-Item -LiteralPath $mainApkPath).Length -eq [long]$artifactAudit.main.bytes
    hostPeerBytesMatchArtifact =
        (Get-Item -LiteralPath $peerApkPath).Length -eq [long]$artifactAudit.peer.bytes
    mainBytesMatch = $mainIdentity.bytes -eq (Get-Item -LiteralPath $mainApkPath).Length
    mainShaMatch = $mainIdentity.sha256 -eq $expectedMainSha
    mainVersionCode4 = $mainIdentity.versionCode -eq 4
    mainVersionNameV5 = $mainIdentity.versionName -eq '2.0-kokoro-runtime-v5'
    mainArm64 = $mainIdentity.primaryCpuAbi -eq 'arm64-v8a'
    mainExactPermissionSet = [bool]$mainPermissions.exactPermissionSetMatches
    mainNoInternetPermission =
        -not $mainIdentity.internetPermission -and
        -not $mainPermissions.internetPermission
    postNotificationsGranted = $postGranted
    peerBytesMatch = $peerIdentity.bytes -eq (Get-Item -LiteralPath $peerApkPath).Length
    peerShaMatch = $peerIdentity.sha256 -eq $expectedPeerSha
    peerExactPermissionSet = [bool]$peerPermissions.exactPermissionSetMatches
    peerNoInternetPermission =
        -not $peerIdentity.internetPermission -and
        -not $peerPermissions.internetPermission
    distinctUids = $mainIdentity.uid -ne $peerIdentity.uid
    installedApkManifestIdentityEstablished =
        [bool]$mainInstalledManifest.byteIdentityEstablished
    installedApkManifestMediaPlaybackType =
        [bool]$mainInstalledManifest.mediaPlaybackForegroundServiceTypeMatches
}
$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$identity = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.install-identity.v1'
    capturedAt = (Get-Date).ToString('o')
    serial = $Serial
    mainApk = $mainApkPath
    peerApk = $peerApkPath
    expectedMainSha256 = $expectedMainSha
    expectedPeerSha256 = $expectedPeerSha
    pinnedMainSha256 = $pinnedMainSha
    pinnedPeerSha256 = $pinnedPeerSha
    artifactAuditPath = $artifactAuditPath
    artifactAuditSha256 = $artifactAuditSha256
    artifactAuditMainSha256 = $expectedMainSha
    artifactAuditPeerSha256 = $expectedPeerSha
    freshInstall =
        $mainFreshInstallProof -and
        $peerFreshInstallProof -and
        [bool]$mainInstallWrapper.freshInstall -and
        [bool]$peerInstallWrapper.freshInstall
    freshInstallEvidence = [ordered]@{
        startedAt = $freshInstallStartedAt.ToString('o')
        deviceEpochRaw = $freshInstallDeviceEpochRaw
        deviceEpochSeconds = $freshInstallDeviceEpochSeconds
        deviceBaseline = $freshInstallDeviceBaseline.ToString('o')
        main = [ordered]@{
            pathBefore = $mainPathBefore
            uninstall = $mainPreUninstall
            pathAfterUninstall = $mainPathAfterUninstall
            pathAfterInstall = $mainIdentity.basePath
            firstInstallTime = $mainIdentity.firstInstallTime
            lastUpdateTime = $mainIdentity.lastUpdateTime
            proofPassed = $mainFreshInstallProof
        }
        peer = [ordered]@{
            pathBefore = $peerPathBefore
            uninstall = $peerPreUninstall
            pathAfterUninstall = $peerPathAfterUninstall
            pathAfterInstall = $peerIdentity.basePath
            firstInstallTime = $peerIdentity.firstInstallTime
            lastUpdateTime = $peerIdentity.lastUpdateTime
            proofPassed = $peerFreshInstallProof
        }
    }
    mainPreUninstall = $mainPreUninstall
    peerPreUninstall = $peerPreUninstall
    mainInstallWrapper = $mainInstallWrapper
    peerInstallWrapper = $peerInstallWrapper
    postNotificationGrant = $grantResult
    main = $mainIdentity
    peer = $peerIdentity
    mainPermissionAudit = $mainPermissions
    peerPermissionAudit = $peerPermissions
    mediaPlaybackForegroundServiceTypeMatches =
        [bool]$mainInstalledManifest.mediaPlaybackForegroundServiceTypeMatches
    installedApkManifest = $mainInstalledManifest
    checks = $checks
    status = if ($passed) { 'PASS_FRESH_INSTALL_DEVICE_IDENTITY' } else { 'FAIL' }
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'install-gate.json'),
    (($identity | ConvertTo-Json -Depth 20) + "`n"),
    $utf8)
if (-not $passed) {
    $identity | ConvertTo-Json -Depth 10
    exit 1
}

& pwsh -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $toolsRoot 'start-logcat.ps1') `
    -Serial $Serial `
    -OutputRoot $outputPath
if ($LASTEXITCODE -ne 0) {
    throw 'Host logcat capture failed to start'
}
$identity | ConvertTo-Json -Depth 10
