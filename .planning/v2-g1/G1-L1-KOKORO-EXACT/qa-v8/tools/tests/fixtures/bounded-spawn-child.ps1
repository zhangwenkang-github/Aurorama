param(
    [Parameter(Mandatory = $true)]
    [string] $ChildPidPath
)

$pwshPath = (Get-Command 'pwsh.exe' -ErrorAction Stop).Source
$child = Start-Process `
    -FilePath $pwshPath `
    -ArgumentList @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', (Join-Path $PSScriptRoot 'sleeper.ps1'),
        '-Seconds', '60'
    ) `
    -WindowStyle Hidden `
    -PassThru
[IO.File]::WriteAllText(
    $ChildPidPath,
    [string]$child.Id,
    [Text.Encoding]::ASCII)
Write-Output 'parent-exited-with-live-child'
exit 0
