param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$BackupDirectory = '',
    [string]$StateDirectory = (Join-Path $PSScriptRoot '..\runtime\monitor'),
    [ValidateRange(5,3600)][int]$IntervalSeconds = 30,
    [switch]$Once
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'OpsMonitoring.psm1') -Force
$url = [Uri]$BaseUrl
if ($url.Scheme -notin @('http','https') -or $url.UserInfo -or $url.Query -or $url.Fragment -or $url.AbsolutePath -ne '/') { throw 'BaseUrl must be a service origin without credentials or a path.' }
if ($url.Scheme -eq 'http' -and -not $url.IsLoopback) { throw 'Remote monitoring requires HTTPS.' }
$root = [IO.Path]::GetFullPath($StateDirectory)
New-Item -ItemType Directory -Path $root -Force | Out-Null
$statePath = Join-Path $root 'state.json'
$logPath = Join-Path $root 'alerts.jsonl'
$lock = $null
try {
    $lock = [IO.File]::Open((Join-Path $root '.monitor.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $previous = $null; $pending = ''; $lastAttempt = 0L
    if (Test-Path -LiteralPath $statePath) {
        try { $previous = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    if ($previous -and $previous.PSObject.Properties.Name -contains 'pendingEvent') { $pending = [string]$previous.pendingEvent }
    do {
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $problems = @(Get-SoomgilHealth -BaseUrl $BaseUrl -BackupDirectory $BackupDirectory)
        $state = Update-SoomgilIncident -Previous $previous -Problems $problems -Now $now
        if ($state.event) {
            if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 1048576) {
                Move-Item -LiteralPath $logPath -Destination (Join-Path $root ('alerts-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.jsonl'))
            }
            $state | ConvertTo-Json -Compress | Add-Content -LiteralPath $logPath -Encoding UTF8
            $pending = $state.event; $lastAttempt = 0L
        }
        if ($pending -and $env:SOOMGIL_OPS_ALERT_WEBHOOK -and $now - $lastAttempt -ge 300000) {
            $lastAttempt = $now
            if (Send-SoomgilAlert -Webhook $env:SOOMGIL_OPS_ALERT_WEBHOOK -Event $pending) { $pending = '' }
        }
        $state | Add-Member -NotePropertyName remoteAlertConfigured -NotePropertyValue ([bool]$env:SOOMGIL_OPS_ALERT_WEBHOOK)
        $state | Add-Member -NotePropertyName remoteAlertPending -NotePropertyValue ([bool]($pending -and $env:SOOMGIL_OPS_ALERT_WEBHOOK))
        $state | Add-Member -NotePropertyName pendingEvent -NotePropertyValue $pending
        $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($statePath + '.partial') -Encoding UTF8
        Move-Item -LiteralPath ($statePath + '.partial') -Destination $statePath -Force
        $previous = $state
        if ($Once) { $state | ConvertTo-Json -Compress; break }
        Start-Sleep -Seconds $IntervalSeconds
    } while ($true)
} finally { if ($null -ne $lock) { $lock.Dispose() } }
