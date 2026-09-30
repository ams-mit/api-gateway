package kln.ams.apigateway.integration;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import kln.ams.apigateway.security.TestKeyUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Downstream failure behaviour (API-GATEWAY.md §34, §36, §105, §131): an unreachable or slow backend
 * yields 503 DEPENDENCY_UNAVAILABLE for its routes only, and Gateway health stays UP while the
 * services component reports DEGRADED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDependencyFailureIntegrationTest {

    /** Only resident-management answers, and slowly; every other service is unreachable. */
    private static final HttpServer SLOW_RESIDENT = startSlowBackend();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        int deadPort = unusedPort();
        TestKeyUtils.registerGatewayProperties(registry, service -> "resident-management-service".equals(service)
                ? "http://localhost:" + SLOW_RESIDENT.getAddress().getPort()
                : "http://localhost:" + deadPort);
        registry.add("GATEWAY_READ_TIMEOUT_MS", () -> "1000");
        registry.add("DOWNSTREAM_HEALTH_TIMEOUT", () -> "500ms");
    }

    @AfterAll
    static void stop() {
        SLOW_RESIDENT.stop(0);
    }

    @Test
    void unreachableBackend_returns503DependencyUnavailable_withServiceName() {
        client().get().uri("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
                .jsonPath("$.error.details.service").isEqualTo("community-service")
                .jsonPath("$.message").isEqualTo("Required service is unavailable")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void slowBackend_returns503DependencyUnavailable() {
        client().get().uri("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void health_staysUp_withServicesDegraded() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.services.status").isEqualTo("DEGRADED")
                .jsonPath("$.components.services.details['community-service']").isEqualTo("DOWN");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
    }

    private static String userJwt() {
        return Jwts.builder()
                .subject("550e8400-e29b-41d4-a716-446655440000")
                .claim("type", "user")
                .claim("roles", List.of("TENANT_RESIDENT"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.IDENTITY_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static HttpServer startSlowBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
