package dev.dev48v.orderhub.saga;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Day 28 — the two subscriptions that feed the choreography saga. order-service is now a CONSUMER as well as
// a producer: it listens for the RESULTS the other services publish and hands each one to the OrderSaga,
// which correlates them per order and decides whether to ship or compensate. There is no orchestrator here —
// these are just reactions to facts, the essence of choreography.
//
//   • inventory-events  -> StockReserved   (inventory reserved, or couldn't)
//   • payment-events    -> PaymentProcessed (payment approved, or declined)
//
// Both listeners:
//   - resolve their topic + the shared saga consumer group from orderhub.saga.* (with literal defaults) so
//     they retune per environment without a recompile and match what the upstream services publish to;
//   - name the JSON-typed container factory built for their event type in SagaKafkaConfig;
//   - bind autoStartup to orderhub.saga.enabled, so when the saga is disabled (the existing full-context
//     tests, a broker-less boot) the containers never start and order-service behaves exactly as before Day 28.
//
// The saga itself is idempotent and thread-safe (keyed on order id, latched terminal flag), so nothing here
// needs to guard against redelivery — the listener just forwards the decoded event.
@Component
public class OrderSagaListener {

    private final OrderSaga saga;

    public OrderSagaListener(OrderSaga saga) {
        this.saga = saga;
    }

    @KafkaListener(
            topics = "${orderhub.saga.stock-events-topic:inventory-events}",
            groupId = "${orderhub.saga.consumer-group-id:order-saga}",
            containerFactory = "stockReservedListenerContainerFactory",
            autoStartup = "${orderhub.saga.enabled:true}")
    public void onStockReserved(StockReservedEvent event) {
        saga.onStockReserved(event);
    }

    @KafkaListener(
            topics = "${orderhub.saga.payment-events-topic:payment-events}",
            groupId = "${orderhub.saga.consumer-group-id:order-saga}",
            containerFactory = "paymentProcessedListenerContainerFactory",
            autoStartup = "${orderhub.saga.enabled:true}")
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        saga.onPaymentProcessed(event);
    }
}
