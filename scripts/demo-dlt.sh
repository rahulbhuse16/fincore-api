#!/usr/bin/env bash
# Retry + dead-letter demonstration. Needs NOTIFICATION_FAILURE_SIMULATION=true in .env (scripts/init-env.sh sets it).
source "$(dirname "$0")/lib.sh"
[ "${NOTIFICATION_FAILURE_SIMULATION:-false}" = true ] || { echo "Set NOTIFICATION_FAILURE_SIMULATION=true in .env and restart notification-service"; exit 1; }
DC="docker compose"

ADMIN="$(admin_token)"; register_customer ALICE; register_customer BOB; deposit "$ADMIN" "$ALICE_ACCOUNT" 500

say "1. A transfer whose notification delivery is made to fail permanently ('[simulate-notification-failure]')"
out="$(transfer "$ALICE_TOKEN" "dlt-$(rnd)-0001" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" 100 "[simulate-notification-failure] dlt demo")"
TID="$(echo "$out" | sed 's/ HTTP:.*//' | jq -r .transferId)"
echo "transfer status (money is NOT affected by the failing notification): $(wait_status "$ALICE_TOKEN" "$TID" COMPLETED)"
echo "alice: $(balance "$ALICE_TOKEN" "$ALICE_ACCOUNT")  bob: $(balance "$BOB_TOKEN" "$BOB_ACCOUNT")"

say "2. notification-service retries with exponential backoff (500ms, 1s, 2s), then dead-letters"
sleep 8
$DC logs --no-color notification-service 2>&1 | grep -i -E "Simulated channel failure|DeadLetter|Backoff|Retry" | tail -8 || true

say "3. The message on transfer.completed.DLT (headers explain the failure)"
$DC exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic transfer.completed.DLT --from-beginning --timeout-ms 15000 --property print.key=true --property print.headers=true 2>/dev/null | head -3 | cut -c1-600

say "4. A malformed message is dead-lettered immediately (no retries: it can never succeed)"
echo 'poison-key:{ this is not json' | $DC exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic transfer.completed --property parse.key=true --property key.separator=: 
sleep 3
$DC exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic transfer.completed.DLT \
  --from-beginning --timeout-ms 10000 --property print.key=true 2>/dev/null | grep poison-key || echo "(not found yet - check logs)"

say "5. Metrics"
curl -s http://localhost:8087/actuator/prometheus | grep -E "^fincore_kafka_(dlt_published|consumer_retries)" || true
