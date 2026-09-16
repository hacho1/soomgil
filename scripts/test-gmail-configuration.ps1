$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'GmailConfiguration.psm1') -Force
$fixture = "# keep comment`r`nSOOMGIL_TOUR_API_KEY=unchanged-fixture`r`nSOOMGIL_SMTP_USERNAME=old@example.test`r`nSOOMGIL_SMTP_FROM=old@example.test`r`nSOOMGIL_SMTP_PASSWORD=old-fixture`r`n"
$result = Update-SoomgilGmailText -Content $fixture -Sender 'sender@gmail.com' -AppPassword 'abcd efgh ijkl mnop'
if (-not $result.Contains("# keep comment`r`nSOOMGIL_TOUR_API_KEY=unchanged-fixture`r`n")) { throw 'Unrelated configuration changed.' }
if ($result.Contains('old-fixture') -or $result.Contains('old@example.test')) { throw 'Old mail settings remain.' }
foreach ($key in @('HOST','PORT','USERNAME','FROM','PASSWORD')) {
    if ([regex]::Matches($result,"(?m)^SOOMGIL_SMTP_$key=").Count -ne 1) { throw 'SMTP key is missing or duplicated.' }
}
if (-not $result.Contains('SOOMGIL_SMTP_PASSWORD=abcdefghijklmnop')) { throw 'Grouped app password was not normalized.' }
if ($result -match '(?<!\r)\n') { throw 'Line endings changed.' }
if ((Update-SoomgilGmailText -Content $result -Sender 'sender@gmail.com' -AppPassword 'abcdefghijklmnop') -cne $result) { throw 'Update is not idempotent.' }
foreach ($password in @('','short','abcd!fghijklmnopq')) {
    $rejected = $false
    try { $null=Update-SoomgilGmailText -Content $fixture -Sender 'sender@gmail.com' -AppPassword $password } catch { $rejected=$true }
    if (-not $rejected) { throw 'Invalid app password accepted.' }
}
$rejected=$false
try { $null=Update-SoomgilGmailText -Content ($fixture+"SOOMGIL_SMTP_PASSWORD=duplicate`n") -Sender 'sender@gmail.com' -AppPassword 'abcdefghijklmnop' } catch { $rejected=$true }
if (-not $rejected) { throw 'Duplicate setting was accepted.' }
'PASS: SMTP replacement, unrelated settings, grouped password, line endings, idempotency, invalid inputs and duplicate protection.'

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('soomgil-gmail-test-' + [guid]::NewGuid().ToString('N'))
$fixtureScripts = Join-Path $fixtureRoot 'scripts'
$fixtureEnv = Join-Path $fixtureRoot '.env'
$helper = Join-Path $fixtureScripts 'configure-gmail.ps1'
$module = Join-Path $fixtureScripts 'GmailConfiguration.psm1'
try {
    $null = New-Item -ItemType Directory -Path $fixtureScripts
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'configure-gmail.ps1') -Destination $helper
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'GmailConfiguration.psm1') -Destination $module
    [IO.File]::WriteAllText($fixtureEnv, $fixture, (New-Object Text.UTF8Encoding($false)))
    $originalAcl = (Get-Acl -LiteralPath $fixtureEnv).Sddl
    function Read-Host {
        param([string]$Prompt, [switch]$AsSecureString)
        if (-not $AsSecureString) { throw 'Password input must be hidden.' }
        ConvertTo-SecureString 'abcd efgh ijkl mnop' -AsPlainText -Force
    }
    $output = (& $helper 6>&1 | Out-String)
    $expected = Update-SoomgilGmailText -Content $fixture -Sender 'soomgil0916@gmail.com' -AppPassword 'abcdefghijklmnop'
    if ($output -notmatch 'DONE:') { throw "Full fixture helper did not report success: $output" }
    if ([IO.File]::ReadAllText($fixtureEnv) -cne $expected) { throw 'Full helper did not save the expected settings.' }
    if ($output.Contains('abcdefghijklmnop') -or $output.Contains('abcd efgh ijkl mnop')) { throw 'Helper exposed the fixture password.' }
    if ((Get-Acl -LiteralPath $fixtureEnv).Sddl -cne $originalAcl) { throw 'File permissions changed.' }
    if (@(Get-ChildItem -LiteralPath $fixtureRoot -Filter '.env.smtp-*.tmp' -Force).Count) { throw 'Temporary configuration remains.' }
    'PASS: Full hidden-input helper saves atomically, preserves permissions and does not expose the password.'
} finally {
    foreach ($file in @($fixtureEnv, $helper, $module)) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
    }
    if (Test-Path -LiteralPath $fixtureScripts) { Remove-Item -LiteralPath $fixtureScripts }
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot }
}
