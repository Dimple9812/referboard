package com.referboard.notification.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.notification.service.EmailCategory;
import com.referboard.notification.service.NotificationService;
import com.referboard.notification.web.dto.NotificationDtos.MarkedRead;
import com.referboard.notification.web.dto.NotificationDtos.NotificationPage;
import com.referboard.notification.web.dto.NotificationDtos.NotificationResponse;
import com.referboard.notification.web.dto.NotificationDtos.Preferences;
import com.referboard.notification.web.dto.NotificationDtos.UnreadCount;
import com.referboard.notification.web.dto.NotificationDtos.UpdatePreferences;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notifications and email preferences")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "My notifications, newest first, with the unread count")
    public NotificationPage list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(CurrentUser.id(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread count for the bell icon (cheap; poll it)")
    public UnreadCount unreadCount() {
        return new UnreadCount(service.unreadCount(CurrentUser.id()));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification read")
    public NotificationResponse markRead(@PathVariable UUID id) {
        return service.markRead(CurrentUser.id(), id);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications read")
    public MarkedRead markAllRead() {
        return service.markAllRead(CurrentUser.id());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a notification")
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }

    @GetMapping("/preferences")
    @Operation(summary = "My email preferences")
    public Preferences preferences() {
        return service.preferences(CurrentUser.id());
    }

    @PutMapping("/preferences")
    @Operation(summary = "Turn reminder or digest emails on or off")
    public Preferences updatePreferences(@RequestBody UpdatePreferences request) {
        return service.updatePreferences(CurrentUser.id(), request);
    }

    /**
     * The unsubscribe link in every email (GET from a browser, POST for RFC 8058 one-click). Public: the token is the
     * credential. Always answers with a small HTML page.
     */
    @RequestMapping(path = "/unsubscribe", method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "One-click unsubscribe from an email link (no login)")
    public ResponseEntity<String> unsubscribe(@RequestParam String token,
                                              @RequestParam(required = false) String category) {
        UUID parsed;
        try {
            parsed = UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            return page(HttpStatus.NOT_FOUND, "Link not recognised",
                    "This unsubscribe link isn't valid. You can manage emails in ReferBoard settings.");
        }
        EmailCategory cat = category == null ? null : EmailCategory.fromParam(category).orElse(null);
        return service.unsubscribe(parsed, cat)
                .map(label -> page(HttpStatus.OK, "You're unsubscribed",
                        "You won't receive " + label + " any more. You can turn emails back on in ReferBoard settings."))
                .orElseGet(() -> page(HttpStatus.NOT_FOUND, "Link not recognised",
                        "This unsubscribe link isn't valid. You can manage emails in ReferBoard settings."));
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String message) {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>" + HtmlUtils.htmlEscape(title)
                + "</title></head><body style=\"font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;"
                + "max-width:480px;margin:64px auto;padding:0 16px;color:#222\"><h1 style=\"font-size:22px\">"
                + HtmlUtils.htmlEscape(title) + "</h1><p style=\"line-height:1.5\">" + HtmlUtils.htmlEscape(message)
                + "</p></body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(html);
    }
}
