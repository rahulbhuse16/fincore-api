#!/usr/bin/env bash
# End-to-end demo: transfer, idempotent retry, key misuse, concurrent spending, reversal, reconciliation.
source "$(dirname "$0")/lib.sh"

say "1. Admin login, two customers (KYC-verified), one INR account each"
ADMIN="$(admin_token)"
register_customer ALICE; register_customer BOB
echo "alice account: $ALICE_ACCOUNT"; echo "bob   account: $BOB_ACCOUNT"

say "2. Admin credits Alice with INR 5000 (double-entry deposit against the cash-clearing account)"
deposit "$ADMIN" "$ALICE_ACCOUNT" 5000
echo "alice: $(balance "$ALICE_TOKEN" "$ALICE_ACCOUNT")"

say "3. Alice sends INR 1000 to Bob (Idempotency-Key: demo-$$-1)"
KEY="demo-$$-1"
out="$(transfer "$ALICE_TOKEN" "$KEY" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" 1000 "rent")"; echo "$out"
TID="$(echo "$out" | sed 's/ HTTP:.*//' | jq -r .transferId)"
echo "final status: $(wait_status "$ALICE_TOKEN" "$TID" COMPLETED)"
echo "alice: $(balance "$ALICE_TOKEN" "$ALICE_ACCOUNT")   bob: $(balance "$BOB_TOKEN" "$BOB_ACCOUNT")"

say "4. CLIENT RETRY with the same key and body -> same transfer, HTTP 200, no second debit"
transfer "$ALICE_TOKEN" "$KEY" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" 1000 "rent"; echo
grep -i idempotent-replay /tmp/fincore-headers.$$ || true
echo "alice: $(balance "$ALICE_TOKEN" "$ALICE_ACCOUNT")  (still 4000)"

say "5. Same key, DIFFERENT amount -> 422 idempotency-key-reuse"
transfer "$ALICE_TOKEN" "$KEY" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" 1001 "rent"; echo

say "6. CONCURRENT SPENDING: 4 parallel transfers (1500, 1501, 1502, 1503) against a 4000 balance -> exactly 2 can fit"
# distinct amounts and <= 5 transfers in total keep the risk engine's repeat/velocity rules out of the way
for i in 0 1 2 3; do
  ( transfer "$ALICE_TOKEN" "demo-$$-par-$i" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" $((1500 + i)) "parallel $i" | sed 's/ HTTP:.*//' | jq -r .transferId > "/tmp/par-$$-$i" ) &
done; wait
completed=0; failed=0
for i in 0 1 2 3; do
  id="$(cat /tmp/par-$$-$i)"
  for _ in $(seq 1 30); do
    s="$(curl -fsS "$BASE/api/v1/transfers/$id" -H "Authorization: Bearer $ALICE_TOKEN")"
    st="$(echo "$s" | jq -r .status)"; { [ "$st" = COMPLETED ] || [ "$st" = FAILED ]; } && break; sleep 1
  done
  [ "$st" = COMPLETED ] && completed=$((completed+1)); [ "$st" = FAILED ] && { failed=$((failed+1)); echo "  failed: $(echo "$s" | jq -r .failureReason)"; }
done
echo "completed=$completed failed=$failed  (exactly 2 completed, 2 failed with INSUFFICIENT_FUNDS)"
echo "alice: $(balance "$ALICE_TOKEN" "$ALICE_ACCOUNT")  <- never negative   bob: $(balance "$BOB_TOKEN" "$BOB_ACCOUNT")"

say "7. Transaction history (paginated)"
curl -fsS "$BASE/api/v1/accounts/$ALICE_ACCOUNT/transactions?size=3&sort=createdAt,desc" -H "Authorization: Bearer $ALICE_TOKEN" \
  | jq '{totalElements, content: [.content[] | {transferId, status, amount}]}'

say "8. Authorization: Bob cannot read Alice's account or spend from it"
curl -s -o /dev/null -w 'bob reads alice account balance -> HTTP %{http_code}\n' "$BASE/api/v1/accounts/$ALICE_ACCOUNT/balance" -H "Authorization: Bearer $BOB_TOKEN"
transfer "$BOB_TOKEN" "demo-$$-theft" "$ALICE_ACCOUNT" "$BOB_ACCOUNT" 10; echo

say "9. Admin reverses the first transfer (compensating journal; original entries stay untouched)"
curl -fsS -X POST "$BASE/api/v1/transfers/$TID/reverse" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"reason":"demo reversal"}' | jq -c '{status}'
echo "final status: $(wait_status "$ADMIN" "$TID" REVERSED)"
JOURNAL="$(curl -fsS "$BASE/api/v1/ledger/accounts/$ALICE_ACCOUNT/entries?size=50&sort=createdAt,asc" -H "Authorization: Bearer $ALICE_TOKEN" | jq -r '.content[-1].journalId')"
curl -fsS "$BASE/api/v1/ledger/journals/$JOURNAL" -H "Authorization: Bearer $ADMIN" | jq -c '{type, reversesJournalId, entries: [.entries[] | {direction, amount}]}'

say "10. Reconciliation with a synthetic settlement file (one amount mismatch, one duplicate, one unknown transfer)"
DATE="$(date -u +%F)"; BATCH="demo-$(rnd)"
curl -fsS "$BASE/api/v1/ledger/accounts/$ALICE_ACCOUNT/entries?size=1" -H "Authorization: Bearer $ADMIN" >/dev/null
OTHER="$(cat /tmp/par-$$-0)"
curl -fsS -X POST "$BASE/api/v1/reconciliation/settlement-batches" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$(jq -n \
  --arg b "$BATCH" --arg d "$DATE" --arg t "$OTHER" --arg u "$(cat /proc/sys/kernel/random/uuid)" '{batchId:$b,settlementDate:$d,lines:[
   {lineNo:1,settlementRef:"S-1",transferRef:$t,amount:999,currency:"INR",status:"SETTLED"},
   {lineNo:2,settlementRef:"S-2",transferRef:$t,amount:999,currency:"INR",status:"SETTLED"},
   {lineNo:3,settlementRef:"S-3",transferRef:$u,amount:50,currency:"INR",status:"SETTLED"}]}')" | jq -c .
RUN="$(curl -fsS -X POST "$BASE/api/v1/reconciliation/runs" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$(jq -n --arg d "$DATE" --arg b "$BATCH" '{businessDate:$d,batchId:$b}')")"
echo "$RUN" | jq -c .
echo "re-running the same (date,batch) is repeatable -> same run id, no duplicate exceptions:"
curl -fsS -X POST "$BASE/api/v1/reconciliation/runs" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$(jq -n --arg d "$DATE" --arg b "$BATCH" '{businessDate:$d,batchId:$b}')" | jq -c '{runId, exceptionCount}'
curl -fsS "$BASE/api/v1/reconciliation/exceptions?runId=$(echo "$RUN" | jq -r .runId)&size=100" -H "Authorization: Bearer $ADMIN" \
  | jq -r '.content | group_by(.type)[] | "\(.[0].type): \(length)"'
echo; echo "Done."
