package com.fincore.risk.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessmentEntity, UUID> {
    Optional<RiskAssessmentEntity> findByReferenceId(UUID referenceId);

    /** Rejected attempts do not count: they moved no money. */
    @Query("select count(r) from RiskAssessmentEntity r where r.sourceAccountId = :src and r.createdAt >= :since "
            + "and r.decision <> com.fincore.risk.domain.Decision.REJECT")
    int countSince(@Param("src") UUID source, @Param("since") Instant since);

    @Query("select coalesce(sum(r.amount), 0) from RiskAssessmentEntity r where r.sourceAccountId = :src "
            + "and r.createdAt >= :since and r.decision <> com.fincore.risk.domain.Decision.REJECT")
    BigDecimal sumSince(@Param("src") UUID source, @Param("since") Instant since);

    @Query("select count(r) from RiskAssessmentEntity r where r.sourceAccountId = :src "
            + "and r.destinationAccountId = :dst and r.amount = :amount and r.createdAt >= :since "
            + "and r.decision <> com.fincore.risk.domain.Decision.REJECT")
    int countIdenticalSince(@Param("src") UUID source, @Param("dst") UUID destination,
                            @Param("amount") BigDecimal amount, @Param("since") Instant since);
}
