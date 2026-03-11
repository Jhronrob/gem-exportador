# ============================================================
# run-dev-server.ps1 — Inicia o Gem Exportador em modo SERVIDOR
# Sobe o backend (Ktor) + frontend (Compose Desktop) juntos.
# Sempre sobrescreve o .env para garantir modo server.
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
if (-not (Test-Path "$configDir\controle")) {
    New-Item -ItemType Directory -Path "$configDir\controle" | Out-Null
}

# Sempre sobrescreve o .env para modo servidor
$envContent = @"
GEM_MODE=server
SERVER_HOST=0.0.0.0
SERVER_PORT=8080
SERVER_URL=http://localhost:8080
INVENTOR_PASTA_CONTROLE=C:\gem-exportador\controle
LOG_LEVEL=INFO
LOG_DIR=C:\gem-exportador\logs

# PostgreSQL KSI (producao)
DB_HOST=192.168.1.152
DB_PORT=5432
DB_NAME=gem_jhonrob
DB_USER=ksi
DB_PASSWORD=ksi

SUPABASE_BACKUP_ENABLED=false
"@

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($envFile, $envContent, $utf8NoBom)

Write-Host ""
Write-Host "[dev-server] Modo: SERVIDOR"
Write-Host "[dev-server] Banco: 192.168.1.152:5432/gem_jhonrob"
Write-Host "[dev-server] API:   http://localhost:8080"
Write-Host ""

.\gradlew.bat :desktopApp:run
