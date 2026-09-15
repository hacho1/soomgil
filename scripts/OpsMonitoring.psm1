Set-StrictMode -Version Latest

function Get-SoomgilHealth {
    param([string]$BaseUrl, [string]$BackupDirectory = '')
    $problems = @()
    try {
        $response = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + '/api/health') -TimeoutSec 5
        if ($response.ok -ne $true) { $problems += 'backend' }
    } catch { $problems += 'backend' }
    if ($BackupDirectory) {
        try {
            $root = [IO.Path]::GetFullPath($BackupDirectory)
            $latest = Get-ChildItem -LiteralPath $root -File -Filter 'soomgil-db-*.zip.json' | Sort-Object Name -Descending | Select-Object -First 1
            if ($null -eq $latest) { throw 'No backup' }
            $record = Get-Content -LiteralPath $latest.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($record.format -ne 1 -or $record.verified -ne $true -or $record.file -notmatch '^soomgil-db-\d{8}T\d{6,9}Z-[a-f0-9]{8}\.zip$') { throw 'Invalid record' }
            $archive = [IO.Path]::GetFullPath((Join-Path $root $record.file))
            if ([IO.Path]::GetDirectoryName($archive) -ne $root -or -not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw 'Missing archive' }
            $age = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - [long]$record.completedAt
            if ($age -lt -300000 -or $age -gt 93600000) { throw 'Stale backup' }
            if ($record.replicaState -eq 'failed') { $problems += 'backup-replica' }
        } catch { $problems += 'backup-stale' }
    }
    return $problems
}

function Update-SoomgilIncident {
    param($Previous, [string[]]$Problems, [long]$Now)
    $failures = 0; $incident = $false
    if ($Previous) { $failures = [int]$Previous.failures; $incident = [bool]$Previous.incident }
    $event = ''
    if ($Problems.Count) {
        $failures = [Math]::Min(1000, $failures + 1)
        if ($failures -ge 3 -and -not $incident) { $incident = $true; $event = 'down' }
    } else {
        $failures = 0
        if ($incident) { $incident = $false; $event = 'recovered' }
    }
    return [pscustomobject]@{ failures=$failures; incident=$incident; problems=@($Problems); checkedAt=$Now; event=$event }
}

function Send-SoomgilAlert {
    param([string]$Webhook, [string]$Event)
    if (-not $Webhook) { return $false }
    try {
        $uri = [Uri]$Webhook
        if ($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Fragment) { return $false }
        $message = if ($Event -eq 'down') { 'Soomgil: service or database backup needs attention.' } else { 'Soomgil: service and monitored backup checks recovered.' }
        $body = @{ text=$message; content=$message } | ConvertTo-Json -Compress
        Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 5 | Out-Null
        return $true
    } catch { return $false }
}
Export-ModuleMember -Function Get-SoomgilHealth, Update-SoomgilIncident, Send-SoomgilAlert
