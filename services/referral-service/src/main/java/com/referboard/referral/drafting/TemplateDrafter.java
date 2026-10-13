package com.referboard.referral.drafting;

import com.referboard.referral.domain.MessageDraft.Kind;
import com.referboard.referral.domain.MessageDraft.Tone;
import com.referboard.referral.domain.PersonType;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic drafts from facts only. Used when no LLM is configured, when the LLM fails, as the replacement for
 * any LLM draft that fails {@link DraftGuard}, and for reminder follow-ups drafted by the scheduler.
 *
 * <p>Connection notes are assembled from a required core plus optional parts that are dropped, least important
 * first, until the note fits in {@link DraftGuard#NOTE_LIMIT} characters.
 */
public final class TemplateDrafter {

    public static final String MODEL = "template";
    public static final String VERSION = "template-v1";

    private TemplateDrafter() {
    }

    public static List<DraftText> intro(DraftFacts f) {
        return List.of(
                new DraftText(Kind.CONNECTION_NOTE, Tone.WARM, connectionNote(f, Tone.WARM)),
                new DraftText(Kind.CONNECTION_NOTE, Tone.CONCISE, connectionNote(f, Tone.CONCISE)),
                new DraftText(Kind.REFERRAL_REQUEST, Tone.WARM, referralRequest(f, Tone.WARM)),
                new DraftText(Kind.REFERRAL_REQUEST, Tone.CONCISE, referralRequest(f, Tone.CONCISE)));
    }

    public static List<DraftText> followUps(DraftFacts f) {
        return List.of(
                new DraftText(Kind.FOLLOW_UP, Tone.WARM, followUp(f, Tone.WARM)),
                new DraftText(Kind.FOLLOW_UP, Tone.CONCISE, followUp(f, Tone.CONCISE)));
    }

    // ---------------------------------------------------------------------------------------------------------

    public static String connectionNote(DraftFacts f, Tone tone) {
        String hi = "Hi " + nz(f.contactFirstName(), "there") + ", ";
        String role = "the " + f.jobTitle() + " role at " + f.companyName();
        String hook = hook(f);
        boolean hookNamesRole = f.userContext() == null && f.personType() == PersonType.RECRUITER;
        if (tone == Tone.CONCISE && hook != null && f.personType() != PersonType.ALUMNI
                && f.personType() != PersonType.EX_COLLEAGUE) {
            hook = null;
            hookNamesRole = false;
        }
        String me = selfIntro(f, tone == Tone.WARM);
        String roleCore = tone == Tone.WARM ? "I'm interested in " + role + " and would love to connect."
                : "I'm interested in " + role + ". Would love to connect.";
        // Parts in order of importance; dropped from the end until the note fits.
        List<String> optional = new ArrayList<>();
        if (hook != null) {
            optional.add(hook);
        }
        if (me != null) {
            optional.add(me);
        }
        for (int keep = optional.size(); keep >= 0; keep--) {
            List<String> parts = new ArrayList<>(optional.subList(0, keep));
            boolean roleAlreadyNamed = hookNamesRole && keep > 0;
            parts.add(roleAlreadyNamed ? "I'd love to connect and learn more about the role." : roleCore);
            String note = hi + continueSentence(String.join(" ", parts));
            if (note.length() <= DraftGuard.NOTE_LIMIT) {
                return note;
            }
        }
        // Even the core is too long (very long job title): drop the role detail.
        String fallback = hi + "I'm interested in an open role at " + f.companyName() + ". Would love to connect.";
        return fallback.length() <= DraftGuard.NOTE_LIMIT ? fallback
                : DraftGuard.shortenToLimit(fallback, DraftGuard.NOTE_LIMIT);
    }

    public static String referralRequest(DraftFacts f, Tone tone) {
        String name = nz(f.contactFirstName(), "there");
        String job = "the " + f.jobTitle() + " role at " + f.companyName();
        String link = f.jobUrl() == null ? "" : " (" + f.jobUrl() + ")";
        String skills = skillsSentence(f);
        String sign = signOff(f);
        if (tone == Tone.CONCISE) {
            StringBuilder sb = new StringBuilder();
            sb.append("Hi ").append(name).append(", thanks for connecting. ");
            String me = selfIntro(f, false);
            if (me != null) {
                sb.append(me).append(' ');
            }
            sb.append("I'm applying for ").append(job).append(link).append(". ");
            if (skills != null) {
                sb.append(skills).append(' ');
            }
            sb.append("Would you be open to referring me? Happy to share my resume.");
            sb.append(sign == null ? "" : "\n\nThanks,\n" + sign);
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(name).append(",\n\n");
        String hook = hook(f);
        if (f.userContext() == null && f.personType() == PersonType.ALUMNI && f.college() != null) {
            sb.append("Thanks for connecting, and great to meet a fellow ").append(f.college()).append(" alum! ");
        } else {
            sb.append("Thanks for connecting! ");
            if (hook != null && f.personType() != PersonType.RECRUITER) {
                sb.append(hook).append(' ');
            }
        }
        sb.append("I'm applying for ").append(job).append(link)
                .append(" and wanted to ask whether you'd be open to referring me.\n\n");
        String me = selfIntro(f, true);
        if (me != null || skills != null) {
            sb.append("A bit about me: ");
            if (me != null) {
                sb.append(me).append(' ');
            }
            if (skills != null) {
                sb.append(skills);
            }
            sb.setLength(sb.length() - (sb.charAt(sb.length() - 1) == ' ' ? 1 : 0));
            sb.append("\n\n");
        }
        if (!f.achievements().isEmpty()) {
            sb.append("A recent highlight from my resume: ").append(stripEndPunct(f.achievements().getFirst()))
                    .append(".\n\n");
        }
        sb.append("I'm happy to send my resume or answer any questions, and I completely understand if this isn't "
                + "something you can do.");
        sb.append("\n\nThanks so much,").append(sign == null ? "" : "\n" + sign);
        return sb.toString();
    }

    public static String followUp(DraftFacts f, Tone tone) {
        String name = nz(f.contactFirstName(), "there");
        String job = "the " + f.jobTitle() + " role at " + f.companyName();
        String sign = signOff(f);
        if (tone == Tone.CONCISE) {
            return "Hi " + name + ", just following up on my note about " + job
                    + ". Would you be open to referring me? Happy to send my resume."
                    + (sign == null ? "" : " Thanks, " + sign);
        }
        return "Hi " + name + ",\n\nJust following up on my earlier message about " + job + ". I know you're busy, "
                + "so no worries if now isn't a good time. If it helps, I can send my resume or a short summary you "
                + "could forward.\n\nThanks again,"
                + (sign == null ? "" : "\n" + sign);
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The reason this person should read on; null when there is nothing specific (and true) to say. */
    static String hook(DraftFacts f) {
        if (f.userContext() != null) {
            return endWithPeriod(capitalize(f.userContext()));
        }
        return switch (f.personType()) {
            case ALUMNI -> f.college() == null ? null : "Fellow " + f.college() + " alum here!";
            case EX_COLLEAGUE -> f.sharedCompany() == null ? null
                    : "I noticed we've both been at " + f.sharedCompany() + ".";
            case RECRUITER -> "I came across the " + f.jobTitle() + " opening at " + f.companyName() + ".";
            default -> null;
        };
    }

    /** "I'm a Backend Engineer at Acme with 4 years of experience." Built only from profile fields. */
    static String selfIntro(DraftFacts f, boolean withYears) {
        if (f.userTitle() == null) {
            return f.experienceYearsText() == null || !withYears ? null
                    : "I'm an engineer with " + f.experienceYearsText() + " years of experience.";
        }
        StringBuilder sb = new StringBuilder("I'm ").append(article(f.userTitle())).append(' ').append(f.userTitle());
        if (f.userCompany() != null) {
            sb.append(" at ").append(f.userCompany());
        }
        if (withYears && f.experienceYearsText() != null) {
            sb.append(" with ").append(f.experienceYearsText()).append(" years of experience");
        }
        return sb.append('.').toString();
    }

    static String skillsSentence(DraftFacts f) {
        List<String> s = f.sharedSkills();
        if (s.isEmpty()) {
            return null;
        }
        return "I've worked with " + joinAnd(s) + ", which the role lists.";
    }

    /** Text following "Hi Priya, " continues the sentence: "fellow IIT Madras alum here!", but "I'm ..." stays. */
    static String continueSentence(String s) {
        if (s.length() < 2 || s.startsWith("I ") || s.startsWith("I'") || Character.isUpperCase(s.charAt(1))) {
            return s;   // "I ...", or an acronym/name such as "IIT ..." or "JSConf"
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String signOff(DraftFacts f) {
        return f.userFirstName();
    }

    static String joinAnd(List<String> items) {
        if (items.size() == 1) {
            return items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    private static String article(String word) {
        return "AEIOUaeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String endWithPeriod(String s) {
        return s.matches(".*[.!?]$") ? s : s + ".";
    }

    private static String stripEndPunct(String s) {
        return s.replaceAll("[.;,\\s]+$", "");
    }

    private static String nz(String s, String fallback) {
        return s == null ? fallback : s;
    }
}
