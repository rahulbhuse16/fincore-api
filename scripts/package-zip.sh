#!/usr/bin/env bash
# Packages the repository (without build output, .env or local caches) into fincore-financial-microservices.zip
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-../fincore-financial-microservices.zip}"
rm -f "$OUT"
cd ..
zip -r -q "$OUT" "$(basename "$OLDPWD")" -x '*/target/*' '*/.env' '*/.verify/*' '*.zip' '*/.git/*' '*/.idea/*' '*/node_modules/*'
echo "Created $OUT"
