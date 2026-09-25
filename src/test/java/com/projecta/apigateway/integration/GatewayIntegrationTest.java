package com.projecta.apigateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.config.SecurityProperties;
import com.projecta.apigateway.filter.RequestTraceFilter;
import com.projecta.apigateway.security.JwtAuthenticationFilter;
import com.projecta.apigateway.security.KeyResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.mock;

class GatewayIntegrationTest {

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setPublicPaths(List.of("/api/v1/auth/login", "/api/v1/auth/register", "/actuator/**"));

        KeyResolverService keyResolverService = mock(KeyResolverService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        RequestTraceFilter traceFilter = new RequestTraceFilter();
        JwtAuthenticationFilter authFilter = new JwtAuthenticationFilter(securityProperties, keyResolverService, objectMapper);

        WebFilter traceWebFilter = (exchange, chain) -> traceFilter.filter(exchange, chain::filter);
        WebFilter authWebFilter = (exchange, chain) -> authFilter.filter(exchange, chain::filter);

        webTestClient = WebTestClient.bindToWebHandler(exchange -> Mono.empty())
                .webFilter(traceWebFilter, authWebFilter)
                .build();
    }

    @Test
    void publicPath_bypassesAuthentication_andGeneratesRequestId() {
        webTestClient.get()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectHeader().value(RequestTraceFilter.REQUEST_ID_HEADER, org.junit.jupiter.api.Assertions::assertNotNull);
    }

    @Test
    void unauthenticatedProtectedPath_returnsUnauthorizedEnvelope() {
        webTestClient.get()
                .uri("/api/v1/residents/123")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().value(RequestTraceFilter.REQUEST_ID_HEADER, org.junit.jupiter.api.Assertions::assertNotNull)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void requestWithCustomRequestId_propagatesCustomRequestIdHeader() {
        String customRequestId = "test-custom-request-id-99999";

        webTestClient.get()
                .uri("/api/v1/auth/login")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, customRequestId)
                .exchange()
                .expectHeader().valueEquals(RequestTraceFilter.REQUEST_ID_HEADER, customRequestId);
    }
}
