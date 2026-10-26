# Financial invariants and how each one is enforced

| # | Invariant | Application level | Database level | Test |
|---|---|---|---|---|
| 1 | Every journal balances (sum debits = sum credits, >= 2 lines, one currency) | `JournalDraft` constructor throws | **deferred constraint trigger** `assert_journal_balanced` at COMMIT; composite FK keeps entry currency = account currency | `JournalDraftTest`, `LedgerIT.databaseRejectsAnUnbalancedJournalAtCommit` |
| 2 | Posted history is immutable | `@Immutable` entities, no update code paths | **triggers** forbid UPDATE / DELETE / TRUNCATE on `ledger_entries` and `journal_transactions` | `LedgerIT.postedEntriesCannotBeUpdatedOrDeleted` |
| 3 | No overspending: `0 <= available <= ledger` | `AccountBalances` makes invalid states unrepresentable | `CHECK` constraints on `ledger_accounts` | `AccountBalancesTest`, `LedgerIT.checkConstraints...` |
| 4 | Concurrent spenders are serialised | `SELECT ... FOR UPDATE ORDER BY id` before reading balances | row locks, ordered to avoid deadlocks | `LedgerIT.concurrentReservationsCanNeverOverspendTheAccount` |
| 5 | One financial effect per transfer, however often a command is delivered | commands keyed by `transfer_ref`; consumer dedupe on `event_id` | `UNIQUE(transfer_ref)` on holds, `UNIQUE(reference, journal_type)` on journals | `LedgerIT.duplicateCommands...` |
| 6 | Money is conserved | `JournalApplier` only moves value between accounts | - | `JournalApplierTest.randomisedTransfers...` (5000 random steps), `LedgerIT.everyPostedJournalBalances...` |
| 7 | Corrections never rewrite history | reversal = new linked journal with flipped lines | `UNIQUE(reverses_journal_id)`: a journal can be reversed once | `LedgerIT.reversalAddsACompensatingJournal...` |
| 8 | Amounts are exact | `BigDecimal` + `Money` (scale = currency minor unit; excess precision rejected, never silently rounded) | `NUMERIC(19,4)`, no floating point anywhere | `MoneyTest`, `EventCodecTest` (precision survives JSON) |
| 9 | A failed transfer neither loses nor duplicates funds | hold released in the same TX as the failure event | - | `LedgerIT`, `TransferSagaTest` |
| 10 | A state change and its event are atomic | `OutboxPublisher` is `Propagation.MANDATORY` | outbox row in the same TX | `OutboxRelayIT` |

## Ledger posting algorithm (per transfer)

```
reserve(ref, src, dst, amount):                          -- one DB transaction
  lock src, dst rows ORDER BY id (FOR UPDATE)
  if hold(ref) exists: re-emit its stored outcome; return   -- idempotent
  if rules reject (not found, currency, status, available < amount): store hold REJECTED; emit funds.rejected; return
  src.available -= amount                                  -- ledger balance unchanged
  store hold(ref) = HELD; emit funds.reserved

post(ref):                                               -- one DB transaction
  lock hold(ref); if POSTED: re-emit completed; if RELEASED/REJECTED: emit failed
  lock src, dst rows ORDER BY id
  if dst CLOSED: src.available += amount; hold = RELEASED; emit posting.failed; return
  insert journal(TRANSFER, ref); insert DEBIT src, CREDIT dst            -- trigger checks balance at COMMIT
  src.ledger -= amount (available was already reduced); dst.ledger += amount; dst.available += amount
  hold = POSTED; emit posting.completed

reverse(ref):                                            -- one DB transaction
  lock hold(ref); require POSTED; if reversal journal exists: re-emit completed
  lock src, dst; require dst.available >= amount
  insert journal(REVERSAL, reverses = original); entries = original lines with direction flipped
  dst.ledger/available -= amount; src.ledger/available += amount; emit reversal.completed
```

Customer accounts are *liabilities* of the bank (a CREDIT increases the balance); the system cash-clearing account is an
*asset* (a DEBIT increases it). A simulated deposit therefore debits cash and credits the customer, and
`sum(customer balances) == cash balance` holds at all times.
