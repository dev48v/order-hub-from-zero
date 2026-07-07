package dev.dev48v.inventory.web;

import dev.dev48v.inventory.domain.StockItem;
import dev.dev48v.inventory.stock.InsufficientStockException;
import dev.dev48v.inventory.stock.InventoryService;
import dev.dev48v.inventory.stock.UnknownSkuException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Day 17 — a slice test of the inventory service's HTTP edge. @WebMvcTest boots ONLY the web layer
// (this controller + the RFC-7807 exception handler), with the service mocked, and drives it via
// MockMvc. It pins the API CONTRACT that order-service will call over the wire from Day 18: the JSON
// shape of a stock read, and the status codes for the reserve write (200 / 400 / 404 / 409).
@WebMvcTest(InventoryController.class)
@DisplayName("InventoryController: the inventory HTTP contract")
class InventoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private InventoryService inventory;

    @Test
    @DisplayName("GET /api/inventory/{sku} returns the stock view as JSON")
    void getOneReturnsStockJson() throws Exception {
        given(inventory.getStock("KEYBOARD-001"))
                .willReturn(new StockItem("KEYBOARD-001", "Mechanical keyboard", 42));

        mvc.perform(get("/api/inventory/KEYBOARD-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("KEYBOARD-001"))
                .andExpect(jsonPath("$.name").value("Mechanical keyboard"))
                .andExpect(jsonPath("$.available").value(42))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    @DisplayName("GET an unknown SKU maps to 404")
    void getUnknownReturns404() throws Exception {
        given(inventory.getStock("NOPE-999")).willThrow(new UnknownSkuException("NOPE-999"));

        mvc.perform(get("/api/inventory/NOPE-999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /reserve returns the updated stock level")
    void reserveReturnsUpdatedLevel() throws Exception {
        given(inventory.reserve("MOUSE-001", 5))
                .willReturn(new StockItem("MOUSE-001", "Wireless mouse", 25));

        mvc.perform(post("/api/inventory/MOUSE-001/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(25));
    }

    @Test
    @DisplayName("POST /reserve with a non-positive quantity is rejected 400 before the service runs")
    void reserveRejectsBadQuantity() throws Exception {
        mvc.perform(post("/api/inventory/MOUSE-001/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());

        verify(inventory, never()).reserve(eq("MOUSE-001"), eq(0));
    }

    @Test
    @DisplayName("POST /reserve beyond available maps to 409 Conflict")
    void reserveInsufficientReturns409() throws Exception {
        given(inventory.reserve("STAND-001", 1))
                .willThrow(new InsufficientStockException("STAND-001", 1, 0));

        mvc.perform(post("/api/inventory/STAND-001/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.available").value(0));
    }
}
