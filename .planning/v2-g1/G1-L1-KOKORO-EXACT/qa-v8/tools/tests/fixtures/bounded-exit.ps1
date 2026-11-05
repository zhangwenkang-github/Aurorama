param(
    [int] $ExitCode = 0
)

Write-Output 'bounded-exit-stdout'
[Console]::Error.WriteLine('bounded-exit-stderr')
exit $ExitCode
