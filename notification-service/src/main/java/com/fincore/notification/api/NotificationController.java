package com.fincore.notification.api;

import com.fincore.notification.persistence.NotificationEntity;
import com.fincore.notification.persistence.NotificationRepository;
import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import com.fincore.platform.web.PageResponse;
import com.fincore.platform.web.Pagination;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    public record NotificationResponse(UUID id, UUID transferId, String template, String subject, String body, Instant createdAt) {
        static NotificationResponse from(NotificationEntity n) {
            return new NotificationResponse(n.getId(), n.getTransferRef(), n.getTemplate(), n.getSubject(), n.getBody(), n.getCreatedAt());
        }
    }

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "Your simulated notifications, newest first")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping
    public PageResponse<NotificationResponse> mine(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.of(jwt);
        if (user.customerId() == null) {
            throw ApiException.forbidden("customer-profile-required", "Your user is not linked to a customer profile.");
        }
        var pageable = Pagination.of(page, size, null, Set.of("createdAt"), "createdAt");
        return PageResponse.from(repository.findByRecipientCustomerId(user.customerId(), pageable), NotificationResponse::from);
    }
}
