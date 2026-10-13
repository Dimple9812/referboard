package com.referboard.notification.email;

import com.referboard.events.DailyMatchDigestEvent;
import com.referboard.events.ReferralReminderDueEvent;
import com.referboard.notification.config.NotificationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders the emails. Kept as plain Java (no template engine) because there are two small templates; everything
 * that came from a user or another service is HTML-escaped, and every email has a text alternative.
 *
 * <p>Inline styles and a single 560px table column, because that is what email clients render reliably.
 */
@Component
public class EmailTemplates {

    private static final DateTimeFormatter DIGEST_DATE = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);
    private static final String ACCENT = "#1f6f5c";

    private final NotificationProperties props;

    public EmailTemplates(NotificationProperties props) {
        this.props = props;
    }

    public RenderedEmail reminder(String firstName, ReferralReminderDueEvent e, String unsubscribeUrl) {
        boolean sendReferral = "SEND_REFERRAL_MESSAGE".equals(e.reminderType());
        String subject = sendReferral
                ? "Send your referral message to " + e.contactName()
                : "Time to follow up with " + e.contactName();
        String link = props.appLink("/referrals/contacts/" + e.contactId());
        String role = e.jobTitle() + " at " + e.companyName();

        StringBuilder html = new StringBuilder();
        html.append(p(greeting(firstName)));
        html.append(p(esc(e.message())));
        if (e.draftedFollowUp() != null && !e.draftedFollowUp().isBlank()) {
            html.append(p("Here's a draft built from your profile. Review it, then copy and send it yourself on "
                    + "LinkedIn:"));
            html.append("<div style=\"border-left:3px solid ").append(ACCENT)
                    .append(";background:#f4f7f6;padding:12px 16px;margin:0 0 16px;font-size:14px;line-height:1.5;"
                            + "color:#222\">")
                    .append(esc(e.draftedFollowUp()).replace("\n", "<br>"))
                    .append("</div>");
        }
        html.append(button("Open in ReferBoard", link));
        html.append(small("This reminder is about " + esc(role) + ". ReferBoard never sends messages for you."));

        StringBuilder text = new StringBuilder();
        text.append(greeting(firstName)).append("\n\n").append(e.message()).append("\n\n");
        if (e.draftedFollowUp() != null && !e.draftedFollowUp().isBlank()) {
            text.append("Draft (copy and send it yourself on LinkedIn):\n\n").append(e.draftedFollowUp())
                    .append("\n\n");
        }
        text.append("Open in ReferBoard: ").append(link).append('\n');
        return new RenderedEmail(subject, layout(subject, html.toString(), unsubscribeUrl, "referral reminders"),
                textFooter(text.toString(), unsubscribeUrl, "referral reminders"), unsubscribeUrl);
    }

    public RenderedEmail digest(String firstName, DailyMatchDigestEvent e, String unsubscribeUrl) {
        List<DailyMatchDigestEvent.MatchSummary> matches = e.matches();
        String subject = "Your top " + matches.size() + " new job " + (matches.size() == 1 ? "match" : "matches")
                + " for " + DIGEST_DATE.format(e.digestDate());

        StringBuilder html = new StringBuilder();
        html.append(p(greeting(firstName)));
        html.append(p("New roles that fit your profile since yesterday, best match first:"));
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;margin:0 0 16px\">");
        StringBuilder text = new StringBuilder();
        text.append(greeting(firstName)).append("\n\nNew roles that fit your profile since yesterday:\n\n");
        for (DailyMatchDigestEvent.MatchSummary m : matches) {
            String link = props.appLink("/jobs/" + m.jobId());
            String where = m.remote() && (m.location() == null || !m.location().toLowerCase(Locale.ROOT).contains("remote"))
                    ? join(m.location(), "Remote") : nz(m.location());
            String skills = m.matchedSkills() == null || m.matchedSkills().isEmpty() ? ""
                    : String.join(", ", m.matchedSkills().subList(0, Math.min(4, m.matchedSkills().size())));
            html.append("<tr><td style=\"padding:12px 0;border-top:1px solid #e6e6e6;vertical-align:top;width:56px\">")
                    .append("<span style=\"display:inline-block;min-width:44px;text-align:center;font-weight:600;"
                            + "font-size:14px;color:").append(ACCENT).append("\">")
                    .append(m.matchPercent()).append("%</span></td>")
                    .append("<td style=\"padding:12px 0;border-top:1px solid #e6e6e6\">")
                    .append("<a href=\"").append(esc(link)).append("\" style=\"color:#111;font-weight:600;"
                            + "text-decoration:none;font-size:15px\">").append(esc(m.title())).append("</a>")
                    .append("<div style=\"color:#555;font-size:13px;margin-top:2px\">").append(esc(m.companyName()))
                    .append(where.isEmpty() ? "" : " · " + esc(where)).append("</div>");
            if (!skills.isEmpty()) {
                html.append("<div style=\"color:#777;font-size:12px;margin-top:4px\">Matches: ").append(esc(skills))
                        .append("</div>");
            }
            html.append("</td></tr>");
            text.append("- ").append(m.matchPercent()).append("%  ").append(m.title()).append(" — ")
                    .append(m.companyName()).append(where.isEmpty() ? "" : " (" + where + ")").append('\n')
                    .append("  ").append(link).append('\n');
        }
        html.append("</table>");
        html.append(button("See all recommendations", props.appLink("/jobs?view=recommended")));
        text.append("\nSee all recommendations: ").append(props.appLink("/jobs?view=recommended")).append('\n');
        return new RenderedEmail(subject, layout(subject, html.toString(), unsubscribeUrl, "the daily digest"),
                textFooter(text.toString(), unsubscribeUrl, "the daily digest"), unsubscribeUrl);
    }

    // ---------------------------------------------------------------------------------------------------------

    private String layout(String title, String content, String unsubscribeUrl, String category) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>" + esc(title) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f2f2f0\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" "
                + "style=\"padding:24px 12px\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;"
                + "background:#ffffff;border-radius:8px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,"
                + "sans-serif;color:#222\"><tr><td style=\"padding:24px 28px\">"
                + "<div style=\"font-weight:700;font-size:16px;color:" + ACCENT + ";margin-bottom:20px\">ReferBoard</div>"
                + content
                + "</td></tr></table>"
                + "<div style=\"max-width:560px;font-family:Arial,sans-serif;font-size:12px;color:#888;padding:12px 8px\">"
                + "You're receiving this because you use ReferBoard. "
                + (unsubscribeUrl == null ? ""
                : "<a href=\"" + esc(unsubscribeUrl) + "\" style=\"color:#888\">Unsubscribe from " + category + "</a> · ")
                + "<a href=\"" + esc(props.appLink("/settings")) + "\" style=\"color:#888\">Email settings</a>"
                + "</div></td></tr></table></body></html>";
    }

    private String textFooter(String text, String unsubscribeUrl, String category) {
        return text + "\n--\nYou're receiving this because you use ReferBoard.\n"
                + (unsubscribeUrl == null ? "" : "Unsubscribe from " + category + ": " + unsubscribeUrl + "\n")
                + "Email settings: " + props.appLink("/settings") + "\n";
    }

    private static String greeting(String firstName) {
        return firstName == null ? "Hi," : "Hi " + firstName + ",";
    }

    private static String p(String safeHtml) {
        return "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.5\">" + safeHtml + "</p>";
    }

    private static String small(String safeHtml) {
        return "<p style=\"margin:16px 0 0;font-size:12px;line-height:1.5;color:#777\">" + safeHtml + "</p>";
    }

    private static String button(String label, String href) {
        return "<a href=\"" + esc(href) + "\" style=\"display:inline-block;background:" + ACCENT + ";color:#ffffff;"
                + "text-decoration:none;padding:10px 18px;border-radius:6px;font-size:14px;font-weight:600\">"
                + esc(label) + "</a>";
    }

    static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : a + " / " + b;
    }
}
