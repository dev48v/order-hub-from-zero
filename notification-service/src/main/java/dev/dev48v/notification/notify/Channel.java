package dev.dev48v.notification.notify;

// Day 41 — the delivery channels a notification can go out on. EMAIL is always attempted; SMS is the OPTIONAL
// second channel, sent only when orderhub.notifications.sms-enabled is on. Kept as a tiny enum so the channel
// is a first-class, type-safe field on every Notification (and on the read API) rather than a loose string.
public enum Channel {
    EMAIL,
    SMS
}
