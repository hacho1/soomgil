$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'OpsMonitoring.psm1') -Force
$state = $null
foreach ($attempt in 1..2) {
    $state = Update-SoomgilIncident -Previous $state -Problems @('backend') -Now $attempt
    if ($state.event) { throw 'Alert fired too early.' }
}
$state = Update-SoomgilIncident -Previous $state -Problems @('backend') -Now 3
if ($state.event -ne 'down' -or -not $state.incident) { throw 'Missing outage event.' }
$state = Update-SoomgilIncident -Previous $state -Problems @('backend') -Now 4
if ($state.event) { throw 'Repeated outage notification.' }
$saved = $state | ConvertTo-Json | ConvertFrom-Json
$state = Update-SoomgilIncident -Previous $saved -Problems @() -Now 5
if ($state.event -ne 'recovered' -or $state.incident) { throw 'Recovery did not survive a monitor restart.' }
$state = Update-SoomgilIncident -Previous $state -Problems @() -Now 6
if ($state.event) { throw 'Repeated recovery notification.' }
if (Send-SoomgilAlert -Webhook 'http://example.invalid/secret' -Event 'down') { throw 'Insecure webhook accepted.' }
if (Send-SoomgilAlert -Webhook '' -Event 'down') { throw 'Unconfigured webhook reported success.' }
'PASS: threshold, incident persistence, recovery, duplicate suppression, webhook guards (8 checks)'
