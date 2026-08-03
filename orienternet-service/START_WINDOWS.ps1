$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Python = $null
foreach ($candidate in @(".venv\Scripts\python.exe", "venv\Scripts\python.exe")) {
    if (Test-Path $candidate) { $Python = (Resolve-Path $candidate).Path; break }
}
if (-not $Python) {
    throw "Virtual environment not found. Run INSTALL_WINDOWS.bat first."
}

$EnvFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
        $name, $value = $trimmed.Split("=", 2)
        [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
    }
}

if (-not $env:ORIENTERNET_HOST) { $env:ORIENTERNET_HOST = "0.0.0.0" }
if (-not $env:ORIENTERNET_PORT) { $env:ORIENTERNET_PORT = "1000" }
if (-not $env:ORIENTERNET_CACHE) { $env:ORIENTERNET_CACHE = Join-Path $PSScriptRoot "cache" }

Write-Host "Checking environment..." -ForegroundColor Cyan
& $Python VERIFY_WINDOWS.py
if ($LASTEXITCODE -ne 0) { throw "Dependency verification failed." }

Write-Host "`nServer: http://$($env:ORIENTERNET_HOST):$($env:ORIENTERNET_PORT)" -ForegroundColor Green
Write-Host "Health: http://127.0.0.1:$($env:ORIENTERNET_PORT)/health"
Write-Host "Stop: Ctrl+C`n"

& $Python -m uvicorn app.main:app --host $env:ORIENTERNET_HOST --port ([int]$env:ORIENTERNET_PORT)
exit $LASTEXITCODE
