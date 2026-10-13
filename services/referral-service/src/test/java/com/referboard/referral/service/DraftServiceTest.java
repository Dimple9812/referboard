package com.referboard.referral.service;

import com.referboard.ai.LlmCallException;
import com.referboard.referral.Fixtures;
import com.referboard.referral.config.ReferralProperties;
import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.MessageDraft;
import com.referboard.referral.domain.MessageDraft.Kind;
import com.referboard.referral.domain.MessageDraft.Tone;
import com.referboard.referral.domain.PersonType;
import com.referboard.referral.drafting.DraftText;
import com.referboard.referral.drafting.LlmDrafter;
import com.referboard.referral.drafting.TemplateDrafter;
import com.referboard.referral.mapper.ReferralMapper;
import com.referboard.referral.repository.ContactRepository;
import com.referboard.referral.repository.MessageDraftRepository;
import com.referboard.referral.web.dto.ReferralDtos.DraftRequest;
import com.referboard.referral.web.dto.ReferralDtos.DraftResponse;
import com.referboard.referral.web.dto.ReferralDtos.DraftType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftServiceTest {

    ContactRepository contacts = mock(ContactRepository.class);
    MessageDraftRepository drafts = mock(MessageDraftRepository.class);
    SnapshotService snapshots = mock(SnapshotService.class);
    LlmDrafter llm = mock(LlmDrafter.class);
    ReferralMapper mapper = mock(ReferralMapper.class);
    ReferralProperties props = new ReferralProperties(Duration.ofDays(2), Duration.ofDays(5), 1000, 25, 8);
    DraftService service = new DraftService(contacts, drafts, snapshots, llm, mapper, props, Clock.systemUTC());

    Contact contact = Fixtures.contact(PersonType.ALUMNI, "Priya Sharma", "SDE at Northwind");

    @BeforeEach
    void setUp() {
        when(contacts.findByIdAndUserId(contact.getId(), Fixtures.USER)).thenReturn(Optional.of(contact));
        when(snapshots.job(Fixtures.JOB)).thenReturn(Fixtures.job());
        when(snapshots.user(Fixtures.USER)).thenReturn(Fixtures.user());
        when(drafts.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any(MessageDraft.class))).thenAnswer(inv -> {
            MessageDraft d = inv.getArgument(0);
            return new DraftResponse(d.getId(), d.getKind(), d.getTone(), d.getContent(), d.getCharCount(),
                    d.getModel(), null, null);
        });
        when(llm.modelId()).thenReturn("test:llm");
    }

    @Test
    void usesTemplatesWhenNoLlmIsConfigured() {
        when(llm.available()).thenReturn(false);
        var set = service.generate(Fixtures.USER, contact.getId(), null);
        assertThat(set.type()).isEqualTo(DraftType.INTRO);
        assertThat(set.generatedBy()).isEqualTo("TEMPLATE");
        assertThat(set.drafts()).hasSize(4).allSatisfy(d -> assertThat(d.model()).isEqualTo(TemplateDrafter.MODEL));
        assertThat(set.drafts()).extracting(DraftResponse::kind, DraftResponse::tone).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Kind.CONNECTION_NOTE, Tone.WARM),
                org.assertj.core.groups.Tuple.tuple(Kind.CONNECTION_NOTE, Tone.CONCISE),
                org.assertj.core.groups.Tuple.tuple(Kind.REFERRAL_REQUEST, Tone.WARM),
                org.assertj.core.groups.Tuple.tuple(Kind.REFERRAL_REQUEST, Tone.CONCISE));
        assertThat(set.notices()).anyMatch(n -> n.contains("never sends messages"));
        verify(llm, never()).intro(any());
    }

    @Test
    void replacesAiDraftsThatFailTheGuard() {
        when(llm.available()).thenReturn(true);
        String longNote = "Hi Priya, fellow IIT Madras alum here! I'm interested in the Senior Backend Engineer role "
                + "at Northwind. " + "I'd love to hear how the team works and what the interview process is like for "
                + "backend roles, and whether there is anything I should prepare. ".repeat(2) + "Thanks!";
        when(llm.intro(any())).thenReturn(List.of(
                new DraftText(Kind.CONNECTION_NOTE, Tone.WARM, longNote),                           // shortened
                new DraftText(Kind.CONNECTION_NOTE, Tone.CONCISE, "Hi Priya, IITM alum, keen on the Northwind role!"),
                new DraftText(Kind.REFERRAL_REQUEST, Tone.WARM, "Hi Priya, I led a team of 12 engineers..."), // invented
                new DraftText(Kind.REFERRAL_REQUEST, Tone.CONCISE, "Hi [Name], would you refer me?")));      // placeholder
        var set = service.generate(Fixtures.USER, contact.getId(), new DraftRequest(DraftType.INTRO, null));

        assertThat(set.generatedBy()).isEqualTo("AI");
        assertThat(set.drafts().get(0).model()).isEqualTo("test:llm");
        assertThat(set.drafts().get(0).content().length()).isLessThanOrEqualTo(300);
        assertThat(set.drafts().get(0).content()).startsWith("Hi Priya, fellow IIT Madras alum here!").doesNotContain("Thanks!");
        assertThat(set.drafts().get(1).model()).isEqualTo("test:llm");
        assertThat(set.drafts().get(2).model()).isEqualTo(TemplateDrafter.MODEL);
        assertThat(set.drafts().get(2).content()).doesNotContain("12 engineers");
        assertThat(set.drafts().get(3).model()).isEqualTo(TemplateDrafter.MODEL);
        assertThat(set.notices()).anyMatch(n -> n.startsWith("2 AI draft(s)"));
    }

    @Test
    void fallsBackEntirelyWhenTheLlmFails() {
        when(llm.available()).thenReturn(true);
        when(llm.followUps(any())).thenThrow(new LlmCallException("boom", null));
        var set = service.generate(Fixtures.USER, contact.getId(), new DraftRequest(DraftType.FOLLOW_UP, null));
        assertThat(set.generatedBy()).isEqualTo("TEMPLATE");
        assertThat(set.drafts()).hasSize(2).allSatisfy(d -> {
            assertThat(d.kind()).isEqualTo(Kind.FOLLOW_UP);
            assertThat(d.model()).isEqualTo(TemplateDrafter.MODEL);
        });
        assertThat(set.notices()).anyMatch(n -> n.contains("unavailable right now"));
    }

    @Test
    void respectsTheDailyAiAllowance() {
        when(llm.available()).thenReturn(true);
        when(drafts.countByUserIdAndModelNotAndCreatedAtAfter(eq(Fixtures.USER), eq("template"), any()))
                .thenReturn(8L);
        var set = service.generate(Fixtures.USER, contact.getId(), null);
        assertThat(set.generatedBy()).isEqualTo("TEMPLATE");
        assertThat(set.notices()).anyMatch(n -> n.contains("today's AI drafting limit"));
        verify(llm, never()).intro(any());
    }
}
