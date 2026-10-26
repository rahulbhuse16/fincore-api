#!/usr/bin/env bash
# Generates a local .env with strong random secrets (synthetic environment only). Refuses to overwrite an existing one.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { echo ".env already exists; leaving it untouched."; exit 0; }
rnd() { openssl rand -hex 16; }
{
  echo "JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
  echo "BOOTSTRAP_ADMIN_PASSWORD=Fc!A1a-$(rnd)"
  echo "POSTGRES_PASSWORD=$(rnd)"
  for s in IDENTITY CUSTOMER ACCOUNT TRANSACTION LEDGER RISK NOTIFICATION RECONCILIATION; do echo "${s}_DB_PASSWORD=$(rnd)"; done
  echo "REDIS_PASSWORD=$(rnd)"
  echo "GRAFANA_ADMIN_PASSWORD=$(rnd)"
  echo "CORS_ALLOWED_ORIGINS="
  echo "NOTIFICATION_FAILURE_SIMULATION=true"
} > .env
chmod 600 .env
echo "Created .env (mode 600). Admin login: admin / see BOOTSTRAP_ADMIN_PASSWORD in .env"
