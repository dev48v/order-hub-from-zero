package dev.dev48v.notification.notify;

// Day 41 — the rendered output of a template: a subject line + a body, channel-agnostic. The dispatcher takes
// one of these and fans it out to whichever channels are enabled (email uses both fields; SMS uses the body as
// the text). Keeping "what the message SAYS" (this record, produced by NotificationTemplates) separate from
// "how it is SENT" (the NotificationSender / SmsClient) is the small separation that lets the copy change
// without touching the transport, and the transport swap (log vs real SMTP) without touching the copy.
public record NotificationMessage(
        String subject,
        String body
) {
}
