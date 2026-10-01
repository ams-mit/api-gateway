package kln.ams.apigateway.integration;

import kln.ams.apigateway.security.TestKeyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * FRONTEND_ALLOWED_ORIGINS=* allows browser calls from any origin, without CORS credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCorsAnyOriginIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestKeyUtils.registerGatewayProperties(registry, service -> "http://localhost:1");
        registry.add("FRONTEND_ALLOWED_ORIGINS", () -> "*");
    }

    @Test
    void preflight_fromAnyOrigin_isAllowed_withoutCredentials() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .options().uri("/api/v1/residents")
                .header(HttpHeaders.ORIGIN, "https://any-frontend.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    @Test
    void actualRequest_fromAnyOrigin_getsCorsHeader_andStillRequiresToken() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/residents")
                .header(HttpHeaders.ORIGIN, "https://any-frontend.example")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    }
}
