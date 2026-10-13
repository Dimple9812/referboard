package com.referboard.referral.service;

import com.referboard.ai.LlmUnavailableException;
import com.referboard.common.web.ApiException;
import com.referboard.referral.config.ReferralProperties;
import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.MessageDraft;
import com.referboard.referral.domain.UserSnapshot;
import com.referboard.referral.drafting.DraftFacts;
import com.referboard.referral.drafting.DraftGuard;
import com.referboard.referral.drafting.DraftText;
import com.referboard.referral.drafting.LlmDrafter;
import com.referboard.referral.drafting.TemplateDrafter;
import com.referboard.referral.mapper.ReferralMapper;
import com.referboard.referral.repository.ContactRepository;
import com.referboard.referral.repository.MessageDraftRepository;
import com.referboard.referral.web.dto.ReferralDtos.DraftRequest;
import com.referboard.referral.web.dto.ReferralDtos.DraftResponse;
import com.referboard.referral.web.dto.ReferralDtos.DraftSetResponse;
import com.referboard.referral.web.dto.ReferralDtos.DraftType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drafts messages for a contact: a connection note (≤300 chars) and a referral request, each in WARM and CONCISE
 * tones, or follow-ups. The LLM is used when configured and within the user's daily allowance; every LLM draft is
 * checked by {@link DraftGuard} and replaced by the template draft if it fails. Drafts are only ever shown to the
 * user to copy — nothing is sent.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    private final ContactRepository contacts;
    private final MessageDraftRepository drafts;
    private final SnapshotService snapshots;
    private final LlmDrafter llm;
    private final ReferralMapper mapper;
    private final ReferralProperties props;
    private final Clock clock;

    public DraftService(ContactRepository contacts, MessageDraftRepository drafts, SnapshotService snapshots,
                        LlmDrafter llm, ReferralMapper mapper, ReferralProperties props, Clock clock) {
        this.contacts = contacts;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.llm = llm;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    public DraftSetResponse generate(UUID userId, UUID contactId, DraftRequest request) {
        DraftType type = request == null || request.type() == null ? DraftType.INTRO : request.type();
        Contact contact = contacts.findByIdAndUserId(contactId, userId)
                .orElseThrow(() -> ApiException.notFound("Contact", contactId));
        JobSnapshot job = snapshots.job(contact.getJobId());
        UserSnapshot user = snapshots.user(userId);
        DraftFacts facts = DraftFacts.of(contact, job, user, request == null ? null : request.context());

        List<DraftText> templates = type == DraftType.INTRO ? TemplateDrafter.intro(facts)
                : TemplateDrafter.followUps(facts);
        List<String> notices = new ArrayList<>();
        if (user == null || (user.getCurrentTitle() == null && user.getSkills().isEmpty())) {
            notices.add("Your profile is empty, so these drafts are generic. Upload your resume for specific drafts.");
        }
        List<MessageDraft> out = new ArrayList<>();
        boolean usedAi = false;

        if (!llm.available()) {
            notices.add("AI drafting isn't configured; these drafts are built from your profile with templates.");
        } else if (aiDraftsToday(userId) >= props.maxAiDraftsPerDay()) {
            notices.add("You've reached today's AI drafting limit; these are template drafts.");
        } else {
            try {
                List<DraftText> ai = type == DraftType.INTRO ? llm.intro(facts) : llm.followUps(facts);
                int replaced = 0;
                for (int i = 0; i < templates.size(); i++) {
                    DraftText t = templates.get(i);
                    DraftText candidate = i < ai.size() ? ai.get(i) : null;
                    DraftGuard.Result r = candidate == null ? new DraftGuard.Result.Rejected("missing")
                            : DraftGuard.check(t.kind(), candidate.content(), facts);
                    if (r instanceof DraftGuard.Result.Ok ok) {
                        out.add(new MessageDraft(userId, contactId, t.kind(), t.tone(), ok.content(), llm.modelId(),
                                LlmDrafter.VERSION));
                        usedAi = true;
                    } else {
                        log.info("AI {} {} draft rejected ({}); using template", t.kind(), t.tone(),
                                ((DraftGuard.Result.Rejected) r).reason());
                        out.add(template(userId, contactId, t));
                        replaced++;
                    }
                }
                if (replaced > 0) {
                    notices.add(replaced + " AI draft(s) didn't pass our checks (length, or a detail not in your "
                            + "profile) and were replaced with template drafts.");
                }
            } catch (LlmUnavailableException e) {
                notices.add("AI drafting isn't configured; these drafts are built from your profile with templates.");
            } catch (RuntimeException e) {
                log.warn("AI drafting failed for contact {}: {}", contactId, e.toString());
                out.clear();
                notices.add("AI drafting is unavailable right now; these are template drafts.");
            }
        }
        if (out.isEmpty()) {
            templates.forEach(t -> out.add(template(userId, contactId, t)));
        }
        List<MessageDraft> saved = drafts.saveAll(out);
        notices.add("Review before sending. ReferBoard never sends messages for you.");
        return new DraftSetResponse(contactId, type, usedAi ? "AI" : "TEMPLATE", notices,
                saved.stream().map(mapper::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public List<DraftResponse> list(UUID userId, UUID contactId) {
        contacts.findByIdAndUserId(contactId, userId).orElseThrow(() -> ApiException.notFound("Contact", contactId));
        return drafts.findByContactIdAndUserIdOrderByCreatedAtDesc(contactId, userId).stream()
                .map(mapper::toResponse).toList();
    }

    /** The user copied a draft (analytics for which tone people actually use). */
    @Transactional
    public DraftResponse markCopied(UUID userId, UUID draftId) {
        MessageDraft d = drafts.findByIdAndUserId(draftId, userId)
                .orElseThrow(() -> ApiException.notFound("Draft", draftId));
        if (d.getCopiedAt() == null) {
            d.setCopiedAt(clock.instant());
        }
        return mapper.toResponse(d);
    }

    private long aiDraftsToday(UUID userId) {
        var startOfDay = LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().toInstant(ZoneOffset.UTC);
        return drafts.countByUserIdAndModelNotAndCreatedAtAfter(userId, TemplateDrafter.MODEL, startOfDay);
    }

    private static MessageDraft template(UUID userId, UUID contactId, DraftText t) {
        return new MessageDraft(userId, contactId, t.kind(), t.tone(), t.content(), TemplateDrafter.MODEL,
                TemplateDrafter.VERSION);
    }
}
