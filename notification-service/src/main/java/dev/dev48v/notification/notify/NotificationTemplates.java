package dev.dev48v.notification.notify;

import dev.dev48v.notification.events.OrderPlacedEvent;
import dev.dev48v.notification.events.PaymentProcessedEvent;
import dev.dev48v.notification.events.ShipmentScheduledEvent;
import org.springframework.stereotype.Component;

// Day 41 — the message TEMPLATES: one small, pure mapping from an event to the customer-facing copy for that
// moment in the order's life. "Templating" here is deliberately simple string interpolation (String.format) —
// no template engine, no I/O — because the value being taught is the SHAPE (an event type selects a template;
// the event's fields fill the blanks), not a particular library. Swapping in Thymeleaf/Freemarker/an HTML
// email later changes only this class; the dispatcher and transports are untouched.
//
// One method per event type the service consumes, each returning a channel-agnostic NotificationMessage
// (subject + body). The PaymentProcessed copy forks on the decision — a "received" note vs a "could not be
// processed" note carrying the decline reason — so the customer always gets the right message.
@Component
public class NotificationTemplates {

    // OrderPlaced -> "your order #X was placed".
    public NotificationMessage orderPlaced(OrderPlacedEvent event) {
        String subject = "Order #" + event.orderId() + " placed";
        String body = String.format(
                "Hi %s, your order #%s was placed — %d x %s. We'll email you again once payment is confirmed.",
                event.customer(), event.orderId(), event.quantity(), event.item());
        return new NotificationMessage(subject, body);
    }

    // PaymentProcessed -> "your order #X was paid" (APPROVED) or a decline notice (DECLINED).
    public NotificationMessage paymentProcessed(PaymentProcessedEvent event) {
        if (event.isApproved()) {
            String subject = "Payment received for order #" + event.orderId();
            String body = String.format(
                    "Hi %s, your order #%s was paid — we received %s. Your order is confirmed and being prepared "
                            + "for shipment.",
                    event.customer(), event.orderId(), event.amount());
            return new NotificationMessage(subject, body);
        }
        String subject = "Payment could not be processed for order #" + event.orderId();
        String body = String.format(
                "Hi %s, we could not process payment for your order #%s (%s). The order has been cancelled — "
                        + "please try again or update your payment details.",
                event.customer(), event.orderId(), event.reason());
        return new NotificationMessage(subject, body);
    }

    // ShipmentScheduled / OrderShipped -> "your order #X was shipped", with the tracking number.
    public NotificationMessage orderShipped(ShipmentScheduledEvent event) {
        String subject = "Order #" + event.orderId() + " shipped";
        String body = String.format(
                "Hi %s, your order #%s was shipped! Track it with %s. Thanks for shopping with OrderHub.",
                event.customer(), event.orderId(), event.trackingNumber());
        return new NotificationMessage(subject, body);
    }
}
