package com.referboard.analyzer.web;

import com.referboard.analyzer.config.AnalyzerProperties;
import com.referboard.analyzer.jd.JdText;
import com.referboard.analyzer.resume.LoadedResume;
import com.referboard.analyzer.service.AnalysisService;
import com.referboard.analyzer.service.AnalysisService.JdInput;
import com.referboard.analyzer.service.ResumeVersionService;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisPage;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisReport;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalyzeRequest;
import com.referboard.analyzer.web.dto.AnalyzerDtos.GenerateRequest;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Usage;
import com.referboard.analyzer.web.dto.AnalyzerDtos.VersionResponse;
import com.referboard.common.security.CurrentUser;
import com.referboard.common.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping("/api/analyses")
@Tag(name = "JD Match Analyzer", description = "Compare your resume with a job description")
public class AnalysisController {

    private final AnalysisService analyses;
    private final ResumeVersionService versions;
    private final AnalyzerProperties props;

    public AnalysisController(AnalysisService analyses, ResumeVersionService versions, AnalyzerProperties props) {
        this.analyses = analyses;
        this.versions = versions;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Analyse a pasted JD or a ReferBoard job against your resume")
    public AnalysisReport analyze(@Valid @RequestBody AnalyzeRequest req) {
        JdInput jd;
        if (req.jobId() != null) {
            jd = analyses.fromJob(req.jobId(), req.jdText());
        } else if (req.jdText() != null && !req.jdText().isBlank()) {
            jd = new JdInput(req.jdText(), "TEXT", null, req.jobTitle(), req.companyName());
        } else {
            throw ApiException.badRequest("JD_REQUIRED", "Paste a job description, upload one, or pick a job.");
        }
        return analyses.analyze(CurrentUser.id(), Roles.unlimited(), jd,
                req.resume() == null ? null : req.resume().source(), req.resume() == null ? null : req.resume().id());
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Analyse an uploaded JD (PDF, DOCX or TXT) against your resume")
    public AnalysisReport analyzeUpload(@RequestPart("file") MultipartFile file,
                                        @RequestParam(required = false) LoadedResume.Source resumeSource,
                                        @RequestParam(required = false) UUID resumeId,
                                        @RequestParam(required = false) UUID jobId,
                                        @RequestParam(required = false) String jobTitle,
                                        @RequestParam(required = false) String companyName) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "The uploaded file is empty.");
        }
        if (file.getSize() > props.maxUploadBytes()) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Job description files can be up to 2 MB.");
        }
        String text;
        try (InputStream in = file.getInputStream()) {
            text = JdText.fromUpload(in, file.getOriginalFilename(), props.maxJdChars());
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "Couldn't read the uploaded file.");
        }
        return analyses.analyze(CurrentUser.id(), Roles.unlimited(),
                new JdInput(text, "UPLOAD", jobId, jobTitle, companyName), resumeSource, resumeId);
    }

    @GetMapping
    @Operation(summary = "My analyses, newest first (filter by job)")
    public AnalysisPage list(@RequestParam(required = false) UUID jobId,
                             @RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return analyses.list(CurrentUser.id(), jobId, page, size);
    }

    @GetMapping("/usage")
    @Operation(summary = "Analyses used this month and the free-tier limit")
    public Usage usage() {
        return analyses.usage(CurrentUser.id(), Roles.unlimited());
    }

    @GetMapping("/{id}/context")
    @Operation(summary = "The posting, skill matches and resume content behind an analysis (used by interview prep)")
    public com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisContext context(@PathVariable UUID id) {
        return analyses.context(CurrentUser.id(), id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "A stored analysis report")
    public AnalysisReport get(@PathVariable UUID id) {
        return analyses.get(CurrentUser.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an analysis")
    public void delete(@PathVariable UUID id) {
        analyses.delete(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/resume")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Accept/reject suggestions and create a tailored resume version (DOCX or PDF)")
    public VersionResponse generate(@PathVariable UUID id, @Valid @RequestBody GenerateRequest req) {
        return versions.generate(CurrentUser.id(), id, req);
    }
}
