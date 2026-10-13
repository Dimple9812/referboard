package com.referboard.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.web.ApiError;
import com.referboard.common.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.List;

/** 401/403 responses from the security filter chain in the same {@link ApiError} shape as everything else. */
public final class JsonAuthErrorHandlers {

    private JsonAuthErrorHandlers() {
    }

    public static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> write(mapper, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Missing or invalid access token", request.getRequestURI());
    }

    public static AccessDeniedHandler accessDenied(ObjectMapper mapper) {
        return (request, response, ex) -> write(mapper, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have access to this resource", request.getRequestURI());
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String code,
                              String message, String path) throws IOException {
        ApiError body = GlobalExceptionHandler.body(status, code, message, path, List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), body);
    }
}
