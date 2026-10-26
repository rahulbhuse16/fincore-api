package com.fincore.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(FincoreSecurityProperties.class)
public class FincoreSecurityConfiguration {





    @Bean
    SecretKey jwtSecretKey(FincoreSecurityProperties props) {
        String secret = props.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32 || secret.startsWith("change-me")) {
            throw new IllegalStateException(
                    "fincore.security.jwt-secret (env JWT_SECRET) must be set to a random value of at least 32 bytes. "
                            + "Generate one with: openssl rand -base64 48");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, FincoreSecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(props.issuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    TokenIssuer tokenIssuer(SecretKey key, FincoreSecurityProperties props) {
        return new TokenIssuer(key, props.issuer());
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> out = new java.util.ArrayList<>();
            if (roles != null) {
                roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return out;
        });
        return converter;
    }

    @Bean
    ProblemSecurityHandlers problemSecurityHandlers(ObjectMapper mapper) {
        return new ProblemSecurityHandlers(mapper);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(FincoreSecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins()); // empty list => no cross-origin access
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Location", "Idempotent-Replay"));
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        System.out.println("Fincore CORS allowed origins = " + props.allowedOrigins());

        return source;
    }

    @Bean
    SecurityFilterChain fincoreFilterChain(HttpSecurity http, ProblemSecurityHandlers handlers,
                                           JwtAuthenticationConverter converter, FincoreSecurityProperties props,
                                           @Qualifier("corsConfigurationSource")
                                           CorsConfigurationSource cors) throws Exception {
        List<String> open = new java.util.ArrayList<>(props.publicPaths());
        if (props.exposeApiDocs()) {
            open.addAll(List.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"));
        }
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API; no cookies are used
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> {
                    h.frameOptions(f -> f.deny());
                    h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
                    h.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                            new NegatedRequestMatcher(new AntPathRequestMatcher("/swagger-ui/**")),
                            new ContentSecurityPolicyHeaderWriter("default-src 'none'; frame-ancestors 'none'")));
                })
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll();
                    a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    open.forEach(p -> a.requestMatchers(p).permitAll());
                    a.requestMatchers("/internal/**").hasRole("SERVICE");
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }
}
