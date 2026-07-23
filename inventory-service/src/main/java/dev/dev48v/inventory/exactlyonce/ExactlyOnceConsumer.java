package dev.dev48v.inventory.exactlyonce;

import dev.dev48v.inventory.events.OrderPlacedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// Day 32 — the EXACTLY-ONCE consumer. It is the opt-in alternative to Day 26's OrderPlacedListener: same fact
// (OrderPlaced on the order-placed topic), but a persistent, transactional, redelivery-safe reaction.
//
// Two things make it different from the Day-26 listener, and both are deliberate:
//
//   • It takes the raw ConsumerRecord, not just the payload, because the dedup key is the record's PHYSICAL
//     coordinates (topic, partition, offset). Those coordinates are what the processor stores and checks, so
//     a redelivery of the same record is recognised no matter what the payload says.
//
//   • It takes an Acknowledgment and commits the offset MANUALLY — and only AFTER processor.process() returns,
//     i.e. after the DB transaction has committed. This ordering is the whole game: DB-commit THEN ack. If the
//     app dies in the gap between them, the offset was never committed, so Kafka redelivers the record; the
//     processor's dedup then finds the marker and skips. If we acked FIRST (or let the client auto-commit on a
//     timer), a crash before the DB commit would advance the offset past an unprocessed record and the
//     reservation would be LOST — the opposite failure. Manual ack after commit is what turns Kafka's
//     at-least-once delivery into effectively-once processing.
//
// The container is configured for this in ExactlyOnceKafkaConfig (enable.auto.commit=false, ackMode
// MANUAL_IMMEDIATE, isolation.level=read_committed). autoStartup is bound to orderhub.exactly-once.enabled, so
// when the feature is off (the default) this listener never starts and the Day-26 path is untouched.
@Component
public class ExactlyOnceConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExactlyOnceConsumer.class);

    private final ExactlyOnceProcessor processor;

    public ExactlyOnceConsumer(ExactlyOnceProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            topics = "${orderhub.exactly-once.order-placed-topic:order-placed}",
            groupId = "${orderhub.exactly-once.consumer-group-id:inventory-exactly-once}",
            containerFactory = "exactlyOnceListenerContainerFactory",
            autoStartup = "${orderhub.exactly-once.enabled:false}")
    public void onOrderPlaced(ConsumerRecord<String, OrderPlacedEvent> record, Acknowledgment ack) {
        // READ-PROCESS-WRITE, atomically, keyed by this record's physical coordinates. Returns the outcome
        // (RESERVED / a graceful business outcome / DUPLICATE_SKIPPED for a redelivery).
        ProcessOutcome outcome = processor.process(
                record.topic(), record.partition(), record.offset(), record.value());

        // Commit the offset ONLY NOW — after the DB transaction above has committed. Everything up to this
        // line is safe to repeat; this line is what tells Kafka "you may stop redelivering this record".
        ack.acknowledge();

        log.debug("Acked {}-{}@{} for order {} -> {}",
                record.topic(), record.partition(), record.offset(),
                record.value().orderId(), outcome);
    }
}
