package dev.dev48v.payment.orchestration;

import dev.dev48v.payment.payment.Payment;
import dev.dev48v.payment.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Day 29 — payment-service's ORCHESTRATION command handler: the participant side of command/reply, added
// ADDITIVELY beside the Day-27 choreography listener (untouched). Where choreography had payment REACT to
// OrderPlaced on its own, here it is COMMANDED — it subscribes to saga-commands, runs only PROCESS_PAYMENT
// (ignoring the other kinds), and REPLIES PAYMENT_APPROVED / PAYMENT_DECLINED on saga-replies so the
// orchestrator can advance or compensate.
//
// It reuses the SAME deterministic PaymentService decision as choreography (unit price × qty, decline over the
// threshold or for the test-card customer), so the two coordination styles charge identically. Idempotent on
// the COMMAND id (a redelivery never double-charges); non-crashing (the decision is a pure function and the
// reply publish swallows its own errors). autoStartup is gated by payment.orchestration.enabled (default false).
@Component
public class PaymentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandHandler.class);

    private final PaymentService paymentService;
    private final KafkaTemplate<String, SagaReply> replies;
    private final OrchestrationProperties properties;

    private final Map<String, Boolean> handled = new ConcurrentHashMap<>();

    public PaymentCommandHandler(PaymentService paymentService,
                                 KafkaTemplate<String, SagaReply> replies,
                                 OrchestrationProperties properties) {
        this.paymentService = paymentService;
        this.replies = replies;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${payment.orchestration.commands-topic:saga-commands}",
            groupId = "${payment.orchestration.consumer-group-id:payment-orchestration}",
            containerFactory = "sagaCommandListenerContainerFactory",
            autoStartup = "${payment.orchestration.enabled:false}")
    public void onCommand(SagaCommand cmd) {
        if (!SagaCommand.PROCESS_PAYMENT.equals(cmd.type())) {
            return; // another participant's command
        }
        if (handled.putIfAbsent(cmd.commandId(), Boolean.TRUE) != null) {
            log.info("Duplicate PROCESS_PAYMENT {} for order {} - already charged, skipping",
                    cmd.commandId(), cmd.orderId());
            return;
        }

        BigDecimal amount = paymentService.amountFor(cmd.quantity());
        Payment payment = paymentService.process(cmd.orderId(), cmd.customer(), amount);
        if (payment.getStatus() == dev.dev48v.payment.payment.PaymentStatus.APPROVED) {
            log.info("Payment APPROVED for order {} ({}) (command {})", cmd.orderId(), amount, cmd.commandId());
            publish(SagaReply.approved(cmd, amount));
        } else {
            log.warn("Payment DECLINED for order {} ({}) - {} (command {})",
                    cmd.orderId(), amount, payment.getReason(), cmd.commandId());
            publish(SagaReply.declined(cmd, payment.getReason(), amount));
        }
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
