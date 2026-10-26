package com.fincore.customer.application;

import com.fincore.customer.domain.KycSimulator;
import com.fincore.customer.domain.KycStatus;
import com.fincore.customer.persistence.CustomerEntity;
import com.fincore.customer.persistence.CustomerRepository;
import com.fincore.platform.audit.AuditLogger;
import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class CustomerService {
    public record CreateCommand(String fullName, String email, String phone, LocalDate dateOfBirth,
                                String documentType, String documentNumber) {}

    private final CustomerRepository customers;
    private final AuditLogger audit;

    public CustomerService(CustomerRepository customers, AuditLogger audit) {
        this.customers = customers;
        this.audit = audit;
    }

    /** The customer id is the caller's customer_id claim: a customer can only create THEIR OWN profile. */
    @Transactional
    public CustomerEntity create(CurrentUser user, CreateCommand cmd) {
        if (!user.isCustomer() || user.customerId() == null) {
            throw ApiException.forbidden("customer-role-required", "Only a registered customer user can create a customer profile.");
        }
        UUID id = user.customerId();
        if (customers.existsById(id)) {
            throw ApiException.conflict("customer-exists", "A customer profile already exists for this user.");
        }
        if (customers.existsByEmail(cmd.email().toLowerCase())) {
            throw ApiException.conflict("email-in-use", "This email is already registered.");
        }
        KycSimulator.Result kyc = KycSimulator.evaluate(cmd.dateOfBirth(), cmd.documentNumber(), LocalDate.now(ZoneOffset.UTC));
        String doc = cmd.documentNumber().trim();
        CustomerEntity saved = customers.save(new CustomerEntity(id, cmd.fullName().trim(), cmd.email().toLowerCase(),
                cmd.phone(), cmd.dateOfBirth(), cmd.documentType(), doc.substring(Math.max(0, doc.length() - 4)),
                kyc.status(), kyc.reason()));
        audit.record(user.subject(), "CUSTOMER_CREATED", "CUSTOMER", id.toString(), "SUCCESS", "kyc=" + kyc.status());
        return saved;
    }

    @Transactional(readOnly = true)
    public CustomerEntity get(UUID id, CurrentUser user) {
        if (!user.canAccessCustomer(id)) {
            throw ApiException.notFound("customer-not-found", "Customer not found.");
        }
        return customers.findById(id).orElseThrow(() -> ApiException.notFound("customer-not-found", "Customer not found."));
    }

    @Transactional
    public CustomerEntity updateKyc(UUID id, KycStatus next, String reason, String actor) {
        CustomerEntity c = customers.findById(id).orElseThrow(() -> ApiException.notFound("customer-not-found", "Customer not found."));
        KycStatus previous = c.getKycStatus();
        if (!previous.canTransitionTo(next)) {
            throw ApiException.conflict("invalid-kyc-transition", "KYC cannot move from " + previous + " to " + next + ".");
        }
        c.changeKyc(next, reason);
        audit.record(actor, "KYC_UPDATED", "CUSTOMER", id.toString(), "SUCCESS", previous + "->" + next);
        return c;
    }
}
