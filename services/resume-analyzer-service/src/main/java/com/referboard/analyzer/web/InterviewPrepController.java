package com.referboard.analyzer.web;

import com.referboard.analyzer.service.InterviewPrepService;
import com.referboard.analyzer.web.dto.PrepDtos.ItemView;
import com.referboard.analyzer.web.dto.PrepDtos.PrepResponse;
import com.referboard.analyzer.web.dto.PrepDtos.PrepSummary;
import com.referboard.analyzer.web.dto.PrepDtos.SaveAnswerRequest;
import com.referboard.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Interview prep", description = "Questions to prepare for one job, from the posting and your resume")
public class InterviewPrepController {

    private final InterviewPrepService preps;

    public InterviewPrepController(InterviewPrepService preps) {
        this.preps = preps;
    }

    @PostMapping("/api/analyses/{analysisId}/interview-prep")
    @Operation(summary = "Make the prep plan for this analysis (returns the existing one if there is one)")
    public ResponseEntity<PrepResponse> create(@PathVariable UUID analysisId) {
        PrepResponse r = preps.create(CurrentUser.id(), analysisId);
        return ResponseEntity.status(r.existing() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    @GetMapping("/api/interview-preps")
    @Operation(summary = "My prep plans, newest first; filter by job")
    public List<PrepSummary> list(@RequestParam(required = false) UUID jobId) {
        return preps.list(CurrentUser.id(), jobId);
    }

    @GetMapping("/api/interview-preps/{id}")
    @Operation(summary = "A prep plan with your notes and progress")
    public PrepResponse get(@PathVariable UUID id) {
        return preps.get(CurrentUser.id(), id);
    }

    @PutMapping("/api/interview-preps/{id}/items/{itemId}")
    @Operation(summary = "Save your notes and confidence for one question")
    public ItemView save(@PathVariable UUID id, @PathVariable String itemId, @Valid @RequestBody SaveAnswerRequest req) {
        return preps.saveAnswer(CurrentUser.id(), id, itemId, req);
    }

    @DeleteMapping("/api/interview-preps/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a prep plan and its notes")
    public void delete(@PathVariable UUID id) {
        preps.delete(CurrentUser.id(), id);
    }
}
