package com.fincore.identity.api;

import com.fincore.identity.application.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public endpoints (no token needed); rate limited per client IP at the gateway. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,50}$", message = "username: 3-50 chars of letters, digits, _ . -") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 72) String password) {}

    public record RegisterResponse(UUID userId, UUID customerId, String username) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @Operation(summary = "Register a CUSTOMER user. Returns the customerId to use in /customers. Passwords are bcrypt-hashed.")
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest r) {
        var u = auth.register(r.username(), r.email(), r.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(u.userId(), u.customerId(), u.username()));
    }

    @Operation(summary = "Exchange credentials for a short-lived JWT (15 min). 5 failures lock the user for 15 minutes.")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r) {
        var t = auth.login(r.username(), r.password());
        return new TokenResponse(t.accessToken(), "Bearer", t.expiresInSeconds());
    }
}
