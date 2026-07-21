package dev.dev48v.orderhub.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Day 30 — the READ half of the transactional outbox: the RELAY (a.k.a. the poller / message relay). The
// writer only RECORDS events durably, inside the order's transaction; this is what actually gets them onto
// Kafka. On a fixed schedule it drains the oldest unsent rows, publishes each to the topic recorded on the row
// (keyed by the order id, so per-order ordering is preserved), and only AFTER the broker acknowledges does it
// flip the row's `processed` flag. That ordering is the delivery guarantee:
//
//   • AT-LEAST-ONCE, never lost: a row stays unsent until Kafka has acked it, so a crash before the ack simply
//     leaves it to be re-sent on the next tick — the event is never silently dropped (the failure mode Day 25's
//     fire-and-forget publish had).
//   • The flip is committed in the SAME transaction as the poll, so a sent row is never re-scanned; and because
//     the re-sent bytes + the stable eventId are identical, a duplicate from a crash-after-ack window is
//     harmlessly deduped by idempotent consumers (Day 32).
//
// It publishes the row's payload as a RAW JSON STRING (KafkaTemplate<String, String>): the bytes recorded with
// the order are re-sent verbatim, not re-derived. Those bytes are the same JSON Day 25's JsonSerializer would
// have produced, so existing consumers (the Day 29 orchestrator, etc.) read them unchanged.
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    // How long we'll wait for the broker to acknowledge one publish before treating it as a failure and leaving
    // the row for the next tick. Bounded so a slow/absent broker can't wedge the relay (or its DB transaction).
    private static final long SEND_ACK_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> outboxKafkaTemplate,
                       OutboxProperties properties) {
        this.repository = repository;
        this.outboxKafkaTemplate = outboxKafkaTemplate;
        this.properties = properties;
    }

    // The scheduled entry point. fixedDelay = the gap AFTER one run finishes before the next starts, so runs
    // never overlap. @Transactional here (not just on the private helper) is deliberate: the scheduler calls
    // this method THROUGH the Spring proxy, so the transaction boundary is established for the whole poll —
    // the load of unsent rows and the markSent flushes all commit together. The gate is checked first so the
    // relay is completely inert until the outbox is switched on (and can be paused independently via
    // relay-enabled) — that's how it stays dormant in every context that isn't exercising it.
    @Scheduled(fixedDelayString = "${orderhub.outbox.relay-poll-delay-ms:1000}")
    @Transactional
    public void relayPendingEvents() {
        if (!properties.enabled() || !properties.relayEnabled()) {
            return;
        }
        publishPending();
    }

    // On-demand relay of one batch, IGNORING the auto-poll gate — used by tests and by any ops trigger that
    // wants to flush the outbox immediately. Also proxied @Transactional, so a direct call gets its own
    // transaction. Returns how many rows were successfully published + marked sent.
    @Transactional
    public int relayOnce() {
        return publishPending();
    }

    // The actual work, run within whichever transaction the caller opened. Drains one bounded batch of the
    // oldest unsent rows; for each, publishes and — only on a confirmed ack — marks it sent. A row whose send
    // fails is left untouched (still processed=false), so the next poll retries it. The rows are managed JPA
    // entities loaded in this transaction, so markSent() is picked up by dirty-checking and flushed on commit.
    private int publishPending() {
        List<OutboxEvent> batch = repository.findByProcessedFalseOrderByCreatedAtAsc(
                PageRequest.of(0, properties.relayBatchSize()));
        if (batch.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent row : batch) {
            try {
                // Publish the recorded bytes verbatim, keyed by the order id (per-order ordering). Block for the
                // broker ack so we only mark the row sent once the event is durably on the topic.
                outboxKafkaTemplate.send(row.getTopic(), row.getAggregateId(), row.getPayload())
                        .get(SEND_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                row.markSent(Instant.now());
                published++;
                log.debug("Relayed outbox row {} (event {}) for order {} to topic '{}'",
                        row.getId(), row.getEventId(), row.getAggregateId(), row.getTopic());
            } catch (InterruptedException ex) {
                // Restore the interrupt flag and stop; the unsent rows (this one included) wait for the next tick.
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted at row {} for order {} (will retry next poll)",
                        row.getId(), row.getAggregateId());
                break;
            } catch (Exception ex) {
                // Broker down / timeout / send error: leave the row unsent (processed stays false) so the next
                // tick retries it. Nothing is lost — that's the point of the outbox. Stop draining this batch so
                // we don't hammer a broker that's clearly unavailable; the remaining rows wait for the next tick.
                log.warn("Failed to relay outbox row {} for order {} (will retry next poll): {}",
                        row.getId(), row.getAggregateId(), ex.toString());
                break;
            }
        }
        return published;
    }
}
