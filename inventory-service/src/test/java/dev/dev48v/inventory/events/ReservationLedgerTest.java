package dev.dev48v.inventory.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Day 26 — a fast, broker-free unit test of the IDEMPOTENCY guard itself. The listener's "process each
// order exactly once" behaviour rests entirely on ReservationLedger.claim being an atomic first-wins
// operation, so it's worth pinning in isolation, without spinning up Kafka.
@DisplayName("ReservationLedger: claim() is first-wins, so an order is handled exactly once")
class ReservationLedgerTest {

    @Test
    @DisplayName("the first claim on an order id wins; every later claim on the same id loses")
    void claimIsFirstWins() {
        ReservationLedger ledger = new ReservationLedger();

        assertThat(ledger.claim("ORD-1")).isTrue();    // first delivery -> process it
        assertThat(ledger.claim("ORD-1")).isFalse();   // redelivery     -> skip
        assertThat(ledger.claim("ORD-1")).isFalse();   // and again      -> skip

        // A different order is independent.
        assertThat(ledger.claim("ORD-2")).isTrue();
    }

    @Test
    @DisplayName("recorded reservations are retrievable by order id")
    void recordsOutcomesByOrderId() {
        ReservationLedger ledger = new ReservationLedger();

        ledger.record(Reservation.reserved("ORD-1", "evt-1", "KEYBOARD-001", 2, 40));
        ledger.record(Reservation.failed("ORD-2", "evt-2", "STAND-001", 1, "INSUFFICIENT_STOCK"));

        assertThat(ledger.forOrder("ORD-1")).isPresent()
                .get().satisfies(r -> {
                    assertThat(r.isReserved()).isTrue();
                    assertThat(r.remaining()).isEqualTo(40);
                });
        assertThat(ledger.forOrder("ORD-2")).isPresent()
                .get().extracting(Reservation::outcome).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(ledger.forOrder("ORD-UNKNOWN")).isEmpty();
        assertThat(ledger.size()).isEqualTo(2);
    }
}
