package com.fincore.transaction.application;

import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TransferQueryService {
    private final TransferRepository transfers;

    public TransferQueryService(TransferRepository transfers) {
        this.transfers = transfers;
    }

    /** Visible to the initiator, the receiving customer, and ADMIN. Anyone else gets 404. */
    public TransferEntity get(UUID id, CurrentUser user) {
        TransferEntity t = transfers.findById(id).orElseThrow(() -> ApiException.notFound("transfer-not-found", "Transfer not found."));
        boolean allowed = user.isAdmin() || user.isService() || (user.customerId() != null
                && (user.customerId().equals(t.getCustomerId()) || user.customerId().equals(t.getDestinationCustomerId())));
        if (!allowed) {
            throw ApiException.notFound("transfer-not-found", "Transfer not found.");
        }
        return t;
    }

    public Page<TransferEntity> forAccount(UUID accountId, CurrentUser user, Pageable pageable) {
        if (user.isAdmin()) {
            return transfers.findForAccount(accountId, pageable);
        }
        if (user.customerId() == null) {
            throw ApiException.forbidden("customer-profile-required", "Your user is not linked to a customer profile.");
        }
        return transfers.findForCustomerAccount(accountId, user.customerId(), pageable);
    }
}
