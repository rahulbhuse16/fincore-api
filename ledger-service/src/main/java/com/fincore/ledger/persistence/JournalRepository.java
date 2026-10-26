package com.fincore.ledger.persistence;

import com.fincore.ledger.domain.JournalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<JournalEntity, UUID> {
    Optional<JournalEntity> findByReferenceAndJournalType(UUID reference, JournalType type);
}
