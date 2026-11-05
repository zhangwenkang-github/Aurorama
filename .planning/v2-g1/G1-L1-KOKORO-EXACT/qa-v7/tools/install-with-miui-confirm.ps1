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
$outputPath = [IO.Path]::GetFullPath($OutputRoot)
$null = New-Item -ItemType Directory -Path $outputPath -Force
$stem = [IO.Path]::GetFileNameWithoutExtension($apkPath)
$stdoutPath = Join-Path $outputPath "$stem-install-stdout.txt"
$stderrPath = Join-Path $outputPath "$stem-install-stderr.txt"
$remoteXmlBase = "/sdcard/qa_v7_install_$stem"
$resultPath = Join-Path $outputPath "$stem-install-wrapper.json"
$utf8 = [Text.UTF8Encoding]::new($false)

Remove-Item -LiteralPath $stdoutPath, $stderrPath, $resultPath -Force -ErrorAction SilentlyContinue
& adb.exe -s $Serial shell rm -f "$remoteXmlBase-*.xml" 2>&1 | Out-Null

$installStartedAt = (Get-Date).ToString('o')
$installProcess = Start-Process -FilePath 'adb.exe' `
    -ArgumentList @(
        '-s', $Serial, 'install', '--no-streaming', '--user', '0', $apkPath
    ) `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath

$deadline = (Get-Date).AddMinutes(3)
$clicked = @()
$clickedKeys = [Collections.Generic.HashSet[string]]::new()
$observedPages = @()
$unsafeIdentityGate = $false
$lastFocus = ''
$attempt = 0

while (-not $installProcess.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 75
    $windowLines = @(& adb.exe -s $Serial shell dumpsys window 2>&1)
    $focusLine = $windowLines |
        Select-String -SimpleMatch 'mCurrentFocus=' |
        Select-Object -First 1
    $focus = if ($null -eq $focusLine) { '' } else { $focusLine.Line.Trim() }
    if ($focus -eq $lastFocus) {
        continue
    }
    $lastFocus = $focus
    if ($focus -notmatch 'com\.miui\.securitycenter/com\.miui\.permcenter\.install\.AdbInstallActivity') {
        continue
    }

    $attempt++
    $remoteXml = "$remoteXmlBase-$attempt.xml"
    $localXml = Join-Path $outputPath "$stem-install-popup-attempt-$attempt.xml"
    $dumpOutput = @(
        & adb.exe -s $Serial shell uiautomator dump --compressed $remoteXml 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        continue
    }
    $pullOutput = @(& adb.exe -s $Serial pull $remoteXml $localXml 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $localXml)) {
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
            ForEach-Object { [string] $_.text } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($allText -match '验证身份|请验证|人脸|指纹|小米账号|Xiaomi Account') {
        $unsafeIdentityGate = $true
        break
    }

    $candidateNodes = @(
        $ui.SelectNodes('//*[@text or @resource-id]') |
            Where-Object {
                $_.text -match '继续安装|仍然安装|继续更新' -or
                $_.'resource-id' -eq 'android:id/button2'
            } |
            ForEach-Object {
                [ordered]@{
                    text = [string] $_.text
                    resourceId = [string] $_.'resource-id'
                    bounds = [string] $_.bounds
                    enabled = [string] $_.enabled
                    clickable = [string] $_.clickable
                }
            }
    )
    $observedPages += [ordered]@{
        attempt = $attempt
        capturedAt = (Get-Date).ToString('o')
        visibleText = @($allText | Select-Object -First 40)
        candidateNodes = $candidateNodes
        localEvidence = $localXml
    }
    & adb.exe -s $Serial shell rm -f $remoteXml 2>&1 | Out-Null

    $pageHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($xmlText)))
    $continueNodes = @(
        $ui.SelectNodes('//*[@text or @resource-id]') |
            Where-Object {
                (
                    $_.text -match '^(继续安装|仍然安装|继续更新)$' -or
                    $_.'resource-id' -eq 'android:id/button2'
                ) -and
                $_.enabled -eq 'true'
            }
    )
    foreach ($node in $continueNodes) {
        $key = "$pageHash|$($node.text)|$($node.bounds)"
        if (-not $clickedKeys.Add($key)) {
            continue
        }
        $boundsMatch = [regex]::Match(
            [string] $node.bounds,
            '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $boundsMatch.Success) {
            throw "Invalid MIUI confirmation bounds: $($node.bounds)"
        }
        $tapX = [int] ((
            [int] $boundsMatch.Groups[1].Value +
            [int] $boundsMatch.Groups[3].Value
        ) / 2)
        $tapY = [int] ((
            [int] $boundsMatch.Groups[2].Value +
            [int] $boundsMatch.Groups[4].Value
        ) / 2)
        $tapOutput = @(
            & adb.exe -s $Serial shell input tap $tapX $tapY 2>&1 |
                ForEach-Object { $_.ToString() }
        )
        $clicked += [ordered]@{
            clickedAt = (Get-Date).ToString('o')
            text = [string] $node.text
            bounds = [string] $node.bounds
            tap = "$tapX,$tapY"
            exitCode = $LASTEXITCODE
            output = $tapOutput
        }
        Start-Sleep -Milliseconds 250
    }
}

if ($unsafeIdentityGate) {
    if (-not $installProcess.HasExited) {
        Stop-Process -Id $installProcess.Id -Force -ErrorAction SilentlyContinue
    }
} else {
    $remainingMilliseconds = [Math]::Max(
        1000,
        [int] [Math]::Min(90000, ($deadline - (Get-Date)).TotalMilliseconds)
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
$exitCode = if ($installProcess.HasExited) {
    $installProcess.ExitCode
} else {
    $null
}
$result = [ordered]@{
    schema = 'aurorama.kokoro-qa-v7.miui-install-wrapper.v1'
    serial = $Serial
    apk = $apkPath
    apkSize = (Get-Item -LiteralPath $apkPath).Length
    apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    startedAt = $installStartedAt
    installExitCode = $exitCode
    stdout = $stdout
    stderr = $stderr
    observedPages = $observedPages
    confirmationClicks = $clicked
    identityGateDetected = $unsafeIdentityGate
    success =
        -not $unsafeIdentityGate -and
        $exitCode -eq 0 -and
        $stdout -match '(?m)^Success\s*$'
}
[IO.File]::WriteAllText(
    $resultPath,
    ($result | ConvertTo-Json -Depth 10),
    $utf8
)
$result | ConvertTo-Json -Depth 10
if (-not $result.success) {
    exit 1
}
