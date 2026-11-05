param(
    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [string] $AdbExecutable
)

$toolsRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
& (Join-Path $toolsRoot 'start-logcat.ps1') `
    -Serial 'owned-serial' `
    -OutputRoot $OutputRoot `
    -AdbExecutable $AdbExecutable
