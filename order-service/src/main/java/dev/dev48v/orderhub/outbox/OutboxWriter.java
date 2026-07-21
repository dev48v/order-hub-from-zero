package dev.dev48v.orderhub.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.events.OrderPlacedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

// Day 30 — the WRITE half of the transactional outbox. OrderService calls append() from inside placeOrder,
// which is @Transactional, so this INSERT joins the very same transaction as the order's own INSERT. That is
// the whole trick: the order row and its "publish OrderPlaced" intent commit ATOMICALLY — either both land or,
// on a rollback/crash, neither does. There is no window where the order exists but the event was lost (or the
// reverse), which is exactly the divergence Day 25's direct dual-write could suffer.
//
// This does NOT talk to Kafka. It only records the event durably; the OutboxRelay publishes it afterwards. The
// event is serialized to JSON here, at write time, and stored VERBATIM in the row, so the relay re-sends the
// exact bytes that were committed with the order — a redelivery is byte-for-byte identical, and its stable
// eventId (minted here, once) lets idempotent consumers dedup it.
//
// enabled() is checked first and returns false when the outbox is off (the default): that lets OrderService
// fall back to the Day-25 direct publish with a single branch, and lets the Day-25 producer slice construct a
// disabled writer without a database — a disabled writer never touches its repository or the mapper.
@Component
public class OutboxWriter {

    // The DOMAIN event type recorded on every outbox row — one value, in one place.
    static final String EVENT_TYPE = "OrderPlaced";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    public OutboxWriter(OutboxEventRepository repository,
                        ObjectMapper objectMapper,
                        OutboxProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // Record an OrderPlaced for a just-saved order into the outbox, IN THE CALLER'S TRANSACTION.
    // Returns true when a row was written (outbox on), false when the outbox is disabled — the caller uses that
    // boolean to decide whether it still needs to publish directly (Day-25 behaviour) or leave it to the relay.
    //
    // MUST run within an active transaction started by the caller (OrderService.placeOrder). We don't put
    // @Transactional here on purpose: a new transaction on this method would DEFEAT the pattern by committing
    // the outbox row independently of the order. It has to be one shared transaction, owned by placeOrder.
    public boolean append(Order order) {
        if (!properties.enabled()) {
            return false;
        }

        // Build the same immutable event Day 25 publishes; its eventId is the STABLE identity we persist and
        // re-send unchanged on every relay attempt (the dedup key for idempotent consumers).
        OrderPlacedEvent event = OrderPlacedEvent.from(order);
        String payload = serialize(event);

        OutboxEvent row = OutboxEvent.pending(
                UUID.randomUUID().toString(),   // this outbox row's own id
                properties.aggregateType(),     // "Order"
                order.getId(),                  // aggregateId — ALSO the Kafka message key (per-order ordering)
                event.eventId(),                // the event's stable id (redelivery-safe dedup key)
                EVENT_TYPE,                      // "OrderPlaced"
                properties.topic(),             // destination topic (matches the OrderPlaced topic, "order-placed")
                payload,                         // the event as JSON, stored verbatim
                Instant.now());                  // written now, inside the order's transaction

        repository.save(row);
        return true;
    }

    private String serialize(OrderPlacedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            // Serialization of our own small, well-formed record should never fail; if it somehow does, fail the
            // whole placeOrder transaction rather than commit an order whose event can't be recorded — that keeps
            // the atomic guarantee intact (no order without its outbox row).
            throw new IllegalStateException("Could not serialize OrderPlaced for outbox: " + event.orderId(), ex);
        }
    }
}
