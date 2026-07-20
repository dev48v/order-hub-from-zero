package dev.dev48v.orderhub.orchestration;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Day 29 — the orchestrator's REPLY subscription: the "await a reply" half of command/reply. Participants
// answer each command on the saga-replies topic, and the orchestrator is the SOLE consumer of that topic —
// every reply re-enters the state machine via SagaOrchestrator.onReply, which decides the next command (or the
// terminal transition). The orchestrator joins in its OWN consumer group (the same group as its OrderPlaced
// subscription), so it gets every reply exactly once across its instances.
//
// The listener holds no logic — it just forwards the decoded reply; all correlation, idempotency (the step
// guard) and thread-safety live in the orchestrator. autoStartup is bound to orderhub.orchestration.enabled
// (default false) so the container only runs when orchestration is switched on.
@Component
public class SagaReplyListener {

    private final SagaOrchestrator orchestrator;

    public SagaReplyListener(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            topics = "${orderhub.orchestration.replies-topic:saga-replies}",
            groupId = "${orderhub.orchestration.consumer-group-id:order-orchestrator}",
            containerFactory = "sagaReplyListenerContainerFactory",
            autoStartup = "${orderhub.orchestration.enabled:false}")
    public void onReply(SagaReply reply) {
        orchestrator.onReply(reply);
    }
}
