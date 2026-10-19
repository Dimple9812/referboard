package com.referboard.auth.web;

import com.referboard.auth.service.ClientInfo;
import com.referboard.auth.service.DemoService;
import com.referboard.auth.web.dto.AuthDtos.AuthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/demo")
@Tag(name = "Auth")
public class DemoController {

    private final DemoService demo;
    private final RefreshCookies cookies;

    public DemoController(DemoService demo, RefreshCookies cookies) {
        this.demo = demo;
        this.cookies = cookies;
    }

    @GetMapping
    @Operation(summary = "Whether one-click demo sign-in is available, and the demo account's email")
    public DemoService.Status status() {
        return demo.status();
    }

    @PostMapping
    @Operation(summary = "Sign in to the shared demo account (only when DEMO_ACCOUNT_EMAIL is set)")
    public ResponseEntity<AuthResponse> signIn(HttpServletRequest http) {
        AuthResponse response = demo.signIn(new ClientInfo(http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookies.create(response.refreshToken(), response.refreshExpiresIn()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }
}
