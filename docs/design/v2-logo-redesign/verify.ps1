$ErrorActionPreference = "Stop"

$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$root = $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param(
        [string] $Check,
        [bool] $Pass,
        [string] $Detail
    )
    $results.Add([pscustomobject]@{ Check = $Check; Result = if ($Pass) { "PASS" } else { "FAIL" }; Detail = $Detail })
    if (-not $Pass) {
        $failures.Add("$Check :: $Detail")
    }
}

function Get-RelativeLuminance {
    param([string] $Hex)
    $clean = $Hex.TrimStart("#")
    $red = [Convert]::ToInt32($clean.Substring(0, 2), 16) / 255.0
    $green = [Convert]::ToInt32($clean.Substring(2, 2), 16) / 255.0
    $blue = [Convert]::ToInt32($clean.Substring(4, 2), 16) / 255.0
    $channels = @($red, $green, $blue)
    $linear = $channels | ForEach-Object {
        if ($_ -le 0.04045) { $_ / 12.92 } else { [Math]::Pow(($_ + 0.055) / 1.055, 2.4) }
    }
    return 0.2126 * $linear[0] + 0.7152 * $linear[1] + 0.0722 * $linear[2]
}

function Get-Contrast {
    param([string] $A, [string] $B)
    $la = Get-RelativeLuminance $A
    $lb = Get-RelativeLuminance $B
    $high = [Math]::Max($la, $lb)
    $low = [Math]::Min($la, $lb)
    return ($high + 0.05) / ($low + 0.05)
}

function Invoke-DomAudit {
    $node = "C:\Users\zhangwenkang\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
    $auditScript = Join-Path $root "dom-audit.mjs"
    $json = (& $node $auditScript) -join ""
    if ($LASTEXITCODE -ne 0 -and -not $json) {
        Add-Result -Check "DOM audit" -Pass $false -Detail "Node / Edge DevTools audit produced no result."
        return
    }
    $audits = $json | ConvertFrom-Json
    foreach ($audit in $audits) {
        Add-Result -Check "DOM $($audit.name)" -Pass ([bool]$audit.ready) -Detail ([string]$audit.detail)
    }
}

Invoke-DomAudit

Add-Type -AssemblyName System.Drawing
$pngs = @(
    @{ Name = "reference-board.png"; Width = 1600; Height = 1800 },
    @{ Name = "directions-overview.png"; Width = 1600; Height = 1300 },
    @{ Name = "polar-aperture-proof.png"; Width = 1600; Height = 1100 },
    @{ Name = "refraction-ridge-proof.png"; Width = 1600; Height = 1100 },
    @{ Name = "magnetotail-trace-proof.png"; Width = 1600; Height = 1100 },
    @{ Name = "logo-proof-contact-sheet.png"; Width = 1600; Height = 1200 }
)

foreach ($entry in $pngs) {
    $path = Join-Path $root $entry.Name
    $bitmap = [System.Drawing.Bitmap]::FromFile($path)
    try {
        $dimensionOk = $bitmap.Width -eq $entry.Width -and $bitmap.Height -eq $entry.Height
        $colors = [System.Collections.Generic.HashSet[int]]::new()
        $stepX = [Math]::Max(1, [int]($bitmap.Width / 64))
        $stepY = [Math]::Max(1, [int]($bitmap.Height / 64))
        for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
                [void]$colors.Add($bitmap.GetPixel($x, $y).ToArgb())
            }
        }
        Add-Result -Check "PNG $($entry.Name)" -Pass ($dimensionOk -and $colors.Count -gt 12) -Detail "$($bitmap.Width)x$($bitmap.Height); sampled colors=$($colors.Count); bytes=$((Get-Item -LiteralPath $path).Length)"
    }
    finally {
        $bitmap.Dispose()
    }
}

$svgs = Get-ChildItem -LiteralPath $root -File -Filter "*.svg" | Sort-Object Name
foreach ($file in $svgs) {
    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
    try {
        $xml = [xml]$text
        $rootElement = $xml.DocumentElement
        $validCanvas = $rootElement.GetAttribute("width") -eq "512" -and $rootElement.GetAttribute("height") -eq "512" -and $rootElement.GetAttribute("viewBox") -eq "0 0 512 512"
        $noEmbeddedImage = (@([regex]::Matches($text, "<image\b", "IgnoreCase")).Count -eq 0)
        $noRemoteReference = (@([regex]::Matches($text, '(?:href|xlink:href)="https?://', "IgnoreCase")).Count -eq 0)
        Add-Result -Check "SVG $($file.Name)" -Pass ($validCanvas -and $noEmbeddedImage -and $noRemoteReference) -Detail "XML PASS; canvas=$validCanvas; externalImage=$(-not $noEmbeddedImage); remoteHref=$(-not $noRemoteReference)"
    }
    catch {
        Add-Result -Check "SVG $($file.Name)" -Pass $false -Detail $_.Exception.Message
    }
}

$htmlFiles = @(
    "reference-board.html",
    "directions-overview.html",
    "proof.html",
    "logo-proof-contact-sheet.html"
)
foreach ($html in $htmlFiles) {
    $path = Join-Path $root $html
    $text = Get-Content -LiteralPath $path -Raw -Encoding utf8
    $localSources = [regex]::Matches($text, '<img[^>]+src="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
    $externalImages = @($localSources | Where-Object { $_ -match '^(https?:|data:)' })
    $missingSources = @($localSources | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) })
    $noOldLogo = $text -notmatch "v2-theme-frames|aurorama-logo-micro"
    Add-Result -Check "HTML assets $html" -Pass ($externalImages.Count -eq 0 -and $missingSources.Count -eq 0 -and $noOldLogo) -Detail "images=$($localSources.Count); external=$($externalImages.Count); missing=$($missingSources.Count); oldLogoRef=$(-not $noOldLogo)"
}

$referenceText = Get-Content -LiteralPath (Join-Path $root "reference-board.html") -Raw -Encoding utf8
$sourceLinks = @([regex]::Matches($referenceText, '<a class="source-url" href="https://'))
Add-Result -Check "Reference access and links" -Pass ($referenceText -match "访问日期 2026-11-02" -and $sourceLinks.Count -ge 9) -Detail "dated=true; sourceLinks=$($sourceLinks.Count)"

$proofText = Get-Content -LiteralPath (Join-Path $root "proof.html") -Raw -Encoding utf8
$proofSizes = @(24, 48, 96, 512) | ForEach-Object {
    @{ Size = $_; Present = $proofText -match ('(?:width|height)="' + $_ + '"') -or $proofText -match ('>' + $_ + ' px<') }
}
$allProofSizes = @($proofSizes | Where-Object { -not $_.Present }).Count -eq 0
$fiveThemes = @("Prism", "Lumen", "Nocturne", "Monolith", "系统动态色") | ForEach-Object { $proofText.Contains($_) }
$proofLightMono = @([regex]::Matches($proofText, 'data-evidence="mono-24-light"')).Count
$proofDarkMono = @([regex]::Matches($proofText, 'data-evidence="mono-24-dark"')).Count
Add-Result -Check "Direction proof requirements" -Pass ($allProofSizes -and (@($fiveThemes | Where-Object { -not $_ }).Count -eq 0) -and $proofLightMono -eq 3 -and $proofDarkMono -eq 3) -Detail "sizes=24,48,96,512; themes=$(@($fiveThemes | Where-Object { $_ }).Count)/5; mono24Light=$proofLightMono; mono24Dark=$proofDarkMono"

$contactText = Get-Content -LiteralPath (Join-Path $root "logo-proof-contact-sheet.html") -Raw -Encoding utf8
$contactLightMono = @([regex]::Matches($contactText, 'data-evidence="mono-24-light"')).Count
$contactDarkMono = @([regex]::Matches($contactText, 'data-evidence="mono-24-dark"')).Count
$matrixRows = @([regex]::Matches($contactText, 'class="theme-matrix-cell theme-matrix-row-label"')).Count
$matrixCells = @([regex]::Matches($contactText, 'class="theme-matrix-cell theme-cell')).Count
$contactThemesPresent = @("Prism", "Lumen", "Nocturne", "Monolith", "系统动态色") | ForEach-Object { $contactText.Contains($_) }
Add-Result -Check "Contact sheet evidence" -Pass ($contactLightMono -eq 3 -and $contactDarkMono -eq 3 -and $matrixRows -eq 3 -and $matrixCells -eq 15 -and (@($contactThemesPresent | Where-Object { -not $_ }).Count -eq 0)) -Detail "mono24Light=$contactLightMono; mono24Dark=$contactDarkMono; matrixRows=$matrixRows; matrixCells=$matrixCells; themes=$(@($contactThemesPresent | Where-Object { $_ }).Count)/5"

$polarSvg = Get-Content -LiteralPath (Join-Path $root "polar-aperture.svg") -Raw -Encoding utf8
$ridgeSvg = Get-Content -LiteralPath (Join-Path $root "refraction-ridge.svg") -Raw -Encoding utf8
$tailSvg = Get-Content -LiteralPath (Join-Path $root "magnetotail-trace.svg") -Raw -Encoding utf8
$polarGeometry = $polarSvg.Contains("横向凹槽") -and $polarSvg.Contains("304 278 424 310")
$ridgeGeometry = $ridgeSvg.Contains("玻璃断口") -and $ridgeSvg.Contains("M389 454")
$tailGeometry = $tailSvg.Contains("负空间") -and $tailSvg.Contains("M150 278 226 329")
Add-Result -Check "Targeted SVG geometry markers" -Pass ($polarGeometry -and $ridgeGeometry -and $tailGeometry) -Detail "polarGroove=$polarGeometry; ridgeBreak=$ridgeGeometry; tailCorridor=$tailGeometry"

$contrastLight = Get-Contrast "#101318" "#F5F4EF"
$contrastDark = Get-Contrast "#FFFFFF" "#0D1116"
Add-Result -Check "Monochrome contrast" -Pass ($contrastLight -ge 4.5 -and $contrastDark -ge 4.5) -Detail ("light={0:N2}:1; dark={1:N2}:1" -f $contrastLight, $contrastDark)

$matrixContrasts = @(
    Get-Contrast "#303A42" "#F3EEE3",
    Get-Contrast "#303A42" "#ECEAf6",
    Get-Contrast "#EDF3F4" "#0B1116",
    Get-Contrast "#EDF3F4" "#110D16",
    Get-Contrast "#EDF3F4" "#17191D"
)
$matrixMinContrast = ($matrixContrasts | Measure-Object -Minimum).Minimum
Add-Result -Check "Theme matrix text contrast" -Pass ($matrixMinContrast -ge 4.5) -Detail ("min={0:N2}:1 across light/dark theme cells" -f $matrixMinContrast)

$reviewDir = Join-Path $root "review"
foreach ($name in @("directions-overview.jpg", "logo-proof-contact-sheet.jpg")) {
    $path = Join-Path $reviewDir $name
    $image = [System.Drawing.Image]::FromFile($path)
    try {
        $size = (Get-Item -LiteralPath $path).Length
        $pass = $image.Width -eq 1280 -and $size -le 307200
        Add-Result -Check "Review copy $name" -Pass $pass -Detail "$($image.Width)x$($image.Height); bytes=$size"
    }
    finally {
        $image.Dispose()
    }
}

$results | Format-Table -AutoSize -Wrap
if ($failures.Count -gt 0) {
    throw ("Verification failed:`n" + ($failures -join "`n"))
}
