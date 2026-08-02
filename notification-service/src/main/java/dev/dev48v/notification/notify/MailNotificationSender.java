package dev.dev48v.notification.notify;

import dev.dev48v.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

// Day 41 — the REAL email transport: sends over SMTP through Spring's JavaMailSender (from
// spring-boot-starter-mail). It is opt-in and OFF by default: @ConditionalOnProperty gates it on
// orderhub.notifications.email-enabled=true, so it only becomes the live NotificationSender when an operator
// deliberately turns email on AND supplies spring.mail.* (host/port/credentials) for the auto-configured
// JavaMailSender. Until then LoggingNotificationSender is used and NO SMTP is required — which is exactly why
// the build and tests never touch a mail server.
//
// It builds a plain SimpleMailMessage (from address from orderhub.notifications.email-from, to the resolved
// recipient, the templated subject + body) and hands it to JavaMailSender. This is the one place that knows
// about mail; the dispatcher and templates stay transport-agnostic.
@Component
@ConditionalOnProperty(prefix = "orderhub.notifications", name = "email-enabled", havingValue = "true")
public class MailNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MailNotificationSender.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public MailNotificationSender(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.emailFrom());
        message.setTo(notification.recipient());
        message.setSubject(notification.subject());
        message.setText(notification.body());
        mailSender.send(message);
        log.info("[EMAIL·smtp] sent to={} subject='{}' (order {}, event {})",
                notification.recipient(), notification.subject(),
                notification.orderId(), notification.eventId());
    }
}
