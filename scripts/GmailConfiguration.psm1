function Update-SoomgilGmailText {
    param([AllowEmptyString()][string]$Content, [string]$Sender, [string]$AppPassword)
    $Sender = $Sender.Trim().ToLowerInvariant()
    $password = $AppPassword -replace '\s', ''
    if ($Sender -notmatch '^[a-z0-9][a-z0-9._+%-]*@gmail\.com$') {
        throw 'Enter a valid Gmail sender address.'
    }
    if ($password -cnotmatch '^[A-Za-z0-9]{16}$') {
        throw 'Enter the 16-character app password, not the Google account password.'
    }
    $settings = [ordered]@{
        SOOMGIL_SMTP_HOST = 'smtp.gmail.com'
        SOOMGIL_SMTP_PORT = '587'
        SOOMGIL_SMTP_USERNAME = $Sender
        SOOMGIL_SMTP_FROM = $Sender
        SOOMGIL_SMTP_PASSWORD = $password
    }
    $newline = if ($Content.Contains("`r`n")) { "`r`n" } else { "`n" }
    foreach ($key in $settings.Keys) {
        $pattern = '(?m)^[ \t]*' + [regex]::Escape($key) + '[ \t]*=[^\r\n]*'
        $matches = [regex]::Matches($Content, $pattern)
        if ($matches.Count -gt 1) { throw 'Duplicate SMTP settings found. Review the private configuration first.' }
        $replacement = "$key=$($settings[$key])"
        if ($matches.Count -eq 1) {
            $Content = [regex]::Replace($Content, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $replacement })
        } else {
            if ($Content.Length -gt 0 -and -not $Content.EndsWith("`n")) { $Content += $newline }
            $Content += $replacement + $newline
        }
    }
    return $Content
}
Export-ModuleMember -Function Update-SoomgilGmailText
