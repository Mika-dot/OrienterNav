param([switch]$Warmup)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$EnvFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
        $name, $value = $trimmed.Split("=", 2)
        [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
    }
}
if (-not $env:ORIENTERNET_PORT) { $env:ORIENTERNET_PORT = "1000" }
$BaseUrl = "http://127.0.0.1:$($env:ORIENTERNET_PORT)"
$Headers = @{}
if ($env:ORIENTERNET_API_KEY) { $Headers["X-API-Key"] = $env:ORIENTERNET_API_KEY }

Write-Host "Health check: $BaseUrl/health" -ForegroundColor Cyan
$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/health" -TimeoutSec 15
$health | ConvertTo-Json -Depth 5

if ($Warmup) {
    Write-Host "`nLoading model..." -ForegroundColor Cyan
    $result = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/warmup" -Headers $Headers -TimeoutSec 300
    $result | ConvertTo-Json -Depth 5
}
