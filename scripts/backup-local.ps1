param([string]$Destination)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $Destination) { $Destination = Join-Path $root 'backup\source-snapshots' }
$destinationRoot = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$archivePath = Join-Path $destinationRoot "soomgil-source-$stamp.zip"
$bundlePath = Join-Path $destinationRoot "soomgil-history-$stamp.bundle"
$files = @(& git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Git file listing failed.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($archivePath, [System.IO.Compression.ZipArchiveMode]::Create)
$count = 0
try {
    foreach ($relative in ($files | Sort-Object -Unique)) {
        $path = [System.IO.Path]::GetFullPath((Join-Path $root $relative))
        if (-not $path.StartsWith($root + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Source path escaped the repository.' }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        # Explicit exclusions also protect accidentally tracked local secrets/data.
        if ($relative -match '(^|/)\.env($|\.)' -and $relative -ne '.env.example') { continue }
        if ($relative -match '^(backup|backend/data|artifacts)/') { continue }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $path, $relative.Replace('\','/'), [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        $count++
    }
} finally { $archive.Dispose() }
& git -C $root bundle create $bundlePath --all
if ($LASTEXITCODE -ne 0) { throw 'Source archive created, but Git history bundle failed.' }
& git -C $root bundle verify $bundlePath
if ($LASTEXITCODE -ne 0) { throw 'Git history bundle verification failed.' }
[pscustomobject]@{ SourceArchive=$archivePath; Files=$count; HistoryBundle=$bundlePath; SHA256=(Get-FileHash -LiteralPath $archivePath).Hash }
# The ZIP includes current uncommitted source. The bundle contains committed history only.
# Live DB files and .env are intentionally excluded; back them up separately and securely.
