package com.referboard.referral.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A drafted message for the user to copy and send themselves. */
@Entity
@Table(name = "message_drafts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageDraft {

    public enum Kind { CONNECTION_NOTE, REFERRAL_REQUEST, FOLLOW_UP }

    public enum Tone { WARM, CONCISE }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID contactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tone tone;

    @Column(nullable = false)
    private String content;

    private int charCount;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String promptVersion;

    private Instant copiedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public MessageDraft(UUID userId, UUID contactId, Kind kind, Tone tone, String content, String model,
                        String promptVersion) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.contactId = contactId;
        this.kind = kind;
        this.tone = tone;
        this.content = content;
        this.charCount = content.length();
        this.model = model;
        this.promptVersion = promptVersion;
    }
}
