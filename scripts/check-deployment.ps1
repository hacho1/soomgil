param([string]$ProjectRoot = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($ProjectRoot)
$java = Join-Path $root 'runtime/java/bin/java.exe'
$jar = Join-Path $root 'backend/build/libs/backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    Write-Host 'FAIL: bundled Java or backend JAR is missing. Use the complete deployment bundle.'
    exit 2
}
# The offline CLI reads configuration and file metadata only. No DB or service is started.
& $java -jar $jar --check-deployment $root
exit $LASTEXITCODE
