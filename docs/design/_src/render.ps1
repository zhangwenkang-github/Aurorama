# 用无头 Edge 把 _src 下的 HTML 设计稿渲染为 PNG。
# 用法（项目根目录）：
#   pwsh docs/design/_src/render.ps1                 # 渲染全部
#   pwsh docs/design/_src/render.ps1 -Only home,icon # 只渲染指定屏
#   pwsh docs/design/_src/render.ps1 -Dir s4-revision # 只渲染指定方向
param(
    [string[]]$Only = @(),
    [string[]]$Dir = @()
)

$ErrorActionPreference = 'Stop'
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$src = $PSScriptRoot
$project = (Resolve-Path (Join-Path $src '..\..\..')).Path
$profile = Join-Path $env:TEMP 'cinefin-s1\edgeprofile'

$targets = @(
    @{ dir = 's1-direction-a'; file = 'direction-a.html'; screens = [ordered]@{
        'home'       = @(2048, 1280)
        'detail'     = @(2048, 1280)
        'player'     = @(2048, 1280)
        'music'      = @(2048, 1280)
        'library'    = @(2048, 1280)
        'reader'     = @(2048, 1280)
        'reader-paper' = @(2048, 1280)
        'phone'      = @(1240, 2560)
        'icon'       = @(1024, 1024)
    } },
    @{ dir = 's1-direction-b'; file = 'direction-b.html'; screens = [ordered]@{
        'home'       = @(2048, 1280)
        'detail'     = @(2048, 1280)
        'player'     = @(2048, 1280)
        'music'      = @(2048, 1280)
        'bookshelf'  = @(2048, 1280)
        'reader'     = @(2048, 1280)
        'reader-paper' = @(2048, 1280)
        'phone'      = @(1240, 2560)
        'icon'       = @(1024, 1024)
    } },
    @{ dir = 's1-direction-c'; file = 'direction-c.html'; screens = [ordered]@{
        'home'       = @(2048, 1280)
        'detail'     = @(2048, 1280)
        'player'     = @(2048, 1280)
        'music'      = @(2048, 1280)
        'settings'   = @(2048, 1280)
        'reader'     = @(2048, 1280)
        'reader-paper' = @(2048, 1280)
        'phone'      = @(1240, 2560)
        'icon'       = @(1024, 1024)
    } },
    @{ dir = 's4-revision'; file = 'direction-s4.html'; screens = [ordered]@{
        'home'          = @(2048, 1280)
        'detail'        = @(2048, 1280)
        'library'       = @(2048, 1280)
        'music'         = @(2048, 1280)
        'board-buttons' = @(2048, 1280)
        'board-cards'   = @(2048, 1280)
        'board-list'    = @(2048, 1280)
    } }
)

foreach ($t in $targets) {
    if ($Dir.Count -gt 0 -and $Dir -notcontains $t.dir) { continue }
    $html = Join-Path $src $t.file
    if (-not (Test-Path $html)) { continue }
    $outDir = Join-Path $project "docs\design\$($t.dir)"
    New-Item -ItemType Directory -Force $outDir | Out-Null
    foreach ($key in $t.screens.Keys) {
        if ($Only.Count -gt 0 -and $Only -notcontains $key) { continue }
        $w = $t.screens[$key][0]
        $h = $t.screens[$key][1]
        $out = Join-Path $outDir "$key.png"
        $uri = 'file:///' + ($html -replace '\\', '/') + '?s=' + $key
        & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
            --virtual-time-budget=3000 --user-data-dir="$profile" `
            --screenshot="$out" --window-size="$w,$h" $uri 2>&1 | Out-Null
        if (Test-Path $out) {
            $size = [math]::Round((Get-Item $out).Length / 1KB)
            Write-Host ("{0,-14} {1,-14} {2,6} KB" -f $t.dir, $key, $size)
        }
        else {
            Write-Warning "FAILED: $($t.dir)/$key"
        }
    }
}
