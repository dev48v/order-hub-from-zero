package dev.dev48v.orderhub.orchestration;

import dev.dev48v.orderhub.events.OrderPlacedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Day 29 — the orchestrator's TRIGGER subscription. The orchestration saga STARTS on OrderPlaced, so the
// coordinator must hear it: order-service publishes OrderPlaced (Day 25) and this listener consumes it back
// in the orchestrator's OWN consumer group (distinct from inventory's and payment's), handing each one to
// SagaOrchestrator.onOrderPlaced to kick off the state machine.
//
// autoStartup is bound to orderhub.orchestration.enabled, which DEFAULTS TO FALSE — so unless orchestration is
// explicitly switched on, this container never starts and order-service behaves exactly as it did under pure
// choreography. That gate is what lets the two saga styles live in the same codebase without colliding.
@Component
public class OrchestrationOrderPlacedListener {

    private final SagaOrchestrator orchestrator;

    public OrchestrationOrderPlacedListener(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            topics = "${orderhub.orchestration.order-placed-topic:order-placed}",
            groupId = "${orderhub.orchestration.consumer-group-id:order-orchestrator}",
            containerFactory = "orchestrationOrderPlacedListenerContainerFactory",
            autoStartup = "${orderhub.orchestration.enabled:false}")
    public void onOrderPlaced(OrderPlacedEvent event) {
        orchestrator.onOrderPlaced(event);
    }
}
