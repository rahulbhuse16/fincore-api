package com.fincore.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * First line of defence: the gateway verifies the JWT and rejects unauthenticated traffic early. It does NOT replace
 * downstream checks: the original Authorization header is forwarded unchanged and every service verifies the token
 * again and enforces its own authorization. No identity is ever passed in client-controllable headers.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(@Value("${fincore.security.jwt-secret:}") String secret,
                                          @Value("${fincore.security.issuer:fincore-identity}") String issuer) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32 || secret.startsWith("change-me")) {
            throw new IllegalStateException("fincore.security.jwt-secret (env JWT_SECRET) must be a random value of at least 32 bytes.");
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), new JwtIssuerValidator(issuer));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<org.springframework.security.core.GrantedAuthority> out = new ArrayList<>();
            if (roles != null) roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
            return out;
        });
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers("/internal/**").denyAll()            // internal endpoints are never exposed at the edge
                        .pathMatchers("/api/v1/reconciliation/**", "/api/v1/risk/**", "/api/v1/ledger/deposits").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtDecoder(decoder).jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint((exchange, ex) -> ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED,
                                "unauthorized", "Authentication is required or the token is invalid."))
                        .accessDeniedHandler((exchange, ex) -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN,
                                "forbidden", "You are not allowed to perform this operation.")))
                .build();
    }
}
