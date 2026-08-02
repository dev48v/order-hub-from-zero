package dev.dev48v.notification.web.dto;

import dev.dev48v.notification.notify.Channel;
import dev.dev48v.notification.notify.Notification;

import java.time.Instant;

// Day 41 — the read-model shape returned by the notifications API. A thin projection of the internal
// Notification value object so the HTTP contract is decoupled from the domain type (the same DTO-at-the-edge
// discipline the other services use for their views). Exposes exactly what an operator wants to see when
// confirming the feature works: which order, which channel, who it went to, the subject, and when.
public record NotificationView(
        String eventId,
        String orderId,
        Channel channel,
        String recipient,
        String subject,
        String body,
        Instant sentAt
) {
    public static NotificationView from(Notification n) {
        return new NotificationView(n.eventId(), n.orderId(), n.channel(), n.recipient(),
                n.subject(), n.body(), n.sentAt());
    }
}
