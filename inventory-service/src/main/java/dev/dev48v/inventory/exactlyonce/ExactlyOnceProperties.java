package dev.dev48v.inventory.exactlyonce;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Day 32 — the switches for the EXACTLY-ONCE consumer, bound from orderhub.exactly-once.* (application.yml).
// Kept as a separate, opt-in prefix (NOT under inventory.events.*) so the feature layers cleanly on top of
// the shipped Day-26 path instead of replacing it:
//
//   enabled           — master switch. DEFAULT FALSE, so a normal boot behaves exactly like Day 31: the
//                       in-memory OrderPlacedListener is the only consumer and the persistent dedup path
//                       never starts (its @KafkaListener autoStartup is bound to this flag). Flip it true to
//                       run the DB-backed, transactional, redelivery-safe consumer instead.
//   orderPlacedTopic  — the topic to consume. Same fact order-service publishes and the Day-26 listener reads.
//   consumerGroupId   — this path's OWN Kafka consumer group, distinct from the Day-26 group, so the two
//                       consumers never fight over partitions when both happen to be enabled.
@ConfigurationProperties(prefix = "orderhub.exactly-once")
public class ExactlyOnceProperties {

    private boolean enabled = false;
    private String orderPlacedTopic = "order-placed";
    private String consumerGroupId = "inventory-exactly-once";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOrderPlacedTopic() {
        return orderPlacedTopic;
    }

    public void setOrderPlacedTopic(String orderPlacedTopic) {
        this.orderPlacedTopic = orderPlacedTopic;
    }

    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    public void setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
    }
}
