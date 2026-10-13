package com.referboard.referral.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The referral pipeline for one contact. The user moves contacts by hand (ReferBoard never sends anything), so
 * the rules only stop impossible jumps:
 * <ul>
 *   <li>forward moves along NOT_CONTACTED → REQUEST_SENT → CONNECTED → REFERRAL_ASKED → REFERRED, skipping steps
 *       allowed (e.g. an existing connection goes straight to REFERRAL_ASKED);</li>
 *   <li>any open status → NO_RESPONSE, and NO_RESPONSE → any later forward status (they replied after all);</li>
 *   <li>one step back, to undo a mis-click;</li>
 *   <li>REFERRED is terminal.</li>
 * </ul>
 */
public enum ContactStatus {
    NOT_CONTACTED, REQUEST_SENT, CONNECTED, REFERRAL_ASKED, REFERRED, NO_RESPONSE;

    private static final ContactStatus[] PIPELINE = {NOT_CONTACTED, REQUEST_SENT, CONNECTED, REFERRAL_ASKED, REFERRED};

    public boolean canMoveTo(ContactStatus target) {
        return allowedTargets().contains(target);
    }

    public Set<ContactStatus> allowedTargets() {
        Set<ContactStatus> out = EnumSet.noneOf(ContactStatus.class);
        if (this == REFERRED) {
            return out;
        }
        if (this == NO_RESPONSE) {
            out.addAll(EnumSet.of(REQUEST_SENT, CONNECTED, REFERRAL_ASKED, REFERRED));
            return out;
        }
        int idx = ordinal();
        for (int i = idx + 1; i < PIPELINE.length; i++) {
            out.add(PIPELINE[i]);
        }
        if (idx > 0) {
            out.add(PIPELINE[idx - 1]);
        }
        if (this != NOT_CONTACTED) {
            out.add(NO_RESPONSE);   // can't "not hear back" from someone you never contacted
        }
        return out;
    }
}
