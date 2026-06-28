package dev.dev48v.orderhub;

import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import dev.dev48v.orderhub.web.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

// Day 9 — a full-stack integration test: HTTP -> controller -> service -> JPA -> real Postgres.
// WHY: the Day 8 web-slice test (@WebMvcTest) mocked the service and never touched a database;
// the repository IT skipped HTTP. This one closes the loop. @SpringBootTest with a RANDOM_PORT
// starts the whole app on an embedded Tomcat, TestRestTemplate makes real HTTP calls against it,
// and AbstractPostgresIT points the datasource at a throwaway PostgreSQL container — so a POST
// here genuinely lands a row in Postgres via Flyway-migrated tables, and the GET reads it back.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiIT extends AbstractPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("POST creates an order (201 + Location), GET reads it back (200) from Postgres")
    void createThenGetRoundTripsOverHttp() {
        CreateOrderRequest request = new CreateOrderRequest("Ada", "Keyboard", 2);

        // POST -> 201 Created with a Location header and the created body.
        ResponseEntity<OrderResponse> created =
                rest.postForEntity("/api/orders", request, OrderResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse body = created.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotBlank();
        assertThat(body.customer()).isEqualTo("Ada");
        assertThat(body.item()).isEqualTo("Keyboard");
        assertThat(body.quantity()).isEqualTo(2);
        assertThat(body.status()).isEqualTo(OrderStatus.PLACED);

        URI location = created.getHeaders().getLocation();
        assertThat(location).isNotNull();
        assertThat(location.getPath()).isEqualTo("/api/orders/" + body.id());

        // GET the same id -> 200 with the persisted data (proves it survived the round trip
        // through Postgres, not just an in-memory echo).
        ResponseEntity<OrderResponse> fetched =
                rest.getForEntity("/api/orders/" + body.id(), OrderResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        OrderResponse fetchedBody = fetched.getBody();
        assertThat(fetchedBody).isNotNull();
        assertThat(fetchedBody.id()).isEqualTo(body.id());
        assertThat(fetchedBody.customer()).isEqualTo("Ada");
        assertThat(fetchedBody.item()).isEqualTo("Keyboard");
        assertThat(fetchedBody.quantity()).isEqualTo(2);
        assertThat(fetchedBody.status()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    @DisplayName("POST then confirm flips the order to CONFIRMED (200) and persists it")
    void confirmUpdatesTheOrder() {
        CreateOrderRequest request = new CreateOrderRequest("Babbage", "Mouse", 1);
        OrderResponse placed = rest.postForObject("/api/orders", request, OrderResponse.class);
        assertThat(placed).isNotNull();
        assertThat(placed.status()).isEqualTo(OrderStatus.PLACED);

        // confirm -> 200 with the updated status.
        ResponseEntity<OrderResponse> confirmed = rest.postForEntity(
                "/api/orders/" + placed.id() + "/confirm", null, OrderResponse.class);

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).isNotNull();
        assertThat(confirmed.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);

        // The change must have been persisted: GET reflects CONFIRMED on a fresh read.
        OrderResponse reread = rest.getForObject("/api/orders/" + placed.id(), OrderResponse.class);
        assertThat(reread).isNotNull();
        assertThat(reread.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("GET a missing id returns 404 with an RFC-7807 ProblemDetail")
    void getMissingReturns404() {
        ResponseEntity<ProblemDetail> response =
                rest.getForEntity("/api/orders/no-such-id", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Order not found");
        assertThat(problem.getDetail()).contains("no-such-id");
    }
}
