package dev.dev48v.orderhub.inventory;

import dev.dev48v.orderhub.config.ServiceAuthProperties;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// Day 24 — attaches the shared SERVICE TOKEN to every outbound Feign call.
//
// This is order-service's side of inter-service authentication. inventory-service's ServiceTokenAuthFilter
// rejects any /api/** request that doesn't carry a valid token; this interceptor makes sure every call
// order-service sends through its @FeignClient carries it — so the reserve-stock path (and the stock reads)
// authenticate automatically, with no change at the call site.
//
// WHY a Feign RequestInterceptor: Feign turns each interface method (InventoryServiceClient.getStock,
// .reserve, .whichInstance) into an HTTP request via a chain of steps; a RequestInterceptor is the hook
// that runs for EVERY such request just before it is sent, letting us mutate the RequestTemplate — here,
// stamp the auth header. Declaring it as a Spring @Bean makes it a GLOBAL interceptor: Feign applies it to
// every client in the context (order-service has exactly one — the inventory client — so every inter-service
// call is covered). This is the clean seam for cross-cutting outbound concerns (auth, tracing headers,
// tenant ids): set once, applied everywhere, invisible to the call site.
//
// The token is read from ServiceAuthProperties, which binds it from the ${SERVICE_TOKEN} env var — so the
// secret is never hardcoded here, and order-service + inventory-service share the same value from the same
// source. If no token is configured (blank) we add nothing, so a token-less local setup still behaves as
// before rather than sending an empty header.
@Component
public class ServiceTokenFeignInterceptor implements RequestInterceptor {

    private final ServiceAuthProperties props;

    public ServiceTokenFeignInterceptor(ServiceAuthProperties props) {
        this.props = props;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (!StringUtils.hasText(props.token())) {
            return; // nothing configured — don't send an empty/blank auth header
        }
        // Overwrite rather than append, so exactly one clean value is sent even if the template already
        // carried the header for some reason.
        template.removeHeader(props.header());
        template.header(props.header(), props.token());
    }
}
