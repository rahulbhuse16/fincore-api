#!/usr/bin/env bash
# Shared helpers for the demo scripts. Requires: curl, jq, and a running stack (docker compose up -d --build).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
command -v jq >/dev/null || { echo "jq is required (brew install jq / apt install jq)"; exit 1; }
[ -f .env ] || { echo "Run scripts/init-env.sh first"; exit 1; }
set -a; . ./.env; set +a
BASE="${BASE:-http://localhost:8080}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
rnd() { openssl rand -hex 4; }

# login USER PASS -> prints access token
login() {
  curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg u "$1" --arg p "$2" '{username:$u,password:$p}')" | jq -r .accessToken
}

# register_customer PREFIX -> exports <PREFIX>_TOKEN, <PREFIX>_CUSTOMER, <PREFIX>_ACCOUNT (INR account, KYC verified)
register_customer() {
  local p="$1" name pass reg token
  name="$(echo "$p" | tr '[:upper:]' '[:lower:]')_$(rnd)"
  pass="Aa1!$(openssl rand -hex 8)"
  reg="$(curl -fsS -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
      -d "$(jq -n --arg u "$name" --arg e "$name@example.test" --arg p "$pass" '{username:$u,email:$e,password:$p}')")"
  token="$(login "$name" "$pass")"
  local cid; cid="$(echo "$reg" | jq -r .customerId)"
  curl -fsS -X POST "$BASE/api/v1/customers" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg n "$name" --arg e "$name@example.test" '{fullName:$n,email:$e,phone:"+919876543210",dateOfBirth:"1990-05-01",documentType:"PASSPORT",documentNumber:"ABCDE1234F"}')" >/dev/null
  local acct; acct="$(curl -fsS -X POST "$BASE/api/v1/accounts" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d '{"currency":"INR"}' | jq -r .accountId)"
  eval "${p}_TOKEN='$token'; ${p}_CUSTOMER='$cid'; ${p}_ACCOUNT='$acct'"
  # the ledger learns about the account asynchronously (account.opened event): wait for it
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/accounts/$acct/balance" -H "Authorization: Bearer $token")"
    [ "$code" = 200 ] && return 0; sleep 1
  done
  echo "ledger account never appeared for $acct" >&2; return 1
}

admin_token() { login admin "$BOOTSTRAP_ADMIN_PASSWORD"; }

# deposit ADMIN_TOKEN ACCOUNT AMOUNT
deposit() {
  curl -fsS -X POST "$BASE/api/v1/ledger/deposits" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg r "$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)" --arg a "$2" --arg m "$3" '{reference:$r,accountId:$a,amount:($m|tonumber),currency:"INR"}')" >/dev/null
}

# transfer TOKEN KEY SRC DST AMOUNT [DESCRIPTION] -> prints "HTTP_CODE BODY"
transfer() {
  curl -sS -X POST "$BASE/api/v1/transfers" -H "Authorization: Bearer $1" -H "Idempotency-Key: $2" -H 'Content-Type: application/json' \
    -w ' HTTP:%{http_code}' -D /tmp/fincore-headers.$$ \
    -d "$(jq -n --arg s "$3" --arg d "$4" --arg a "$5" --arg n "${6:-demo}" '{sourceAccountId:$s,destinationAccountId:$d,amount:($a|tonumber),currency:"INR",description:$n}')"
}

# wait_status TOKEN TRANSFER_ID STATUS [SECONDS]
wait_status() {
  local end=$((SECONDS + ${4:-60})) s
  while [ $SECONDS -lt $end ]; do
    s="$(curl -fsS "$BASE/api/v1/transfers/$2" -H "Authorization: Bearer $1" | jq -r .status)"
    [ "$s" = "$3" ] && { echo "$s"; return 0; }; sleep 1
  done
  echo "timeout waiting for $3 (last: $s)"; return 1
}

balance() { curl -fsS "$BASE/api/v1/accounts/$2/balance" -H "Authorization: Bearer $1" | jq -c '{ledgerBalance,availableBalance,heldAmount}'; }
