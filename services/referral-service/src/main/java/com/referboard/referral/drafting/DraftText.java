package com.referboard.referral.drafting;

import com.referboard.referral.domain.MessageDraft;

/** One drafted text before it is stored. */
public record DraftText(MessageDraft.Kind kind, MessageDraft.Tone tone, String content) {
}
