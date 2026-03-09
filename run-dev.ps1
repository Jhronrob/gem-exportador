# ============================================================
# run-dev.ps1 — Inicia o Gem Exportador em modo desenvolvimento
# Sobe o backend (Ktor) + frontend (Compose Desktop) juntos.
# ============================================================

$ErrorActionPreference = "Stop"

# --- Garante pasta de config e .env de dev ---
$configDir = "C:\gem-exportador"
$envFile   = "$configDir\.env"

if (-not (Test-Path $configDir)) {
    New-Item -ItemType Directory -Path $configDir | Out-Null
    Write-Host "[dev] Criado $configDir"
}

if (-not (Test-Path $envFile)) {
    Write-Host "[dev] Criando .env de desenvolvimento em $envFile"
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
    # Escreve sem BOM (dotenv-kotlin nao suporta BOM)
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($envFile, $envContent, $utf8NoBom)
} else {
    Write-Host "[dev] Usando .env existente em $envFile"
}

if (-not (Test-Path "$configDir\logs")) {
    New-Item -ItemType Directory -Path "$configDir\logs" | Out-Null
}
if (-not (Test-Path "$configDir\controle")) {
    New-Item -ItemType Directory -Path "$configDir\controle" | Out-Null
}

# --- Inicia o app (front + backend embutido) ---
Write-Host ""
Write-Host "[dev] Iniciando Gem Exportador (frontend + backend)..."
Write-Host "[dev] Banco: 192.168.1.152:5432/gem_jhonrob"
Write-Host "[dev] Servidor: http://localhost:8080"
Write-Host ""

.\gradlew.bat :desktopApp:run
