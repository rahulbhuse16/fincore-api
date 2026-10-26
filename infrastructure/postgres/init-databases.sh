#!/bin/bash
# Runs once on first start of the postgres container. One database AND one login role per service:
# a service can only reach its own data (database-per-service, enforced by credentials, not just by convention).
set -euo pipefail
for svc in identity customer account transaction ledger risk notification reconciliation; do
  pw_var="$(echo "${svc}" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  pw="${!pw_var:?missing ${pw_var}}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pw="$pw" <<SQL
CREATE ROLE ${svc}_user LOGIN PASSWORD :'pw';
CREATE DATABASE ${svc}_db OWNER ${svc}_user;
REVOKE ALL ON DATABASE ${svc}_db FROM PUBLIC;
SQL
done
