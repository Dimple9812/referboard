package com.referboard.interview.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.interview.service.PrepService;
import com.referboard.interview.web.dto.PrepDtos.CreatePrepRequest;
import com.referboard.interview.web.dto.PrepDtos.ItemView;
import com.referboard.interview.web.dto.PrepDtos.PackDetail;
import com.referboard.interview.web.dto.PrepDtos.PackSummary;
import com.referboard.interview.web.dto.PrepDtos.UpdateItemRequest;
import com.referboard.interview.web.dto.PrepDtos.Usage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
@RequestMapping("/api/interview-preps")
@Tag(name = "Interview prep")
public class PrepController {

    private static final Set<String> UNLIMITED = Set.of("ROLE_PRO", "ROLE_ADMIN");

    private final PrepService preps;

    public PrepController(PrepService preps) {
        this.preps = preps;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Build a prep pack from one of my analyses")
    public PackDetail create(@Valid @RequestBody CreatePrepRequest req) {
        return preps.create(CurrentUser.id(), pro(), req.analysisId());
    }

    @GetMapping
    @Operation(summary = "My prep packs, newest first; filter by job")
    public List<PackSummary> list(@RequestParam(required = false) UUID jobId) {
        return preps.list(CurrentUser.id(), jobId);
    }

    @GetMapping("/usage")
    @Operation(summary = "Prep packs used this month")
    public Usage usage() {
        return preps.usage(CurrentUser.id(), pro());
    }

    @GetMapping("/{id}")
    @Operation(summary = "A prep pack with all its questions")
    public PackDetail get(@PathVariable UUID id) {
        return preps.get(CurrentUser.id(), id);
    }

    @PatchMapping("/{id}/items/{itemId}")
    @Operation(summary = "Save my notes or how confident I feel about a question")
    public ItemView update(@PathVariable UUID id, @PathVariable UUID itemId, @Valid @RequestBody UpdateItemRequest req) {
        return preps.updateItem(CurrentUser.id(), id, itemId, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a prep pack")
    public void delete(@PathVariable UUID id) {
        preps.delete(CurrentUser.id(), id);
    }

    private static boolean pro() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> UNLIMITED.contains(a.getAuthority()));
    }
}
