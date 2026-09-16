param([string]$Sender = 'soomgil0916@gmail.com')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'GmailConfiguration.psm1') -Force
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$path = Join-Path $root '.env'
$temporary = Join-Path $root ('.env.smtp-' + [guid]::NewGuid().ToString('N') + '.tmp')
$secret = $null
$pointer = [IntPtr]::Zero
$stage = 'reading-config'
try {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Missing or linked private configuration.'
    }
    $original = [IO.File]::ReadAllText($path)
    Write-Host "New Gmail sender: $Sender"
    Write-Host 'Only local .env mail settings will change. No server restart or email will be sent.'
    $stage = 'password-input'
    $secret = Read-Host 'Paste the NEW Gmail app password (input is hidden), then press Enter' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $stage = 'validating-input'
    $updated = Update-SoomgilGmailText -Content $original -Sender $Sender -AppPassword $plain
    $stage = 'checking-config'
    if ([IO.File]::ReadAllText($path) -cne $original) { throw 'Configuration changed during input. Try again.' }
    $stage = 'saving-config'
    New-Item -ItemType File -Path $temporary | Out-Null
    Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $path)
    [IO.File]::WriteAllText($temporary, $updated, (New-Object Text.UTF8Encoding($false)))
    # NullString prevents Windows PowerShell from passing an empty backup path.
    [IO.File]::Replace($temporary, $path, [System.Management.Automation.Language.NullString]::Value)
    Write-Host 'DONE: Gmail sender and app password saved locally. The password was not printed.'
    Write-Host 'Tell Codex this finished. Existing server and external-drive copies are unchanged.'
} catch {
    Write-Host "NOT UPDATED [$stage]: check the Gmail address, 16-character app password and local file permissions. Never paste the password into chat."
    exit 1
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    if ($null -ne $secret) { $secret.Dispose() }
    $plain = $null; $updated = $null; $original = $null
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
