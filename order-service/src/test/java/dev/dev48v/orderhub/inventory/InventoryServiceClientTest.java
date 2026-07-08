package dev.dev48v.orderhub.inventory;

import feign.FeignException;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day 18 — proves the OpenFeign client is REAL: it turns each interface method into the right HTTP
// request and binds the response back, over an actual socket. We do it WITHOUT inventory-service (and
// without Docker) by standing up an OkHttp MockWebServer, pointing inventory.service.url at it via
// @DynamicPropertySource (before the context starts), and letting Spring build the genuine @FeignClient
// proxy. The context is deliberately TINY — just enough auto-config to wire Feign + its JSON encoder/
// decoder (FeignAutoConfiguration + the HTTP message converters + Jackson) — so this stays a fast, focused
// test of the client, not a full app boot.
//
// The mock server is driven by a DISPATCHER that routes on method + path (not a FIFO queue), so the tests
// are order-independent even though they share one static server on one fixed port (the port is baked into
// the client at bean creation, so it can't change per test). Each test calls a client method and asserts on
// the decoded result — and, for the write, on the request the client actually sent.
@SpringBootTest(classes = InventoryServiceClientTest.FeignTestConfig.class)
class InventoryServiceClientTest {

    private static MockWebServer server;

    // The requests the dispatcher actually received, captured for the request-shape assertions.
    private static final AtomicReference<RecordedRequest> lastGet = new AtomicReference<>();
    private static final AtomicReference<RecordedRequest> lastReserve = new AtomicReference<>();
    private static final AtomicReference<String> lastReserveBody = new AtomicReference<>();

    @DynamicPropertySource
    static void feignProperties(DynamicPropertyRegistry registry) throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                String method = request.getMethod();
                if ("GET".equals(method) && "/api/inventory/KEYBOARD-001".equals(path)) {
                    lastGet.set(request);
                    return json(200, "{\"sku\":\"KEYBOARD-001\",\"name\":\"Mechanical keyboard\","
                            + "\"available\":42,\"inStock\":true}");
                }
                if ("POST".equals(method) && "/api/inventory/KEYBOARD-001/reserve".equals(path)) {
                    lastReserve.set(request);
                    lastReserveBody.set(request.getBody().readUtf8());
                    return json(200, "{\"sku\":\"KEYBOARD-001\",\"name\":\"Mechanical keyboard\","
                            + "\"available\":40,\"inStock\":true}");
                }
                if ("POST".equals(method) && "/api/inventory/MONITOR-4K/reserve".equals(path)) {
                    // Inventory-service refuses: not enough stock → 409, the way its ExceptionHandler maps it.
                    return json(409, "{\"title\":\"Insufficient stock\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        // The @FeignClient's url resolves this property; aim it at the mock server's ephemeral port.
        registry.add("inventory.service.url", () -> "http://localhost:" + server.getPort());
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    @AfterAll
    static void shutdown() throws IOException {
        server.shutdown();
    }

    @Autowired
    private InventoryServiceClient client;

    @Test
    @DisplayName("getStock issues GET /api/inventory/{sku} and decodes the JSON into a StockView")
    void getStockIssuesGetAndDecodesJson() {
        StockView stock = client.getStock("KEYBOARD-001");

        // The response JSON was bound onto the record by field name.
        assertThat(stock.sku()).isEqualTo("KEYBOARD-001");
        assertThat(stock.name()).isEqualTo("Mechanical keyboard");
        assertThat(stock.available()).isEqualTo(42);
        assertThat(stock.inStock()).isTrue();

        // And the client sent exactly the request the interface annotations describe.
        RecordedRequest request = lastGet.get();
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/inventory/KEYBOARD-001");
    }

    @Test
    @DisplayName("reserve issues POST /{sku}/reserve with a JSON body and decodes the new stock level")
    void reserveIssuesPostWithBody() {
        StockView stock = client.reserve("KEYBOARD-001", new ReserveRequest(2));

        assertThat(stock.available()).isEqualTo(40);

        RecordedRequest request = lastReserve.get();
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/inventory/KEYBOARD-001/reserve");
        // The ReserveRequest record was serialised to JSON as the request body.
        assertThat(lastReserveBody.get()).contains("\"quantity\":2");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("a non-2xx response surfaces as a FeignException carrying the status")
    void nonSuccessResponseSurfacesAsFeignException() {
        // Inventory-service answers 409 (insufficient stock). Feign's default ErrorDecoder turns any
        // non-2xx into a FeignException — this is what the order flow catches and translates.
        assertThatThrownBy(() -> client.reserve("MONITOR-4K", new ReserveRequest(99)))
                .isInstanceOf(FeignException.class)
                .satisfies(ex -> assertThat(((FeignException) ex).status()).isEqualTo(409));
    }

    // A minimal context: enable just this Feign client and import only the auto-config it needs to wire
    // the proxy plus its JSON encoder/decoder. No web server, no database — nothing else boots.
    @EnableFeignClients(clients = InventoryServiceClient.class)
    @ImportAutoConfiguration({
            FeignAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class
    })
    static class FeignTestConfig {
    }
}
