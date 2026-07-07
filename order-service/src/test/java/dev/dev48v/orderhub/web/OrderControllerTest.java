package dev.dev48v.orderhub.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.service.OrderNotFoundException;
import dev.dev48v.orderhub.service.OrderService;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Day 8 — a web-slice test for the controller using MockMvc.
// WHY: @WebMvcTest boots only the MVC layer (the controller, JSON mapping, validation and
// the exception handler) — no service, no database. The OrderService is replaced by a Mockito
// @MockBean so we drive each endpoint's behaviour and assert the HTTP contract: status codes,
// headers, JSON body and the RFC-7807 error shape. ApiExceptionHandler is imported explicitly
// so the slice maps domain exceptions to the right statuses (404 / 409).
@WebMvcTest(OrderController.class)
@Import(ApiExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService service;

    @Test
    @DisplayName("POST a valid order returns 201 with the created order body and Location header")
    void createValidOrderReturns201() throws Exception {
        // The controller calls placeOrder(customer, item, quantity); return a known Order.
        Order created = Order.rehydrate(
                "order-1", "Ada", "Keyboard", 2, OrderStatus.PLACED, java.time.Instant.now());
        when(service.placeOrder(eq("Ada"), eq("Keyboard"), anyInt())).thenReturn(created);

        CreateOrderRequest body = new CreateOrderRequest("Ada", "Keyboard", 2);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/order-1"))
                .andExpect(jsonPath("$.id").value("order-1"))
                .andExpect(jsonPath("$.customer").value("Ada"))
                .andExpect(jsonPath("$.item").value("Keyboard"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("PLACED"));
    }

    @Test
    @DisplayName("POST an order with a blank customer returns 400 with a ProblemDetail")
    void createBlankCustomerReturns400() throws Exception {
        // Blank customer trips @NotBlank before any service call — a validation 400.
        String json = "{\"customer\":\"\",\"item\":\"Keyboard\",\"quantity\":2}";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.customer").exists());
    }

    @Test
    @DisplayName("POST an order with quantity 0 returns 400 with a ProblemDetail")
    void createZeroQuantityReturns400() throws Exception {
        // quantity 0 violates @Min(1) — another validation 400.
        String json = "{\"customer\":\"Ada\",\"item\":\"Keyboard\",\"quantity\":0}";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    @DisplayName("GET a missing order returns 404 with a ProblemDetail")
    void getMissingOrderReturns404() throws Exception {
        when(service.getOrder("missing")).thenThrow(new OrderNotFoundException("missing"));

        mockMvc.perform(get("/api/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order missing not found"));
    }

    @Test
    @DisplayName("GET an existing order returns 200 with its body")
    void getExistingOrderReturns200() throws Exception {
        Order found = Order.rehydrate(
                "order-9", "Babbage", "Mouse", 1, OrderStatus.CONFIRMED, java.time.Instant.now());
        when(service.getOrder("order-9")).thenReturn(found);

        mockMvc.perform(get("/api/orders/order-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("order-9"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("confirm on an order the service rejects with IllegalStateException returns 409")
    void confirmIllegalStateReturns409() throws Exception {
        // The domain rule (only a PLACED order can be confirmed) surfaces as IllegalStateException,
        // which the handler maps to 409 Conflict.
        when(service.confirmOrder("order-5"))
                .thenThrow(new IllegalStateException("Only a PLACED order can be confirmed (was CONFIRMED)"));

        mockMvc.perform(post("/api/orders/order-5/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid order state"))
                .andExpect(jsonPath("$.status").value(409));
    }
}
