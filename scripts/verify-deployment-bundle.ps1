param([Parameter(Mandatory=$true)][string]$Bundle)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Bundle).Path.TrimEnd('\')
$manifest = Get-Content -LiteralPath (Join-Path $root 'manifest.json') -Raw | ConvertFrom-Json
$checked = 0
foreach ($entry in $manifest.files) {
    $path = [IO.Path]::GetFullPath((Join-Path $root $entry.path))
    if (-not $path.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Manifest path escaped bundle.' }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing file: $($entry.path)" }
    $item = Get-Item -LiteralPath $path
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Symbolic link in bundle.' }
    if ($item.Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $path).Hash -ne $entry.sha256) { throw "Checksum mismatch: $($entry.path)" }
    $checked++
}
if ($checked -eq 0) { throw 'Manifest is empty.' }
[pscustomobject]@{Verified=$true;Files=$checked;BackupTime=[DateTimeOffset]::FromUnixTimeMilliseconds($manifest.databaseBackupAt).ToLocalTime().ToString('o');Note='Transfer integrity only, not deployment approval.'}
