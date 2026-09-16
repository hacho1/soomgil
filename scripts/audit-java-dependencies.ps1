param([string]$Inventory, [string]$Output)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $Inventory) { $Inventory = Join-Path $root 'backend/build/reports/dependencies/runtime.json' }
if (-not $Output) { $Output = Join-Path $root 'backend/build/reports/dependencies/osv.json' }
$modules = @(Get-Content -LiteralPath $Inventory -Raw | ConvertFrom-Json)
if ($modules.Count -eq 0) { throw 'Run gradlew dependencyInventory first.' }
$queries = @($modules | ForEach-Object { @{package=@{name=$_.name; ecosystem='Maven'}; version=$_.version} })
$response = Invoke-RestMethod 'https://api.osv.dev/v1/querybatch' -Method Post -ContentType 'application/json' -Body (@{queries=$queries} | ConvertTo-Json -Depth 5) -TimeoutSec 60
if (@($response.results).Count -ne $modules.Count) { throw 'Incomplete OSV response.' }
$findings = @(for ($i=0; $i -lt $modules.Count; $i++) {
    if ($response.results[$i].vulns) { [pscustomobject]@{name=$modules[$i].name; version=$modules[$i].version; advisories=@($response.results[$i].vulns.id)} }
})
$report = [ordered]@{checkedAt=(Get-Date).ToUniversalTime().ToString('o'); source='https://api.osv.dev'; modules=$modules.Count; findings=$findings; note='Known published advisories only; not a security guarantee.'}
$parent = Split-Path -Parent ([IO.Path]::GetFullPath($Output))
New-Item -ItemType Directory -Path $parent -Force | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding UTF8
$report | ConvertTo-Json -Depth 8
if ($findings.Count -gt 0) { exit 1 }
