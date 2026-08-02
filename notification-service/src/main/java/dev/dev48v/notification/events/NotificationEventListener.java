package dev.dev48v.notification.events;

import dev.dev48v.notification.notify.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Day 41 — the three entry points into notification-service: one @KafkaListener per lifecycle topic the system
// already publishes. This class is deliberately THIN — it just subscribes and delegates to NotificationService,
// which owns idempotency, templating, and the email/SMS fan-out. Keeping the Kafka wiring (topics, group,
// container factory, autoStartup) here and the business behaviour there is the same separation the other
// consumers use.
//
// All three listeners share notification-service's ONE consumer group and are all gated by the SAME master
// switch: autoStartup is bound to orderhub.notifications.enabled (default FALSE), so when the feature is off
// none of the containers start — nothing is consumed and the app boots without a broker. That single property
// is what makes this whole capability composable and dark-by-default: adding notification-service changes
// nothing about the running system until an operator turns it on. Each listener names its own JSON-typed
// container factory from KafkaConsumerConfig so the method receives the concrete event with no manual parsing.
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // OrderPlaced (order-service) -> "your order was placed" notification.
    @KafkaListener(
            topics = "${orderhub.notifications.order-placed-topic:order-placed}",
            groupId = "${orderhub.notifications.consumer-group-id:notification-service}",
            containerFactory = "orderPlacedListenerContainerFactory",
            autoStartup = "${orderhub.notifications.enabled:false}")
    public void onOrderPlaced(OrderPlacedEvent event) {
        notificationService.onOrderPlaced(event);
    }

    // PaymentProcessed (payment-service) -> "payment received" or "payment declined" notification.
    @KafkaListener(
            topics = "${orderhub.notifications.payment-events-topic:payment-events}",
            groupId = "${orderhub.notifications.consumer-group-id:notification-service}",
            containerFactory = "paymentEventListenerContainerFactory",
            autoStartup = "${orderhub.notifications.enabled:false}")
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        notificationService.onPaymentProcessed(event);
    }

    // ShipmentScheduled / OrderShipped (shipping-service) -> "your order shipped" notification (with tracking).
    @KafkaListener(
            topics = "${orderhub.notifications.shipment-events-topic:shipping-events}",
            groupId = "${orderhub.notifications.consumer-group-id:notification-service}",
            containerFactory = "shipmentEventListenerContainerFactory",
            autoStartup = "${orderhub.notifications.enabled:false}")
    public void onOrderShipped(ShipmentScheduledEvent event) {
        notificationService.onOrderShipped(event);
    }
}
