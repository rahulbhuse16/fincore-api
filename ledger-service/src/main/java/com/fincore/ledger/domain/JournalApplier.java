package com.fincore.ledger.domain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a balanced journal to account balances. Pure function: no I/O, fully unit-testable.
 * The persistence layer locks the account rows first, calls this, then writes the result.
 */
public final class JournalApplier {
    private JournalApplier() {}

    /**
     * @param heldDebitAccounts accounts whose DEBIT lines were already reserved by a hold (normal transfer posting);
     *                          for those the debit settles the hold instead of consuming available funds again.
     */
    public static PostingResult apply(JournalDraft draft, Map<UUID, AccountState> accounts, Set<UUID> heldDebitAccounts) {
        Map<UUID, AccountBalances> working = new HashMap<>();
        accounts.forEach((id, state) -> working.put(id, state.balances()));
        List<PostingResult.AppliedLine> applied = new ArrayList<>();

        for (JournalLine line : draft.lines()) {
            AccountState account = accounts.get(line.accountId());
            if (account == null) {
                throw new IllegalArgumentException("unknown account " + line.accountId());
            }
            if (!account.currency().equals(line.amount().currency())) {
                throw new IllegalArgumentException("currency mismatch on account " + account.id());
            }
            AccountBalances before = working.get(account.id());
            boolean increases = account.kind().increases(line.direction());
            AccountBalances after;
            if (increases) {
                after = before.increase(line.amount().amount());
            } else if (line.direction() == Direction.DEBIT && heldDebitAccounts.contains(account.id())) {
                after = before.settleHold(line.amount().amount());
            } else {
                after = before.decrease(line.amount().amount());
            }
            working.put(account.id(), after);
            applied.add(new PostingResult.AppliedLine(line, after.ledger()));
        }
        Map<UUID, AccountBalances> changed = new LinkedHashMap<>();
        draft.lines().forEach(l -> changed.put(l.accountId(), working.get(l.accountId())));
        return new PostingResult(changed, applied);
    }
}
