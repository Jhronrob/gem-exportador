# ============================================================
# run-dev.ps1 — Inicia o Gem Exportador em modo VIEWER
# Conecta ao app servidor em 192.168.2.121:8080.
# Sempre sobrescreve o .env para garantir modo viewer.
# ============================================================

$ErrorActionPreference = "Stop"

$configDir = "C:\gem-exportador"
$envFile   = "$configDir\.env"

if (-not (Test-Path $configDir)) {
    New-Item -ItemType Directory -Path $configDir | Out-Null
}
if (-not (Test-Path "$configDir\logs")) {
    New-Item -ItemType Directory -Path "$configDir\logs" | Out-Null
}

# Sempre sobrescreve o .env para modo viewer
$envContent = @"
GEM_MODE=viewer
SERVER_URL=http://192.168.2.121:8080
LOG_LEVEL=INFO
LOG_DIR=C:\gem-exportador\logs
"@

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($envFile, $envContent, $utf8NoBom)

Write-Host ""
Write-Host "[dev-viewer] Modo: VIEWER"
Write-Host "[dev-viewer] Servidor: http://192.168.2.121:8080"
Write-Host ""

.\gradlew.bat :desktopApp:run
