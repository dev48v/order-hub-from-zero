package dev.dev48v.notification;

import dev.dev48v.notification.web.NotificationController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

// Day 41 — proof that notification-service is a REAL, bootable Spring Boot app. This @SpringBootTest starts the
// full notification-service context (its own auto-config, its own beans) and asserts the controller wired up.
//
// eureka.client.enabled=false switches discovery off so the context loads instantly with no background
// registration/heartbeat threads or connection-refused noise. orderhub.notifications.enabled=false (also the
// DEFAULT) keeps the smoke test hermetic: the three @KafkaListener containers never start, so the context loads
// without a background consumer trying (and failing) to reach a broker that isn't running during the build. With
// email-enabled/sms-enabled off, the LoggingNotificationSender + MockSmsClient are the active transports, so no
// SMTP and no SMS provider are ever needed. The dedicated NotificationConsumerTest exercises the
// consume-template-send round trip for real, against an embedded broker.
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "orderhub.notifications.enabled=false"
})
@DisplayName("notification-service boots as its own Spring Boot application")
class NotificationServiceApplicationTests {

    @Autowired
    private NotificationController notificationController;

    @Test
    @DisplayName("the application context loads and the notification controller is present")
    void contextLoads() {
        assertThat(notificationController).isNotNull();
    }
}
