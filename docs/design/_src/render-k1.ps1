$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$src = 'F:\Develop\code_data\work_tree\Aurorama\v2-merge-141\docs\design\_src\direction-k1.html'
$outDir = 'F:\Develop\code_data\work_tree\Aurorama\v2-merge-141\docs\design\k1-aurora'
New-Item -ItemType Directory -Force $outDir | Out-Null
$screens = [ordered]@{
  'phone-home'   = @(1240, 2560)
  'phone-detail' = @(1240, 2560)
  'phone-music'  = @(1240, 2560)
  'tablet-home'  = @(2048, 1280)
  'icon-a'       = @(1024, 1024)
  'icon-b'       = @(1024, 1024)
}
foreach ($key in $screens.Keys) {
  $w = $screens[$key][0]; $h = $screens[$key][1]
  $out = Join-Path $outDir "$key.png"
  $uri = 'file:///' + ($src -replace '\\', '/') + '?s=' + $key
  $ok = $false
  for ($i = 0; $i -lt 4 -and -not $ok; $i++) {
    $profile = Join-Path $env:TEMP ("cinefin-k1\edge-{0}-{1}" -f $key, $i)
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
      --virtual-time-budget=3000 --user-data-dir="$profile" `
      --screenshot="$out" --window-size="$w,$h" $uri 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
    $ok = (Test-Path $out) -and ((Get-Item $out).Length -gt 10KB)
  }
  if ($ok) {
    Write-Host ("{0,-14} {1,6} KB" -f $key, [math]::Round((Get-Item $out).Length / 1KB))
  } else {
    Write-Warning "FAILED: $key"
  }
}
