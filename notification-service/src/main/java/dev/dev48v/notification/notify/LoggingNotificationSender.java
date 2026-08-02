package dev.dev48v.notification.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Day 41 — the DEFAULT email transport: a no-op/log sender that "delivers" by writing the message to the log.
// It is the fallback that keeps the whole service self-contained — build, tests and a local boot need NO SMTP
// server. It wins whenever orderhub.notifications.email-enabled is absent or false (matchIfMissing = true), and
// backs off the moment MailNotificationSender is switched on, so the two are mutually exclusive and exactly one
// NotificationSender bean ever exists. In the @EmbeddedKafka test THIS is the "mock sender" the assertions run
// through — the dispatcher records every notification it hands here into the NotificationLedger, which the test
// then inspects for content and exactly-once behaviour.
@Component
@ConditionalOnProperty(prefix = "orderhub.notifications", name = "email-enabled",
        havingValue = "false", matchIfMissing = true)
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification notification) {
        // The "send": a structured log line standing in for a real email. Deterministic and side-effect-free,
        // so it is safe on a consumer thread and needs no external system.
        log.info("[EMAIL·log] to={} | subject='{}' | body='{}' (order {}, event {})",
                notification.recipient(), notification.subject(), notification.body(),
                notification.orderId(), notification.eventId());
    }
}
