param(
    [ValidateRange(1, 600)]
    [int] $Seconds = 30
)

Start-Sleep -Seconds $Seconds
