$ErrorActionPreference='Stop'
$root=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime=Join-Path $root 'runtime\share'
if (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 8080,18787 }) {
    throw 'Stop the existing backend with Ctrl+C before running run-share.cmd. The same DB must not be opened twice.'
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
Push-Location (Join-Path $root 'frontend')
try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' } } finally { Pop-Location }
Push-Location (Join-Path $root 'backend')
try { & .\gradlew.bat bootJar; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' } } finally { Pop-Location }
$ngrok=(Get-Command ngrok -ErrorAction Stop).Source
if (-not (Get-Process -Name ngrok -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $ngrok -ArgumentList @('http','http://127.0.0.1:18787','--inspect=false','--log=stdout','--log-format=json') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'ngrok.out.log') -RedirectStandardError (Join-Path $runtime 'ngrok.err.log') | Out-Null
}
$tunnel=$null
for ($attempt=0; $attempt -lt 20; $attempt++) {
    try {
        $tunnels=Invoke-RestMethod 'http://127.0.0.1:4040/api/tunnels' -TimeoutSec 2
        $tunnel=$tunnels.tunnels | Where-Object { $_.config.addr -eq 'http://127.0.0.1:18787' -and $_.public_url.StartsWith('https://') } | Select-Object -First 1
    } catch { }
    if ($tunnel) { break }
    Start-Sleep -Seconds 1
}
if (-not $tunnel) { throw 'No matching ngrok tunnel found. Check runtime/share/ngrok.err.log; leave other tunnels unchanged.' }
$origin=[uri]$tunnel.public_url
if ($origin.Scheme -ne 'https' -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/') { throw 'Invalid sharing origin' }
$publicOrigin=$origin.GetLeftPart([System.UriPartial]::Authority)
Write-Host "Temporary sharing URL: $publicOrigin"
Write-Host 'Use the HTTPS URL. Email signup/login is enabled; social login and administrator access are disabled in sharing mode.'
Write-Host 'Keep this window and the computer running. Ctrl+C stops the website. Return to run-backend.cmd for local development.'
Push-Location (Join-Path $root 'backend')
try {
    & java -jar build\libs\backend-0.0.1-SNAPSHOT.jar '--spring.profiles.active=sharing' "--soomgil.public-origin=$publicOrigin"
    if ($LASTEXITCODE -ne 0) { throw 'Sharing backend stopped with an error' }
} finally { Pop-Location }
