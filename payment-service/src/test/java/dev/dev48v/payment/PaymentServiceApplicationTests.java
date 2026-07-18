package dev.dev48v.payment;

import dev.dev48v.payment.web.PaymentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

// Day 27 — proof that payment-service is a REAL, bootable Spring Boot app. This @SpringBootTest starts the
// full payment-service context (its own auto-config, its own beans) and asserts the controller wired up.
//
// eureka.client.enabled=false switches discovery off so the context loads instantly with no background
// registration/heartbeat threads or connection-refused noise. payment.events.enabled=false keeps the smoke
// test hermetic: the @KafkaListener container never starts, so the context loads without a background consumer
// trying (and failing) to reach a broker that isn't running during the build. The dedicated
// PaymentProcessedConsumerTest exercises the consume-decide-emit round trip for real, against an embedded broker.
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "payment.events.enabled=false"
})
@DisplayName("payment-service boots as its own Spring Boot application")
class PaymentServiceApplicationTests {

    @Autowired
    private PaymentController paymentController;

    @Test
    @DisplayName("the application context loads and the payment controller is present")
    void contextLoads() {
        assertThat(paymentController).isNotNull();
    }
}
