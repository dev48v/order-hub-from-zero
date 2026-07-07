package dev.dev48v.inventory.stock;

import dev.dev48v.inventory.domain.StockItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day 17 — the inventory service's business rules, proven with a plain unit test (no Spring
// context, no HTTP). We wire the real InventoryService onto the real in-memory repository and
// assert the two operations the Inventory context offers — read stock, reserve stock — behave, and
// that the guard rails (unknown SKU, over-reservation) fire. Fast: runs in the surefire suite with
// zero infrastructure.
@DisplayName("InventoryService: read + reserve stock, with guard rails")
class InventoryServiceTest {

    private final InventoryService service = new InventoryService(new InMemoryStockRepository());

    @Test
    @DisplayName("getStock returns the seeded level for a known SKU")
    void getStockReturnsSeededLevel() {
        StockItem keyboard = service.getStock("KEYBOARD-001");

        assertThat(keyboard.name()).isEqualTo("Mechanical keyboard");
        assertThat(keyboard.available()).isEqualTo(42);
        assertThat(keyboard.inStock()).isTrue();
    }

    @Test
    @DisplayName("getStock on an unknown SKU throws UnknownSkuException (-> 404)")
    void getStockUnknownSkuThrows() {
        assertThatThrownBy(() -> service.getStock("NOPE-999"))
                .isInstanceOf(UnknownSkuException.class);
    }

    @Test
    @DisplayName("reserve decrements availability and returns the new level")
    void reserveDecrementsAvailability() {
        StockItem after = service.reserve("MOUSE-001", 5);

        assertThat(after.available()).isEqualTo(25);          // 30 seeded - 5 reserved
        assertThat(service.getStock("MOUSE-001").available()).isEqualTo(25); // persisted
    }

    @Test
    @DisplayName("reserving more than available throws InsufficientStockException (-> 409)")
    void reserveBeyondAvailableThrows() {
        // STAND-001 is seeded out of stock (0 available), so any reservation must be refused.
        assertThatThrownBy(() -> service.reserve("STAND-001", 1))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 0 available");
    }

    @Test
    @DisplayName("reserving against an unknown SKU throws UnknownSkuException (-> 404)")
    void reserveUnknownSkuThrows() {
        assertThatThrownBy(() -> service.reserve("NOPE-999", 1))
                .isInstanceOf(UnknownSkuException.class);
    }
}
