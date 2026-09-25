package com.projecta.apigateway.integration;

import com.projecta.apigateway.filter.RequestTraceFilter;
import com.projecta.apigateway.security.TestKeyUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the real Gateway (route config, filters, error handling) against a stub backend.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    private static final HttpServer BACKEND = startBackend();
    private static final AtomicReference<Headers> LAST_BACKEND_HEADERS = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BACKEND_PATH = new AtomicReference<>();

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestKeyUtils.registerKeyProperties(registry);
        String backendUrl = "http://localhost:" + BACKEND.getAddress().getPort();
        registry.add("projecta.services.identity-access", () -> backendUrl);
        registry.add("projecta.services.resident-management", () -> backendUrl);
        registry.add("projecta.services.property-unit", () -> "http://localhost:" + unusedPort());
        registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "1s");
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @BeforeEach
    void setUp() {
        LAST_BACKEND_HEADERS.set(null);
        LAST_BACKEND_PATH.set(null);
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void protectedRoute_withoutToken_returns401Envelope_andDoesNotReachBackend() {
        client.get().uri("/api/v1/residents/123")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().exists(RequestTraceFilter.REQUEST_ID_HEADER)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.requestId").isNotEmpty();

        assertNull(LAST_BACKEND_PATH.get());
    }

    @Test
    void spoofedIdentityHeaders_doNotBypassAuthentication() {
        client.get().uri("/api/v1/residents/123")
                .header("X-User-ID", "administrator")
                .header("X-Role", "ADMIN")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);

        assertNull(LAST_BACKEND_PATH.get());
    }

    @Test
    void validUserJwt_isReplacedWithGatewayJwt_andRequestIdPropagated() {
        String userJwt = Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .claim("roles", List.of("TENANT"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.IDENTITY_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();

        client.get().uri("/api/v1/residents/123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt)
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "it-request-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(RequestTraceFilter.REQUEST_ID_HEADER, "it-request-1");

        assertEquals("/api/v1/residents/123", LAST_BACKEND_PATH.get());
        Headers backendHeaders = LAST_BACKEND_HEADERS.get();
        assertEquals("it-request-1", backendHeaders.getFirst(RequestTraceFilter.REQUEST_ID_HEADER));

        List<String> authorization = backendHeaders.get(HttpHeaders.AUTHORIZATION);
        assertEquals(1, authorization.size());
        String forwardedJwt = authorization.getFirst().substring("Bearer ".length());
        assertNotEquals(userJwt, forwardedJwt);

        Claims claims = Jwts.parser().verifyWith(TestKeyUtils.GATEWAY_KEYS.getPublic()).build()
                .parseSignedClaims(forwardedJwt).getPayload();
        assertEquals("user_123", claims.getSubject());
        assertEquals("user", claims.get("type", String.class));
        assertEquals(List.of("TENANT"), claims.get("roles", List.class));
    }

    @Test
    void validServiceJwt_isReplacedWithGatewayServiceJwt() {
        String serviceJwt = Jwts.builder()
                .subject("resident-management-service")
                .claim("type", "service")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.RESIDENT_SERVICE_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();

        client.get().uri("/api/v1/admin/users/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceJwt)
                .exchange()
                .expectStatus().isOk();

        String forwardedJwt = LAST_BACKEND_HEADERS.get().getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length());
        Claims claims = Jwts.parser().verifyWith(TestKeyUtils.GATEWAY_KEYS.getPublic()).build()
                .parseSignedClaims(forwardedJwt).getPayload();
        assertEquals("resident-management-service", claims.getSubject());
        assertEquals("service", claims.get("type", String.class));
    }

    @Test
    void publicRoute_isForwardedWithoutCallerAuthorization() {
        client.post().uri("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer caller.supplied.token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(RequestTraceFilter.REQUEST_ID_HEADER);

        assertEquals("/api/v1/auth/login", LAST_BACKEND_PATH.get());
        assertNull(LAST_BACKEND_HEADERS.get().getFirst(HttpHeaders.AUTHORIZATION));
        assertNotNull(LAST_BACKEND_HEADERS.get().getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
    }

    @Test
    void unknownRoute_returns404RouteNotFound_withRequestId() {
        client.get().uri("/api/v1/does-not-exist")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "it-request-404")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(RequestTraceFilter.REQUEST_ID_HEADER, "it-request-404")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("ROUTE_NOT_FOUND")
                .jsonPath("$.message").isEqualTo("API endpoint not found")
                .jsonPath("$.requestId").isEqualTo("it-request-404");
    }

    @Test
    void unreachableBackend_returns503DependencyUnavailable() {
        client.post().uri("/api/v1/properties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validUserJwt())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void slowBackend_returns503DependencyUnavailable() {
        client.get().uri("/api/v1/residents/slow")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validUserJwt())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void health_isPublic_andHidesDetails() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    private static String validUserJwt() {
        return Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .claim("roles", List.of("TENANT"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.IDENTITY_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                LAST_BACKEND_HEADERS.set(exchange.getRequestHeaders());
                LAST_BACKEND_PATH.set(exchange.getRequestURI().getPath());
                if (exchange.getRequestURI().getPath().endsWith("/slow")) {
                    sleep(3000);
                }
                byte[] body = "{\"success\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub backend", e);
        }
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
