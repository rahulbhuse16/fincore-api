package com.fincore.account.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {
    List<AccountEntity> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    long countByCustomerId(UUID customerId);
}
