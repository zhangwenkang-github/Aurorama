#Requires -Version 7.0
# Cinefin 文档 + engramory 记忆库备份。
# 规则：文档大改后或每周运行一次；docs 与 memory 各保留最近 10 份，更老自动清理。
# DocsPath 默认取脚本所在仓的 docs/（主仓与 worktree 各自备份自己），可用 -DocsPath 覆盖。
param(
    [string]$BackupRoot = 'F:\Develop\codex_work\backups\aurorama',
    [string]$DocsPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'docs'),
    [int]$Keep = 10
)
$ErrorActionPreference = 'Stop'
$memory   = 'F:\Develop\codex_work\Documents\Kimi\engramory-memory'
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null

function Backup-Dir([string]$Src, [string]$Name) {
    if (-not (Test-Path $Src)) { Write-Warning "skip missing: $Src"; return }
    $zip = Join-Path $BackupRoot "$Name-$ts.zip"
    Compress-Archive -Path $Src -DestinationPath $zip -CompressionLevel Optimal
    $size = (Get-Item $zip).Length
    Write-Output ("OK {0} ({1:N1} MB)" -f $zip, ($size / 1MB))
    Get-ChildItem $BackupRoot -Filter "$Name-*.zip" |
        Sort-Object Name -Descending |
        Select-Object -Skip $Keep |
        Remove-Item -Force
}

Backup-Dir $DocsPath 'docs'
Backup-Dir $memory 'memory'
