package com.referboard.referral.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.referral.domain.ContactStatus;
import com.referboard.referral.domain.Reminder;
import com.referboard.referral.service.ContactService;
import com.referboard.referral.service.DraftService;
import com.referboard.referral.service.ReminderService;
import com.referboard.referral.service.SuggestionService;
import com.referboard.referral.service.SuggestionService.Suggestions;
import com.referboard.referral.web.dto.ReferralDtos.ChangeStatusRequest;
import com.referboard.referral.web.dto.ReferralDtos.ContactDetailResponse;
import com.referboard.referral.web.dto.ReferralDtos.ContactPage;
import com.referboard.referral.web.dto.ReferralDtos.ContactResponse;
import com.referboard.referral.web.dto.ReferralDtos.CreateContactRequest;
import com.referboard.referral.web.dto.ReferralDtos.DraftRequest;
import com.referboard.referral.web.dto.ReferralDtos.DraftResponse;
import com.referboard.referral.web.dto.ReferralDtos.DraftSetResponse;
import com.referboard.referral.web.dto.ReferralDtos.PipelineSummary;
import com.referboard.referral.web.dto.ReferralDtos.ReminderResponse;
import com.referboard.referral.web.dto.ReferralDtos.UpdateContactRequest;
import com.referboard.referral.web.dto.ReferralDtos.UpdateReminderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/referrals")
@Tag(name = "Referrals", description = "Find people, draft messages you send yourself, track your referral pipeline")
public class ReferralController {

    private final SuggestionService suggestions;
    private final ContactService contacts;
    private final DraftService drafts;
    private final ReminderService reminders;

    public ReferralController(SuggestionService suggestions, ContactService contacts, DraftService drafts,
                              ReminderService reminders) {
        this.suggestions = suggestions;
        this.contacts = contacts;
        this.drafts = drafts;
        this.reminders = reminders;
    }

    @GetMapping("/jobs/{jobId}/suggestions")
    @Operation(summary = "Kinds of people to reach for this job, each with a LinkedIn search link you open yourself")
    public Suggestions suggestions(@PathVariable UUID jobId) {
        return suggestions.suggest(CurrentUser.id(), jobId);
    }

    @GetMapping("/jobs/{jobId}/contacts")
    @Operation(summary = "My contacts for this job")
    public List<ContactResponse> contactsForJob(@PathVariable UUID jobId) {
        return contacts.listForJob(CurrentUser.id(), jobId);
    }

    @PostMapping("/jobs/{jobId}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a person you found (name, profile URL and/or headline)")
    public ContactResponse addContact(@PathVariable UUID jobId, @Valid @RequestBody CreateContactRequest request) {
        return contacts.create(CurrentUser.id(), jobId, request);
    }

    @GetMapping("/contacts")
    @Operation(summary = "Referral CRM: all my contacts, optionally filtered by status")
    public ContactPage contacts(@RequestParam(required = false) Set<ContactStatus> status,
                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return contacts.list(CurrentUser.id(), status, page, size);
    }

    @GetMapping("/pipeline")
    @Operation(summary = "Contact counts per status and the number of active reminders")
    public PipelineSummary pipeline() {
        return contacts.summary(CurrentUser.id());
    }

    @GetMapping("/contacts/{id}")
    @Operation(summary = "A contact with status history, recent drafts and reminders")
    public ContactDetailResponse contact(@PathVariable UUID id) {
        return contacts.detail(CurrentUser.id(), id);
    }

    @PatchMapping("/contacts/{id}")
    @Operation(summary = "Edit a contact's details or notes")
    public ContactResponse updateContact(@PathVariable UUID id, @Valid @RequestBody UpdateContactRequest request) {
        return contacts.update(CurrentUser.id(), id, request);
    }

    @PostMapping("/contacts/{id}/status")
    @Operation(summary = "Record what you did: REQUEST_SENT, CONNECTED, REFERRAL_ASKED, REFERRED or NO_RESPONSE")
    public ContactResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return contacts.changeStatus(CurrentUser.id(), id, request.status());
    }

    @DeleteMapping("/contacts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a contact")
    public void deleteContact(@PathVariable UUID id) {
        contacts.delete(CurrentUser.id(), id);
    }

    @PostMapping("/contacts/{id}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Draft a connection note (≤300 chars) and referral request in two tones, or follow-ups")
    public DraftSetResponse draft(@PathVariable UUID id, @Valid @RequestBody(required = false) DraftRequest request) {
        return drafts.generate(CurrentUser.id(), id, request);
    }

    @GetMapping("/contacts/{id}/drafts")
    @Operation(summary = "All drafts for a contact, newest first")
    public List<DraftResponse> drafts(@PathVariable UUID id) {
        return drafts.list(CurrentUser.id(), id);
    }

    @PostMapping("/drafts/{id}/copied")
    @Operation(summary = "Record that you copied a draft")
    public DraftResponse copied(@PathVariable UUID id) {
        return drafts.markCopied(CurrentUser.id(), id);
    }

    @GetMapping("/reminders")
    @Operation(summary = "My reminders (default: due ones, status FIRED)")
    public List<ReminderResponse> reminders(@RequestParam(required = false) Set<Reminder.Status> status,
                                            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return reminders.list(CurrentUser.id(), status, limit);
    }

    @PatchMapping("/reminders/{id}")
    @Operation(summary = "Mark a reminder DONE or DISMISSED")
    public ReminderResponse updateReminder(@PathVariable UUID id, @Valid @RequestBody UpdateReminderRequest request) {
        return reminders.update(CurrentUser.id(), id, request.status());
    }
}
