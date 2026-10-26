package com.fincore.platform.web;

import org.springframework.http.HttpStatus;

/** Business/HTTP exception mapped to an RFC 9457 problem document. Messages must be safe to show to clients. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static ApiException badRequest(String code, String msg) { return new ApiException(HttpStatus.BAD_REQUEST, code, msg); }
    public static ApiException unauthorized(String code, String msg) { return new ApiException(HttpStatus.UNAUTHORIZED, code, msg); }
    public static ApiException forbidden(String code, String msg) { return new ApiException(HttpStatus.FORBIDDEN, code, msg); }
    public static ApiException notFound(String code, String msg) { return new ApiException(HttpStatus.NOT_FOUND, code, msg); }
    public static ApiException conflict(String code, String msg) { return new ApiException(HttpStatus.CONFLICT, code, msg); }
    public static ApiException unprocessable(String code, String msg) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, msg); }
    public static ApiException unavailable(String code, String msg) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, msg); }
}
