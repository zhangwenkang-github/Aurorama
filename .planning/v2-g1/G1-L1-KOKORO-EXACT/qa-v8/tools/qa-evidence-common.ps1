function Get-MediaSessionEvidence {
    param(
        [AllowNull()]
        [string] $Text,

        [string[]] $Packages = @()
    )

    $lines = if ([string]::IsNullOrEmpty($Text)) {
        @()
    } else {
        @($Text -split "`r?`n")
    }
    $stackStarts = [Collections.Generic.List[int]]::new()
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match '^\s*Sessions Stack - have \d+ sessions:\s*$') {
            $stackStarts.Add($index)
        }
    }

    $segments = [Collections.Generic.List[object]]::new()
    $records = [Collections.Generic.List[object]]::new()
    $excerptLines = [Collections.Generic.List[string]]::new()
    foreach ($start in $stackStarts) {
        $declaredCount = [int]([regex]::Match(
            $lines[$start],
            'have (\d+) sessions').Groups[1].Value)
        $end = $lines.Count
        for ($cursor = $start + 1; $cursor -lt $lines.Count; $cursor++) {
            if (
                $lines[$cursor] -match '^\s*Audio playback \(lastly played comes first\)\s*$' -or
                $lines[$cursor] -match '^\s*Sessions Stack - have \d+ sessions:\s*$'
            ) {
                $end = $cursor
                break
            }
        }
        $block = if ($end -gt $start) {
            @($lines[$start..($end - 1)])
        } else {
            @()
        }
        foreach ($line in $block) {
            $excerptLines.Add($line)
        }

        $currentRecord = $null
        $finalizeRecord = {
            param($Record)
            if ($null -eq $Record) {
                return
            }
            if ($Packages.Count -gt 0 -and $Record.package -notin $Packages) {
                return
            }
            $records.Add([pscustomobject]$Record)
        }
        foreach ($line in $block) {
            $header = [regex]::Match(
                $line,
                '^\s{4}(?<name>.+?) (?<package>[A-Za-z0-9_.]+)/(?<tag>.+?) \(userId=(?<user>\d+)\)\s*$')
            if ($header.Success -and $line -notmatch '\bownerPid=') {
                & $finalizeRecord $currentRecord
                $currentRecord = [ordered]@{
                    name = $header.Groups['name'].Value.Trim()
                    package = $header.Groups['package'].Value
                    tag = $header.Groups['tag'].Value.Trim()
                    userId = [int]$header.Groups['user'].Value
                    ownerPid = $null
                    ownerUid = $null
                    active = $false
                    playbackState = $null
                    playbackStateLabel = $null
                    metadataTitle = $null
                    metadataArtist = $null
                    metadataAlbum = $null
                }
                continue
            }
            if ($null -eq $currentRecord) {
                continue
            }
            $owner = [regex]::Match(
                $line,
                '\bownerPid=(\d+),\s*ownerUid=(\d+)')
            if ($owner.Success) {
                $currentRecord.ownerPid = [int]$owner.Groups[1].Value
                $currentRecord.ownerUid = [int]$owner.Groups[2].Value
            }
            $package = [regex]::Match($line, '^\s*package=([A-Za-z0-9_.]+)\s*$')
            if ($package.Success) {
                $currentRecord.package = $package.Groups[1].Value
            }
            if ($line -match '^\s*active=(true|false)\s*$') {
                $currentRecord.active = $Matches[1] -eq 'true'
            }
            $playbackState = [regex]::Match(
                $line,
                'state=PlaybackState \{state=(?<state>\d+)(?:,\s*speed=(?<speed>[-0-9.]+))?')
            if ($playbackState.Success) {
                $currentRecord.playbackState = [int]$playbackState.Groups['state'].Value
                $currentRecord.playbackStateLabel = switch (
                    $currentRecord.playbackState
                ) {
                    0 { 'NONE' }
                    1 { 'STOPPED' }
                    2 { 'PAUSED' }
                    3 { 'PLAYING' }
                    4 { 'FAST_FORWARDING' }
                    5 { 'REWINDING' }
                    6 { 'BUFFERING' }
                    7 { 'ERROR' }
                    8 { 'CONNECTING' }
                    9 { 'SKIPPING_TO_PREVIOUS' }
                    10 { 'SKIPPING_TO_NEXT' }
                    11 { 'SKIPPING_TO_QUEUE_ITEM' }
                    default { "UNKNOWN_$($currentRecord.playbackState)" }
                }
            }
            $metadata = [regex]::Match($line, '^\s*metadata:\s*size=\d+,\s*(?<value>.*)$')
            if ($metadata.Success) {
                $metadataParts = @($metadata.Groups['value'].Value -split ', ')
                $currentRecord.metadataTitle = if ($metadataParts.Count -gt 0) {
                    $metadataParts[0] -replace '^description=', ''
                } else {
                    $null
                }
                if ($metadataParts.Count -ge 3) {
                    $currentRecord.metadataArtist = $metadataParts[$metadataParts.Count - 2]
                    $currentRecord.metadataAlbum = $metadataParts[$metadataParts.Count - 1]
                }
            }
        }
        & $finalizeRecord $currentRecord
        $segments.Add([ordered]@{
            startLine = $start + 1
            endLine = $end
            declaredSessionCount = $declaredCount
            excerpt = ($block -join "`n")
        })
    }

    $recordArray = @($records)
    $activeRecords = @($recordArray | Where-Object active)
    $uniqueActive = @(
        $activeRecords |
            Group-Object {
                '{0}|{1}|{2}|{3}' -f
                    $_.ownerPid,
                    $_.package,
                    $_.tag,
                    $_.userId
            } |
            ForEach-Object { $_.Group[0] }
    )
    return [pscustomobject]@{
        stackFound = $stackStarts.Count -gt 0
        stackCount = $stackStarts.Count
        segments = @($segments)
        sessionRecords = $recordArray
        activeSessions = $uniqueActive
        activeCount = $uniqueActive.Count
        historyExcluded = $true
        excerpt = ($excerptLines -join "`n")
    }
}

function Get-LiveForegroundServiceEvidence {
    param(
        [AllowNull()]
        [string] $Text,

        [string] $ServiceName = 'KokoroRuntimeService'
    )

    $servicePattern = [regex]::Escape($ServiceName)
    $typeMatches = @(
        [regex]::Matches(
            [string]$Text,
            '(?:types=|foregroundServiceType=)(0x[0-9a-fA-F]+)') |
            ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } |
            Sort-Object -Unique
    )
    return [pscustomobject]@{
        servicePresent = [string]$Text -match $servicePattern
        isForeground = [string]$Text -match '\bisForeground=true\b'
        explicitTypeFields = $typeMatches
        explicitTypeFieldPresent = $typeMatches.Count -gt 0
        explicitMediaPlaybackType = $typeMatches -contains '0x00000002'
        api33TypeFieldMayBeAbsent = $typeMatches.Count -eq 0
    }
}

function Resolve-Aapt2Path {
    param([string] $PreferredPath)

    if (-not [string]::IsNullOrWhiteSpace($PreferredPath) -and
        (Test-Path -LiteralPath $PreferredPath)) {
        return (Resolve-Path -LiteralPath $PreferredPath).Path
    }
    $command = Get-Command 'aapt2.exe' -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        'F:\Develop\Android\AndroidSDK'
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($sdkRoot in $sdkCandidates) {
        $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
        if (-not (Test-Path -LiteralPath $buildToolsRoot)) {
            continue
        }
        $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if ($candidate) {
            return $candidate
        }
    }
    throw 'aapt2.exe was not found; cannot audit the packaged foregroundServiceType.'
}

function Get-ApkManifestForegroundEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ApkPath,

        [string] $ExpectedPackage,

        [string] $ServiceName = 'KokoroRuntimeService',

        [string] $Aapt2Path
    )

    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $resolvedAapt2 = Resolve-Aapt2Path $Aapt2Path
    $output = @(
        & $resolvedAapt2 dump xmltree --file AndroidManifest.xml $resolvedApk 2>&1 |
            ForEach-Object { [string]$_ }
    )
    $exitCode = $LASTEXITCODE
    $text = $output -join "`n"
    $packageMatch = [regex]::Match($text, '(?m)^\s*A: package="([^"]+)"')
    $serviceCount = [regex]::Matches($text, '(?m)^\s*E: service\b').Count
    $serviceNameMatches = $text -match (
        'android:name\([^)]*\)="[^"]*' +
        [regex]::Escape($ServiceName.TrimStart('.')) +
        '"')
    $mediaPlaybackTypeMatches = $text -match (
        'foregroundServiceType\(0x[0-9a-fA-F]+\)=0x00000002\b')
    $packageMatches = if ([string]::IsNullOrWhiteSpace($ExpectedPackage)) {
        $true
    } else {
        $packageMatch.Groups[1].Value -eq $ExpectedPackage
    }
    return [pscustomobject]@{
        aapt2Path = $resolvedAapt2
        apkPath = $resolvedApk
        exitCode = $exitCode
        packageName = $packageMatch.Groups[1].Value
        packageMatches = $packageMatches
        serviceCount = $serviceCount
        serviceNameMatches = $serviceNameMatches
        mediaPlaybackForegroundServiceTypeMatches = $mediaPlaybackTypeMatches
        xmltreeExcerpt = @(
            $output |
                Where-Object {
                    $_ -match '^E: service|foregroundServiceType|android:name\([^)]*\)="[^"]*KokoroRuntimeService|^A: package='
                } |
                Select-Object -First 20
        )
    }
}

function Get-ApkPermissionEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ApkPath,

        [string[]] $ExpectedPermissions = @(),

        [string] $Aapt2Path
    )

    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $resolvedAapt2 = Resolve-Aapt2Path $Aapt2Path
    $output = @(
        & $resolvedAapt2 dump permissions $resolvedApk 2>&1 |
            ForEach-Object { [string]$_ }
    )
    $exitCode = $LASTEXITCODE
    $permissions = @(
        $output |
            ForEach-Object {
                $match = [regex]::Match(
                    $_,
                    "(?:uses-permission(?:-sdk-\d+)?):\s*name='([^']+)'")
                if ($match.Success) {
                    $match.Groups[1].Value
                }
            } |
            Sort-Object -Unique
    )
    $expected = @($ExpectedPermissions | Sort-Object -Unique)
    return [pscustomobject]@{
        aapt2Path = $resolvedAapt2
        apkPath = $resolvedApk
        exitCode = $exitCode
        permissions = $permissions
        expectedPermissions = $expected
        exactPermissionSetMatches =
            $exitCode -eq 0 -and
            (@($permissions) -join "`n") -eq (@($expected) -join "`n")
        internetPermission = $permissions -contains 'android.permission.INTERNET'
    }
}

function Get-InstalledApkManifestForegroundEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Serial,

        [Parameter(Mandatory = $true)]
        [string] $Package,

        [Parameter(Mandatory = $true)]
        [string] $HostApkPath,

        [string] $ServiceName = 'KokoroRuntimeService',

        [string] $Aapt2Path
    )

    $pathResult = @(& adb.exe -s $Serial shell pm path $Package 2>&1)
    $pathExitCode = $LASTEXITCODE
    $devicePath = @(
        $pathResult |
            Where-Object { [string]$_ -match '^package:' } |
            ForEach-Object { ([string]$_ -replace '^package:', '').Trim() }
    ) | Select-Object -First 1
    if ($pathExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($devicePath)) {
        throw "Installed APK path is unavailable for $Package"
    }
    $deviceShaText = @(
        & adb.exe -s $Serial shell sha256sum $devicePath 2>&1
    ) -join "`n"
    $deviceShaExitCode = $LASTEXITCODE
    $deviceBytesText = @(
        & adb.exe -s $Serial shell stat -c '%s' $devicePath 2>&1
    ) -join "`n"
    $deviceBytesExitCode = $LASTEXITCODE
    $deviceSha = [regex]::Match(
        $deviceShaText,
        '\b([0-9a-fA-F]{64})\b').Groups[1].Value.ToLowerInvariant()
    $deviceBytes = [regex]::Match($deviceBytesText, '^\s*(\d+)').Groups[1].Value
    $hostSha = (Get-FileHash -LiteralPath $HostApkPath -Algorithm SHA256).
        Hash.ToLowerInvariant()
    $hostBytes = (Get-Item -LiteralPath $HostApkPath).Length
    $manifest = Get-ApkManifestForegroundEvidence `
        -ApkPath $HostApkPath `
        -ExpectedPackage $Package `
        -ServiceName $ServiceName `
        -Aapt2Path $Aapt2Path
    $byteIdentityEstablished =
        $deviceShaExitCode -eq 0 -and
        $deviceBytesExitCode -eq 0 -and
        $deviceSha -eq $hostSha -and
        $deviceBytes -match '^\d+$' -and
        [long]$deviceBytes -eq [long]$hostBytes
    return [pscustomobject]@{
        package = $Package
        devicePath = $devicePath
        deviceBytes = if ($deviceBytes -match '^\d+$') {
            [long]$deviceBytes
        } else {
            $null
        }
        deviceSha256 = $deviceSha
        hostApkPath = (Resolve-Path -LiteralPath $HostApkPath).Path
        hostBytes = [long]$hostBytes
        hostSha256 = $hostSha
        byteIdentityEstablished = $byteIdentityEstablished
        manifestAuditTarget =
            'host APK bytes proven identical to installed base.apk by size and SHA-256'
        manifest = $manifest
        mediaPlaybackForegroundServiceTypeMatches =
            $byteIdentityEstablished -and
            $manifest.exitCode -eq 0 -and
            $manifest.packageMatches -and
            $manifest.serviceCount -eq 1 -and
            $manifest.serviceNameMatches -and
            $manifest.mediaPlaybackForegroundServiceTypeMatches
    }
}

function Get-LatestRuntimeState {
    param(
        [AllowNull()]
        [string[]] $Lines
    )

    $matches = @(
        @($Lines) |
            Select-String -Pattern 'KOKORO_RUNTIME: STATE (\{[^\r\n]+\})'
    )
    if ($matches.Count -eq 0) {
        return $null
    }
    try {
        return $matches[$matches.Count - 1].Matches[0].Groups[1].Value |
            ConvertFrom-Json -Depth 30
    } catch {
        return $null
    }
}
