package com.fincore.ledger.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<EntryEntity, UUID> {
    Page<EntryEntity> findByAccountId(UUID accountId, Pageable pageable);

    List<EntryEntity> findByJournalIdOrderByDirectionAscCreatedAtAsc(UUID journalId);
}
