package dev.dev48v.orderhub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// CORS — let the React UI (on Vercel / localhost) call this API from the browser.
// WHY: a browser blocks cross-origin requests unless the server opts in. The Vite
// dev server (localhost:5173) and every Vercel deploy (*.vercel.app) are allowed.
// allowedOriginPatterns (not allowedOrigins) so the wildcard works.
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("https://*.vercel.app", "http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH")
                .allowedHeaders("*");
    }
}
