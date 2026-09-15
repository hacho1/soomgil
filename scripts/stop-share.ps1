$ErrorActionPreference='Stop'
$expected=(Get-Command ngrok -ErrorAction Stop).Source
$stopped=0
foreach ($process in @(Get-CimInstance Win32_Process -Filter "name = 'ngrok.exe'")) {
    # Leave every other project's tunnel unchanged.
    if ($process.ExecutablePath -eq $expected -and $process.CommandLine -match 'http://127\.0\.0\.1:18787(?:\s|$)') {
        Stop-Process -Id $process.ProcessId -ErrorAction Stop
        $stopped++
    }
}
Write-Host "Stopped $stopped Soomgil sharing tunnel(s)."
Write-Host 'To stop the backend as well, press Ctrl+C in its window. Use run-backend.cmd to return to local development.'
