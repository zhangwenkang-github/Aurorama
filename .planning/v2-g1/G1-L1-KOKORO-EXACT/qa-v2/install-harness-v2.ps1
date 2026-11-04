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
$RepoRoot = (git -C $QaRoot rev-parse --show-toplevel).Trim()
$deviceRoot = Join-Path $QaRoot "device-$Label-$Serial"
$null = New-Item -ItemType Directory -Path $deviceRoot -Force
$commandLog = Join-Path $QaRoot 'commands.log'
$apk = (Resolve-Path -LiteralPath (Join-Path $RepoRoot '.planning\v2-g1\G1-L1-KOKORO-EXACT\harness\build\outputs\kokoro-isolated-harness-debug.apk')).Path
$expectedBytes = 426980914L
$expectedSha = '051a73dadb1b9b239f95e543e24dfdbeebf82c026d46652f3b392559e2357826'
$package = 'dev.aurorama.kokoro.harness'
$remoteXml = "/sdcard/kokoro_qav2_install_$Serial.xml"

function Write-Utf8([string] $Path, [AllowNull()][object] $Value) {
    $text = if ($null -eq $Value) { '' } else { [string]$Value }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-Log([string] $Message) {
    $line = (Get-Date).ToString('o') + ' [' + $Serial + '] ' + $Message + [Environment]::NewLine
    [IO.File]::AppendAllText($commandLog, $line, $Utf8NoBom)
}

$lockPath = 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md'
$active = @(Get-Content -LiteralPath $lockPath -Encoding utf8 |
    Where-Object { $_ -match '^### .* — ACTIVE(?:\s|$)' })
$expectedLock = "QAV2-$Label-$Serial"
if ($active.Count -ne 1 -or $active[0] -notmatch [regex]::Escape($expectedLock)) {
    throw "Unique expected lock missing: $expectedLock"
}
$identity = @{
    api = (& adb.exe -s $Serial shell getprop ro.build.version.sdk | Out-String).Trim()
    abi = (& adb.exe -s $Serial shell getprop ro.product.cpu.abi | Out-String).Trim()
}
if ($identity.api -notmatch '^\d+$' -or $identity.abi -ne 'arm64-v8a') {
    throw "Device ABI/API gate failed: $($identity | ConvertTo-Json -Compress)"
}
$hostItem = Get-Item -LiteralPath $apk
$hostSha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hostItem.Length -ne $expectedBytes -or $hostSha -ne $expectedSha) {
    throw 'Host APK size/SHA drift'
}
$listedBefore = ((& adb.exe -s $Serial shell pm list packages $package | Out-String).Trim())
if ($listedBefore -match [regex]::Escape("package:$package")) {
    throw 'Harness unexpectedly preinstalled'
}

$stdout = Join-Path $deviceRoot 'install-stdout.txt'
$stderr = Join-Path $deviceRoot 'install-stderr.txt'
$actions = Join-Path $deviceRoot 'install-actions.log'
Remove-Item -LiteralPath $stdout, $stderr, $actions -Force -ErrorAction SilentlyContinue
Write-Log "ADB install --no-streaming --user 0 $expectedSha"
$process = Start-Process -FilePath 'adb.exe' `
    -ArgumentList @('-s', $Serial, 'install', '--no-streaming', '--user', '0', $apk) `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

$clicked = [Collections.Generic.HashSet[string]]::new()
$identityGate = $false
$deadline = (Get-Date).AddMinutes(10)
$localXml = Join-Path $deviceRoot 'install-ui.xml'
while (-not $process.HasExited -and (Get-Date) -lt $deadline) {
    $activity = (& adb.exe -s $Serial shell dumpsys activity activities 2>&1 | Out-String)
    if ($activity -match 'AdbInstallActivity|PackageInstaller|packageinstaller') {
        & adb.exe -s $Serial shell rm -f $remoteXml *> $null
        & adb.exe -s $Serial shell uiautomator dump $remoteXml *> $null
        & adb.exe -s $Serial pull $remoteXml $localXml *> $null
        if (Test-Path -LiteralPath $localXml) {
            $xmlText = [IO.File]::ReadAllText($localXml, [Text.Encoding]::UTF8)
            if ($xmlText -match '请验证身份|请将人脸|人脸正对|指纹|小米账号|账号验证') {
                $identityGate = $true
                [IO.File]::AppendAllText(
                    $actions,
                    (Get-Date).ToString('o') + ' IDENTITY_GATE' + [Environment]::NewLine,
                    $Utf8NoBom)
                break
            }
            try {
                [xml] $document = $xmlText
                $nodes = @($document.SelectNodes('//*[@text]'))
                $pageHash = [Convert]::ToHexString(
                    [Security.Cryptography.SHA256]::HashData(
                        [Text.Encoding]::UTF8.GetBytes($xmlText)))
                foreach ($pattern in @(
                    '已了解.*未经安全检测|未经安全检测',
                    '^(继续安装|仍然安装|继续更新)$',
                    '^安装$')) {
                    $node = $nodes | Where-Object { $_.text -match $pattern } | Select-Object -First 1
                    if ($null -eq $node) { continue }
                    $key = $pageHash + ':' + $node.text + ':' + $node.bounds
                    if (-not $clicked.Add($key)) { continue }
                    $match = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
                    if ($match.Success) {
                        $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
                        $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
                        & adb.exe -s $Serial shell input tap $x $y *> $null
                        [IO.File]::AppendAllText(
                            $actions,
                            (Get-Date).ToString('o') + " CLICK $($node.text) $($node.bounds)" + [Environment]::NewLine,
                            $Utf8NoBom)
                    }
                    break
                }
            } catch {
                Write-Log "INSTALL_UI_PARSE_RETRY $($_.Exception.Message)"
            }
        }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $process.HasExited) {
    $null = $process.WaitForExit(30000)
}
& adb.exe -s $Serial shell rm -f $remoteXml *> $null
$stdoutText = if (Test-Path -LiteralPath $stdout) {
    [IO.File]::ReadAllText($stdout, [Text.Encoding]::UTF8)
} else { '' }
$stderrText = if (Test-Path -LiteralPath $stderr) {
    [IO.File]::ReadAllText($stderr, [Text.Encoding]::UTF8)
} else { '' }
if ($identityGate) { throw 'BLOCKED_IDENTITY_GATE' }
if (-not $process.HasExited -or $process.ExitCode -ne 0 -or $stdoutText -notmatch '(?m)^Success\s*$') {
    throw "INSTALL_FAILED exit=$($process.ExitCode) stdout=$stdoutText stderr=$stderrText"
}
Write-Log 'ADB install PASS exit=0 stdout=Success'

$listed = (& adb.exe -s $Serial shell pm list packages $package | Out-String)
if ($listed -notmatch [regex]::Escape("package:$package")) {
    throw 'Installed package not visible'
}
$packageDump = (& adb.exe -s $Serial shell dumpsys package $package | Out-String)
Write-Utf8 (Join-Path $deviceRoot 'installed-package.txt') $packageDump
$pathLine = @(& adb.exe -s $Serial shell pm path $package |
    Where-Object { $_ -match '^package:' } | Select-Object -First 1)
if (-not $pathLine) { throw 'pm path missing' }
$installedPath = ($pathLine -replace '^package:', '').Trim()
$remoteSizeText = ((& adb.exe -s $Serial shell stat -c '%s' $installedPath | Out-String).Trim())
$remoteHashText = ((& adb.exe -s $Serial shell sha256sum $installedPath | Out-String).Trim())
$remoteBytes = [long]$remoteSizeText
$remoteSha = ($remoteHashText -split '\s+')[0].ToLowerInvariant()
$uidLine = ((& adb.exe -s $Serial shell cmd package list packages -U $package | Out-String).Trim())
$uid = if ($uidLine -match '\buid:(\d+)') { [long]$Matches[1] } else { $null }
$primaryAbi = [regex]::Match($packageDump, 'primaryCpuAbi=([^\s]+)').Groups[1].Value
$aapt = 'F:\Develop\Android\AndroidSDK\build-tools\37.0.0\aapt2.exe'
$permissions = ((& $aapt dump permissions $apk 2>&1 | Out-String).Trim())
$badging = ((& $aapt dump badging $apk 2>&1 | Out-String).Trim())
$info = [ordered]@{
    installedAt = (Get-Date).ToString('o')
    package = $package
    installExit = $process.ExitCode
    installStdout = $stdoutText.Trim()
    installStderr = $stderrText.Trim()
    installedPath = $installedPath
    installedBytes = $remoteBytes
    installedSha256 = $remoteSha
    hostBytes = $hostItem.Length
    hostSha256 = $hostSha
    sizeMatch = ($remoteBytes -eq $expectedBytes)
    shaMatch = ($remoteSha -eq $expectedSha)
    primaryCpuAbi = $primaryAbi
    abiMatch = ($primaryAbi -eq 'arm64-v8a')
    permissions = $permissions
    internetPermissionPresent = [bool]($permissions -match 'android.permission.INTERNET')
    nativeCodes = @($badging -split "`r?`n" | Where-Object { $_ -match '^native-code:' })
    uid = $uid
    uidLine = $uidLine
}
$infoText = $info | ConvertTo-Json -Depth 12
Write-Utf8 (Join-Path $deviceRoot 'install-info.json') $infoText
Write-Log "INSTALL VERIFY size=$remoteBytes sha=$remoteSha abi=$primaryAbi uid=$uid internet=$($info.internetPermissionPresent)"
if (-not $info.sizeMatch -or -not $info.shaMatch -or -not $info.abiMatch) {
    throw 'Installed APK identity drift'
}
$infoText
