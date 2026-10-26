package com.fincore.transaction.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransferHistoryRepository extends JpaRepository<TransferHistoryEntity, UUID> {
}
