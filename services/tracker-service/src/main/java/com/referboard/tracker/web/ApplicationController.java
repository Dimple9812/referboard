package com.referboard.tracker.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.tracker.service.ApplicationService;
import com.referboard.tracker.web.dto.TrackerDtos.AddNoteRequest;
import com.referboard.tracker.web.dto.TrackerDtos.ApplicationDetail;
import com.referboard.tracker.web.dto.TrackerDtos.Board;
import com.referboard.tracker.web.dto.TrackerDtos.CardResponse;
import com.referboard.tracker.web.dto.TrackerDtos.CreateApplicationRequest;
import com.referboard.tracker.web.dto.TrackerDtos.MoveRequest;
import com.referboard.tracker.web.dto.TrackerDtos.UpdateApplicationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/applications")
@Tag(name = "Applications", description = "Your application board")
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping("/board")
    @Operation(summary = "All my applications as Kanban columns: Saved → Applied → Referral requested → Referred → "
            + "Interview → Offer → Rejected")
    public Board board() {
        return service.board(CurrentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a ReferBoard job (jobId) or a job found elsewhere (jobTitle + companyName)")
    public CardResponse create(@Valid @RequestBody CreateApplicationRequest request) {
        return service.create(CurrentUser.id(), request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "A card with its full timeline")
    public ApplicationDetail detail(@PathVariable UUID id) {
        return service.detail(CurrentUser.id(), id);
    }

    @GetMapping("/by-job/{jobId}")
    @Operation(summary = "My card for a ReferBoard job (404 if not tracked)")
    public CardResponse byJob(@PathVariable UUID jobId) {
        return service.byJob(CurrentUser.id(), jobId);
    }

    @PostMapping("/{id}/move")
    @Operation(summary = "Move a card to a column and position (drag and drop)")
    public CardResponse move(@PathVariable UUID id, @Valid @RequestBody MoveRequest request) {
        return service.move(CurrentUser.id(), id, request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit notes, dates and next step (and job details for jobs found elsewhere)")
    public CardResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateApplicationRequest request) {
        return service.update(CurrentUser.id(), id, request);
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a dated note to the card's timeline")
    public ApplicationDetail addNote(@PathVariable UUID id, @Valid @RequestBody AddNoteRequest request) {
        return service.addNote(CurrentUser.id(), id, request.text());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a card from the board")
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }
}
