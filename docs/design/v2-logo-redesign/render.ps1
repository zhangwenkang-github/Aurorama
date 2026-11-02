$ErrorActionPreference = "Stop"

$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path -LiteralPath $edge)) {
    throw "Microsoft Edge not found: $edge"
}

$root = $PSScriptRoot
$profile = Join-Path ([System.IO.Path]::GetTempPath()) ("aurorama-logo-edge-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $profile -Force | Out-Null

function Render-Page {
    param(
        [Parameter(Mandatory = $true)][string] $Html,
        [Parameter(Mandatory = $true)][string] $Png,
        [Parameter(Mandatory = $true)][int] $Width,
        [Parameter(Mandatory = $true)][int] $Height,
        [string] $Query = ""
    )

    $htmlPath = Join-Path $root $Html
    $pngPath = Join-Path $root $Png
    $uri = ([System.Uri]$htmlPath).AbsoluteUri
    if ($Query) {
        $uri = "$uri`?$Query"
    }

    & $edge `
        "--headless=new" `
        "--disable-gpu" `
        "--hide-scrollbars" `
        "--no-first-run" `
        "--disable-extensions" `
        "--force-device-scale-factor=1" `
        "--run-all-compositor-stages-before-draw" `
        "--virtual-time-budget=3000" `
        "--user-data-dir=$profile" `
        "--window-size=$Width,$Height" `
        "--screenshot=$pngPath" `
        $uri | Out-Null

    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $pngPath)) {
        throw "Render failed: $Html -> $Png"
    }
}

Render-Page -Html "reference-board.html" -Png "reference-board.png" -Width 1600 -Height 1800
Render-Page -Html "directions-overview.html" -Png "directions-overview.png" -Width 1600 -Height 1300
Render-Page -Html "proof.html" -Png "polar-aperture-proof.png" -Width 1600 -Height 1100 -Query "direction=polar"
Render-Page -Html "proof.html" -Png "refraction-ridge-proof.png" -Width 1600 -Height 1100 -Query "direction=ridge"
Render-Page -Html "proof.html" -Png "magnetotail-trace-proof.png" -Width 1600 -Height 1100 -Query "direction=tail"
Render-Page -Html "logo-proof-contact-sheet.html" -Png "logo-proof-contact-sheet.png" -Width 1600 -Height 1200

$reviewDir = Join-Path $root "review"
New-Item -ItemType Directory -Path $reviewDir -Force | Out-Null

Add-Type -AssemblyName System.Drawing
function Write-ReviewJpeg {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Target
    )

    $sourcePath = Join-Path $root $Source
    $targetPath = Join-Path $reviewDir $Target
    $image = [System.Drawing.Image]::FromFile($sourcePath)
    try {
        $width = 1280
        $height = [int][Math]::Round($image.Height * ($width / $image.Width))
        $bitmap = New-Object System.Drawing.Bitmap($width, $height)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.DrawImage($image, 0, 0, $width, $height)
            }
            finally {
                $graphics.Dispose()
            }

            $encoder = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
                Where-Object { $_.MimeType -eq "image/jpeg" } |
                Select-Object -First 1
            $parameters = New-Object System.Drawing.Imaging.EncoderParameters(1)
            $parameters.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
                [System.Drawing.Imaging.Encoder]::Quality,
                [long]88
            )
            $bitmap.Save($targetPath, $encoder, $parameters)
            $parameters.Dispose()
        }
        finally {
            $bitmap.Dispose()
        }
    }
    finally {
        $image.Dispose()
    }
}

Write-ReviewJpeg -Source "directions-overview.png" -Target "directions-overview.jpg"
Write-ReviewJpeg -Source "logo-proof-contact-sheet.png" -Target "logo-proof-contact-sheet.jpg"

Get-ChildItem -LiteralPath $root -File -Filter "*.png" |
    Sort-Object Name |
    Select-Object Name, Length
Get-ChildItem -LiteralPath $reviewDir -File -Filter "*.jpg" |
    Sort-Object Name |
    Select-Object Name, Length

$resolvedProfile = (Resolve-Path -LiteralPath $profile).Path
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
if (
    $resolvedProfile.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
    (Split-Path -Leaf $resolvedProfile).StartsWith("aurorama-logo-edge-")
) {
    Remove-Item -LiteralPath $resolvedProfile -Recurse -Force -ErrorAction SilentlyContinue
}
