package com.fincore.transaction.persistence;

import com.fincore.transaction.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<TransferEntity, UUID> {

    Optional<TransferEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Row lock so concurrent saga events for one transfer are applied one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferEntity t where t.id = :id")
    Optional<TransferEntity> lockById(@Param("id") UUID id);

    @Query("select coalesce(sum(t.amount), 0) from TransferEntity t where t.sourceAccountId = :accountId "
            + "and t.createdAt >= :since and t.status in :statuses")
    BigDecimal sumAmountSince(@Param("accountId") UUID accountId, @Param("since") Instant since,
                              @Param("statuses") Collection<TransferStatus> statuses);

    /** Transactions visible to a customer for an account: only rows where THEY own the relevant side. */
    @Query("select t from TransferEntity t where (t.sourceAccountId = :accountId and t.customerId = :customerId) "
            + "or (t.destinationAccountId = :accountId and t.destinationCustomerId = :customerId)")
    Page<TransferEntity> findForCustomerAccount(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId,
                                                Pageable pageable);

    @Query("select t from TransferEntity t where t.sourceAccountId = :accountId or t.destinationAccountId = :accountId")
    Page<TransferEntity> findForAccount(@Param("accountId") UUID accountId, Pageable pageable);

    @Query(value = "select * from transfers where status in ('INITIATED','PENDING','REVERSING') and needs_attention = false "
            + "and updated_at < :before and (last_recovery_at is null or last_recovery_at < :before) "
            + "order by updated_at limit :limit for update skip locked", nativeQuery = true)
    List<TransferEntity> lockStuck(@Param("before") Instant before, @Param("limit") int limit);

    long countByNeedsAttentionTrue();
}
