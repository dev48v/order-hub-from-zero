package dev.dev48v.orderhub.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Day 29 — the orchestrator's OUTBOUND side: it publishes the explicit COMMANDS that drive each participant.
// This is the mechanical difference from choreography's SagaResultPublisher (which emitted FACTS); here we
// emit IMPERATIVES on the saga-commands topic, all four kinds through one KafkaTemplate<String, SagaCommand>.
// It follows the same golden rule as every producer that runs inside a consumer thread (the orchestrator sends
// the NEXT command from within its reply handler): publishing must never crash or block that thread. So it is
// strictly non-blocking and swallows every failure:
//   • enabled=false  -> skip entirely (a broker-less boot).
//   • send() throws synchronously (broker down -> metadata timeout, capped by MAX_BLOCK_MS) -> caught.
//   • the async delivery later fails -> logged in whenComplete, never rethrown.
// Every command is KEYED by the order id, so all commands about one order stay ordered on one partition.
@Component
public class SagaCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(SagaCommandPublisher.class);

    private final KafkaTemplate<String, SagaCommand> template;
    private final OrchestrationProperties properties;

    public SagaCommandPublisher(KafkaTemplate<String, SagaCommand> template,
                                OrchestrationProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    public void send(SagaCommand command) {
        if (!properties.enabled()) {
            log.debug("Orchestration disabled - not sending {} for {}", command.type(), command.orderId());
            return;
        }
        String topic = properties.commandsTopic();
        try {
            template.send(topic, command.orderId(), command)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to send {} for order {} to '{}': {}",
                                    command.type(), command.orderId(), topic, ex.toString());
                        } else {
                            log.info("Sent command {} for order {} to {}-{}@{}",
                                    command.type(), command.orderId(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            log.warn("Could not send {} for order {}: {}", command.type(), command.orderId(), ex.toString());
        }
    }
}
