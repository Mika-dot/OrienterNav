param(
    [string]$Python = "py -3.11",
    [string]$TorchIndex = "https://download.pytorch.org/whl/cu128",
    [switch]$SkipTorch,
    [switch]$SkipExternalRepos
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$RepoRoot = Split-Path $PSScriptRoot -Parent
$VenvPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
$VenvPip = Join-Path $PSScriptRoot ".venv\Scripts\pip.exe"

function Step([string]$Text) {
    Write-Host "`n=== $Text ===" -ForegroundColor Cyan
}

function Run-PythonLauncher([string[]]$Arguments) {
    $parts = $Python -split " "
    $exe = $parts[0]
    $prefix = @()
    if ($parts.Length -gt 1) { $prefix = $parts[1..($parts.Length - 1)] }
    & $exe @prefix @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Python command failed: $Python $($Arguments -join ' ')" }
}

Step "Checking Git"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "Git is not installed or is not available in PATH. Install Git for Windows first."
}
git --version

Step "Creating Python 3.11 virtual environment"
if (-not (Test-Path $VenvPython)) {
    Run-PythonLauncher @("-m", "venv", ".venv")
}
& $VenvPython -m pip install --upgrade pip setuptools wheel

Step "Installing FastAPI and service dependencies"
& $VenvPython -m pip install -r requirements.txt -c constraints.txt

if (-not $SkipTorch) {
    Step "Installing CUDA PyTorch"
    & $VenvPython -m pip install --upgrade torch torchvision torchaudio --index-url $TorchIndex
}

Step "Installing full OrienterNet runtime"
& $VenvPython -m pip install -r requirements-orienternet.txt -c constraints.txt

if (-not $SkipExternalRepos) {
    $OrienterNetDir = Join-Path $RepoRoot "OrienterNet"
    $PerspectiveDir = Join-Path $RepoRoot "PerspectiveFields"

    Step "Cloning or updating Facebook OrienterNet"
    if (-not (Test-Path (Join-Path $OrienterNetDir ".git"))) {
        git clone https://github.com/facebookresearch/OrienterNet.git $OrienterNetDir
    } else {
        git -C $OrienterNetDir pull --ff-only
    }

    Step "Cloning or updating PerspectiveFields"
    if (-not (Test-Path (Join-Path $PerspectiveDir ".git"))) {
        git clone https://github.com/jinlinyi/PerspectiveFields.git $PerspectiveDir
    } else {
        git -C $PerspectiveDir pull --ff-only
    }

    Step "Installing PerspectiveFields and maploc as editable packages"
    & $VenvPython -m pip install -e $PerspectiveDir --no-deps
    & $VenvPython -m pip install -e $OrienterNetDir --no-deps
}

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created orienternet-service\.env. Change ORIENTERNET_API_KEY before using the phone." -ForegroundColor Yellow
}

Step "Verifying installation"
& $VenvPython VERIFY_WINDOWS.py
if ($LASTEXITCODE -ne 0) { throw "Environment verification failed." }

Write-Host "`nInstallation completed." -ForegroundColor Green
Write-Host "1. Put orienternet_mgl.ckpt in the repository root, or set ORIENTERNET_CHECKPOINT in .env."
Write-Host "2. Run START_WINDOWS.bat."
Write-Host "3. Run CHECK_WINDOWS.bat in another window."
