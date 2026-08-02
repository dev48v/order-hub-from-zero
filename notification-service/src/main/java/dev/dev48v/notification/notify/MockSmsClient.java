package dev.dev48v.notification.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Day 41 — the MOCK SMS adapter: the only SmsClient shipped today. It "sends" by logging, standing in for a
// real provider (Twilio, Vonage, …) without a paid API, an account, or network I/O — so SMS can be exercised in
// tests and demos with zero external dependencies. A real adapter would implement the SAME SmsClient interface
// and be swapped in by configuration; the dispatcher, which only knows the interface, would not change.
@Component
public class MockSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(MockSmsClient.class);

    @Override
    public void send(String toPhoneNumber, String message) {
        log.info("[SMS·mock] to={} | text='{}'", toPhoneNumber, message);
    }
}
