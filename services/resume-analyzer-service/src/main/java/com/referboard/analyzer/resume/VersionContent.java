package com.referboard.analyzer.resume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.analyzer.domain.ResumeVersion;
import com.referboard.analyzer.model.ResumeContent;
import org.springframework.stereotype.Component;

/** JSON (de)serialisation of resume content stored on versions and analyses. */
@Component
public class VersionContent {

    private final ObjectMapper mapper;

    public VersionContent(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ResumeContent read(ResumeVersion v) {
        return read(v.getContent());
    }

    public ResumeContent read(String json) {
        try {
            return mapper.readValue(json, ResumeContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored resume content", e);
        }
    }

    public String write(ResumeContent content) {
        try {
            return mapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
