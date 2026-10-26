package com.fincore.identity.application;

import com.fincore.identity.domain.LockoutPolicy;
import com.fincore.identity.domain.PasswordPolicy;
import com.fincore.identity.persistence.UserEntity;
import com.fincore.identity.persistence.UserRepository;
import com.fincore.platform.audit.AuditLogger;
import com.fincore.platform.security.TokenIssuer;
import com.fincore.platform.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {
    public record Registered(UUID userId, UUID customerId, String username) {}
    public record Token(String accessToken, long expiresInSeconds) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenIssuer tokens;
    private final IdentityConfig.IdentityProperties props;
    private final AuditLogger audit;
    private final MeterRegistry meters;
    private final Clock clock;
    /** Hash compared against when the username does not exist, so response time does not reveal valid usernames. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, TokenIssuer tokens,
                       IdentityConfig.IdentityProperties props, AuditLogger audit, MeterRegistry meters) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.props = props;
        this.audit = audit;
        this.meters = meters;
        this.clock = Clock.systemUTC();
        this.dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());
    }

    @Transactional
    public Registered register(String username, String email, String password) {
        return create(username, email, password, UserEntity.Role.CUSTOMER, UUID.randomUUID());
    }

    @Transactional
    public Registered createAdmin(String username, String email, String password) {
        return create(username, email, password, UserEntity.Role.ADMIN, null);
    }

    private Registered create(String username, String email, String password, UserEntity.Role role, UUID customerId) {
        List<String> problems = PasswordPolicy.violations(password, username);
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("weak-password", "Password must contain: " + String.join(", ", problems) + ".");
        }
        if (users.existsByUsernameOrEmail(username, email.toLowerCase())) {
            // Same message for username and email: do not reveal which one exists.
            throw ApiException.conflict("already-registered", "That username or email is already registered.");
        }
        UserEntity user = users.save(new UserEntity(UUID.randomUUID(), username, email.toLowerCase(),
                encoder.encode(password), role, customerId));
        audit.record(username, "USER_REGISTERED", "USER", user.getId().toString(), "SUCCESS", "role=" + role);
        return new Registered(user.getId(), customerId, username);
    }

    /** noRollbackFor: the failed-attempt counter must be saved even though we then throw 401. */
    @Transactional(noRollbackFor = ApiException.class)
    public Token login(String username, String password) {
        Instant now = clock.instant();
        UserEntity user = users.findByUsername(username).orElse(null);
        if (user == null) {
            encoder.matches(password, dummyHash); // burn comparable CPU time
            return failLogin(username, null, "unknown-user");
        }
        if (!user.isEnabled() || LockoutPolicy.isLocked(user.getLockedUntil(), now)) {
            encoder.matches(password, dummyHash);
            return failLogin(username, user, "locked-or-disabled");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            LockoutPolicy.State next = LockoutPolicy.afterFailure(user.getFailedAttempts(), now);
            user.setLockState(next.failedAttempts(), next.lockedUntil());
            return failLogin(username, user, next.lockedUntil() != null ? "locked-after-failures" : "bad-password");
        }
        user.setLockState(0, null);
        TokenIssuer.IssuedToken token = tokens.issueUserToken(user.getId(), user.getRole().name(), user.getCustomerId(), props.tokenTtl());
        audit.record(username, "LOGIN", "USER", user.getId().toString(), "SUCCESS", null);
        meters.counter("fincore.auth.logins", "outcome", "success").increment();
        return new Token(token.value(), props.tokenTtl().toSeconds());
    }

    private Token failLogin(String username, UserEntity user, String reason) {
        meters.counter("fincore.auth.logins", "outcome", "failure").increment();
        audit.record(username, "LOGIN", "USER", user == null ? null : user.getId().toString(), "FAILURE", reason);
        // One generic message for every failure cause.
        throw ApiException.unauthorized("invalid-credentials", "Invalid username or password.");
    }
}
