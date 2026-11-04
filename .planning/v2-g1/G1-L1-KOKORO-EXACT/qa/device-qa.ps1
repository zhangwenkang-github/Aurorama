[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('8e875894', '43af8627')]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet('K60', 'Pad5')]
    [string] $Label,

    [string] $ExpectedModel,

    [switch] $CleanupOnly,

    [switch] $RemainingOnly
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$QaRoot = $PSScriptRoot
$TaskRoot = (Resolve-Path -LiteralPath (Join-Path $QaRoot '..')).Path
$Apk = (Resolve-Path -LiteralPath (Join-Path $TaskRoot 'harness\build\outputs\kokoro-isolated-harness-debug.apk')).Path
$Aapt2 = 'F:\Develop\Android\AndroidSDK\build-tools\37.0.0\aapt2.exe'
$PackageName = 'dev.aurorama.kokoro.harness'
$RemoteFiles = "/sdcard/Android/data/$PackageName/files"
$RemoteInstallXml = "/sdcard/kokoro_qa_install_$Serial.xml"
$RemoteUiXml = "/sdcard/kokoro_qa_ui_$Serial.xml"
$CommandLog = Join-Path $QaRoot 'commands.log'
$DeviceRoot = Join-Path $QaRoot ("device-$Label-$Serial")
$null = New-Item -ItemType Directory -Path $DeviceRoot -Force
$script:Serial = $Serial
$script:NetworkTouched = $false
$script:SleepTouched = $false

function Write-EvidenceText {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [AllowNull()] [object] $Content
    )
    $text = if ($null -eq $Content) {
        ''
    } elseif ($Content -is [array]) {
        ($Content | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    } else {
        [string]$Content
    }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-QaLog {
    param([string] $Message)
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff zzz'), $Serial, $Message
    [IO.File]::AppendAllText($CommandLog, $line + [Environment]::NewLine, $Utf8NoBom)
}

function Get-CommandDisplay {
    param([string[]] $Arguments)
    $rendered = $Arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }
    return "adb -s $Serial " + ($rendered -join ' ')
}

function Invoke-AdbCapture {
    param(
        [Parameter(Mandatory = $true)] [string[]] $Arguments,
        [switch] $AllowFailure,
        [switch] $Quiet
    )
    $output = @(& adb.exe -s $Serial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    if (-not $Quiet) {
        $preview = ($output | Select-Object -First 3) -join ' | '
        if ($preview.Length -gt 500) { $preview = $preview.Substring(0, 500) + '...' }
        Write-QaLog ("CMD {0} ; EXIT={1} ; OUT={2}" -f (Get-CommandDisplay $Arguments), $exitCode, $preview)
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "ADB command failed (exit=$exitCode): $(Get-CommandDisplay $Arguments)`n$text"
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Text = $text; Lines = $output }
}

function Invoke-AdbShell {
    param(
        [Parameter(Mandatory = $true)] [string[]] $ShellArguments,
        [switch] $AllowFailure,
        [switch] $Quiet
    )
    return Invoke-AdbCapture -Arguments (@('shell') + $ShellArguments) -AllowFailure:$AllowFailure -Quiet:$Quiet
}

function Get-SettingValue {
    param([string] $Namespace, [string] $Key)
    $result = Invoke-AdbShell -ShellArguments @('settings', 'get', $Namespace, $Key) -Quiet
    return $result.Text.Trim()
}

function Get-Pid {
    $result = Invoke-AdbShell -ShellArguments @('pidof', $PackageName) -AllowFailure -Quiet
    $value = $result.Text.Trim()
    if ($value -match '^(\d+)') { return [int]$Matches[1] }
    return $null
}

function Test-RemoteFile {
    param([string] $Path)
    & adb.exe -s $Serial shell test -f $Path *> $null
    return ($LASTEXITCODE -eq 0)
}

function Pull-RemoteFile {
    param([string] $RemotePath, [string] $LocalPath)
    $result = Invoke-AdbCapture -Arguments @('pull', $RemotePath, $LocalPath)
    if ($result.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $LocalPath)) {
        throw "Pull failed: $RemotePath -> $LocalPath"
    }
}

function Get-FirstMatch {
    param([string] $Text, [string] $Pattern)
    $match = [regex]::Match($Text, $Pattern)
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return $null
}

function Get-PackageSnapshot {
    param([string] $Package)
    $result = Invoke-AdbShell -ShellArguments @('dumpsys', 'package', $Package) -AllowFailure
    $text = $result.Text
    $safeName = $Package -replace '[^A-Za-z0-9_.-]', '_'
    Write-EvidenceText -Path (Join-Path $DeviceRoot "package-$safeName.txt") -Content $text
    $listed = Invoke-AdbShell -ShellArguments @('pm', 'list', 'packages', $Package) -AllowFailure -Quiet
    $installed = $listed.Text -match ([regex]::Escape("package:$Package"))
    return [ordered]@{
        package = $Package
        installed = $installed
        versionName = Get-FirstMatch $text 'versionName=([^\s]+)'
        versionCode = Get-FirstMatch $text 'versionCode=(\d+)'
        codePath = Get-FirstMatch $text 'codePath=([^\r\n]+)'
        firstInstallTime = Get-FirstMatch $text 'firstInstallTime=([^\r\n]+)'
        lastUpdateTime = Get-FirstMatch $text 'lastUpdateTime=([^\r\n]+)'
        dataDir = Get-FirstMatch $text 'dataDir=([^\r\n]+)'
        primaryCpuAbi = Get-FirstMatch $text 'primaryCpuAbi=([^\s]+)'
        userId = Get-FirstMatch $text 'userId=(\d+)'
    }
}

function Get-StateSnapshot {
    $connectivity = (Invoke-AdbShell -ShellArguments @('dumpsys', 'connectivity') -AllowFailure).Text
    $connectivityLines = @($connectivity -split "`r?`n" | Where-Object {
        $_ -match 'Active default network|NetworkAgentInfo.*(WIFI|MOBILE|ETHERNET)|NetworkRequest'
    } | Select-Object -First 40)
    $power = (Invoke-AdbShell -ShellArguments @('dumpsys', 'power') -AllowFailure).Text
    $windowPolicy = (Invoke-AdbShell -ShellArguments @('dumpsys', 'window', 'policy') -AllowFailure).Text
    $activity = (Invoke-AdbShell -ShellArguments @('dumpsys', 'activity', 'activities') -AllowFailure).Text
    $currentUser = (Invoke-AdbShell -ShellArguments @('am', 'get-current-user') -AllowFailure -Quiet).Text.Trim()
    $wifiStatus = (Invoke-AdbShell -ShellArguments @('svc', 'wifi', 'status') -AllowFailure -Quiet).Text.Trim()
    $dataStatus = (Invoke-AdbShell -ShellArguments @('svc', 'data', 'status') -AllowFailure -Quiet).Text.Trim()

    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        currentUser = $currentUser
        airplaneMode = Get-SettingValue 'global' 'airplane_mode_on'
        wifiOnSetting = Get-SettingValue 'global' 'wifi_on'
        mobileDataSetting = Get-SettingValue 'global' 'mobile_data'
        wifiService = $wifiStatus
        dataService = $dataStatus
        connectivity = $connectivityLines
        wakefulness = Get-FirstMatch $power 'mWakefulness=([^\s]+)'
        keyguardShowing = Get-FirstMatch $windowPolicy 'showing=([^\s]+)'
        screenOffTimeout = Get-SettingValue 'system' 'screen_off_timeout'
        lockAfterTimeout = Get-SettingValue 'secure' 'lock_screen_lock_after_timeout'
        stayOnWhilePluggedIn = Get-SettingValue 'global' 'stay_on_while_plugged_in'
        topActivityLines = @($activity -split "`r?`n" | Where-Object {
            $_ -match 'topResumedActivity|mResumedActivity'
        } | Select-Object -First 5)
    }
}

function Get-DeviceSnapshot {
    $identity = [ordered]@{
        serial = $Serial
        model = (Invoke-AdbShell -ShellArguments @('getprop', 'ro.product.model') -Quiet).Text.Trim()
        product = (Invoke-AdbShell -ShellArguments @('getprop', 'ro.product.name') -Quiet).Text.Trim()
        androidRelease = (Invoke-AdbShell -ShellArguments @('getprop', 'ro.build.version.release') -Quiet).Text.Trim()
        api = (Invoke-AdbShell -ShellArguments @('getprop', 'ro.build.version.sdk') -Quiet).Text.Trim()
        abiList = (Invoke-AdbShell -ShellArguments @('getprop', 'ro.product.cpu.abilist') -Quiet).Text.Trim()
        runtimeAbi = (Invoke-AdbShell -ShellArguments @('getprop', 'ro.product.cpu.abi') -Quiet).Text.Trim()
    }
    if ($ExpectedModel -and $identity.model -ne $ExpectedModel) {
        throw "Device model drift: expected $ExpectedModel, got $($identity.model)"
    }
    $release = Get-PackageSnapshot 'io.github.zhangwenkang.aurorama'
    $debug = Get-PackageSnapshot 'io.github.zhangwenkang.aurorama.debug'
    $harness = Get-PackageSnapshot $PackageName
    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        identity = $identity
        state = Get-StateSnapshot
        packages = [ordered]@{ release = $release; debug = $debug; harness = $harness }
    }
}

function Get-RemoteRuntimeFiles {
    $listing = Invoke-AdbShell -ShellArguments @('ls', '-la', $RemoteFiles) -AllowFailure -Quiet
    $find = Invoke-AdbShell -ShellArguments @('find', $RemoteFiles, '-maxdepth', '2', '-type', 'f') -AllowFailure -Quiet
    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        listing = @($listing.Text -split "`r?`n" | Where-Object { $_ })
        files = @($find.Text -split "`r?`n" | Where-Object { $_ })
    }
}

function Clear-HarnessRuntimeFiles {
    if ((Get-Pid) -ne $null) {
        [void](Invoke-AdbShell -ShellArguments @('am', 'force-stop', $PackageName) -AllowFailure)
        Start-Sleep -Seconds 1
    }
    [void](Invoke-AdbShell -ShellArguments @('rm', '-rf', $RemoteFiles) -AllowFailure -Quiet)
    Write-QaLog "Cleared only harness external root $RemoteFiles"
}

function Force-Stop-Harness {
    [void](Invoke-AdbShell -ShellArguments @('am', 'force-stop', $PackageName) -AllowFailure)
    $deadline = (Get-Date).AddSeconds(12)
    do {
        if ((Get-Pid) -eq $null) { return $true }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Read-UiText {
    param([string] $Name)
    [void](Invoke-AdbShell -ShellArguments @('rm', '-f', $RemoteUiXml) -AllowFailure -Quiet)
    $dump = Invoke-AdbShell -ShellArguments @('uiautomator', 'dump', $RemoteUiXml) -AllowFailure
    if ($dump.ExitCode -ne 0) {
        return [ordered]@{ dumpExit = $dump.ExitCode; texts = @(); error = $dump.Text }
    }
    $localXml = Join-Path $DeviceRoot "ui-$Name.xml"
    $pull = Invoke-AdbCapture -Arguments @('pull', $RemoteUiXml, $localXml) -AllowFailure
    $texts = @()
    if ($pull.ExitCode -eq 0 -and (Test-Path -LiteralPath $localXml)) {
        try {
            $xmlText = [IO.File]::ReadAllText($localXml, [Text.Encoding]::UTF8)
            if (-not [string]::IsNullOrWhiteSpace($xmlText)) {
                [xml]$document = $xmlText
                $texts = @($document.SelectNodes('//*[@text]') | ForEach-Object { $_.text } | Where-Object { $_ })
            }
        } catch {
            Write-QaLog "UI XML parse failed for ${Name}: $($_.Exception.Message)"
        }
    }
    Write-EvidenceText -Path (Join-Path $DeviceRoot "ui-$Name.txt") -Content $texts
    return [ordered]@{ dumpExit = $dump.ExitCode; texts = $texts; localXml = $localXml }
}

function Invoke-InstallerUiStep {
    param(
        [Parameter(Mandatory = $true)] [hashtable] $Clicked,
        [Parameter(Mandatory = $true)] [ref] $IdentityGate
    )
    $activity = (Invoke-AdbShell -ShellArguments @('dumpsys', 'activity', 'activities') -AllowFailure -Quiet).Text
    if ($activity -notmatch 'AdbInstallActivity|PackageInstaller|packageinstaller') { return $false }
    [void](Invoke-AdbShell -ShellArguments @('rm', '-f', $RemoteInstallXml) -AllowFailure -Quiet)
    [void](Invoke-AdbShell -ShellArguments @('uiautomator', 'dump', $RemoteInstallXml) -AllowFailure -Quiet)
    $localXml = Join-Path $DeviceRoot "install-ui-$Serial.xml"
    $pull = Invoke-AdbCapture -Arguments @('pull', $RemoteInstallXml, $localXml) -AllowFailure -Quiet
    if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $localXml)) { return $false }
    $xmlText = [IO.File]::ReadAllText($localXml, [Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($xmlText)) { return $false }
    if ($xmlText -match '请验证身份|请将人脸|人脸正对|指纹|小米账号|账号验证') {
        $IdentityGate.Value = $true
        return $true
    }
    [xml]$document = $xmlText
    $nodes = @($document.SelectNodes('//*[@text]'))
    $pageHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($xmlText)))
    $patterns = @(
        '已了解.*未经安全检测|未经安全检测',
        '^(继续安装|仍然安装|继续更新)$',
        '^安装$'
    )
    foreach ($pattern in $patterns) {
        $node = $nodes | Where-Object { $_.text -match $pattern } | Select-Object -First 1
        if ($null -eq $node) { continue }
        $key = "${pageHash}:$($node.text):$($node.bounds)"
        if ($Clicked.ContainsKey($key)) { continue }
        $match = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $match.Success) { continue }
        $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
        $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
        [void](Invoke-AdbShell -ShellArguments @('input', 'tap', "$x", "$y") -AllowFailure)
        $Clicked[$key] = $true
        Write-QaLog "INSTALL_UI_CLICK text=$($node.text) bounds=$($node.bounds)"
        return $true
    }
    return $false
}

function Install-Harness {
    if ((Get-PackageSnapshot $PackageName).installed) {
        throw "Harness package is unexpectedly preinstalled before QA install"
    }
    $stdout = Join-Path $DeviceRoot "install-$Label-stdout.txt"
    $stderr = Join-Path $DeviceRoot "install-$Label-stderr.txt"
    $process = Start-Process -FilePath 'adb.exe' `
        -ArgumentList @('-s', $Serial, 'install', '--no-streaming', '--user', '0', $Apk) `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    Write-QaLog "CMD adb -s $Serial install --no-streaming --user 0 `"$Apk`" ; PID=$($process.Id)"
    $clicked = @{}
    $identityGate = $false
    $deadline = (Get-Date).AddMinutes(5)
    while (-not $process.HasExited -and (Get-Date) -lt $deadline) {
        [void](Invoke-InstallerUiStep -Clicked $clicked -IdentityGate ([ref]$identityGate))
        Start-Sleep -Milliseconds 500
    }
    if (-not $process.HasExited) {
        $process.WaitForExit(30000) | Out-Null
    }
    [void](Invoke-AdbShell -ShellArguments @('rm', '-f', $RemoteInstallXml) -AllowFailure -Quiet)
    if ($identityGate) { throw 'BLOCKED_IDENTITY_GATE: installer requested account or biometric verification' }
    if (-not $process.HasExited) { throw 'INSTALL_TIMEOUT' }
    $stdoutText = if (Test-Path -LiteralPath $stdout) { [IO.File]::ReadAllText($stdout, [Text.Encoding]::UTF8) } else { '' }
    $stderrText = if (Test-Path -LiteralPath $stderr) { [IO.File]::ReadAllText($stderr, [Text.Encoding]::UTF8) } else { '' }
    Write-QaLog "INSTALL EXIT=$($process.ExitCode) STDOUT=$($stdoutText.Trim()) STDERR=$($stderrText.Trim())"
    if ($process.ExitCode -ne 0 -or $stdoutText -notmatch '(?m)^Success\s*$') {
        throw "INSTALL_FAILED exit=$($process.ExitCode) stdout=$stdoutText stderr=$stderrText"
    }
    $state = Get-PackageSnapshot $PackageName
    if (-not $state.installed) { throw 'INSTALL_NOT_VISIBLE' }
    $pathResult = Invoke-AdbShell -ShellArguments @('pm', 'path', $PackageName)
    $basePath = (($pathResult.Text -split "`r?`n" | Where-Object { $_ -match '^package:' } | Select-Object -First 1) -replace '^package:', '').Trim()
    $sizeResult = Invoke-AdbShell -ShellArguments @('stat', '-c', '%s', $basePath)
    $hashResult = Invoke-AdbShell -ShellArguments @('sha256sum', $basePath)
    $aaptPermissions = @(& $Aapt2 dump permissions $Apk 2>&1)
    Write-EvidenceText -Path (Join-Path $DeviceRoot "aapt-permissions-$Label.txt") -Content $aaptPermissions
    $packageText = (Invoke-AdbShell -ShellArguments @('dumpsys', 'package', $PackageName)).Text
    $permissionLines = @($packageText -split "`r?`n" | Where-Object {
        $_ -match 'requested permissions|install permissions|android.permission.INTERNET|grantedPermissions'
    })
    Write-EvidenceText -Path (Join-Path $DeviceRoot "installed-permissions-$Label.txt") -Content $permissionLines
    return [ordered]@{
        installExit = $process.ExitCode
        installStdout = $stdoutText.Trim()
        installStderr = $stderrText.Trim()
        packageState = $state
        installedPath = $basePath
        installedBytes = [long]$sizeResult.Text.Trim()
        installedSha256 = ($hashResult.Text.Trim() -split '\s+')[0]
        aaptPermissionOutput = $aaptPermissions
        installedPermissionLines = $permissionLines
    }
}

function Export-LogcatEvidence {
    param([string] $Phase)
    $logResult = Invoke-AdbCapture -Arguments @('logcat', '-d', '-v', 'threadtime') -AllowFailure -Quiet
    $filtered = @($logResult.Lines | Where-Object {
        [string]$_ -match 'KOKORO_HARNESS|dev\.aurorama\.kokoro\.harness|FATAL EXCEPTION|ANR in|Input dispatching timed out|AudioTrack|AudioFocus'
    })
    $path = Join-Path $DeviceRoot "logcat-$Label-$Phase.txt"
    Write-EvidenceText -Path $path -Content $filtered
    $crash = Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-d', '-v', 'threadtime') -AllowFailure -Quiet
    $crashFiltered = @($crash.Lines | Where-Object {
        [string]$_ -match 'KOKORO_HARNESS|dev\.aurorama\.kokoro\.harness|FATAL EXCEPTION|ANR in'
    })
    Write-EvidenceText -Path (Join-Path $DeviceRoot "crash-$Label-$Phase.txt") -Content $crashFiltered
    return $path
}

function Capture-AudioOwnerEvidence {
    param([string] $Phase)
    $audio = Invoke-AdbShell -ShellArguments @('dumpsys', 'audio') -AllowFailure -Quiet
    $audioLines = @($audio.Text -split "`r?`n" | Where-Object {
        $_ -match 'Audio Focus|focus|source:|clientId|ownerPid|AudioPlaybackConfiguration|playerIId|state:|uid='
    } | Select-Object -First 250)
    $audioPath = Join-Path $DeviceRoot "audio-$Label-$Phase.txt"
    Write-EvidenceText -Path $audioPath -Content $audioLines
    $sessions = Invoke-AdbShell -ShellArguments @('dumpsys', 'media_session') -AllowFailure -Quiet
    $sessionLines = @($sessions.Text -split "`r?`n" | Where-Object {
        $_ -match 'Sessions Stack|active|package=|packageName|dev\.aurorama\.kokoro\.harness'
    } | Select-Object -First 180)
    $sessionPath = Join-Path $DeviceRoot "media-session-$Label-$Phase.txt"
    Write-EvidenceText -Path $sessionPath -Content $sessionLines
    return [ordered]@{
        packageInAudioDump = [bool]($audio.Text -match [regex]::Escape($PackageName))
        packageInMediaSessionDump = [bool]($sessions.Text -match [regex]::Escape($PackageName))
        audioEvidence = $audioPath
        mediaSessionEvidence = $sessionPath
    }
}

function Parse-Wav {
    param([string] $Path)
    $stream = [IO.File]::OpenRead($Path)
    $reader = [IO.BinaryReader]::new($stream)
    try {
        $riff = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
        [void]$reader.ReadUInt32()
        $wave = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
        $format = $null
        $dataBytes = $null
        while ($stream.Position + 8 -le $stream.Length) {
            $chunk = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
            $size = [long]$reader.ReadUInt32()
            if ($chunk -eq 'fmt ') {
                $audioFormat = $reader.ReadUInt16()
                $channels = $reader.ReadUInt16()
                $sampleRate = $reader.ReadUInt32()
                $byteRate = $reader.ReadUInt32()
                [void]$reader.ReadUInt16()
                $bits = $reader.ReadUInt16()
                $format = [ordered]@{
                    audioFormat = $audioFormat
                    channels = $channels
                    sampleRate = $sampleRate
                    byteRate = $byteRate
                    bitsPerSample = $bits
                }
                $remaining = $size - 16
                if ($remaining -gt 0) { $stream.Seek($remaining, [IO.SeekOrigin]::Current) | Out-Null }
            } elseif ($chunk -eq 'data') {
                $dataBytes = $size
                $stream.Seek($size, [IO.SeekOrigin]::Current) | Out-Null
            } else {
                $stream.Seek($size + ($size % 2), [IO.SeekOrigin]::Current) | Out-Null
            }
        }
        return [ordered]@{
            path = $Path
            bytes = (Get-Item -LiteralPath $Path).Length
            sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
            riff = $riff
            wave = $wave
            audioFormat = $format.audioFormat
            channels = $format.channels
            sampleRate = $format.sampleRate
            byteRate = $format.byteRate
            bitsPerSample = $format.bitsPerSample
            dataBytes = $dataBytes
            durationSeconds = if ($null -ne $dataBytes -and $format.byteRate -gt 0) {
                [Math]::Round($dataBytes / [double]$format.byteRate, 3)
            } else { $null }
        }
    } finally {
        $reader.Dispose()
        $stream.Dispose()
    }
}

function Get-ModeFiles {
    param([string] $Mode)
    switch ($Mode) {
        'zf' { return @{ Metrics = 'metrics_zf.json'; Outputs = @('zf_001_sid3.wav') } }
        'zm' { return @{ Metrics = 'metrics_zm.json'; Outputs = @('zm_010_sid59.wav') } }
        'warm-zf' { return @{ Metrics = 'metrics_warm-zf.json'; Outputs = @('warmup_zf_001_sid3.wav') } }
        'probe-zf' { return @{ Metrics = 'metrics_probe-zf.json'; Outputs = @('zf_001_sid3_probe.wav') } }
        'both' { return @{ Metrics = 'metrics_both.json'; Outputs = @('zf_001_sid3.wav', 'zm_010_sid59.wav') } }
        default { throw "Unsupported mode: $Mode" }
    }
}

function Run-Generation {
    param(
        [Parameter(Mandatory = $true)] [string] $Mode,
        [Parameter(Mandatory = $true)] [string] $Phase,
        [int] $TimeoutSeconds = 180,
        [int] $SleepAfterSeconds = 0,
        [switch] $CaptureAudioOwner
    )
    $files = Get-ModeFiles $Mode
    [void](Force-Stop-Harness)
    foreach ($name in @($files.Metrics) + @($files.Outputs) + @('metrics_failure.json')) {
        [void](Invoke-AdbShell -ShellArguments @('rm', '-f', "$RemoteFiles/$name") -AllowFailure -Quiet)
    }
    [void](Invoke-AdbCapture -Arguments @('logcat', '-c') -AllowFailure -Quiet)
    $startClock = [Diagnostics.Stopwatch]::StartNew()
    $launch = Invoke-AdbShell -ShellArguments @('am', 'start', '-W', '-n', "$PackageName/.HarnessActivity", '--es', 'mode', $Mode)
    Write-EvidenceText -Path (Join-Path $DeviceRoot "launch-$Label-$Phase.txt") -Content $launch.Text

    $samples = [Collections.Generic.List[object]]::new()
    $audioOwner = $null
    $slept = $false
    $nextTargetMs = 0.0
    $missingPss = 0
    $completed = $false
    while ($startClock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $elapsed = [Math]::Round($startClock.Elapsed.TotalSeconds, 3)
        $procId = Get-Pid
        $pssKb = $null
        $totalKb = $null
        $vmRssKb = $null
        $vmHwmKb = $null
        $vmSizeKb = $null
        if ($null -ne $procId) {
            $mem = Invoke-AdbShell -ShellArguments @('dumpsys', 'meminfo', $PackageName) -AllowFailure -Quiet
            $pssRaw = Get-FirstMatch $mem.Text 'TOTAL PSS:\s*([0-9,]+)'
            if (-not $pssRaw) { $pssRaw = Get-FirstMatch $mem.Text 'TOTAL:\s*([0-9,]+)' }
            if ($pssRaw) { $pssKb = [long]($pssRaw -replace ',', '') }
            $totalRaw = Get-FirstMatch $mem.Text '(?m)^\s*TOTAL:\s*([0-9,]+)'
            if ($totalRaw) { $totalKb = [long]($totalRaw -replace ',', '') }
            if ($null -eq $pssKb) { $missingPss++ }
            $procStatus = Invoke-AdbShell -ShellArguments @('cat', "/proc/$procId/status") -AllowFailure -Quiet
            $vmRssRaw = Get-FirstMatch $procStatus.Text 'VmRSS:\s*(\d+)\s*kB'
            $vmHwmRaw = Get-FirstMatch $procStatus.Text 'VmHWM:\s*(\d+)\s*kB'
            $vmSizeRaw = Get-FirstMatch $procStatus.Text 'VmSize:\s*(\d+)\s*kB'
            if ($vmRssRaw) { $vmRssKb = [long]$vmRssRaw }
            if ($vmHwmRaw) { $vmHwmKb = [long]$vmHwmRaw }
            if ($vmSizeRaw) { $vmSizeKb = [long]$vmSizeRaw }
        }
        $samples.Add([pscustomobject]@{
            timestamp = (Get-Date).ToString('o')
            elapsedSeconds = $elapsed
            pid = $procId
            totalPssKb = $pssKb
            totalKb = $totalKb
            vmRssKb = $vmRssKb
            vmHwmKb = $vmHwmKb
            vmSizeKb = $vmSizeKb
        })
        if ($CaptureAudioOwner -and $null -eq $audioOwner -and $elapsed -ge 5) {
            $audioOwner = Capture-AudioOwnerEvidence -Phase $Phase
        }
        if ($SleepAfterSeconds -gt 0 -and -not $slept -and $elapsed -ge $SleepAfterSeconds) {
            [void](Invoke-AdbShell -ShellArguments @('input', 'keyevent', '223') -AllowFailure)
            Start-Sleep -Seconds 1
            $power = (Invoke-AdbShell -ShellArguments @('dumpsys', 'power') -AllowFailure -Quiet).Text
            Write-QaLog "BACKGROUND_SLEEP phase=$Phase wakefulness=$(Get-FirstMatch $power 'mWakefulness=([^\s]+)')"
            $script:SleepTouched = $true
            $slept = $true
        }
        if (Test-RemoteFile "$RemoteFiles/$($files.Metrics)") {
            $completed = $true
            break
        }
        if ($null -eq $procId) {
            $failCheck = Test-RemoteFile "$RemoteFiles/metrics_failure.json"
            if ($failCheck) { break }
        }
        $nextTargetMs += 1500.0
        $sleepMs = [int][Math]::Max(100, $nextTargetMs - $startClock.Elapsed.TotalMilliseconds)
        Start-Sleep -Milliseconds $sleepMs
    }
    $startClock.Stop()

    if ($slept) {
        [void](Invoke-AdbShell -ShellArguments @('input', 'keyevent', '224') -AllowFailure)
        Start-Sleep -Seconds 1
        [void](Invoke-AdbShell -ShellArguments @('wm', 'dismiss-keyguard') -AllowFailure -Quiet)
        $script:SleepTouched = $false
    }

    $csvPath = Join-Path $DeviceRoot "pss-$Label-$Phase.csv"
    $csvLines = @('timestamp,elapsed_seconds,pid,total_pss_kb,total_kb,vm_rss_kb,vm_hwm_kb,vm_size_kb')
    foreach ($sample in $samples) {
        $csvLines += '{0},{1},{2},{3},{4},{5},{6},{7}' -f `
            $sample.timestamp, $sample.elapsedSeconds, $sample.pid, $sample.totalPssKb,
            $sample.totalKb, $sample.vmRssKb, $sample.vmHwmKb, $sample.vmSizeKb
    }
    Write-EvidenceText -Path $csvPath -Content $csvLines

    $metricsLocal = $null
    $metricsJson = $null
    $wavResults = @()
    if (Test-RemoteFile "$RemoteFiles/$($files.Metrics)") {
        $metricsLocal = Join-Path $DeviceRoot "$Phase-$($files.Metrics)"
        Pull-RemoteFile "$RemoteFiles/$($files.Metrics)" $metricsLocal
        $metricsJson = Get-Content -Raw -LiteralPath $metricsLocal | ConvertFrom-Json
        foreach ($output in $files.Outputs) {
            $remoteOutput = "$RemoteFiles/$output"
            if (Test-RemoteFile $remoteOutput) {
                $localOutput = Join-Path $DeviceRoot "$Phase-$output"
                Pull-RemoteFile $remoteOutput $localOutput
                $wavResults += Parse-Wav $localOutput
            }
        }
    } elseif (Test-RemoteFile "$RemoteFiles/metrics_failure.json") {
        $metricsLocal = Join-Path $DeviceRoot "$Phase-metrics_failure.json"
        Pull-RemoteFile "$RemoteFiles/metrics_failure.json" $metricsLocal
        $metricsJson = Get-Content -Raw -LiteralPath $metricsLocal | ConvertFrom-Json
    }
    $logcatPath = Export-LogcatEvidence -Phase $Phase

    $pssValues = @($samples | Where-Object { $null -ne $_.totalPssKb } | ForEach-Object { [long]$_.totalPssKb })
    $sampleGaps = @()
    for ($i = 1; $i -lt $samples.Count; $i++) {
        $sampleGaps += [double]$samples[$i].elapsedSeconds - [double]$samples[$i - 1].elapsedSeconds
    }
    $result = [ordered]@{
        phase = $Phase
        mode = $Mode
        completed = $completed
        elapsedWallSeconds = [Math]::Round($startClock.Elapsed.TotalSeconds, 3)
        launchOutput = $launch.Text
        sampleCount = $samples.Count
        missingPssSamples = $missingPss
        samplingIntervalTargetSeconds = 1.5
        maxObservedSampleGapSeconds = if ($sampleGaps.Count) { [Math]::Round(($sampleGaps | Measure-Object -Maximum).Maximum, 3) } else { $null }
        peakPssKb = if ($pssValues.Count) { ($pssValues | Measure-Object -Maximum).Maximum } else { $null }
        minPssKb = if ($pssValues.Count) { ($pssValues | Measure-Object -Minimum).Minimum } else { $null }
        sleepSupplement = $slept
        audioOwner = $audioOwner
        metricsPath = $metricsLocal
        metrics = $metricsJson
        wavs = $wavResults
        pssCsv = $csvPath
        logcat = $logcatPath
    }
    $resultPath = Join-Path $DeviceRoot "run-$Label-$Phase.json"
    $result | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $resultPath -Encoding utf8
    Write-QaLog "RUN phase=$Phase mode=$Mode completed=$completed samples=$($samples.Count) peakPssKb=$($result.peakPssKb) wall=$($result.elapsedWallSeconds)s"
    return [pscustomobject]$result
}

function Test-NoResume {
    param(
        [Parameter(Mandatory = $true)] [string] $Case,
        [switch] $DuringInference
    )
    if ($DuringInference) {
        $files = Get-ModeFiles 'zm'
        [void](Force-Stop-Harness)
        foreach ($name in @($files.Metrics) + @($files.Outputs) + @('metrics_failure.json')) {
            [void](Invoke-AdbShell -ShellArguments @('rm', '-f', "$RemoteFiles/$name") -AllowFailure -Quiet)
        }
        [void](Invoke-AdbCapture -Arguments @('logcat', '-c') -AllowFailure -Quiet)
        [void](Invoke-AdbShell -ShellArguments @('am', 'start', '-W', '-n', "$PackageName/.HarnessActivity", '--es', 'mode', 'zm'))
        Start-Sleep -Seconds 8
        $pidDuring = Get-Pid
        $stopped = Force-Stop-Harness
        $pidAfter = Get-Pid
        $filesAfterStop = Get-RemoteRuntimeFiles
        Write-EvidenceText -Path (Join-Path $DeviceRoot "cancel-$Label-force-stop-files.json") -Content ($filesAfterStop | ConvertTo-Json -Depth 5)
        [void](Export-LogcatEvidence -Phase 'force-stop-cancel' -ErrorAction SilentlyContinue)
    } else {
        $pidDuring = Get-Pid
        $stopped = Force-Stop-Harness
        $pidAfter = Get-Pid
        $filesAfterStop = Get-RemoteRuntimeFiles
    }

    $beforeUi = Read-UiText -Name "$Case-before-restart"
    [void](Invoke-AdbCapture -Arguments @('logcat', '-c') -AllowFailure -Quiet)
    $restart = Invoke-AdbShell -ShellArguments @('am', 'start', '-W', '-n', "$PackageName/.HarnessActivity")
    Start-Sleep -Seconds 4
    $pidRestart = Get-Pid
    $restartUi = Read-UiText -Name "$Case-after-restart"
    $metricsBoth = Test-RemoteFile "$RemoteFiles/metrics_both.json"
    $stopAfterRestart = Force-Stop-Harness
    $logcat = Export-LogcatEvidence -Phase "restart-$Case"
    $result = [ordered]@{
        case = $Case
        duringInference = [bool]$DuringInference
        pidDuring = $pidDuring
        forceStopSucceeded = $stopped
        pidAfterStop = $pidAfter
        remoteFilesAfterStop = $filesAfterStop
        beforeRestartUi = $beforeUi
        restartCommand = $restart.Text
        pidAfterRestart = $pidRestart
        afterRestartUi = $restartUi
        metricsBothPresentAfter4Seconds = $metricsBoth
        forceStopAfterRestartSucceeded = $stopAfterRestart
        logcat = $logcat
    }
    $path = Join-Path $DeviceRoot "recreate-$Label-$Case.json"
    $result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding utf8
    return $result
}

function Set-AirplaneMode {
    param([bool] $Enabled)
    $verb = if ($Enabled) { 'enable' } else { 'disable' }
    $cmd = Invoke-AdbShell -ShellArguments @('cmd', 'connectivity', 'airplane-mode', $verb) -AllowFailure
    $wanted = if ($Enabled) { '1' } else { '0' }
    $observed = Get-SettingValue 'global' 'airplane_mode_on'
    if ($observed -ne $wanted) {
        $stateValue = if ($Enabled) { '1' } else { '0' }
        $stateBool = if ($Enabled) { 'true' } else { 'false' }
        [void](Invoke-AdbShell -ShellArguments @('settings', 'put', 'global', 'airplane_mode_on', $stateValue) -AllowFailure)
        [void](Invoke-AdbShell -ShellArguments @('am', 'broadcast', '-a', 'android.intent.action.AIRPLANE_MODE', '--ez', 'state', $stateBool) -AllowFailure)
    }
    $script:NetworkTouched = $true
    Write-QaLog "AIRPLANE_SET enabled=$Enabled commandExit=$($cmd.ExitCode) observed=$(Get-SettingValue 'global' 'airplane_mode_on')"
}

function Restore-NetworkState {
    param([object] $OriginalState)
    if ($null -eq $OriginalState) { return }
    $airplane = [string]$OriginalState.airplaneMode
    Set-AirplaneMode -Enabled ($airplane -eq '1')
    if ([string]$OriginalState.wifiOnSetting -eq '1') {
        [void](Invoke-AdbShell -ShellArguments @('svc', 'wifi', 'enable') -AllowFailure)
    } else {
        [void](Invoke-AdbShell -ShellArguments @('svc', 'wifi', 'disable') -AllowFailure)
    }
    if ([string]$OriginalState.mobileDataSetting -eq '1') {
        [void](Invoke-AdbShell -ShellArguments @('svc', 'data', 'enable') -AllowFailure)
    } else {
        [void](Invoke-AdbShell -ShellArguments @('svc', 'data', 'disable') -AllowFailure)
    }
    Start-Sleep -Seconds 8
    $after = Get-StateSnapshot
    Write-EvidenceText -Path (Join-Path $DeviceRoot "network-$Label-restored.json") -Content ($after | ConvertTo-Json -Depth 8)
    $script:NetworkTouched = $false
}

function Parse-NetStatsForUid {
    param([string] $Text, [long] $Uid)
    $lines = $Text -split "`r?`n"
    $inBlock = $false
    $identityRxBytes = [long]0
    $identityTxBytes = [long]0
    $blockCount = 0
    $headerLines = @()
    foreach ($line in $lines) {
        if ($line -match '^\s*ident=.*\buid=' + $Uid + '\b') {
            $inBlock = $true
            $blockCount++
            $headerLines += $line.Trim()
        } elseif ($line -match '^\s*ident=') {
            $inBlock = $false
        }
        if ($inBlock) {
            foreach ($match in [regex]::Matches($line, '\brb=(\d+)')) {
                $identityRxBytes += [long]$match.Groups[1].Value
            }
            foreach ($match in [regex]::Matches($line, '\btb=(\d+)')) {
                $identityTxBytes += [long]$match.Groups[1].Value
            }
        }
    }
    $appMapMatches = @([regex]::Matches(
        $Text,
        '(?m)^\s+' + $Uid + '\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$'))
    $appMapPresent = $appMapMatches.Count -gt 0
    $appMapRxBytes = if ($appMapPresent) { [long]$appMapMatches[-1].Groups[1].Value } else { [long]0 }
    $appMapTxBytes = if ($appMapPresent) { [long]$appMapMatches[-1].Groups[3].Value } else { [long]0 }
    $counterSetPresent = [bool]($Text -match ('(?m)^\s+uid=' + $Uid + '\s+set=\d+\s*$'))
    return [ordered]@{
        uid = $Uid
        identityBlockCount = $blockCount
        identityRxBytes = $identityRxBytes
        identityTxBytes = $identityTxBytes
        identityTotalBytes = $identityRxBytes + $identityTxBytes
        appUidStatsMapPresent = $appMapPresent
        appUidStatsMapRxBytes = $appMapRxBytes
        appUidStatsMapTxBytes = $appMapTxBytes
        appUidStatsMapTotalBytes = $appMapRxBytes + $appMapTxBytes
        uidCounterSetPresent = $counterSetPresent
        headers = $headerLines
    }
}

function Test-ZeroNetwork {
    param([object] $BeforeSnapshot)
    $uidLine = (Invoke-AdbShell -ShellArguments @('cmd', 'package', 'list', 'packages', '-U', $PackageName)).Text
    $uidText = Get-FirstMatch $uidLine '\buid:(\d+)'
    if (-not $uidText) {
        $pkgText = (Invoke-AdbShell -ShellArguments @('dumpsys', 'package', $PackageName)).Text
        $uidText = Get-FirstMatch $pkgText '\buserId=(\d+)'
    }
    if (-not $uidText) { throw 'Harness UID unavailable' }
    $uid = [long]$uidText
    $networkBefore = Get-StateSnapshot
    $script:NetworkTouched = $true
    try {
        Set-AirplaneMode -Enabled $true
        [void](Invoke-AdbShell -ShellArguments @('svc', 'wifi', 'disable') -AllowFailure)
        [void](Invoke-AdbShell -ShellArguments @('svc', 'data', 'disable') -AllowFailure)
        Start-Sleep -Seconds 8
        $offState = Get-StateSnapshot
        Write-EvidenceText -Path (Join-Path $DeviceRoot "network-$Label-airplane-off.json") -Content ($offState | ConvertTo-Json -Depth 8)
        $beforeStatsResult = Invoke-AdbCapture -Arguments @('shell', 'dumpsys', 'netstats', 'detail') -AllowFailure
        $beforeStatsPath = Join-Path $DeviceRoot "netstats-$Label-zero-network-before.txt"
        Write-EvidenceText -Path $beforeStatsPath -Content $beforeStatsResult.Text
        $beforeStats = Parse-NetStatsForUid -Text $beforeStatsResult.Text -Uid $uid
        $run = Run-Generation -Mode 'warm-zf' -Phase 'zero-network' -TimeoutSeconds 180 -CaptureAudioOwner
        Start-Sleep -Seconds 10
        $afterStatsResult = Invoke-AdbCapture -Arguments @('shell', 'dumpsys', 'netstats', 'detail') -AllowFailure
        $afterStatsPath = Join-Path $DeviceRoot "netstats-$Label-zero-network-after.txt"
        Write-EvidenceText -Path $afterStatsPath -Content $afterStatsResult.Text
        $afterStats = Parse-NetStatsForUid -Text $afterStatsResult.Text -Uid $uid
        $summary = [ordered]@{
            uid = $uid
            airplaneStateBefore = $networkBefore
            airplaneStateDuring = $offState
            netstatsBefore = $beforeStats
            netstatsAfter = $afterStats
            identityRxDeltaBytes = $afterStats.identityRxBytes - $beforeStats.identityRxBytes
            identityTxDeltaBytes = $afterStats.identityTxBytes - $beforeStats.identityTxBytes
            identityTotalDeltaBytes = $afterStats.identityTotalBytes - $beforeStats.identityTotalBytes
            appUidStatsMapRxDeltaBytes = $afterStats.appUidStatsMapRxBytes - $beforeStats.appUidStatsMapRxBytes
            appUidStatsMapTxDeltaBytes = $afterStats.appUidStatsMapTxBytes - $beforeStats.appUidStatsMapTxBytes
            appUidStatsMapTotalDeltaBytes = $afterStats.appUidStatsMapTotalBytes - $beforeStats.appUidStatsMapTotalBytes
            run = $run
            beforeEvidence = $beforeStatsPath
            afterEvidence = $afterStatsPath
        }
        $summaryPath = Join-Path $DeviceRoot "netstats-$Label-zero-network-summary.json"
        $summary | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $summaryPath -Encoding utf8
        return [pscustomobject]$summary
    } finally {
        Restore-NetworkState -OriginalState $BeforeSnapshot.state
        $script:NetworkTouched = $false
    }
}

function Test-LongText {
    $fixture = (Resolve-Path -LiteralPath (Join-Path $QaRoot 'long-text.txt')).Path
    $text = [IO.File]::ReadAllText($fixture, [Text.Encoding]::UTF8).Trim()
    $hanCount = ([regex]::Matches($text, '[\p{IsCJKUnifiedIdeographs}]')).Count
    [void](Invoke-AdbCapture -Arguments @('push', $fixture, "$RemoteFiles/narration_override.txt"))
    try {
        $run = Run-Generation -Mode 'probe-zf' -Phase 'long-text' -TimeoutSeconds 360
        $summary = [ordered]@{
            mode = 'probe-zf existing override parameter'
            textCharacters = $text.Length
            hanCharacters = $hanCount
            fixture = $fixture
            run = $run
        }
        $path = Join-Path $DeviceRoot "long-text-$Label.json"
        $summary | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $path -Encoding utf8
        return [pscustomobject]$summary
    } finally {
        [void](Invoke-AdbShell -ShellArguments @('rm', '-f', "$RemoteFiles/narration_override.txt") -AllowFailure -Quiet)
    }
}

function Compare-PackageSnapshots {
    param([object] $Before, [object] $After)
    $names = @('installed', 'versionName', 'versionCode', 'codePath', 'firstInstallTime', 'lastUpdateTime', 'dataDir', 'primaryCpuAbi', 'userId')
    $differences = @()
    foreach ($packageKey in @('release', 'debug')) {
        foreach ($name in $names) {
            $beforeValue = [string]$Before.packages.$packageKey.$name
            $afterValue = [string]$After.packages.$packageKey.$name
            if ($beforeValue -ne $afterValue) {
                $differences += [ordered]@{
                    packageKey = $packageKey
                    field = $name
                    before = $beforeValue
                    after = $afterValue
                }
            }
        }
    }
    return $differences
}

function Invoke-Cleanup {
    param([object] $BeforeSnapshot, [string] $Reason = 'completed')
    $cleanup = [ordered]@{ startedAt = (Get-Date).ToString('o'); reason = $Reason }
    try {
        if ($script:NetworkTouched -and $null -ne $BeforeSnapshot) {
            Restore-NetworkState -OriginalState $BeforeSnapshot.state
        }
        $cleanup.forceStopSucceeded = Force-Stop-Harness
        $cleanup.runtimeBeforeUninstall = Get-RemoteRuntimeFiles
        $packageBefore = Get-PackageSnapshot $PackageName
        if ($packageBefore.installed) {
            $uninstall = Invoke-AdbCapture -Arguments @('uninstall', $PackageName) -AllowFailure
            $cleanup.uninstallExit = $uninstall.ExitCode
            $cleanup.uninstallOutput = $uninstall.Text.Trim()
        } else {
            $cleanup.uninstallExit = $null
            $cleanup.uninstallOutput = 'NOT_INSTALLED'
        }
        [void](Invoke-AdbShell -ShellArguments @('rm', '-rf', $RemoteFiles) -AllowFailure -Quiet)
        [void](Invoke-AdbShell -ShellArguments @('rm', '-f', $RemoteInstallXml, $RemoteUiXml) -AllowFailure -Quiet)
        Start-Sleep -Seconds 2
        $cleanup.packageAfter = Get-PackageSnapshot $PackageName
        $cleanup.pidAfter = Get-Pid
        $cleanup.remoteFilesAfter = Get-RemoteRuntimeFiles
        $cleanup.stateAfter = Get-StateSnapshot
        $afterSnapshot = Get-DeviceSnapshot
        Write-EvidenceText -Path (Join-Path $DeviceRoot "snapshot-after-$Label.json") -Content ($afterSnapshot | ConvertTo-Json -Depth 12)
        $differences = @(Compare-PackageSnapshots -Before $BeforeSnapshot -After $afterSnapshot | Where-Object { $null -ne $_ })
        $cleanup.productionPackageDifferences = $differences
        $cleanup.productionPackagesUnchanged = ($differences.Count -eq 0)
        $cleanup.finishedAt = (Get-Date).ToString('o')
    } catch {
        $cleanup.cleanupError = $_.Exception.ToString()
        Write-QaLog "CLEANUP_ERROR $($_.Exception.Message)"
    }
    Write-EvidenceText -Path (Join-Path $DeviceRoot "cleanup-$Label.json") -Content ($cleanup | ConvertTo-Json -Depth 12)
    return [pscustomobject]$cleanup
}

function Invoke-All {
    if (-not (Test-Path -LiteralPath $Apk)) { throw "APK missing: $Apk" }
    $before = Get-DeviceSnapshot
    Write-EvidenceText -Path (Join-Path $DeviceRoot "snapshot-before-$Label.json") -Content ($before | ConvertTo-Json -Depth 12)
    if ($before.packages.harness.installed) { throw 'Harness package preinstalled before QA' }
    $summary = [ordered]@{
        startedAt = (Get-Date).ToString('o')
        serial = $Serial
        label = $Label
        expectedModel = $ExpectedModel
        apk = $Apk
        apkBytes = (Get-Item -LiteralPath $Apk).Length
        apkSha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
        before = $before
    }
    try {
        $summary.install = Install-Harness
        Clear-HarnessRuntimeFiles
        $summary.zf = Run-Generation -Mode 'zf' -Phase 'first-zf' -TimeoutSeconds 180
        $summary.zm = Run-Generation -Mode 'zm' -Phase 'first-zm' -TimeoutSeconds 180 -CaptureAudioOwner
        $summary.completedRestart = Test-NoResume -Case 'completed'
        $summary.interruptedCancel = Test-NoResume -Case 'interrupted' -DuringInference
        $summary.backgroundShort = Run-Generation -Mode 'zf' -Phase 'background-short' -TimeoutSeconds 180 -SleepAfterSeconds 5
        $summary.zeroNetwork = Test-ZeroNetwork -BeforeSnapshot $before
        $summary.longText = Test-LongText
        $allLogs = Get-ChildItem -LiteralPath $DeviceRoot -Filter "logcat-$Label-*.txt" -File
        $forbiddenHits = @($allLogs | Select-String -Pattern '\bsid\s*[=:]\s*58\b|\bzm_009\b|speaker\s+58\b' -ErrorAction SilentlyContinue)
        $summary.speaker58RuntimeHits = $forbiddenHits.Count
        $summary.speaker58Evidence = @($allLogs.FullName)
        $summary.runtimeFinishedAt = (Get-Date).ToString('o')
    } catch {
        $summary.executionError = $_.Exception.ToString()
        Write-QaLog "EXECUTION_ERROR $($_.Exception.Message)"
        Write-EvidenceText -Path (Join-Path $DeviceRoot "execution-error-$Label.txt") -Content $_.Exception.ToString()
    } finally {
        $summary.cleanup = Invoke-Cleanup -BeforeSnapshot $before -Reason $(if ($summary.Contains('executionError')) { 'execution-error' } else { 'completed' })
        $summary.finishedAt = (Get-Date).ToString('o')
        Write-EvidenceText -Path (Join-Path $DeviceRoot "summary-$Label.json") -Content ($summary | ConvertTo-Json -Depth 16)
    }
    return [pscustomobject]$summary
}

if ($CleanupOnly) {
    $beforePath = Join-Path $DeviceRoot "snapshot-before-$Label.json"
    if (-not (Test-Path -LiteralPath $beforePath)) { throw "Cleanup baseline missing: $beforePath" }
    $before = Get-Content -Raw -LiteralPath $beforePath | ConvertFrom-Json
    $cleanup = Invoke-Cleanup -BeforeSnapshot $before -Reason 'cleanup-only'
    $cleanup | ConvertTo-Json -Depth 12
    exit $(if ($cleanup.productionPackagesUnchanged -and -not $cleanup.packageAfter.installed -and $null -eq $cleanup.pidAfter) { 0 } else { 1 })
}

if ($RemainingOnly) {
    $beforePath = Join-Path $DeviceRoot "snapshot-before-$Label.json"
    if (-not (Test-Path -LiteralPath $beforePath)) { throw "Remaining baseline missing: $beforePath" }
    $before = Get-Content -Raw -LiteralPath $beforePath | ConvertFrom-Json
    $current = Get-DeviceSnapshot
    if ($current.packages.harness.installed) { throw 'Harness package preinstalled before remaining QA' }
    $summary = [ordered]@{
        startedAt = (Get-Date).ToString('o')
        serial = $Serial
        label = $Label
        mode = 'remaining-only-after-qa-probe-fix'
        apk = $Apk
        apkBytes = (Get-Item -LiteralPath $Apk).Length
        apkSha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
        baseline = $before
    }
    try {
        $summary.install = Install-Harness
        Clear-HarnessRuntimeFiles
        $summary.zeroNetwork = Test-ZeroNetwork -BeforeSnapshot $before
        $summary.longText = Test-LongText
        $allLogs = Get-ChildItem -LiteralPath $DeviceRoot -Filter "logcat-$Label-*.txt" -File
        $forbiddenHits = @($allLogs | Select-String -Pattern '\bsid\s*[=:]\s*58\b|\bzm_009\b|speaker\s+58\b' -ErrorAction SilentlyContinue)
        $summary.speaker58RuntimeHits = $forbiddenHits.Count
        $summary.speaker58Evidence = @($allLogs.FullName)
        $summary.runtimeFinishedAt = (Get-Date).ToString('o')
    } catch {
        $summary.executionError = $_.Exception.ToString()
        Write-QaLog "REMAINING_EXECUTION_ERROR $($_.Exception.Message)"
        Write-EvidenceText -Path (Join-Path $DeviceRoot "remaining-execution-error-$Label.txt") -Content $_.Exception.ToString()
    } finally {
        $summary.cleanup = Invoke-Cleanup -BeforeSnapshot $before -Reason $(if ($summary.Contains('executionError')) { 'remaining-execution-error' } else { 'remaining-completed' })
        $summary.finishedAt = (Get-Date).ToString('o')
        Write-EvidenceText -Path (Join-Path $DeviceRoot "summary-$Label-remaining.json") -Content ($summary | ConvertTo-Json -Depth 16)
    }
    $remainingResult = [pscustomobject]$summary
    $remainingResult | ConvertTo-Json -Depth 16
    $hasExecutionError = $null -ne $remainingResult.PSObject.Properties['executionError']
    if ($hasExecutionError -or -not $remainingResult.cleanup.productionPackagesUnchanged -or
        $remainingResult.cleanup.packageAfter.installed -or $null -ne $remainingResult.cleanup.pidAfter) {
        exit 1
    }
    exit 0
}

$result = Invoke-All
$result | ConvertTo-Json -Depth 16
$hasExecutionError = $null -ne $result.PSObject.Properties['executionError']
if ($hasExecutionError -or -not $result.cleanup.productionPackagesUnchanged -or
    $result.cleanup.packageAfter.installed -or $null -ne $result.cleanup.pidAfter) {
    exit 1
}
exit 0
