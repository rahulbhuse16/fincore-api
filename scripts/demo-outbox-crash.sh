#!/usr/bin/env bash
# Broker outage: the transfer is accepted and committed, the event waits in the transactional outbox, nothing is lost.
source "$(dirname "$0")/lib.sh"
DC="docker compose"
pg() { $DC exec -T postgres psql -U "$1" -d "$2" -At -c "$3"; }

ADMIN="$(admin_token)"; register_customer ALICE; register_customer BOB; deposit "$ADMIN" "$ALICE_ACCOUNT" 500

say "1. Stop Kafka"
$DC stop kafka

say "2. Submit a transfer while the broker is DOWN: still accepted (202) because the event is written to the outbox in the same DB transaction"
out="$(transfer "$ALICE_TOKEN" "outbox-$(rnd)-0001" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" 100 "outbox demo")"; echo "$out"
TID="$(echo "$out" | sed 's/ HTTP:.*//' | jq -r .transferId)"
sleep 3
echo "outbox rows still waiting (published_at IS NULL):"
pg transaction_user transaction_db "select seq, topic, attempts, left(coalesce(last_error,''),60) from outbox_events where published_at is null"
echo "transfer status right now: $(curl -fsS "$BASE/api/v1/transfers/$TID" -H "Authorization: Bearer $ALICE_TOKEN" | jq -r .status)   (INITIATED - not falsely completed)"

say "3. Start Kafka again - the relay publishes the SAME rows, the saga continues"
$DC start kafka
echo "final status: $(wait_status "$ALICE_TOKEN" "$TID" COMPLETED 180)"
pg transaction_user transaction_db "select count(*) || ' unpublished rows remain' from outbox_events where published_at is null"
echo "alice: $(balance "$ALICE_TOKEN" "$ALICE_ACCOUNT")  bob: $(balance "$BOB_TOKEN" "$BOB_ACCOUNT")  (debited and credited exactly once)"
