package com.referboard.auth.web;

import com.referboard.auth.service.ClientInfo;
import com.referboard.auth.service.ExtensionSessionService;
import com.referboard.auth.web.dto.ExtensionDtos.ExtensionConnection;
import com.referboard.auth.web.dto.ExtensionDtos.ExtensionRefreshRequest;
import com.referboard.auth.web.dto.ExtensionDtos.ExtensionTokens;
import com.referboard.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/extension")
@Tag(name = "Browser extension sessions")
public class ExtensionController {

    private final ExtensionSessionService sessions;

    public ExtensionController(ExtensionSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/connect")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a session for the browser extension (called by the signed-in web app)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ExtensionTokens> connect(HttpServletRequest http) {
        return noStore(HttpStatus.CREATED, sessions.connect(CurrentUser.id(), client(http)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the extension's refresh token for new tokens (rotates it)")
    public ResponseEntity<ExtensionTokens> refresh(@Valid @RequestBody ExtensionRefreshRequest req, HttpServletRequest http) {
        return noStore(HttpStatus.OK, sessions.refresh(req.refreshToken(), client(http)));
    }

    @PostMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign the extension out (revokes its session)")
    public void disconnect(@Valid @RequestBody ExtensionRefreshRequest req) {
        sessions.disconnect(req.refreshToken());
    }

    @GetMapping("/connections")
    @Operation(summary = "My connected extensions", security = @SecurityRequirement(name = "bearerAuth"))
    public List<ExtensionConnection> connections() {
        return sessions.connections(CurrentUser.id());
    }

    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect an extension", security = @SecurityRequirement(name = "bearerAuth"))
    public void revoke(@PathVariable UUID id) {
        sessions.revoke(CurrentUser.id(), id);
    }

    private static ResponseEntity<ExtensionTokens> noStore(HttpStatus status, ExtensionTokens body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .body(body);
    }

    private static ClientInfo client(HttpServletRequest request) {
        return new ClientInfo(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
    }
}
