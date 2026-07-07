package dev.dev48v.orderhub.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.context.annotation.Configuration;

// Day 10 — the API's "cover page".
// WHY: springdoc reads the controllers/DTOs to build the OpenAPI spec automatically, but the
// document-level metadata (title, version, description, contact) has to come from somewhere.
// @OpenAPIDefinition supplies it once, here. Keeping it on a tiny dedicated @Configuration class
// (rather than on OrderHubApplication) keeps the entry point clean and groups all the OpenAPI
// wiring in the config package. The actual spec is served at /v3/api-docs and rendered by
// Swagger UI at /swagger-ui.html.
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "OrderHub API",
                version = "0.1.0",
                description = """
                        A small e-commerce order-fulfillment backend, built feature by feature.

                        Create orders, fetch them by id, page/filter the list, and confirm them.
                        Errors follow RFC-7807 (application/problem+json): a 400 for bad input,
                        404 for a missing order, and 409 for an illegal state transition.""",
                contact = @Contact(name = "dev48v", url = "https://github.com/dev48v"),
                license = @License(name = "MIT")
        )
)
public class OpenApiConfig {
}
