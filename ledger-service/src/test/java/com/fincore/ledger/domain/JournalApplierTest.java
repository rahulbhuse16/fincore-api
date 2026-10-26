package com.fincore.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JournalApplierTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static AccountState customer(UUID id, String balance) {
        return new AccountState(id, AccountKind.LIABILITY, AccountStatus.ACTIVE, "INR",
                new AccountBalances(d(balance), d(balance)));
    }

    @Test
    void reservedTransferMovesMoneyAndSettlesTheHold() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AccountState source = customer(a, "5000").withBalances(customer(a, "5000").balances().reserve(d("1000")));
        Map<UUID, AccountState> accounts = Map.of(a, source, b, customer(b, "200"));

        PostingResult r = JournalApplier.apply(JournalDraft.transfer(UUID.randomUUID(), a, b, Money.of("1000", "INR")),
                accounts, Set.of(a));

        assertEquals(0, r.balancesAfter().get(a).ledger().compareTo(d("4000")));
        assertEquals(0, r.balancesAfter().get(a).available().compareTo(d("4000")));
        assertEquals(0, r.balancesAfter().get(b).ledger().compareTo(d("1200")));
        assertEquals(0, r.balancesAfter().get(b).available().compareTo(d("1200")));
        assertEquals(2, r.lines().size());
    }

    @Test
    void depositIncreasesBothTheAssetAndTheLiabilityAccount() {
        UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
        AccountState cashAcc = new AccountState(cash, AccountKind.ASSET, AccountStatus.ACTIVE, "INR", AccountBalances.zero());
        PostingResult r = JournalApplier.apply(JournalDraft.deposit(UUID.randomUUID(), cash, cust, Money.of("2500", "INR")),
                Map.of(cash, cashAcc, cust, customer(cust, "0")), Set.of());
        assertEquals(0, r.balancesAfter().get(cash).ledger().compareTo(d("2500")));
        assertEquals(0, r.balancesAfter().get(cust).ledger().compareTo(d("2500")));
    }

    @Test
    void reversalRestoresOriginalBalancesAndKeepsHistoryAdditive() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AccountState sa = customer(a, "5000"), sb = customer(b, "200");
        AccountState reserved = sa.withBalances(sa.balances().reserve(d("1000")));
        JournalDraft original = JournalDraft.transfer(UUID.randomUUID(), a, b, Money.of("1000", "INR"));
        PostingResult posted = JournalApplier.apply(original, Map.of(a, reserved, b, sb), Set.of(a));

        Map<UUID, AccountState> afterPost = Map.of(
                a, sa.withBalances(posted.balancesAfter().get(a)), b, sb.withBalances(posted.balancesAfter().get(b)));
        JournalDraft reversal = JournalDraft.reversalOf(UUID.randomUUID(), UUID.randomUUID(), "INR", original.lines());
        PostingResult undone = JournalApplier.apply(reversal, afterPost, Set.of());

        assertEquals(0, undone.balancesAfter().get(a).ledger().compareTo(d("5000")));
        assertEquals(0, undone.balancesAfter().get(b).ledger().compareTo(d("200")));
        assertEquals(2, original.lines().size()); // the original draft is untouched
    }

    @Test
    void unreservedDebitBeyondAvailableFundsFails() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThrows(InsufficientFundsException.class, () -> JournalApplier.apply(
                JournalDraft.transfer(UUID.randomUUID(), a, b, Money.of("100", "INR")),
                Map.of(a, customer(a, "50"), b, customer(b, "0")), Set.of()));
    }

    @Test
    void currencyMismatchBetweenAccountAndLineIsRejected() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AccountState usd = new AccountState(b, AccountKind.LIABILITY, AccountStatus.ACTIVE, "USD", AccountBalances.zero());
        assertThrows(IllegalArgumentException.class, () -> JournalApplier.apply(
                JournalDraft.transfer(UUID.randomUUID(), a, b, Money.of("10", "INR")),
                Map.of(a, customer(a, "50"), b, usd), Set.of()));
    }

    /**
     * Financial invariant: over thousands of random reserve/post/release/reverse sequences, the total of all ledger
     * balances never changes (money is only moved, never created or destroyed), no balance goes negative,
     * and available never exceeds ledger.
     */
    @Test
    void randomisedTransfersConserveMoneyAndNeverOverspend() {
        Random rnd = new Random(42);
        UUID[] ids = new UUID[5];
        Map<UUID, AccountState> accounts = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
            accounts.put(ids[i], customer(ids[i], "1000"));
        }
        BigDecimal total = d("5000");

        for (int step = 0; step < 5000; step++) {
            UUID src = ids[rnd.nextInt(ids.length)], dst = ids[rnd.nextInt(ids.length)];
            if (src.equals(dst)) continue;
            BigDecimal amount = BigDecimal.valueOf(1 + rnd.nextInt(600)).add(BigDecimal.valueOf(rnd.nextInt(100), 2));
            AccountState s = accounts.get(src), t = accounts.get(dst);
            Money m = new Money(amount, "INR");
            if (PostingRules.reserveRejection(s, t, m).isPresent()) {
                assertThrows(InsufficientFundsException.class, () -> s.balances().reserve(amount));
                continue;
            }
            AccountState reserved = s.withBalances(s.balances().reserve(amount));
            Map<UUID, AccountState> view = new HashMap<>(accounts);
            view.put(src, reserved);
            JournalDraft j = JournalDraft.transfer(UUID.randomUUID(), src, dst, m);
            PostingResult r = JournalApplier.apply(j, view, Set.of(src));
            r.balancesAfter().forEach((id, b) -> accounts.put(id, accounts.get(id).withBalances(b)));

            if (rnd.nextInt(5) == 0) { // reverse some of them, when the destination can afford it
                if (PostingRules.reversalRejection(accounts.get(src), accounts.get(dst), amount).isEmpty()) {
                    PostingResult rev = JournalApplier.apply(
                            JournalDraft.reversalOf(UUID.randomUUID(), UUID.randomUUID(), "INR", j.lines()), accounts, Set.of());
                    rev.balancesAfter().forEach((id, b) -> accounts.put(id, accounts.get(id).withBalances(b)));
                }
            }
            BigDecimal sum = accounts.values().stream().map(a -> a.balances().ledger()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, sum.compareTo(total), "money must be conserved at step " + step);
            accounts.values().forEach(a -> {
                assertTrue(a.balances().available().signum() >= 0);
                assertTrue(a.balances().available().compareTo(a.balances().ledger()) <= 0);
            });
        }
    }
}
