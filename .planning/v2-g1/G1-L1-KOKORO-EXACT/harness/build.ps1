[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$harnessRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $taskRoot '..\..\..')).Path
$buildRoot = Join-Path $harnessRoot 'build'
if (-not $buildRoot.StartsWith($harnessRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe build directory: $buildRoot"
}

$androidSdk = 'F:\Develop\Android\AndroidSDK'
$androidJar = Join-Path $androidSdk 'platforms\android-37.0\android.jar'
$buildTools = Join-Path $androidSdk 'build-tools\37.0.0'
$jdkRoot = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($jdkRoot)) {
    $jdkRoot = 'F:\Develop\Android\Android Studio\jbr'
}
$java = Join-Path $jdkRoot 'bin\java.exe'
$javac = Join-Path $jdkRoot 'bin\javac.exe'
$jarTool = Join-Path $jdkRoot 'bin\jar.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$dexdump = Join-Path $buildTools 'dexdump.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$debugKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$modelRoot = Join-Path $taskRoot 'extracted\kokoro-multi-lang-v1_1'
$aarRoot = Join-Path $taskRoot 'extracted\sherpa-onnx-2.0.1'
$sherpaJar = Join-Path $aarRoot 'classes.jar'
$kotlinStdlibRoot =
    Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.4.20'
$kotlinStdlib =
    Get-ChildItem -LiteralPath $kotlinStdlibRoot -Recurse -Filter 'kotlin-stdlib-2.4.20.jar' -File |
        Select-Object -First 1 -ExpandProperty FullName
$logRoot = Join-Path $taskRoot 'logs'
$null = New-Item -ItemType Directory -Path $logRoot -Force
$buildLog = Join-Path $logRoot 'android-build.log'
$dexAuditLog = Join-Path $logRoot 'dex-api-audit.log'

$required = @(
    $androidJar,
    $aapt2,
    $d8,
    $dexdump,
    $zipalign,
    $apksigner,
    $debugKeystore,
    $sherpaJar,
    $kotlinStdlib
)
foreach ($path in $required) {
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path)) {
        throw "Required cached/tooling input is missing: $path"
    }
}

$expectedHashes = @{
    (Join-Path $modelRoot 'model.onnx') =
        '99db0bc8fd0c5033d249fe922db16768b71b4a6b26cfd93d206fbf66c76efb5f'
    (Join-Path $modelRoot 'voices.bin') =
        'e64a5a581d8c2a350d848f51c3121657cd83aa07ed6109172177345874a7244c'
    (Join-Path $modelRoot 'tokens.txt') =
        '931ab2df2400cd65d580a22402024c2347ced8ae9ea300e545144b1aacc48e14'
    (Join-Path $modelRoot 'lexicon-zh.txt') =
        'fea060803db3e59193987f1a3436f56917bda09f3b3595b6588e46ae60cebf93'
}
foreach ($entry in $expectedHashes.GetEnumerator()) {
    $actual = (Get-FileHash -LiteralPath $entry.Key -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $entry.Value) {
        throw "Input drift: $($entry.Key) expected $($entry.Value), got $actual"
    }
}

if (Test-Path -LiteralPath $buildRoot) {
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
}
$assetsRoot = Join-Path $buildRoot 'assets'
$classesRoot = Join-Path $buildRoot 'classes'
$classesJar = Join-Path $buildRoot 'harness-classes.jar'
$dexRoot = Join-Path $buildRoot 'dex'
$stageRoot = Join-Path $buildRoot 'apk-stage'
$outputRoot = Join-Path $buildRoot 'outputs'
$null = New-Item -ItemType Directory -Path $assetsRoot, $classesRoot, $dexRoot, $stageRoot, $outputRoot -Force

foreach ($name in @(
    'model.onnx',
    'voices.bin',
    'tokens.txt',
    'lexicon-zh.txt',
    'date-zh.fst',
    'number-zh.fst',
    'phone-zh.fst'
)) {
    Copy-Item -LiteralPath (Join-Path $modelRoot $name) -Destination (Join-Path $assetsRoot $name)
}
Copy-Item -LiteralPath (Join-Path $modelRoot 'espeak-ng-data') -Destination $assetsRoot -Recurse
Copy-Item -LiteralPath (Join-Path $modelRoot 'dict') -Destination $assetsRoot -Recurse

$manifest = Join-Path $harnessRoot 'app\src\main\AndroidManifest.xml'
$resourcesRoot = Join-Path $harnessRoot 'app\src\main\res'
$compiledResources = Join-Path $buildRoot 'compiled-resources.zip'
$unsignedApk = Join-Path $buildRoot 'kokoro-isolated-harness-unsigned.apk'
$alignedApk = Join-Path $buildRoot 'kokoro-isolated-harness-aligned.apk'
$signedApk = Join-Path $outputRoot 'kokoro-isolated-harness-debug.apk'
$peerClassesRoot = Join-Path $buildRoot 'peer-classes'
$peerClassesJar = Join-Path $buildRoot 'focus-peer-classes.jar'
$peerDexRoot = Join-Path $buildRoot 'peer-dex'
$peerStageRoot = Join-Path $buildRoot 'peer-apk-stage'
$peerManifest = Join-Path $harnessRoot 'peer\src\main\AndroidManifest.xml'
$peerUnsignedApk = Join-Path $buildRoot 'kokoro-focus-peer-unsigned.apk'
$peerAlignedApk = Join-Path $buildRoot 'kokoro-focus-peer-aligned.apk'
$peerSignedApk = Join-Path $outputRoot 'kokoro-focus-peer-debug.apk'
$peerBadgingLog = Join-Path $logRoot 'focus-peer-badging.txt'
$null =
    New-Item -ItemType Directory -Path $peerClassesRoot, $peerDexRoot, $peerStageRoot -Force

$sources = @(
    Get-ChildItem -LiteralPath (Join-Path $harnessRoot 'contract\src\main\java') -Recurse -Filter '*.java' -File
    Get-ChildItem -LiteralPath (Join-Path $harnessRoot 'app\src\main\java') -Recurse -Filter '*.java' -File
) | ForEach-Object FullName
$sourceList = Join-Path $buildRoot 'sources.txt'
[IO.File]::WriteAllLines($sourceList, $sources, [Text.UTF8Encoding]::new($false))
$peerSources = @(
    Get-ChildItem -LiteralPath (Join-Path $harnessRoot 'peer\src\main\java') -Recurse -Filter '*.java' -File
) | ForEach-Object FullName
$peerSourceList = Join-Path $buildRoot 'focus-peer-sources.txt'
[IO.File]::WriteAllLines($peerSourceList, $peerSources, [Text.UTF8Encoding]::new($false))
$classpath = @($androidJar, $sherpaJar, $kotlinStdlib) -join ';'

$commands = [Collections.Generic.List[string]]::new()
$commands.Add(
    "& '$aapt2' compile --dir '$resourcesRoot' -o '$compiledResources'")
$commands.Add(
    "& '$aapt2' link -o '$unsignedApk' --manifest '$manifest' -I '$androidJar' -A '$assetsRoot' -R '$compiledResources' " +
    "--min-sdk-version 26 --target-sdk-version 37 --version-code 3 --version-name '2.0-kokoro-runtime-v4' " +
    "--debug-mode --no-compress")
$commands.Add(
    "& '$javac' -encoding UTF-8 -source 17 -target 17 -classpath '$classpath' -d '$classesRoot' '@$sourceList'")
$commands.Add(
    "& '$jarTool' --create --file '$classesJar' -C '$classesRoot' .")
$commands.Add(
    "& '$d8' --min-api 26 --lib '$androidJar' --output '$dexRoot' '$classesJar' '$sherpaJar' '$kotlinStdlib'")
$commands.Add(
    "& '$jarTool' --update --file '$unsignedApk' -C '$stageRoot' .")
$commands.Add(
    "& '$zipalign' -f -p 4 '$unsignedApk' '$alignedApk'")
$commands.Add(
    "& '$apksigner' sign --ks '$debugKeystore' --ks-pass pass:android --key-pass pass:android " +
    "--ks-key-alias androiddebugkey --out '$signedApk' '$alignedApk'")
$commands.Add(
    "& '$aapt2' link -o '$peerUnsignedApk' --manifest '$peerManifest' -I '$androidJar' " +
    "--min-sdk-version 26 --target-sdk-version 37 --version-code 1 --version-name '1.0-focus-peer' " +
    "--debug-mode --no-compress")
$commands.Add(
    "& '$javac' -encoding UTF-8 -source 17 -target 17 -classpath '$androidJar' -d '$peerClassesRoot' '@$peerSourceList'")
$commands.Add(
    "& '$jarTool' --create --file '$peerClassesJar' -C '$peerClassesRoot' .")
$commands.Add(
    "& '$d8' --min-api 26 --lib '$androidJar' --output '$peerDexRoot' '$peerClassesJar'")
$commands.Add(
    "& '$jarTool' --update --file '$peerUnsignedApk' -C '$peerStageRoot' .")
$commands.Add(
    "& '$zipalign' -f -p 4 '$peerUnsignedApk' '$peerAlignedApk'")
$commands.Add(
    "& '$apksigner' sign --ks '$debugKeystore' --ks-pass pass:android --key-pass pass:android " +
    "--ks-key-alias androiddebugkey --out '$peerSignedApk' '$peerAlignedApk'")
[IO.File]::WriteAllLines(
    (Join-Path $logRoot 'android-build-commands.log'),
    $commands,
    [Text.UTF8Encoding]::new($false))

& $aapt2 compile --dir $resourcesRoot -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw "aapt2 resource compile failed: $LASTEXITCODE" }

& $aapt2 link -o $unsignedApk --manifest $manifest -I $androidJar -A $assetsRoot -R $compiledResources `
    --min-sdk-version 26 --target-sdk-version 37 --version-code 3 --version-name '2.0-kokoro-runtime-v4' `
    --debug-mode --no-compress
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

& $javac -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d $classesRoot "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

& $jarTool --create --file $classesJar -C $classesRoot .
if ($LASTEXITCODE -ne 0) { throw "classes jar failed: $LASTEXITCODE" }

& $d8 --min-api 26 --lib $androidJar --output $dexRoot $classesJar $sherpaJar $kotlinStdlib
if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }

Copy-Item -LiteralPath (Join-Path $dexRoot 'classes.dex') -Destination $stageRoot
$nativeStage = Join-Path $stageRoot 'lib\arm64-v8a'
$null = New-Item -ItemType Directory -Path $nativeStage -Force
Get-ChildItem -LiteralPath (Join-Path $aarRoot 'jni\arm64-v8a') -Filter '*.so' -File |
    Copy-Item -Destination $nativeStage

& $jarTool --update --file $unsignedApk -C $stageRoot .
if ($LASTEXITCODE -ne 0) { throw "jar update failed: $LASTEXITCODE" }

& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

& $apksigner sign --ks $debugKeystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $signedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }

& $apksigner verify --verbose --print-certs $signedApk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }

& $aapt2 link -o $peerUnsignedApk --manifest $peerManifest -I $androidJar `
    --min-sdk-version 26 --target-sdk-version 37 --version-code 1 --version-name '1.0-focus-peer' `
    --debug-mode --no-compress
if ($LASTEXITCODE -ne 0) { throw "focus peer aapt2 link failed: $LASTEXITCODE" }

& $javac -encoding UTF-8 -source 17 -target 17 -classpath $androidJar -d $peerClassesRoot "@$peerSourceList"
if ($LASTEXITCODE -ne 0) { throw "focus peer javac failed: $LASTEXITCODE" }

& $jarTool --create --file $peerClassesJar -C $peerClassesRoot .
if ($LASTEXITCODE -ne 0) { throw "focus peer classes jar failed: $LASTEXITCODE" }

& $d8 --min-api 26 --lib $androidJar --output $peerDexRoot $peerClassesJar
if ($LASTEXITCODE -ne 0) { throw "focus peer d8 failed: $LASTEXITCODE" }

Copy-Item -LiteralPath (Join-Path $peerDexRoot 'classes.dex') -Destination $peerStageRoot
& $jarTool --update --file $peerUnsignedApk -C $peerStageRoot .
if ($LASTEXITCODE -ne 0) { throw "focus peer jar update failed: $LASTEXITCODE" }

& $zipalign -f -p 4 $peerUnsignedApk $peerAlignedApk
if ($LASTEXITCODE -ne 0) { throw "focus peer zipalign failed: $LASTEXITCODE" }

& $apksigner sign --ks $debugKeystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $peerSignedApk $peerAlignedApk
if ($LASTEXITCODE -ne 0) { throw "focus peer apksigner failed: $LASTEXITCODE" }

& $apksigner verify --verbose --print-certs $peerSignedApk
if ($LASTEXITCODE -ne 0) { throw "Focus peer APK signature verification failed" }

& $aapt2 dump badging $peerSignedApk 2>&1 |
    Set-Content -LiteralPath $peerBadgingLog -Encoding utf8

$verifyRoot = Join-Path $buildRoot 'verify-apk'
$null = New-Item -ItemType Directory -Path $verifyRoot -Force
Push-Location $verifyRoot
try {
    & $jarTool xf $signedApk classes.dex
    if ($LASTEXITCODE -ne 0) { throw "signed APK classes.dex extraction failed" }
    $signedDex = Join-Path $verifyRoot 'classes.dex'
    $forbiddenApiPattern =
        'java/util/HexFormat|Ljava/nio/file/Files;\.writeString|Ljava/nio/file/Files;\.readString|Ljava/lang/String;\.isBlank|Ljava/util/List;\.of'
    $forbiddenApiMatches = @(
        & $dexdump -d $signedDex 2>&1 |
            Select-String -Pattern $forbiddenApiPattern
    )
    if ($LASTEXITCODE -ne 0) { throw "dexdump failed: $LASTEXITCODE" }
    $dexApiAudit = [ordered]@{
        status = if ($forbiddenApiMatches.Count -eq 0) { 'PASS' } else { 'FAIL' }
        apkClassesDexBytes = (Get-Item -LiteralPath $signedDex).Length
        apkClassesDexSha256 = (Get-FileHash -LiteralPath $signedDex -Algorithm SHA256).Hash.ToLowerInvariant()
        forbiddenPattern = $forbiddenApiPattern
        forbiddenMatchCount = $forbiddenApiMatches.Count
        log = $dexAuditLog
    }
    $dexApiAudit | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $dexAuditLog -Encoding utf8
    if ($forbiddenApiMatches.Count -ne 0) {
        $forbiddenApiMatches | Set-Content -LiteralPath $dexAuditLog -Encoding utf8
        throw "APK references forbidden minSdk-incompatible APIs: $($forbiddenApiMatches.Count)"
    }
} finally {
    Pop-Location
}

$apkHash = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$peerApkHash = (Get-FileHash -LiteralPath $peerSignedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$peerApkBytes = (Get-Item -LiteralPath $peerSignedApk).Length
$nativeLibraries =
    Get-ChildItem -LiteralPath $nativeStage -Filter '*.so' -File |
        ForEach-Object {
            [ordered]@{
                name = $_.Name
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
$buildInfo = [ordered]@{
    status = 'PASS'
    builtAt = (Get-Date).ToString('o')
    apk = $signedApk
    apkBytes = (Get-Item -LiteralPath $signedApk).Length
    apkSha256 = $apkHash
    abi = 'arm64-v8a'
    minSdk = 26
    targetSdk = 37
    versionCode = 3
    versionName = '2.0-kokoro-runtime-v4'
    longRunTargetMillis = 1860000
    longRunConservativeCycleMillis = 48000
    longRunCycleCount = 39
    longRunPlannedMinimumMillis = 1872000
    focusPeer = [ordered]@{
        apk = $peerSignedApk
        apkBytes = $peerApkBytes
        apkSha256 = $peerApkHash
        package = 'dev.aurorama.kokoro.focuspeer'
        versionCode = 1
        versionName = '1.0-focus-peer'
        focus = 'AUDIOFOCUS_GAIN_TRANSIENT'
        audioTrack = 'USAGE_MEDIA / CONTENT_TYPE_MUSIC / mono 24 kHz'
        networkPermission = $false
    }
    kotlinStdlib = $kotlinStdlib
    aar = (Join-Path $taskRoot 'assets\sherpa-onnx-2.0.1.aar')
    nativeLibraries = $nativeLibraries
    dexApiAudit = $dexApiAudit
    assets = [ordered]@{
        model = 'model.onnx'
        voices = 'voices.bin'
        tokens = 'tokens.txt'
        lexicon = 'lexicon-zh.txt'
        dataDir = 'espeak-ng-data'
        dict = 'dict'
        ruleFsts = @('date-zh.fst', 'number-zh.fst', 'phone-zh.fst')
    }
}
$buildInfo | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $logRoot 'build-info.json') -Encoding utf8
& $aapt2 dump badging $signedApk 2>&1 |
    Set-Content -LiteralPath $buildLog -Encoding utf8

Write-Output "APK=$signedApk"
Write-Output "BYTES=$($buildInfo.apkBytes)"
Write-Output "SHA256=$apkHash"
Write-Output "PEER_APK=$peerSignedApk"
Write-Output "PEER_BYTES=$peerApkBytes"
Write-Output "PEER_SHA256=$peerApkHash"
