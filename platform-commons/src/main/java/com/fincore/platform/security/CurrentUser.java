package com.fincore.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Caller identity extracted from a verified JWT. Never built from request headers. */
public record CurrentUser(String subject, UUID customerId, Set<String> roles) {

    public static CurrentUser of(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        String cid = jwt.getClaimAsString("customer_id");
        return new CurrentUser(jwt.getSubject(), cid == null ? null : UUID.fromString(cid),
                roles == null ? Set.of() : Set.copyOf(roles));
    }

    public boolean isAdmin() { return roles.contains("ADMIN"); }
    public boolean isService() { return roles.contains("SERVICE"); }
    public boolean isCustomer() { return roles.contains("CUSTOMER"); }

    /** True for the customer themself, or for ADMIN/SERVICE principals. */
    public boolean canAccessCustomer(UUID customerIdToAccess) {
        return isAdmin() || isService() || (customerId != null && customerId.equals(customerIdToAccess));
    }
}
