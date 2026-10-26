package com.fincore.ledger.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccountEntity, UUID> {

    /**
     * SELECT ... ORDER BY id FOR UPDATE. Acquiring row locks in a deterministic (id) order means two transfers
     * touching the same pair of accounts in opposite directions cannot deadlock, and concurrent spenders of the
     * same account are serialised: the second one sees the balance AFTER the first one's reservation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccountEntity a where a.id in :ids order by a.id")
    List<LedgerAccountEntity> lockAllByIdIn(@Param("ids") Collection<UUID> ids);
}
