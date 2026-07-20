package dev.dev48v.orderhub.saga;

import java.math.BigDecimal;
import java.time.Instant;

// Day 28 — order-service's CONSUMER-SIDE view of the result payment-service published on Day 27. In the
// choreography saga, payment-service reacts to OrderPlaced by deciding the charge and ANSWERS with a
// PaymentProcessed fact on the "payment-events" topic (APPROVED / DECLINED). The order saga subscribes to
// that fact — it is the second of the two signals (the other is StockReserved) it waits on before it can
// decide whether to ship the order or compensate it.
//
// WHY a matching record rather than a SHARED class: same reasoning as the other events — separate bounded
// contexts, separate deployables, coupled only by the JSON CONTRACT, not a compiled type. The field names
// mirror payment-service's emitted PaymentProcessedEvent EXACTLY so Jackson binds the incoming JSON onto
// this record; the producer sends NO Java type headers, so the deserializer is told this concrete target
// type in SagaKafkaConfig.
public record PaymentProcessedEvent(
        String eventId,          // unique id of THIS PaymentProcessed emission
        String orderId,          // which order was charged — the saga key
        String customer,         // who was charged
        BigDecimal amount,       // how much was charged
        String status,           // APPROVED | DECLINED
        String reason,           // APPROVED | AMOUNT_OVER_LIMIT | TEST_CARD_DECLINED
        String causedByEventId,  // the OrderPlaced eventId that triggered the payment — for tracing
        Instant occurredAt       // when payment-service produced this result
) {

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }
}
