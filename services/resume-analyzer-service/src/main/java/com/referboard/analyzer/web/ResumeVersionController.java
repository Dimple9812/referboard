package com.referboard.analyzer.web;

import com.referboard.analyzer.service.ResumeVersionService;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Format;
import com.referboard.analyzer.web.dto.AnalyzerDtos.VersionResponse;
import com.referboard.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/resume-versions")
@Tag(name = "Tailored resumes", description = "Resume versions created from analyses")
public class ResumeVersionController {

    private final ResumeVersionService versions;

    public ResumeVersionController(ResumeVersionService versions) {
        this.versions = versions;
    }

    @GetMapping
    @Operation(summary = "My tailored resumes, newest first")
    public List<VersionResponse> list() {
        return versions.list(CurrentUser.id());
    }

    @GetMapping("/{id}")
    @Operation(summary = "A tailored resume with its structured content")
    public VersionResponse get(@PathVariable UUID id) {
        return versions.get(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "Download as DOCX or PDF (default: the format chosen when it was created)")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam(required = false) Format format) {
        ResumeVersionService.RenderedFile f = versions.file(CurrentUser.id(), id, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .body(f.bytes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a tailored resume")
    public void delete(@PathVariable UUID id) {
        versions.delete(CurrentUser.id(), id);
    }
}
