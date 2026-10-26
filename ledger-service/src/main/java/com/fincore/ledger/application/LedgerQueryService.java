package com.fincore.ledger.application;

import com.fincore.ledger.persistence.EntryEntity;
import com.fincore.ledger.persistence.EntryRepository;
import com.fincore.ledger.persistence.JournalEntity;
import com.fincore.ledger.persistence.JournalRepository;
import com.fincore.ledger.persistence.LedgerAccountEntity;
import com.fincore.ledger.persistence.LedgerAccountRepository;
import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read side with customer-level authorization. 404 (not 403) is used for foreign resources to avoid account enumeration. */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {
    private final LedgerAccountRepository accounts;
    private final EntryRepository entries;
    private final JournalRepository journals;

    public LedgerQueryService(LedgerAccountRepository accounts, EntryRepository entries, JournalRepository journals) {
        this.accounts = accounts;
        this.entries = entries;
        this.journals = journals;
    }

    public LedgerAccountEntity accountFor(UUID accountId, CurrentUser user) {
        LedgerAccountEntity account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account-not-found", "Account not found"));
        if (!isOwnerOrPrivileged(account, user)) {
            throw ApiException.notFound("account-not-found", "Account not found");
        }
        return account;
    }

    public Page<EntryEntity> entries(UUID accountId, CurrentUser user, Pageable pageable) {
        accountFor(accountId, user);
        return entries.findByAccountId(accountId, pageable);
    }

    public record JournalView(JournalEntity journal, List<EntryEntity> entries) {}

    public JournalView journal(UUID journalId, CurrentUser user) {
        JournalEntity journal = journals.findById(journalId)
                .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));
        List<EntryEntity> all = entries.findByJournalIdOrderByDirectionAscCreatedAtAsc(journalId);
        if (user.isAdmin() || user.isService()) {
            return new JournalView(journal, all);
        }
        // Customers only see the lines that touch their own accounts.
        Set<UUID> mine = accounts.findAllById(all.stream().map(EntryEntity::getAccountId).collect(Collectors.toSet())).stream()
                .filter(a -> user.customerId() != null && user.customerId().equals(a.getOwnerCustomerId()))
                .map(LedgerAccountEntity::getId).collect(Collectors.toSet());
        if (mine.isEmpty()) {
            throw ApiException.notFound("journal-not-found", "Journal not found");
        }
        return new JournalView(journal, all.stream().filter(e -> mine.contains(e.getAccountId())).toList());
    }

    private static boolean isOwnerOrPrivileged(LedgerAccountEntity account, CurrentUser user) {
        return user.isAdmin() || user.isService()
                || (user.customerId() != null && user.customerId().equals(account.getOwnerCustomerId()));
    }
}
