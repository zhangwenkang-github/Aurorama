[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $Apk,

    [Parameter(Mandatory = $true)]
    [string] $OutputRoot
)

$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$outputPath = (Resolve-Path -LiteralPath $OutputRoot).Path
$stdoutPath = Join-Path $outputPath 'miui-install-stdout.txt'
$stderrPath = Join-Path $outputPath 'miui-install-stderr.txt'
$remoteXml = '/sdcard/qa_v8_miui_install.xml'
$localXml = Join-Path $outputPath 'miui-install-popup.xml'
$resultPath = Join-Path $outputPath 'miui-install-wrapper.json'
$utf8 = [Text.UTF8Encoding]::new($false)

Remove-Item -LiteralPath $stdoutPath, $stderrPath, $localXml, $resultPath -Force -ErrorAction SilentlyContinue
& adb.exe -s $Serial shell rm -f $remoteXml 2>&1 | Out-Null

$installProcess = Start-Process -FilePath 'adb.exe' `
    -ArgumentList @('-s', $Serial, 'install', '--no-streaming', '--user', '0', '-g', $apkPath) `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath

$deadline = (Get-Date).AddMinutes(3)
$clicked = @()
$clickedKeys = [Collections.Generic.HashSet[string]]::new()
$observedActivities = [Collections.Generic.List[string]]::new()
$unsafeIdentityGate = $false
$lastFocus = ''

while (-not $installProcess.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 180
    $windowLines = @(& adb.exe -s $Serial shell dumpsys window 2>&1)
    $focusLine = $windowLines | Select-String -SimpleMatch 'mCurrentFocus=' | Select-Object -First 1
    $focus = if ($null -eq $focusLine) { '' } else { $focusLine.Line.Trim() }
    if ($focus -ne $lastFocus) {
        $lastFocus = $focus
        if ($focus) {
            $observedActivities.Add($focus)
        }
    }
    if ($focus -notmatch 'com\.miui\.securitycenter/com\.miui\.permcenter\.install\.AdbInstallActivity') {
        continue
    }

    $dumpOutput = @(& adb.exe -s $Serial shell uiautomator dump $remoteXml 2>&1)
    if ($LASTEXITCODE -ne 0) {
        continue
    }
    $pullOutput = @(& adb.exe -s $Serial pull $remoteXml $localXml 2>&1)
    if (
        $LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $localXml) -or
        (Get-Item -LiteralPath $localXml).Length -eq 0
    ) {
        continue
    }

    try {
        $xmlText = [IO.File]::ReadAllText($localXml, [Text.Encoding]::UTF8)
        [xml] $ui = $xmlText
    } catch {
        continue
    }

    $allText = @(
        $ui.SelectNodes('//*[@text]') |
            ForEach-Object { [string]$_.text } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if (
        $allText -match '验证身份|请验证|人脸|指纹|小米账号|Xiaomi Account'
    ) {
        $unsafeIdentityGate = $true
        break
    }

    $pageHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($xmlText)))
    $continueNodes = @(
        $ui.SelectNodes('//*[@text]') |
            Where-Object {
                $_.text -match '^(继续安装|仍然安装|继续更新)$' -and
                $_.enabled -eq 'true' -and
                $_.clickable -eq 'true'
            }
    )
    foreach ($continueNode in $continueNodes) {
        $key = "$pageHash|$($continueNode.text)|$($continueNode.bounds)"
        if (-not $clickedKeys.Add($key)) {
            continue
        }
        $bounds = [regex]::Match(
            $continueNode.bounds,
            '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $bounds.Success) {
            throw "Invalid MIUI confirmation bounds: $($continueNode.bounds)"
        }
        $tapX = [int]((
            [int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value
        ) / 2)
        $tapY = [int]((
            [int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value
        ) / 2)
        $clickedAt = (Get-Date).ToString('o')
        $tapResult = @(& adb.exe -s $Serial shell input tap $tapX $tapY 2>&1)
        $clicked += [ordered]@{
            clickedAt = $clickedAt
            text = [string]$continueNode.text
            bounds = [string]$continueNode.bounds
            tap = "$tapX,$tapY"
            exitCode = $LASTEXITCODE
            output = ($tapResult -join "`n")
        }
        Start-Sleep -Milliseconds 220
    }
}

if ($unsafeIdentityGate) {
    if (-not $installProcess.HasExited) {
        Stop-Process -Id $installProcess.Id -Force -ErrorAction SilentlyContinue
    }
} else {
    $remainingMilliseconds = [Math]::Max(
        1000,
        [int][Math]::Min(90000, ($deadline - (Get-Date)).TotalMilliseconds)
    )
    $installProcess.WaitForExit($remainingMilliseconds) | Out-Null
}

$stdout = if (Test-Path -LiteralPath $stdoutPath) {
    [IO.File]::ReadAllText($stdoutPath, [Text.Encoding]::UTF8)
} else {
    ''
}
$stderr = if (Test-Path -LiteralPath $stderrPath) {
    [IO.File]::ReadAllText($stderrPath, [Text.Encoding]::UTF8)
} else {
    ''
}
$exitCode = if ($installProcess.HasExited) { $installProcess.ExitCode } else { $null }
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v8.miui-install-wrapper.v1'
    freshInstall = $true
    serial = $Serial
    apk = $apkPath
    apkSize = (Get-Item -LiteralPath $apkPath).Length
    apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    startedAt = (Get-Date).ToString('o')
    installExitCode = $exitCode
    stdout = $stdout
    stderr = $stderr
    observedFocusTransitions = @($observedActivities)
    confirmationClicks = @($clicked)
    identityGateDetected = $unsafeIdentityGate
    success = -not $unsafeIdentityGate -and $exitCode -eq 0 -and $stdout -match '(?m)^Success\s*$'
}
[IO.File]::WriteAllText(
    $resultPath,
    ($result | ConvertTo-Json -Depth 8),
    $utf8
)
$result | ConvertTo-Json -Depth 8
if (-not $result.success) {
    exit 1
}
