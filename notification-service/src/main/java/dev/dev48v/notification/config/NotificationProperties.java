package dev.dev48v.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 41 — the knobs for notification-service, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "orderhub.notifications") — the mirror of the other services' *EventProperties.
// A pure consumer needs to know which channels it reads from, whether the feature is on, and how each transport
// behaves:
//
//   • enabled            — the MASTER feature switch. It drives every @KafkaListener's autoStartup, so with it
//                          OFF (the DEFAULT) the listener containers never start, nothing is consumed and the
//                          app boots without touching a broker. This is what keeps the feature dark until
//                          deliberately turned on and keeps the whole existing test suite green — notification
//                          is ADDED to the system without changing how anything else behaves.
//   • orderPlacedTopic   — the OrderPlaced topic to subscribe to (order-service's). Must match its producer.
//   • paymentEventsTopic — the PaymentProcessed topic (payment-service's). Same contract shipping-service reads.
//   • shipmentEventsTopic— the ShipmentScheduled/OrderShipped topic (shipping-service's happy-path emissions).
//   • consumerGroupId    — notification-service's OWN Kafka consumer group, distinct from every other service's,
//                          so it gets its own independent copy of every event across all three topics.
//   • emailEnabled       — selects the EMAIL transport: false (default) => LoggingNotificationSender (no SMTP);
//                          true => MailNotificationSender over real SMTP (needs spring.mail.*). Read by the
//                          @ConditionalOnProperty on those two beans.
//   • emailFrom          — the From address the real mail sender stamps on outgoing email.
//   • smsEnabled         — turns the OPTIONAL SMS channel on/off (mock adapter today). Default off.
//   • retryAttempts      — how many EXTRA deliveries a failing record gets before the dead-letter topic.
//   • retryBackoffMs     — how long (ms) the error handler waits between those retries.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(NotificationProperties.class) on KafkaConsumerConfig.
@ConfigurationProperties(prefix = "orderhub.notifications")
public record NotificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("order-placed") String orderPlacedTopic,
        @DefaultValue("payment-events") String paymentEventsTopic,
        @DefaultValue("shipping-events") String shipmentEventsTopic,
        @DefaultValue("notification-service") String consumerGroupId,
        @DefaultValue("false") boolean emailEnabled,
        @DefaultValue("orderhub@example.com") String emailFrom,
        @DefaultValue("false") boolean smsEnabled,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("500") long retryBackoffMs
) {
}
