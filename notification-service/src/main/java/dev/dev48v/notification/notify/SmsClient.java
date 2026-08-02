package dev.dev48v.notification.notify;

// Day 41 — the SMS transport abstraction: a deliberately Twilio-SHAPED interface (send a text to a phone
// number), so a real provider adapter could drop in later with no change to the dispatcher. SMS is the OPTIONAL
// second channel — the dispatcher only calls this when orderhub.notifications.sms-enabled is on. Kept as its
// own interface (separate from the email NotificationSender) because a phone message is a different medium with
// a different address space; modelling both as "just a sender" would blur that. The only implementation shipped
// today is a MOCK (MockSmsClient) that logs instead of hitting a paid API — nothing real is ever sent.
public interface SmsClient {

    // Send `message` to `toPhoneNumber`. Implementations must be safe to call from a Kafka consumer thread.
    void send(String toPhoneNumber, String message);
}
