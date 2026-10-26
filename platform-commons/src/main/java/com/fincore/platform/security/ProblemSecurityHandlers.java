package com.fincore.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 401/403 raised inside the security filter chain, rendered in the same problem+json format as controller errors. */
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    public ProblemSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required or the token is invalid.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "You are not allowed to perform this operation.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://fincore.example/problems/" + code);
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        body.put("timestamp", Instant.now().toString());
        if (MDC.get("correlationId") != null) {
            body.put("correlationId", MDC.get("correlationId"));
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), body);
    }
}
