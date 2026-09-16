param([string]$Destination)
$ErrorActionPreference = 'Stop'
if (-not $Destination) { $Destination = Join-Path $PSScriptRoot '../runtime/deployment-tools' }
$Destination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$packages = @(
    @{name='java'; file='OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'; version='17.0.20.1+1'; url='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'; sha256='e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'; hashSource='Adoptium release API'},
    @{name='node'; file='node-v22.23.2-win-x64.zip'; version='22.23.2'; url='https://nodejs.org/dist/v22.23.2/node-v22.23.2-win-x64.zip'; sha256='1177b4137ba5adaa56354ae40f1080c7450e8ae09cecb47da459d1c52ac99f97'; hashSource='Node.js SHASUMS256.txt'},
    @{name='caddy'; file='caddy_2.11.4_windows_amd64.zip'; version='2.11.4'; url='https://github.com/caddyserver/caddy/releases/download/v2.11.4/caddy_2.11.4_windows_amd64.zip'; sha256='1708333f79e274c7697285afe6d592ab39314e0b131e9ec6bea08ad27df62ebf'; hashSource='Official GitHub release asset digest'},
    @{name='winsw'; file='WinSW.NET461.exe'; version='2.12.0'; url='https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'; sha256='b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f'; hashSource='Pinned after download from official release; upstream has no digest/signature'}
)
foreach ($package in $packages) {
    $path = Join-Path $Destination $package.file
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "Downloading $($package.name) $($package.version) from official distribution"
        Invoke-WebRequest $package.url -OutFile $path -TimeoutSec 600 -UseBasicParsing
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $package.sha256) { throw "Checksum mismatch: $($package.file). Existing file was not removed." }
}
$licensePath = Join-Path $Destination 'WinSW-LICENSE.txt'
if (-not (Test-Path -LiteralPath $licensePath)) {
    $license = Invoke-RestMethod 'https://api.github.com/repos/winsw/winsw/license?ref=v2.12.0' -Headers @{'User-Agent'='Soomgil-deployment'}
    [IO.File]::WriteAllBytes($licensePath, [Convert]::FromBase64String($license.content))
}
$packages | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Destination 'tools.json') -Encoding UTF8
Write-Host 'Downloads verified. No software or services were installed.'
