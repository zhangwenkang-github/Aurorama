[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $ApkPath,

    [string] $ExpectedPackage = 'dev.aurorama.kokoro.harness'
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$logRoot = Join-Path $taskRoot 'logs'
$null = New-Item -ItemType Directory -Path $logRoot -Force
$stdoutLog = Join-Path $logRoot 'install-stdout.log'
$stderrLog = Join-Path $logRoot 'install-stderr.log'
$uiXml = Join-Path $logRoot 'install-retry-ui.xml'
$remoteXml = '/sdcard/kokoro_harness_install_retry.xml'
$expectedPackage = $ExpectedPackage

Remove-Item -LiteralPath $stdoutLog, $stderrLog, $uiXml -Force -ErrorAction SilentlyContinue
$install = Start-Process -FilePath 'adb.exe' `
    -ArgumentList @('-s', $Serial, 'install', '--no-streaming', '--user', '0', $apk) `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru

$deadline = (Get-Date).AddMinutes(2)
$clicked = [Collections.Generic.HashSet[string]]::new()
$identityGate = $false
while (-not $install.HasExited -and (Get-Date) -lt $deadline) {
    $activity = & adb.exe -s $Serial shell dumpsys activity activities 2>$null
    if ($activity -match 'AdbInstallActivity') {
        & adb.exe -s $Serial shell uiautomator dump $remoteXml 2>$null | Out-Null
        & adb.exe -s $Serial pull $remoteXml $uiXml 2>$null | Out-Null
        if (Test-Path -LiteralPath $uiXml) {
            $xmlText = [IO.File]::ReadAllText($uiXml, [Text.Encoding]::UTF8)
            if ($xmlText -match '请验证身份|请将人脸|人脸正对|指纹|小米账号|账号验证') {
                $identityGate = $true
                break
            }
            [xml] $document = $xmlText
            $nodes = @($document.SelectNodes('//*[@text]'))
            $pageHash = [Convert]::ToHexString(
                [Security.Cryptography.SHA256]::HashData(
                    [Text.Encoding]::UTF8.GetBytes($xmlText)))

            $ack = $nodes |
                Where-Object { $_.text -match '已了解.*未经安全检测|未经安全检测' } |
                Select-Object -First 1
            if ($null -ne $ack) {
                $key = "ack:${pageHash}:$($ack.bounds)"
                if ($clicked.Add($key)) {
                    $match = [regex]::Match($ack.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
                    if ($match.Success) {
                        $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
                        $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
                        & adb.exe -s $Serial shell input tap $x $y
                        Add-Content -LiteralPath (Join-Path $logRoot 'install-actions.log') `
                            -Value "$(Get-Date -Format o) ACK $($ack.text) $($ack.bounds)" `
                            -Encoding utf8
                    }
                }
            }

            $target = $nodes |
                Where-Object { $_.text -match '^(继续安装|仍然安装|继续更新)$' } |
                Select-Object -First 1
            if ($null -ne $target) {
                $key = "${pageHash}:$($target.text):$($target.bounds)"
                if ($clicked.Add($key)) {
                    $match = [regex]::Match($target.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
                    if ($match.Success) {
                        $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
                        $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
                        & adb.exe -s $Serial shell input tap $x $y
                        Add-Content -LiteralPath (Join-Path $logRoot 'install-actions.log') `
                            -Value "$(Get-Date -Format o) CLICK $($target.text) $($target.bounds)" `
                            -Encoding utf8
                    }
                }
            }
        }
    }
    Start-Sleep -Milliseconds 150
}

if (-not $install.HasExited) {
    $install.WaitForExit(15000) | Out-Null
}
& adb.exe -s $Serial shell rm -f $remoteXml | Out-Null

$stdout = if (Test-Path -LiteralPath $stdoutLog) {
    [IO.File]::ReadAllText($stdoutLog, [Text.Encoding]::UTF8)
} else {
    ''
}
$stderr = if (Test-Path -LiteralPath $stderrLog) {
    [IO.File]::ReadAllText($stderrLog, [Text.Encoding]::UTF8)
} else {
    ''
}
if ($identityGate) {
    throw "BLOCKED_IDENTITY_GATE: installer requested account/biometric verification"
}
if (-not $install.HasExited -or $install.ExitCode -ne 0 -or $stdout -notmatch '(?m)^Success\s*$') {
    throw "INSTALL_FAILED exit=$($install.ExitCode) stdout=$stdout stderr=$stderr"
}
$packages = & adb.exe -s $Serial shell pm list packages $expectedPackage
if ($packages -notmatch [regex]::Escape("package:$expectedPackage")) {
    throw "INSTALL_NOT_VISIBLE expectedPackage=$expectedPackage"
}

Write-Output "INSTALL_PASS=$expectedPackage"
Write-Output ($stdout.Trim())
