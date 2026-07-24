package com.example.subscription.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS is registered as its own servlet Filter with HIGHEST precedence,
 * NOT via WebMvcConfigurer.addCorsMappings(). This matters because this
 * app has custom filters (SessionAuthFilter, AdminAuthFilter) that also
 * run at the servlet-filter level and reject unauthenticated requests
 * with 401 - including the browser's CORS preflight OPTIONS request,
 * which never carries an Authorization header. If CORS were only wired
 * up via WebMvcConfigurer (which only applies once a request reaches
 * DispatcherServlet's handler mapping), those custom filters would
 * intercept and reject the preflight OPTIONS request BEFORE any CORS
 * headers get attached, and the browser would report a CORS failure
 * even though the real request would have been handled correctly.
 *
 * Registering CorsFilter here with Ordered.HIGHEST_PRECEDENCE guarantees
 * it runs first, attaches the right headers, and short-circuits preflight
 * OPTIONS requests before they ever reach SessionAuthFilter/AdminAuthFilter.
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(false);
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}