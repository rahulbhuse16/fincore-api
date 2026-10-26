package com.fincore.reconciliation.persistence;

import com.fincore.reconciliation.domain.ExceptionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Repositories {
    private Repositories() {}

    public interface ExpectedRepository extends JpaRepository<ExpectedTransactionEntity, UUID> {
        List<ExpectedTransactionEntity> findByBusinessDate(LocalDate date);
    }

    public interface SettlementRepository extends JpaRepository<SettlementRecordEntity, UUID> {
        List<SettlementRecordEntity> findByBatchId(String batchId);
    }

    public interface RunRepository extends JpaRepository<RunEntity, UUID> {
        Optional<RunEntity> findByBusinessDateAndBatchId(LocalDate date, String batchId);
    }

    public interface ExceptionRepository extends JpaRepository<ExceptionEntity, UUID> {
        @Query("select e from ExceptionEntity e where (:runId is null or e.runId = :runId) and (:type is null or e.type = :type)")
        Page<ExceptionEntity> search(@Param("runId") UUID runId, @Param("type") ExceptionType type, Pageable pageable);
    }
}
