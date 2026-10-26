package com.fincore.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Issues HS256 JWTs. Used by identity-service for user tokens and by services for short-lived service tokens. */
public class TokenIssuer {
    public record IssuedToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final String issuer;

    public TokenIssuer(SecretKey key, String issuer) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.issuer = issuer;
    }

    public IssuedToken issueUserToken(UUID userId, String role, UUID customerId, Duration ttl) {
        return issue(userId.toString(), role, customerId, ttl);
    }

    /** Service-to-service token (role SERVICE, 2 minutes). Authorises calls to /internal/** endpoints. */
    public String issueServiceToken(String serviceName) {
        return issue(serviceName, "SERVICE", null, Duration.ofMinutes(2)).value();
    }

    private IssuedToken issue(String subject, String role, UUID customerId, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer).subject(subject).issuedAt(now).expiresAt(exp).id(UUID.randomUUID().toString())
                .claim("roles", List.of(role));
        if (customerId != null) {
            claims.claim("customer_id", customerId.toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, exp);
    }
}
