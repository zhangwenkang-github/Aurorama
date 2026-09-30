<#
.SYNOPSIS
  统计 debug APK 体积基线（按 ABI 拆分），输出 bytes / MiB / SHA1。

.DESCRIPTION
  对应 docs/TEST_PLAN.md §2.4。对比红线要求「同 ABI、同构建类型」比较，
  debug 与 release 体积不可混用。默认不触发构建，用 -Build 可先跑
  :app:phone:assembleDebug。

.EXAMPLE
  .\tools\test\Measure-ApkSize.ps1 -Build
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [string]$ApkDir = 'app\phone\build\outputs\apk',
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path $env:TEMP "cinefin-apksize-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

if ($Build) {
    $env:JAVA_HOME = 'D:\Android\Android Studio\jbr'
    & .\gradlew.bat :app:phone:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug 失败（退出码 $LASTEXITCODE）" }
}

$apks = Get-ChildItem -Path $ApkDir -Recurse -Filter *.apk
if (-not $apks) { throw "未找到 APK：$ApkDir（先运行 -Build 或 assembleDebug）" }

$items = $apks | ForEach-Object {
    [ordered]@{
        name      = $_.Name
        abi       = if ($_.Name -match '(arm64-v8a|armeabi-v7a|x86_64|x86)') { $Matches[1] } else { 'n/a' }
        bytes     = $_.Length
        mib       = [math]::Round($_.Length / 1MB, 2)
        sha1      = (Get-FileHash $_.FullName -Algorithm SHA1).Hash
        modified  = $_.LastWriteTime.ToString('s')
    }
}
$summary = [ordered]@{
    collectedAt = (Get-Date).ToString('s')
    buildType   = 'debug (libre)'
    buildCommand= '.\gradlew.bat :app:phone:assembleDebug（JAVA_HOME=D:\Android\Android Studio\jbr）'
    apkCount    = $apks.Count
    apks        = $items
}

$summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'apksize.json')
$items | ForEach-Object { '{0,-40} {1,10} MiB  sha1={2}' -f $_.name, $_.mib, $_.sha1.Substring(0, 12) }
Write-Output "`n[OK] 原始输出: $OutputDir"
