package dev.dev48v.notification.notify;

import dev.dev48v.notification.config.NotificationProperties;
import dev.dev48v.notification.events.OrderPlacedEvent;
import dev.dev48v.notification.events.PaymentProcessedEvent;
import dev.dev48v.notification.events.ShipmentScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Day 41 — the heart of the service: given a lifecycle event, dispatch the right customer notification EXACTLY
// ONCE. It sits between the @KafkaListener (which just hands it typed events) and the transports (email sender +
// SMS client), and it owns the two production-shaping behaviours every consumer in this project has:
//
//   • IDEMPOTENT — Kafka is AT-LEAST-ONCE, so the same event can arrive twice. We claim the event id
//     (NotificationLedger.claim) and notify each event exactly once; a redelivery is skipped BEFORE anything is
//     sent, so the customer never gets a duplicate email/SMS.
//   • NON-CRASHING but HONEST on failure — if a transport throws (a real SMTP hiccup), we RELEASE the claim and
//     rethrow so the container's DefaultErrorHandler retries with backoff and, once exhausted, dead-letters the
//     record — rather than leaving it "claimed" (which would make a genuine failure look handled).
//
// The flow per event: resolve the recipient, render the template, send EMAIL (always) + SMS (optional, gated by
// orderhub.notifications.sms-enabled), and record each dispatched Notification in the ledger for observability
// (the read API) and for the test to assert against. Recipients are DERIVED from the customer name here (a mock
// stand-in) because the events carry a name, not contact details; a real system would look up the customer's
// email/phone from a profile service.
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLedger ledger;
    private final NotificationTemplates templates;
    private final NotificationSender emailSender;
    private final SmsClient smsClient;
    private final NotificationProperties properties;

    public NotificationService(NotificationLedger ledger,
                               NotificationTemplates templates,
                               NotificationSender emailSender,
                               SmsClient smsClient,
                               NotificationProperties properties) {
        this.ledger = ledger;
        this.templates = templates;
        this.emailSender = emailSender;
        this.smsClient = smsClient;
        this.properties = properties;
    }

    // ---- one entry point per consumed event type; each renders its template then dispatches uniformly -------

    public void onOrderPlaced(OrderPlacedEvent event) {
        dispatch(event.eventId(), event.orderId(), event.customer(), templates.orderPlaced(event));
    }

    public void onPaymentProcessed(PaymentProcessedEvent event) {
        dispatch(event.eventId(), event.orderId(), event.customer(), templates.paymentProcessed(event));
    }

    public void onOrderShipped(ShipmentScheduledEvent event) {
        dispatch(event.eventId(), event.orderId(), event.customer(), templates.orderShipped(event));
    }

    // ---- the shared, idempotent, exactly-once dispatch --------------------------------------------------------

    private void dispatch(String eventId, String orderId, String customer, NotificationMessage message) {
        // IDEMPOTENCY: claim the event id atomically. If it was already handled (a redelivery), skip — notifying
        // again would send the customer a duplicate. This check happens BEFORE any transport is touched.
        if (!ledger.claim(eventId)) {
            log.info("Duplicate event {} for order {} - already notified, skipping", eventId, orderId);
            return;
        }

        try {
            // EMAIL — always attempted, through the abstraction (log fallback or real SMTP, chosen by config).
            String email = toEmail(customer);
            Notification emailNote = Notification.email(eventId, orderId, email,
                    message.subject(), message.body());
            emailSender.send(emailNote);
            ledger.record(emailNote);

            // SMS — the OPTIONAL second channel, only when enabled. Mock adapter today (logs, no real send).
            if (properties.smsEnabled()) {
                String phone = toPhone(customer);
                Notification smsNote = Notification.sms(eventId, orderId, phone,
                        message.subject(), message.body());
                smsClient.send(phone, message.body());
                ledger.record(smsNote);
            }

            log.info("Notified customer for order {} (event {}): '{}'", orderId, eventId, message.subject());
        } catch (RuntimeException ex) {
            // A TECHNICAL failure in a transport (e.g. SMTP down). Release the claim so the retry re-processes,
            // then rethrow so the container's error handler retries with backoff and finally dead-letters —
            // rather than swallowing the miss or leaving the event falsely "handled".
            ledger.unclaim(eventId);
            throw ex;
        }
    }

    // Mock recipient resolution — the events carry a customer NAME, not contact details, so we derive a
    // deterministic placeholder address. A real service would resolve these from a customer/profile lookup.
    private String toEmail(String customer) {
        String local = customer == null || customer.isBlank()
                ? "customer"
                : customer.trim().toLowerCase().replaceAll("\\s+", ".");
        return local + "@example.com";
    }

    private String toPhone(String customer) {
        // A stable pseudo-number derived from the name — enough for the mock adapter/log; never dialled.
        int suffix = Math.abs((customer == null ? "" : customer).hashCode()) % 10000;
        return String.format("+1-555-%04d", suffix);
    }
}
