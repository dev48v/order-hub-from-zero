package dev.dev48v.shipping.orchestration;

import dev.dev48v.shipping.shipment.Shipment;
import dev.dev48v.shipping.shipment.ShipmentLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Day 29 — shipping-service's ORCHESTRATION command handler: the participant side of command/reply, added
// ADDITIVELY beside the Day-28 choreography listener (untouched). Where choreography had shipping REACT to a
// PaymentProcessed fact and DECIDE whether to ship or compensate, here it is COMMANDED — the orchestrator only
// sends SCHEDULE_SHIPMENT once it has already confirmed stock + payment, so this handler's job is simply to
// schedule the shipment, mint a tracking number, and REPLY SHIPMENT_SCHEDULED. (The compensation decision now
// lives in the orchestrator, not here — that is the whole point of centralizing the flow.)
//
// Idempotent on the COMMAND id (a redelivery never double-ships); non-crashing (scheduling is local, the reply
// publish swallows its own errors). autoStartup is gated by shipping.orchestration.enabled (default false).
@Component
public class ShipmentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentCommandHandler.class);

    private final ShipmentLedger ledger;
    private final KafkaTemplate<String, SagaReply> replies;
    private final OrchestrationProperties properties;

    private final Map<String, Boolean> handled = new ConcurrentHashMap<>();

    public ShipmentCommandHandler(ShipmentLedger ledger,
                                  KafkaTemplate<String, SagaReply> replies,
                                  OrchestrationProperties properties) {
        this.ledger = ledger;
        this.replies = replies;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${shipping.orchestration.commands-topic:saga-commands}",
            groupId = "${shipping.orchestration.consumer-group-id:shipping-orchestration}",
            containerFactory = "sagaCommandListenerContainerFactory",
            autoStartup = "${shipping.orchestration.enabled:false}")
    public void onCommand(SagaCommand cmd) {
        if (!SagaCommand.SCHEDULE_SHIPMENT.equals(cmd.type())) {
            return; // another participant's command
        }
        if (handled.putIfAbsent(cmd.commandId(), Boolean.TRUE) != null) {
            log.info("Duplicate SCHEDULE_SHIPMENT {} for order {} - already scheduled, skipping",
                    cmd.commandId(), cmd.orderId());
            return;
        }

        Shipment shipment = Shipment.shipped(cmd.orderId(), cmd.customer(), cmd.amount());
        ledger.record(shipment);
        log.info("Scheduled shipment {} for order {} (command {})",
                shipment.getTrackingNumber(), cmd.orderId(), cmd.commandId());
        publish(SagaReply.scheduled(cmd, shipment.getTrackingNumber()));
    }

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
