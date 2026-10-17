package com.referboard.analyzer.web;

import com.referboard.analyzer.service.CoverLetterService;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Format;
import com.referboard.analyzer.web.dto.LetterDtos.CreateLetterRequest;
import com.referboard.analyzer.web.dto.LetterDtos.LetterResponse;
import com.referboard.analyzer.web.dto.LetterDtos.UpdateLetterRequest;
import com.referboard.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@Tag(name = "Cover letters", description = "Letters written from an analysis, using only what your resume says")
public class CoverLetterController {

    private final CoverLetterService letters;

    public CoverLetterController(CoverLetterService letters) {
        this.letters = letters;
    }

    @PostMapping("/api/analyses/{analysisId}/cover-letters")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write a cover letter for the job in this analysis")
    public LetterResponse create(@PathVariable UUID analysisId, @Valid @RequestBody(required = false) CreateLetterRequest req) {
        return letters.create(CurrentUser.id(), Roles.unlimited(), analysisId, req == null ? new CreateLetterRequest(null, null) : req);
    }

    @GetMapping("/api/cover-letters")
    @Operation(summary = "My cover letters, newest first; filter by job")
    public List<LetterResponse> list(@RequestParam(required = false) UUID jobId) {
        return letters.list(CurrentUser.id(), jobId);
    }

    @GetMapping("/api/cover-letters/{id}")
    @Operation(summary = "A cover letter with its original draft")
    public LetterResponse get(@PathVariable UUID id) {
        return letters.get(CurrentUser.id(), id);
    }

    @PutMapping("/api/cover-letters/{id}")
    @Operation(summary = "Save your edits")
    public LetterResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateLetterRequest req) {
        return letters.update(CurrentUser.id(), id, req);
    }

    @GetMapping("/api/cover-letters/{id}/file")
    @Operation(summary = "Download as DOCX (default) or PDF")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam(required = false) Format format) {
        CoverLetterService.RenderedFile f = letters.file(CurrentUser.id(), id, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .body(f.bytes());
    }

    @DeleteMapping("/api/cover-letters/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a cover letter")
    public void delete(@PathVariable UUID id) {
        letters.delete(CurrentUser.id(), id);
    }
}
