package com.fincore.account.application;

import com.fincore.account.client.CustomerClient;
import com.fincore.account.domain.AccountNumbers;
import com.fincore.account.domain.AccountStatus;
import com.fincore.account.persistence.AccountEntity;
import com.fincore.account.persistence.AccountRepository;
import com.fincore.platform.audit.AuditLogger;
import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountService {
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD");

    private final AccountRepository accounts;
    private final CustomerClient customers;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final int maxAccountsPerCustomer;
    private final BigDecimal defaultPerTransfer;
    private final BigDecimal defaultDaily;

    public AccountService(AccountRepository accounts, CustomerClient customers, OutboxPublisher outbox, AuditLogger audit,
                          org.springframework.transaction.PlatformTransactionManager tm,
                          @Value("${fincore.accounts.max-per-customer:5}") int maxAccountsPerCustomer,
                          @Value("${fincore.accounts.default-per-transfer-limit:50000}") BigDecimal defaultPerTransfer,
                          @Value("${fincore.accounts.default-daily-limit:200000}") BigDecimal defaultDaily) {
        this.accounts = accounts;
        this.customers = customers;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.maxAccountsPerCustomer = maxAccountsPerCustomer;
        this.defaultPerTransfer = defaultPerTransfer;
        this.defaultDaily = defaultDaily;
    }

    /**
     * Not @Transactional: the KYC call must happen before (and outside) the database transaction.
     * A CUSTOMER can only open accounts for themself; ADMIN may specify the customer.
     */
    public AccountEntity open(CurrentUser user, UUID requestedCustomerId, String currency) {
        UUID customerId = user.isAdmin() && requestedCustomerId != null ? requestedCustomerId : user.customerId();
        if (customerId == null || (!user.isAdmin() && requestedCustomerId != null && !requestedCustomerId.equals(user.customerId()))) {
            throw ApiException.forbidden("not-your-customer", "You can only open accounts for your own customer profile.");
        }
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw ApiException.unprocessable("unsupported-currency", "Supported currencies: " + SUPPORTED_CURRENCIES);
        }
        CustomerClient.KycView kyc = customers.kyc(customerId)
                .orElseThrow(() -> ApiException.unprocessable("customer-profile-missing", "Create a customer profile first."));
        if (!"VERIFIED".equals(kyc.kycStatus()) || !"ACTIVE".equals(kyc.status())) {
            throw ApiException.unprocessable("kyc-not-verified", "Customer must be KYC-verified and active to open an account.");
        }
        return tx.execute(status -> persistAccount(user, customerId, currency));
    }

    private AccountEntity persistAccount(CurrentUser user, UUID customerId, String currency) {
        if (accounts.countByCustomerId(customerId) >= maxAccountsPerCustomer) {
            throw ApiException.conflict("account-limit-reached", "Maximum number of accounts reached.");
        }
        try {
            AccountEntity account = accounts.saveAndFlush(new AccountEntity(UUID.randomUUID(), customerId,
                    AccountNumbers.next(), currency, defaultPerTransfer, defaultDaily));
            outbox.publish(Topics.ACCOUNT_OPENED, account.getId().toString(),
                    new Events.AccountOpened(account.getId(), customerId, currency));
            audit.record(user.subject(), "ACCOUNT_OPENED", "ACCOUNT", account.getId().toString(), "SUCCESS", currency);
            return account;
        } catch (DataIntegrityViolationException clash) {
            // account_number collision (1 in 10^10 per attempt). The failed flush poisons the transaction, so report a
            // retryable conflict; the client's retry draws a new random number.
            throw ApiException.conflict("account-number-clash", "Could not allocate an account number; retry.");
        }
    }

    @Transactional(readOnly = true)
    public AccountEntity get(UUID id, CurrentUser user) {
        AccountEntity a = accounts.findById(id).orElseThrow(() -> ApiException.notFound("account-not-found", "Account not found."));
        if (!user.canAccessCustomer(a.getCustomerId())) {
            throw ApiException.notFound("account-not-found", "Account not found.");
        }
        return a;
    }

    @Transactional(readOnly = true)
    public List<AccountEntity> listForCustomer(UUID customerId, CurrentUser user) {
        if (!user.canAccessCustomer(customerId)) {
            throw ApiException.notFound("customer-not-found", "Customer not found.");
        }
        return accounts.findByCustomerIdOrderByCreatedAtAsc(customerId);
    }

    @Transactional
    public AccountEntity changeStatus(UUID id, AccountStatus next, String actor) {
        AccountEntity a = accounts.findById(id).orElseThrow(() -> ApiException.notFound("account-not-found", "Account not found."));
        if (!a.getStatus().canTransitionTo(next)) {
            throw ApiException.conflict("invalid-account-transition", "Account cannot move from " + a.getStatus() + " to " + next + ".");
        }
        a.changeStatus(next);
        outbox.publish(Topics.ACCOUNT_STATUS_CHANGED, id.toString(), new Events.AccountStatusChanged(id, next.name()));
        audit.record(actor, "ACCOUNT_STATUS_CHANGED", "ACCOUNT", id.toString(), "SUCCESS", next.name());
        return a;
    }

    @Transactional
    public AccountEntity changeLimits(UUID id, BigDecimal perTransfer, BigDecimal daily, String actor) {
        if (daily.compareTo(perTransfer) < 0) {
            throw ApiException.unprocessable("invalid-limits", "dailyLimit must be >= perTransferLimit.");
        }
        AccountEntity a = accounts.findById(id).orElseThrow(() -> ApiException.notFound("account-not-found", "Account not found."));
        a.changeLimits(perTransfer, daily);
        audit.record(actor, "ACCOUNT_LIMITS_CHANGED", "ACCOUNT", id.toString(), "SUCCESS", perTransfer + "/" + daily);
        return a;
    }

    @Transactional(readOnly = true)
    public AccountEntity internalView(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("account-not-found", "Account not found."));
    }
}
