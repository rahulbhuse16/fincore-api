package com.fincore.ledger.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<HoldEntity, UUID> {
    Optional<HoldEntity> findByTransferRef(UUID transferRef);

    /** Row lock on the hold: serialises concurrent duplicate post/reverse commands for the same transfer. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HoldEntity h where h.transferRef = :ref")
    Optional<HoldEntity> lockByTransferRef(@Param("ref") UUID ref);
}
