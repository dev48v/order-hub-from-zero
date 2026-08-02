package dev.dev48v.notification.notify;

// Day 41 — the EMAIL transport ABSTRACTION. The dispatcher depends on THIS interface, never on a concrete
// mailer, so the transport is a swappable strategy: the same dispatch code runs whether emails are actually
// sent over SMTP (MailNotificationSender) or merely logged (LoggingNotificationSender, the default fallback).
// Which impl is live is decided by ONE property — orderhub.notifications.email-enabled — via @ConditionalOn...
// on the two impls, so exactly one bean of this type exists. That is the point of the abstraction: the build,
// the tests and an offline boot need NO real SMTP; turning email-enabled on (with spring.mail.* configured)
// swaps in the real sender without changing a line of the dispatcher.
public interface NotificationSender {

    // Deliver one email notification. Implementations must be safe to call from a Kafka consumer thread.
    void send(Notification notification);
}
