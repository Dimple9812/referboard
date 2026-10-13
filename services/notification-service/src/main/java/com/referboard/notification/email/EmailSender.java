package com.referboard.notification.email;

import com.referboard.notification.config.NotificationProperties;
import com.referboard.notification.domain.EmailDelivery;
import com.referboard.notification.repository.EmailDeliveryRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends queued emails. Due rows are locked with {@code FOR UPDATE SKIP LOCKED} so several instances can run;
 * failures are retried with backoff (1 min, 4 min, 16 min, ...) up to {@code max-attempts}, then marked FAILED.
 * Without an SMTP host (plain local runs) emails are marked SKIPPED, so the rendered email is still inspectable.
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    static final int BATCH = 20;

    private final EmailDeliveryRepository deliveries;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties mail;
    private final NotificationProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EmailSender(EmailDeliveryRepository deliveries, ObjectProvider<JavaMailSender> mailSender,
                       MailProperties mail, NotificationProperties props, TransactionTemplate tx, Clock clock) {
        this.deliveries = deliveries;
        this.mailSender = mailSender;
        this.mail = mail;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${referboard.notification.sender-interval-ms:10000}",
            initialDelayString = "${referboard.notification.sender-initial-delay-ms:5000}")
    public void scheduledRun() {
        try {
            int n;
            do {
                n = sendDue();
            } while (n == BATCH);
        } catch (RuntimeException e) {
            log.warn("Email sending run failed; will retry next run", e);
        }
    }

    /** Sends one batch of due emails; returns how many were processed. */
    public int sendDue() {
        Integer n = tx.execute(status -> {
            Instant now = clock.instant();
            List<EmailDelivery> due = deliveries.lockDue(now, BATCH);
            for (EmailDelivery d : due) {
                process(d, now);
            }
            return due.size();
        });
        return n == null ? 0 : n;
    }

    private void process(EmailDelivery d, Instant now) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mail.getHost() == null || mail.getHost().isBlank()) {
            d.skip("MAIL_NOT_CONFIGURED");
            log.info("Email '{}' to user {} not sent: no SMTP host configured (set SMTP_HOST)", d.getSubject(),
                    d.getUserId());
            return;
        }
        d.setAttempts(d.getAttempts() + 1);
        try {
            sender.send(toMime(sender, d));
            d.setStatus(EmailDelivery.Status.SENT);
            d.setSentAt(now);
            d.setLastError(null);
            log.info("Sent {} email {} to user {}", d.getTemplate(), d.getId(), d.getUserId());
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            d.setLastError(e.getMessage() == null ? e.getClass().getSimpleName() : truncate(e.getMessage(), 2000));
            if (d.getAttempts() >= props.maxAttempts()) {
                d.setStatus(EmailDelivery.Status.FAILED);
                log.warn("Email {} failed permanently after {} attempts: {}", d.getId(), d.getAttempts(),
                        d.getLastError());
            } else {
                d.setNextAttemptAt(now.plus(backoff(d.getAttempts())));
                log.info("Email {} attempt {} failed, retrying at {}: {}", d.getId(), d.getAttempts(),
                        d.getNextAttemptAt(), d.getLastError());
            }
        }
    }

    static Duration backoff(int attempts) {
        return Duration.ofMinutes((long) Math.pow(4, Math.max(0, attempts - 1)));
    }

    private MimeMessage toMime(JavaMailSender sender, EmailDelivery d)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        InternetAddress[] from = InternetAddress.parse(props.mailFrom());
        h.setFrom(from[0]);
        h.setTo(d.getToAddress());
        h.setSubject(d.getSubject().replaceAll("[\\r\\n]+", " "));   // no header injection via names/titles
        h.setText(d.getTextBody(), d.getHtmlBody());
        if (d.getUnsubscribeUrl() != null) {
            // RFC 8058 one-click unsubscribe (Gmail/Yahoo bulk-sender requirements)
            msg.addHeader("List-Unsubscribe", "<" + d.getUnsubscribeUrl() + ">");
            msg.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        msg.addHeader("X-ReferBoard-Template", d.getTemplate());
        return msg;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
