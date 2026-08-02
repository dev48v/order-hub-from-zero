package dev.dev48v.notification.notify;

import java.time.Instant;

// Day 41 — the notification bounded context's core value object: ONE message dispatched to ONE recipient on ONE
// channel, about one lifecycle event. It is immutable (a record) because a sent notification is a historical
// fact, not something to mutate afterwards. It carries the `eventId` that caused it (the idempotency key, so the
// ledger can prove exactly-once) and the `orderId` it concerns (so the read API can group a customer's messages
// by order), plus the rendered subject/body — the template's output — and which channel actually carried it.
//
// This is the shipping/payment "domain object with behaviour" idea applied to messaging: the two factory
// methods are the only ways to build one, so a Notification is always fully-formed and channel-correct.
public record Notification(
        String eventId,       // the event that triggered this notification — the idempotency key
        String orderId,       // which order the message is about
        Channel channel,      // EMAIL | SMS — how it was delivered
        String recipient,     // the resolved email address or phone number
        String subject,       // the templated subject (email); reused as a short label for SMS
        String body,          // the templated message body
        Instant sentAt        // when notification-service dispatched it
) {

    // An EMAIL notification: recipient is an email address, subject + body both used.
    public static Notification email(String eventId, String orderId, String recipient,
                                     String subject, String body) {
        return new Notification(eventId, orderId, Channel.EMAIL, recipient, subject, body, Instant.now());
    }

    // An SMS notification: recipient is a phone number; the body is the SMS text (subject kept for context/label).
    public static Notification sms(String eventId, String orderId, String recipient,
                                   String subject, String body) {
        return new Notification(eventId, orderId, Channel.SMS, recipient, subject, body, Instant.now());
    }
}
