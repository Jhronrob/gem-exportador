#!/usr/bin/env bash
# =============================================================================
# seed-dev.sh - Aplica o seed de desenvolvimento no banco local
# Alternativa ao endpoint POST /api/dev/seed (não precisa de servidor rodando)
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env.dev"

if [ ! -f "$ENV_FILE" ]; then
  echo "[seed-dev] Arquivo .env.dev não encontrado em $SCRIPT_DIR"
  exit 1
fi

# Carrega variáveis do .env.dev
set -a
# shellcheck source=/dev/null
source <(grep -v '^#' "$ENV_FILE" | grep -v '^$')
set +a

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-gem_dev}"
DB_USER="${DB_USER:-admin}"
DB_PASSWORD="${DB_PASSWORD:-}"

# Adiciona postgresql@16 do homebrew ao PATH se não estiver lá
export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"

if ! command -v psql &>/dev/null; then
  echo "[seed-dev] psql não encontrado. Instale com: brew install postgresql@16"
  exit 1
fi

echo ""
echo "======================================================"
echo " GEM Exportador - Seed Dev"
echo "======================================================"
echo " Host  : $DB_HOST:$DB_PORT/$DB_NAME"
echo " User  : $DB_USER"
echo "======================================================"
echo ""

# Monta a string de conexão
if [ -n "$DB_PASSWORD" ]; then
  export PGPASSWORD="$DB_PASSWORD"
  CONN="postgresql://$DB_USER:$DB_PASSWORD@$DB_HOST:$DB_PORT/$DB_NAME"
else
  CONN="postgresql://$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
fi

if [ "${DB_SSL_MODE}" = "require" ]; then
  CONN="${CONN}?sslmode=require"
fi

echo "[seed-dev] Aplicando seed-dev.sql..."
psql "$CONN" -f "$SCRIPT_DIR/seed-dev.sql" -v ON_ERROR_STOP=1

echo ""
echo "[seed-dev] ✓ Seed aplicado com sucesso!"
echo "[seed-dev] Registros por status:"
psql "$CONN" -c "SELECT status, count(*) FROM desenho GROUP BY status ORDER BY count(*) DESC;"
echo ""
