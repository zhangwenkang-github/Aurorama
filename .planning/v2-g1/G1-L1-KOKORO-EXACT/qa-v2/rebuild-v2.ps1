[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('8e875894', '43af8627')]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet('K60', 'Pad5')]
    [string] $Label,

    [int] $CompletedWaitSeconds = 55
)

$ErrorActionPreference = 'Stop'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$QaRoot = $PSScriptRoot
$serial = $Serial
$label = $Label
$deviceRoot = Join-Path $QaRoot "device-$label-$serial"
$commandLog = Join-Path $QaRoot 'commands.log'
$package = 'dev.aurorama.kokoro.harness'
$remoteRoot = "/sdcard/Android/data/$package/files"
$remoteUi = "/sdcard/kokoro_qav2_rebuild_$serial.xml"

function Write-Utf8([string] $Path, [AllowNull()][object] $Value) {
    $text = if ($null -eq $Value) { '' } else { [string]$Value }
    [IO.File]::WriteAllText($Path, $text + $(if ($text.EndsWith("`n")) { '' } else { [Environment]::NewLine }), $Utf8NoBom)
}

function Write-Log([string] $Message) {
    [IO.File]::AppendAllText(
        $commandLog,
        (Get-Date).ToString('o') + ' [' + $serial + '] ' + $Message + [Environment]::NewLine,
        $Utf8NoBom)
}

function Adb([string[]] $Arguments) {
    $output = @(& adb.exe -s $serial @Arguments 2>&1)
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = ($output -join [Environment]::NewLine)
    }
}

function Get-Pid {
    $text = (Adb @('shell', 'pidof', $package)).Text.Trim()
    if ($text -match '^(\d+)') { return [int]$Matches[1] }
    return $null
}

function Stop-Harness {
    $null = Adb @('shell', 'am', 'force-stop', $package)
    for ($i = 0; $i -lt 40; $i++) {
        if ($null -eq (Get-Pid)) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Test-RemoteFile([string] $Path) {
    & adb.exe -s $serial shell test -f $Path *> $null
    return ($LASTEXITCODE -eq 0)
}

function Read-Ui([string] $Name) {
    $null = Adb @('shell', 'rm', '-f', $remoteUi)
    $dump = Adb @('shell', 'uiautomator', 'dump', $remoteUi)
    $xmlPath = Join-Path $deviceRoot "ui-$Name.xml"
    $pull = Adb @('pull', $remoteUi, $xmlPath)
    $texts = @()
    if ($pull.ExitCode -eq 0 -and (Test-Path -LiteralPath $xmlPath)) {
        $xmlText = [IO.File]::ReadAllText($xmlPath, [Text.Encoding]::UTF8)
        if ($xmlText) {
            try {
                [xml]$document = $xmlText
                $texts = @($document.SelectNodes('//*[@text]') |
                    ForEach-Object { $_.text } | Where-Object { $_ })
            } catch {}
        }
    }
    Write-Utf8 (Join-Path $deviceRoot "ui-$Name.txt") ($texts -join [Environment]::NewLine)
    return [ordered]@{
        dumpExit = $dump.ExitCode
        texts = $texts
        xmlPath = $xmlPath
    }
}

function Run-Case([string] $Case, [int] $WaitSeconds) {
    $null = Stop-Harness
    $null = Adb @('shell', 'rm', '-rf', $remoteRoot)
    $launch1 = Adb @('shell', 'am', 'start', '-W', '-n', "$package/.HarnessActivity", '--es', 'mode', 'zf')
    Start-Sleep -Seconds $WaitSeconds
    $pidBeforeStop = Get-Pid
    $stop = Stop-Harness
    $pidAfterStop = Get-Pid
    $filesAfterStop = (Adb @('shell', 'find', $remoteRoot, '-maxdepth', '2', '-type', 'f')).Text
    $restart = Adb @('shell', 'am', 'start', '-W', '-n', "$package/.HarnessActivity")
    Start-Sleep -Seconds 4
    $pidAfterRestart = Get-Pid
    $ui = Read-Ui "rebuild-$Case-after-restart"
    $metricsVisible = Test-RemoteFile "$remoteRoot/metrics_both.json"
    $stop2 = Stop-Harness
    return [ordered]@{
        case = $Case
        waitedInferenceSeconds = $WaitSeconds
        firstLaunch = $launch1.Text
        pidBeforeStop = $pidBeforeStop
        forceStopConverged = $stop
        pidAfterStop = $pidAfterStop
        filesAfterStop = @($filesAfterStop -split "`r?`n" | Where-Object { $_ })
        restartWithoutMode = $restart.Text
        pidAfterRestart = $pidAfterRestart
        uiAfterRestart = $ui
        metricsBothAfterFourSeconds = $metricsVisible
        secondForceStopConverged = $stop2
    }
}

$active = @(Get-Content -LiteralPath 'F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md' -Encoding utf8 |
    Where-Object { $_ -match '^### .* — ACTIVE(?:\s|$)' })
if ($active.Count -ne 1 -or $active[0] -notmatch [regex]::Escape("QAV2-$Label-$Serial")) { throw 'Lock gate failed' }

$interrupted = Run-Case 'interrupted' 8
$completed = Run-Case 'completed' $CompletedWaitSeconds
$result = [ordered]@{
    schema = 'aurorama.kokoro-exact.independent-qa.v2.rebuild'
    device = $label
    serial = $serial
    interrupted = $interrupted
    completed = $completed
    persistentResumeStatePresent = $false
    status = 'BLOCKED: NO_RESUME_STATE'
}
Write-Utf8 (Join-Path $deviceRoot "rebuild-$Label.json") ($result | ConvertTo-Json -Depth 20)
Write-Log "REBUILD end interruptedStop=$($interrupted.forceStopConverged) completedStop=$($completed.forceStopConverged) status=NO_RESUME_STATE"
$result | ConvertTo-Json -Depth 20
