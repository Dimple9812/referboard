package com.referboard.referral.drafting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referboard.ai.JsonSchemas;
import com.referboard.ai.LlmClient;
import com.referboard.ai.StructuredPrompt;
import com.referboard.referral.domain.MessageDraft.Kind;
import com.referboard.referral.domain.MessageDraft.Tone;
import org.springframework.stereotype.Component;

import java.util.List;

/** Drafts via the configured LLM with a forced JSON schema. Output still goes through {@link DraftGuard}. */
@Component
public class LlmDrafter {

    public static final String VERSION = "referral-v1";

    private static final String SYSTEM = JsonSchemas.loadText("prompts/referral-draft-system.txt");
    private static final JsonNode INTRO_SCHEMA = JsonSchemas.load("prompts/referral-intro-schema.json");
    private static final JsonNode FOLLOW_UP_SCHEMA = JsonSchemas.load("prompts/referral-followup-schema.json");

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public LlmDrafter(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public record IntroOutput(String warmConnectionNote, String conciseConnectionNote, String warmReferralRequest,
                              String conciseReferralRequest) {
    }

    public record FollowUpOutput(String warmFollowUp, String conciseFollowUp) {
    }

    public boolean available() {
        return llm.available();
    }

    public String modelId() {
        return llm.modelId();
    }

    public List<DraftText> intro(DraftFacts facts) {
        IntroOutput out = llm.structured(new StructuredPrompt<>(SYSTEM,
                userMessage(facts, "Write both tones of the connection note and the referral request."),
                "referral_drafts", "Return the four drafted messages.", INTRO_SCHEMA, IntroOutput.class, 1500, 0.6));
        return List.of(
                new DraftText(Kind.CONNECTION_NOTE, Tone.WARM, out.warmConnectionNote()),
                new DraftText(Kind.CONNECTION_NOTE, Tone.CONCISE, out.conciseConnectionNote()),
                new DraftText(Kind.REFERRAL_REQUEST, Tone.WARM, out.warmReferralRequest()),
                new DraftText(Kind.REFERRAL_REQUEST, Tone.CONCISE, out.conciseReferralRequest()));
    }

    public List<DraftText> followUps(DraftFacts facts) {
        FollowUpOutput out = llm.structured(new StructuredPrompt<>(SYSTEM,
                userMessage(facts, "The user asked for a referral and has had no reply. Write both tones of a "
                        + "follow-up message."),
                "referral_follow_ups", "Return the two follow-up messages.", FOLLOW_UP_SCHEMA,
                FollowUpOutput.class, 800, 0.6));
        return List.of(
                new DraftText(Kind.FOLLOW_UP, Tone.WARM, out.warmFollowUp()),
                new DraftText(Kind.FOLLOW_UP, Tone.CONCISE, out.conciseFollowUp()));
    }

    String userMessage(DraftFacts f, String task) {
        ObjectNode facts = mapper.createObjectNode();
        ObjectNode recipient = facts.putObject("recipient");
        recipient.put("firstName", f.contactFirstName());
        recipient.put("headline", f.contactHeadline());
        recipient.put("personType", f.personType().name());
        ObjectNode job = facts.putObject("job");
        job.put("title", f.jobTitle());
        job.put("company", f.companyName());
        job.put("location", f.jobLocation());
        job.put("jobUrl", f.jobUrl());
        job.set("skills", mapper.valueToTree(f.jobSkills()));
        ObjectNode me = facts.putObject("sender");
        me.put("userFirstName", f.userFirstName());
        me.put("currentTitle", f.userTitle());
        me.put("currentCompany", f.userCompany());
        me.put("yearsOfExperience", f.experienceYearsText());
        me.put("college", f.college());
        me.set("pastCompanies", mapper.valueToTree(f.pastCompanies()));
        me.set("skillsMatchingTheJob", mapper.valueToTree(f.sharedSkills()));
        me.set("achievements", mapper.valueToTree(f.achievements()));
        facts.put("sharedCompany", f.sharedCompany());
        facts.put("userContext", f.userContext());
        try {
            return task + "\n\n<FACTS>\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts)
                    + "\n</FACTS>";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
