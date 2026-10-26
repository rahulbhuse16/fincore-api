package com.fincore.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * fincore.security.jwt-secret has NO default on purpose: the service refuses to start without a real secret.
 * HS256 with a shared secret is a deliberate simplification for this simulation (see docs/architecture-decisions.md);
 * production would use asymmetric keys (RS256/ES256) published through a JWKS endpoint.
 */
@ConfigurationProperties("fincore.security")
public record FincoreSecurityProperties(
        String jwtSecret,
        @DefaultValue("fincore-identity") String issuer,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue List<String> publicPaths,
        @DefaultValue("true") boolean exposeApiDocs) {
}
