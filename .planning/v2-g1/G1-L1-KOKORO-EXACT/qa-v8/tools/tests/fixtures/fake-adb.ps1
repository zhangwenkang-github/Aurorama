[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Arguments = @()
)

$marker = $env:FAKE_ADB_MARKER
if (-not [string]::IsNullOrWhiteSpace($marker)) {
    [IO.File]::AppendAllText(
        $marker,
        (($Arguments -join ' ') + "`n"),
        [Text.UTF8Encoding]::new($false))
}

if ($Arguments -contains 'logcat') {
    if ($env:FAKE_ADB_LOGCAT -eq 'exit1') {
        [Console]::Error.WriteLine('fake logcat immediate failure')
        exit 2
    }
    Write-Output '11-05 12:00:00.000  100  100 I FakeLogcat: stream'
    Start-Sleep -Seconds 600
    exit 0
}

if (@($Arguments | Where-Object { $_ -like '*%s*' }).Count -gt 0) {
    if ($env:FAKE_ADB_CLOCK -eq 'hang') {
        Start-Sleep -Seconds 600
    }
    Write-Output '1762329600'
    exit 0
}

if (@($Arguments | Where-Object { $_ -like '*%Y-%m-%d*' }).Count -gt 0) {
    Write-Output '2026-11-05 12:00:00+0000'
    exit 0
}

exit 0
