package dev.dev48v.inventory.orchestration;

import dev.dev48v.inventory.domain.StockItem;
import dev.dev48v.inventory.stock.InsufficientStockException;
import dev.dev48v.inventory.stock.InventoryService;
import dev.dev48v.inventory.stock.UnknownSkuException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Day 29 — inventory-service's ORCHESTRATION command handler: the participant side of command/reply, added
// ADDITIVELY beside the Day-26/28 choreography listeners (which are untouched). Where choreography had
// inventory REACT to a fact (OrderPlaced) on its own initiative, here it is COMMANDED: it subscribes to the
// shared saga-commands topic, executes only the kinds it owns — RESERVE_STOCK (forward) and RELEASE_STOCK
// (the compensation) — ignores the rest, and REPLIES on saga-replies so the orchestrator can advance.
//
// Same two production disciplines as every consumer in the project:
//   • IDEMPOTENT — Kafka is at-least-once, so a command can arrive twice. We claim the COMMAND id (not the order
//     id — reserve and release are two distinct commands for one order) with an atomic putIfAbsent, so a
//     redelivery is skipped BEFORE it double-reserves or double-releases.
//   • NON-CRASHING — a business failure (insufficient stock, unknown SKU) is EXPECTED: we catch it, reply
//     STOCK_REJECTED, and return normally so the offset advances; the reply publish swallows its own errors.
//
// autoStartup is bound to inventory.orchestration.enabled (default false), so the container only runs when
// orchestration is switched on — otherwise inventory-service behaves exactly as it did under pure choreography.
@Component
public class StockCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(StockCommandHandler.class);

    private final InventoryService inventoryService;
    private final KafkaTemplate<String, SagaReply> replies;
    private final OrchestrationProperties properties;

    // Idempotency: the set of command ids already handled. Keyed on commandId so RESERVE and RELEASE for the
    // same order (different commands) both proceed, but a REDELIVERED copy of either is skipped.
    private final Map<String, Boolean> handled = new ConcurrentHashMap<>();

    public StockCommandHandler(InventoryService inventoryService,
                               KafkaTemplate<String, SagaReply> replies,
                               OrchestrationProperties properties) {
        this.inventoryService = inventoryService;
        this.replies = replies;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${inventory.orchestration.commands-topic:saga-commands}",
            groupId = "${inventory.orchestration.consumer-group-id:inventory-orchestration}",
            containerFactory = "sagaCommandListenerContainerFactory",
            autoStartup = "${inventory.orchestration.enabled:false}")
    public void onCommand(SagaCommand cmd) {
        // Only the kinds this service owns; every other command (payment, shipment) is another participant's.
        if (!SagaCommand.RESERVE_STOCK.equals(cmd.type()) && !SagaCommand.RELEASE_STOCK.equals(cmd.type())) {
            return;
        }
        if (handled.putIfAbsent(cmd.commandId(), Boolean.TRUE) != null) {
            log.info("Duplicate command {} ({}) for order {} - already handled, skipping",
                    cmd.commandId(), cmd.type(), cmd.orderId());
            return;
        }

        if (SagaCommand.RESERVE_STOCK.equals(cmd.type())) {
            reserve(cmd);
        } else {
            release(cmd);
        }
    }

    // Forward step: reserve the units and reply STOCK_RESERVED, or reply STOCK_REJECTED on a business failure.
    private void reserve(SagaCommand cmd) {
        try {
            StockItem item = inventoryService.reserve(cmd.sku(), cmd.quantity());
            log.info("Reserved {} x {} for order {} - {} remaining (command {})",
                    cmd.quantity(), cmd.sku(), cmd.orderId(), item.available(), cmd.commandId());
            publish(SagaReply.reserved(cmd, item.available()));
        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock for order {} ({} x {}) - replying STOCK_REJECTED",
                    cmd.orderId(), cmd.quantity(), cmd.sku());
            publish(SagaReply.rejected(cmd, "INSUFFICIENT_STOCK"));
        } catch (UnknownSkuException e) {
            log.warn("Unknown SKU '{}' on order {} - replying STOCK_REJECTED", cmd.sku(), cmd.orderId());
            publish(SagaReply.rejected(cmd, "UNKNOWN_SKU"));
        }
    }

    // Compensation step: put the units back on hand and acknowledge with STOCK_RELEASED.
    private void release(SagaCommand cmd) {
        try {
            inventoryService.release(cmd.sku(), cmd.quantity());
            log.info("Released {} x {} for cancelled order {} (command {})",
                    cmd.quantity(), cmd.sku(), cmd.orderId(), cmd.commandId());
        } catch (RuntimeException e) {
            // Never crash the consumer on a compensation hiccup; still acknowledge so the orchestrator moves on.
            log.warn("Could not release stock for order {}: {}", cmd.orderId(), e.toString());
        }
        publish(SagaReply.released(cmd));
    }

    // Non-blocking reply publish — mirrors every producer-inside-a-consumer in the project: swallow all errors
    // so the reply channel can never crash or loop the command consumer.
    private void publish(SagaReply reply) {
        if (!properties.enabled()) {
            return;
        }
        String topic = properties.repliesTopic();
        try {
            replies.send(topic, reply.orderId(), reply)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish {} for order {}: {}",
                                    reply.type(), reply.orderId(), ex.toString());
                        }
                    });
        } catch (Exception ex) {
            log.warn("Could not publish {} for order {}: {}", reply.type(), reply.orderId(), ex.toString());
        }
    }
}
