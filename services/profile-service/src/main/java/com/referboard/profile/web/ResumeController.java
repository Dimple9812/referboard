package com.referboard.profile.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.common.web.ApiException;
import com.referboard.profile.service.ResumeService;
import com.referboard.profile.web.dto.ProfileDtos.ParsedResumeResponse;
import com.referboard.profile.web.dto.ProfileDtos.ResumeResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile/resumes")
@Tag(name = "Resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a resume (PDF/DOCX/DOC/TXT, max 5 MB). Parsing runs in the background: poll "
            + "GET /{id} until parseStatus is PARSED or FAILED.")
    public ResponseEntity<ResumeResponse> upload(@RequestPart("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "false") boolean primary)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Choose a file to upload");
        }
        ResumeService.UploadResult result = resumeService.upload(CurrentUser.id(), file.getOriginalFilename(),
                file.getBytes(), primary);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.resume());
    }

    @GetMapping
    @Operation(summary = "My resumes, newest first")
    public List<ResumeResponse> list() {
        return resumeService.list(CurrentUser.id());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One resume, including parse status")
    public ResumeResponse get(@PathVariable UUID id) {
        return resumeService.get(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "Download the original file")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        ResumeService.FileContent file = resumeService.download(CurrentUser.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }

    @GetMapping("/{id}/parsed")
    @Operation(summary = "Structured parse and extracted text (used by the JD Match Analyzer)")
    public ParsedResumeResponse parsed(@PathVariable UUID id) {
        return resumeService.parsed(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/primary")
    @Operation(summary = "Make this the primary resume; the profile is rebuilt from it")
    public ResumeResponse makePrimary(@PathVariable UUID id) {
        return resumeService.setPrimary(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/reparse")
    @Operation(summary = "Parse again (e.g. after an API key was configured)")
    public ResponseEntity<ResumeResponse> reparse(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(resumeService.reparse(CurrentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a resume and its stored file")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        resumeService.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
