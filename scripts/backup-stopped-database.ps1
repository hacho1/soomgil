param(
    [Parameter(Mandatory=$true)][string]$DatabaseFile,
    [Parameter(Mandatory=$true)][string]$Destination,
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar = Join-Path $root 'backend/build/libs/backend-0.0.1-SNAPSHOT.jar'
$database = Get-Item -LiteralPath $DatabaseFile
if ($database.PSIsContainer -or $database.Name -notmatch '^[A-Za-z0-9_-]+\.mv\.db$' -or $database.Length -le 0 -or ($database.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Choose an existing, non-linked H2 database file.'
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build the backend before backing up.' }
$destinationPath = [IO.Path]::GetFullPath($Destination)
$publicPath = [IO.Path]::GetFullPath((Join-Path $root 'frontend')).TrimEnd('\')
if ($destinationPath -eq $publicPath -or $destinationPath.StartsWith($publicPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backups must remain outside the frontend directory.'
}
$null = New-Item -ItemType Directory -Path $destinationPath -Force
if ((Get-Item -LiteralPath $destinationPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup directory must not be linked.' }
$destinationPath = (Resolve-Path -LiteralPath $destinationPath).Path
$name = 'soomgil-db-' + [DateTime]::UtcNow.ToString("yyyyMMdd'T'HHmmssfff'Z'") + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.zip'
$archive = Join-Path $destinationPath $name
$partial = $archive + '.partial'
$inputStream = $null; $outputStream = $null; $zip = $null; $entryStream = $null
try {
    # Deny all sharing for the entire copy. A running H2 process makes this fail.
    $inputStream = [IO.File]::Open($database.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
    $snapshotAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Add-Type -AssemblyName System.IO.Compression
    $outputStream = [IO.File]::Open($partial, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $zip = New-Object IO.Compression.ZipArchive($outputStream, [IO.Compression.ZipArchiveMode]::Create, $true)
    $entry = $zip.CreateEntry($database.Name, [IO.Compression.CompressionLevel]::Optimal)
    $entryStream = $entry.Open()
    $inputStream.CopyTo($entryStream)
    $entryStream.Dispose(); $entryStream = $null
    $zip.Dispose(); $zip = $null
    $outputStream.Dispose(); $outputStream = $null
    $inputStream.Dispose(); $inputStream = $null
    $verification = & $Java -jar $jar --verify-backup $partial (Join-Path $destinationPath 'restore-work')
    if ($LASTEXITCODE -ne 0) { throw 'Independent database restore verification failed.' }
    $record = ($verification -join "`n") | ConvertFrom-Json
    if (-not $record.verified -or $record.sha256 -ne (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash) {
        throw 'Backup verification or checksum mismatch.'
    }
    [IO.File]::Move($partial, $archive)
    $metadata = [ordered]@{
        format = 1; file = $name; sha256 = $record.sha256; bytes = (Get-Item -LiteralPath $archive).Length
        verified = $true; snapshotAt = $snapshotAt; completedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        tables = $record.tables; replicaState = 'not-configured'; method = 'stopped-exclusive-copy'
    }
    [IO.File]::WriteAllText($archive + '.json', ($metadata | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    [pscustomobject]@{Archive=$archive;Verified=$true;SnapshotAt=$snapshotAt;Tables=$record.tables}
} finally {
    foreach ($stream in @($entryStream, $zip, $outputStream, $inputStream)) { if ($null -ne $stream) { $stream.Dispose() } }
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
}
