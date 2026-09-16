param(
    [Parameter(Mandatory=$true)][string]$Destination,
    [Parameter(Mandatory=$true)][string]$DatabaseBackup,
    [switch]$IncludePrivateConfig,
    [string]$ToolsDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $ToolsDirectory) { $ToolsDirectory = Join-Path $root 'runtime/deployment-tools' }
$tools = @(Get-Content -LiteralPath (Join-Path $ToolsDirectory 'tools.json') -Raw | ConvertFrom-Json)
foreach ($tool in $tools) {
    if ((Get-FileHash -LiteralPath (Join-Path $ToolsDirectory $tool.file)).Hash -ne $tool.sha256) { throw "Unverified tool: $($tool.name)" }
}
$DatabaseBackup = (Resolve-Path -LiteralPath $DatabaseBackup).Path
$metadata = Get-Content -LiteralPath "$DatabaseBackup.json" -Raw | ConvertFrom-Json
if (-not $metadata.verified -or (Get-FileHash -LiteralPath $DatabaseBackup).Hash -ne $metadata.sha256) { throw 'Database backup verification metadata does not match.' }
$jar = Join-Path $root 'backend/build/libs/backend-0.0.1-SNAPSHOT.jar'
foreach ($file in @($jar, (Join-Path $root 'frontend/dist/index.html'))) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Build the frontend and backend before packaging.' }
}
if ($IncludePrivateConfig -and -not (Test-Path -LiteralPath (Join-Path $root '.env'))) { throw 'Private configuration was requested but .env is absent.' }
$destinationRoot = [IO.Path]::GetFullPath($Destination)
$bundle = Join-Path $destinationRoot ('soomgil-jiri-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
if (-not $bundle.StartsWith($destinationRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid destination.' }
New-Item -ItemType Directory -Path $bundle | Out-Null
$project = Join-Path $bundle 'project'
New-Item -ItemType Directory -Path $project | Out-Null

# Source snapshot includes uncommitted changes, but never local secrets or a live database.
$sources = @(& git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard | Sort-Object -Unique)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate project source.' }
foreach ($relative in $sources) {
    if ($relative -match '(^|/)\.env($|\.)' -and $relative -ne '.env.example') { continue }
    if ($relative -match '^(backup|runtime|artifacts|backend/data|backend/build|frontend/dist|frontend/node_modules)/') { continue }
    $source = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $source.StartsWith($root+'\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Source path escaped project.' }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Do not package symbolic links.' }
    $target = Join-Path $project $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
}
New-Item -ItemType Directory -Path (Join-Path $project 'backend/build/libs') -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $project 'backend/build/libs')
Copy-Item -LiteralPath (Join-Path $root 'frontend/dist') -Destination (Join-Path $project 'frontend/dist') -Recurse
foreach ($tool in $tools | Where-Object { $_.name -in @('java','node') }) {
    $expanded = Join-Path $bundle ('unpack-' + $tool.name)
    Expand-Archive -LiteralPath (Join-Path $ToolsDirectory $tool.file) -DestinationPath $expanded
    $children = @(Get-ChildItem -LiteralPath $expanded -Directory)
    if ($children.Count -ne 1) { throw 'Unexpected runtime archive layout.' }
    $target = Join-Path $project ('runtime/' + $tool.name)
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $children[0].FullName -Destination $target -Recurse
    # Only remove the freshly created, validated unpack directory within this bundle.
    $resolved = (Resolve-Path -LiteralPath $expanded).Path
    if (-not $resolved.StartsWith($bundle+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe unpack cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$caddy = $tools | Where-Object name -eq 'caddy'
$caddyTarget = Join-Path $project 'runtime/caddy'
Expand-Archive -LiteralPath (Join-Path $ToolsDirectory $caddy.file) -DestinationPath $caddyTarget
Copy-Item -LiteralPath (Join-Path $caddyTarget 'caddy.exe') -Destination (Join-Path $project 'deploy/windows/caddy.exe')
$winsw = $tools | Where-Object name -eq 'winsw'
foreach ($service in @('backend','web','monitor')) {
    Copy-Item -LiteralPath (Join-Path $ToolsDirectory $winsw.file) -Destination (Join-Path $project "deploy/windows/soomgil-$service.exe")
}
Copy-Item -LiteralPath (Join-Path $ToolsDirectory 'WinSW-LICENSE.txt') -Destination (Join-Path $project 'deploy/windows/WinSW-LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $ToolsDirectory 'tools.json') -Destination (Join-Path $bundle 'tools.json')
$private = Join-Path $bundle 'private'
New-Item -ItemType Directory -Path $private | Out-Null
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User, [Security.Principal.SecurityIdentifier]'S-1-5-32-544', [Security.Principal.SecurityIdentifier]'S-1-5-18')) {
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $private -AclObject $acl
Copy-Item -LiteralPath $DatabaseBackup -Destination $private
Copy-Item -LiteralPath "$DatabaseBackup.json" -Destination $private
if ($IncludePrivateConfig) { Copy-Item -LiteralPath (Join-Path $root '.env') -Destination (Join-Path $private 'config.env') }
& git -C $root bundle create (Join-Path $bundle 'history.bundle') --all
if ($LASTEXITCODE -ne 0) { throw 'Git history packaging failed.' }
Copy-Item -LiteralPath (Join-Path $root 'docs/JIRI_HANDOFF.md') -Destination (Join-Path $bundle 'START-HERE.md')
Copy-Item -LiteralPath (Join-Path $root 'deploy/bundle.gitignore') -Destination (Join-Path $bundle '.gitignore')
$entries = @(Get-ChildItem -LiteralPath $bundle -File -Recurse -Force | ForEach-Object {
    [ordered]@{path=$_.FullName.Substring($bundle.Length+1).Replace('\','/'); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
$manifest = [ordered]@{createdAt=(Get-Date).ToUniversalTime().ToString('o'); host='jiri.hknu.ac.kr'; gitHead=(& git -C $root rev-parse HEAD); includesUncommittedSource=$true; privateConfig=[bool]$IncludePrivateConfig; databaseBackupAt=$metadata.completedAt; files=$entries}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $bundle 'manifest.json') -Encoding UTF8
[pscustomobject]@{Bundle=$bundle; Files=$entries.Count; DatabaseSnapshot=$metadata.file; PrivateConfig=[bool]$IncludePrivateConfig; Note='Not installed or publicly served. Protect private/; it is not encrypted.'}
