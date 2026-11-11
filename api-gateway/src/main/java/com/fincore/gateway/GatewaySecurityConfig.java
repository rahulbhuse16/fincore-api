package com.fincore.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * First line of defence: the gateway verifies the JWT and rejects unauthenticated
 * traffic early. It does NOT replace downstream checks: the original Authorization
 * header is forwarded unchanged and every service verifies the token again and
 * enforces its own authorization. No identity is ever passed in client-controllable
 * headers.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    /**
     * Centralized CORS configuration.
     * The API Gateway is the only browser-facing CORS owner.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration cors = new CorsConfiguration();

        cors.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://localhost:3000"
        ));

        cors.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        cors.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "Idempotency-Key",
                "X-Correlation-Id"
        ));

        cors.setExposedHeaders(List.of(
                "Authorization",
                "X-Correlation-Id",
                "Location",
                "Idempotent-Replay"
        ));

        // JWT Bearer token authentication does not require cookie credentials.
        cors.setAllowCredentials(false);

        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", cors);

        return source;
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${fincore.security.jwt-secret:}") String secret,
            @Value("${fincore.security.issuer:fincore-identity}") String issuer
    ) {

        if (secret.getBytes(StandardCharsets.UTF_8).length < 32
                || secret.startsWith("change-me")) {

            throw new IllegalStateException(
                    "fincore.security.jwt-secret (env JWT_SECRET) must be a random value of at least 32 bytes."
            );
        }

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(
                        new SecretKeySpec(
                                secret.getBytes(StandardCharsets.UTF_8),
                                "HmacSHA256"
                        )
                )
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> validator =
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(),
                        new JwtIssuerValidator(issuer)
                );

        decoder.setJwtValidator(validator);

        return decoder;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder decoder,
            CorsConfigurationSource corsConfigurationSource
    ) {

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {

            List<String> roles = jwt.getClaimAsStringList("roles");

            Collection<org.springframework.security.core.GrantedAuthority> out =
                    new ArrayList<>();

            if (roles != null) {
                roles.forEach(role ->
                        out.add(new SimpleGrantedAuthority("ROLE_" + role))
                );
            }

            return out;
        });

        return http

                // Enable centralized CORS handling
                .cors(cors ->
                        cors.configurationSource(corsConfigurationSource)
                )

                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                .authorizeExchange(a -> a

                        // Allow browser preflight requests
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Public authentication endpoints
                        .pathMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login"
                        ).permitAll()

                        // Public actuator endpoints
                        .pathMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()

                        .pathMatchers("/fallback/**").permitAll()

                        // Internal endpoints are never exposed at the edge
                        .pathMatchers("/internal/**").denyAll()

                        // Admin-only endpoints
                        .pathMatchers(
                                "/api/v1/reconciliation/**",
                                "/api/v1/risk/**",
                                "/api/v1/ledger/deposits"
                        ).hasRole("ADMIN")

                        // All remaining endpoints require authentication
                        .anyExchange().authenticated()
                )

                .oauth2ResourceServer(o -> o

                        .jwt(j -> j
                                .jwtDecoder(decoder)
                                .jwtAuthenticationConverter(
                                        new ReactiveJwtAuthenticationConverterAdapter(
                                                converter
                                        )
                                )
                        )

                        .authenticationEntryPoint((exchange, ex) ->
                                ProblemResponses.write(
                                        exchange,
                                        HttpStatus.UNAUTHORIZED,
                                        "unauthorized",
                                        "Authentication is required or the token is invalid."
                                )
                        )

                        .accessDeniedHandler((exchange, ex) ->
                                ProblemResponses.write(
                                        exchange,
                                        HttpStatus.FORBIDDEN,
                                        "forbidden",
                                        "You are not allowed to perform this operation."
                                )
                        )
                )

                .build();
    }
}