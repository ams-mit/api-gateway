package kln.ams.apigateway.integration;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import kln.ams.apigateway.config.GatewayConfigurationValidator;
import kln.ams.apigateway.filter.RequestTraceFilter;
import kln.ams.apigateway.security.TestKeyUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the real Gateway (route configuration, filters, error handling) against one stub backend per
 * Project A service, and verifies every contract route and every internal allow-list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Map<String, HttpServer> BACKENDS = new TreeMap<>();
    private static final Map<String, Headers> LAST_HEADERS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_PATH = new ConcurrentHashMap<>();

    static {
        GatewayConfigurationValidator.REQUIRED_SERVICES.forEach(s -> BACKENDS.put(s, startBackend(s)));
    }

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestKeyUtils.registerGatewayProperties(registry,
                service -> "http://localhost:" + BACKENDS.get(service).getAddress().getPort());
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.values().forEach(server -> server.stop(0));
    }

    @BeforeEach
    void setUp() {
        LAST_HEADERS.clear();
        LAST_PATH.clear();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                // The generated Gateway OpenAPI document is larger than the 256 KB default
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    // ================= Route registry: every contract route reaches its owner =================

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            // Group 1 — identity-access-service
            "GET,    /api/v1/auth/me,                                      identity-access-service",
            "GET,    /api/v1/users,                                        identity-access-service",
            "POST,   /api/v1/users,                                        identity-access-service",
            "PATCH,  /api/v1/users/u1/status,                              identity-access-service",
            "PUT,    /api/v1/users/u1/roles,                               identity-access-service",
            "DELETE, /api/v1/roles/r1,                                     identity-access-service",
            "PUT,    /api/v1/roles/r1/permissions,                         identity-access-service",
            "GET,    /api/v1/permissions,                                  identity-access-service",
            // Group 1 — resident-management-service
            "GET,    /api/v1/residents,                                    resident-management-service",
            "PATCH,  /api/v1/residents/r1,                                 resident-management-service",
            "GET,    /api/v1/owners/o1,                                    resident-management-service",
            "POST,   /api/v1/tenants,                                      resident-management-service",
            "GET,    /api/v1/staff,                                        resident-management-service",
            // Group 2 — property-unit-service
            "POST,   /api/v1/buildings,                                    property-unit-service",
            "GET,    /api/v1/unit-types,                                   property-unit-service",
            "GET,    /api/v1/units,                                        property-unit-service",
            "GET,    /api/v1/ownerships,                                   property-unit-service",
            // Group 2 — lease-occupancy-service (active-occupancy beats /units/**)
            "GET,    /api/v1/units/u1/active-occupancy,                    lease-occupancy-service",
            "GET,    /api/v1/leases/units/u1,                              lease-occupancy-service",
            "PATCH,  /api/v1/leases/l1/status,                             lease-occupancy-service",
            "GET,    /api/v1/occupancies/residents/r1,                     lease-occupancy-service",
            // Group 3 — billing-payment-service
            "PUT,    /api/v1/charge-rules/c1,                              billing-payment-service",
            "GET,    /api/v1/invoices/units/u1/period/2026/9,              billing-payment-service",
            "POST,   /api/v1/payments,                                     billing-payment-service",
            "GET,    /api/v1/receipts/payments/p1,                         billing-payment-service",
            "POST,   /api/v1/adjustments,                                  billing-payment-service",
            "GET,    /api/v1/balance/units/u1,                             billing-payment-service",
            "GET,    /api/v1/reports/finance-dashboard,                    billing-payment-service",
            "GET,    /api/v1/reports/payment-history,                      billing-payment-service",
            // Group 3 — utility-charge-service
            "PATCH,  /api/v1/utility-charges/c1/status,                    utility-charge-service",
            "GET,    /api/v1/utility-charges/units/u1/summary,             utility-charge-service",
            "GET,    /api/v1/reports/utility-summary,                      utility-charge-service",
            "GET,    /api/v1/reports/utility-consumption,                  utility-charge-service",
            // Group 4 — operations-service
            "POST,   /api/v1/maintenance-requests,                         operations-service",
            "GET,    /api/v1/work-orders/w1,                               operations-service",
            "GET,    /api/v1/assignments,                                  operations-service",
            "GET,    /api/v1/facilities,                                   operations-service",
            "POST,   /api/v1/bookings,                                     operations-service",
            // Group 4 — community-service
            "POST,   /api/v1/visitors,                                     community-service",
            "GET,    /api/v1/announcements,                                community-service",
            "GET,    /api/v1/notifications,                                community-service",
    })
    void userRoutes_reachOwningService(String method, String path, String expectedService) {
        client.method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo(expectedService);
        assertEquals(path, LAST_PATH.get(expectedService), "path must be preserved");
    }

    @ParameterizedTest(name = "{2}: {0} {1} -> {3}")
    @CsvSource({
            "GET,  /api/v1/internal/users/u1/validate,             community-service,           identity-access-service",
            "GET,  /api/v1/internal/users/u1/status,               billing-payment-service,     identity-access-service",
            "GET,  /api/v1/internal/residents/r1/validate,         property-unit-service,       resident-management-service",
            "GET,  /api/v1/internal/users/u1/relationships,        operations-service,          resident-management-service",
            "GET,  /api/v1/internal/units/u1/exists,               resident-management-service, property-unit-service",
            "GET,  /api/v1/internal/units/u1/validate,             lease-occupancy-service,     property-unit-service",
            "GET,  /api/v1/internal/units/u1/ownership,            billing-payment-service,     property-unit-service",
            "GET,  /api/v1/internal/units/u1/status,               community-service,           property-unit-service",
            "GET,  /api/v1/internal/units/u1/occupancy,            operations-service,          lease-occupancy-service",
            "GET,  /api/v1/internal/units/u1/occupants,            resident-management-service, lease-occupancy-service",
            "GET,  /api/v1/internal/users/u1/occupancy,            billing-payment-service,     lease-occupancy-service",
            "GET,  /api/v1/internal/units/u1/balance,              operations-service,          billing-payment-service",
            "GET,  /api/v1/internal/users/u1/balance-status,       community-service,           billing-payment-service",
            "GET,  /api/v1/internal/payments/p1/status,            community-service,           billing-payment-service",
            "GET,  /api/v1/internal/units/u1/charges,              billing-payment-service,     utility-charge-service",
            "GET,  /api/v1/internal/utility-charges/c1/validate,   billing-payment-service,     utility-charge-service",
            "POST, /api/v1/internal/notifications,                 property-unit-service,       community-service",
            "GET,  /api/v1/internal/notifications/n1/status,       resident-management-service, community-service",
            "POST, /api/v1/internal/notifications/resident,        lease-occupancy-service,     community-service",
    })
    void internalRoutes_reachProvider_forAllowedCaller(String method, String path, String caller, String provider) {
        String callerJwt = serviceJwt(caller);

        client.method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerJwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo(provider);

        // The provider receives a Gateway Service JWT for the caller, never the caller's own token
        String forwarded = bearer(provider);
        assertNotEquals(callerJwt, forwarded);
        Claims claims = verifyGatewayJwt(forwarded);
        assertEquals(caller, claims.getSubject());
        assertEquals("service", claims.get("type", String.class));
        assertNull(claims.get("roles"));
    }

    @ParameterizedTest(name = "{2}: {0} {1} -> {3}")
    @CsvSource({
            // Any registered service may call any internal endpoint (no allow-list), including callers
            // the cross-service registry does not list as consumers
            "GET,    /api/v1/internal/users/u1/validate,             utility-charge-service,      identity-access-service",
            "GET,    /api/v1/internal/residents/r1/validate,         identity-access-service,     resident-management-service",
            "GET,    /api/v1/internal/units/u1/status,               resident-management-service, property-unit-service",
            "GET,    /api/v1/internal/units/u1/occupancy,            property-unit-service,       lease-occupancy-service",
            "GET,    /api/v1/internal/payments/p1/status,            operations-service,          billing-payment-service",
            "GET,    /api/v1/internal/units/u1/charges,              operations-service,          utility-charge-service",
            "POST,   /api/v1/internal/notifications,                 identity-access-service,     community-service",
            // Ownership routing: any method, and internal resources not listed in the registry
            "POST,   /api/v1/internal/users/u1/validate,             billing-payment-service,     identity-access-service",
            "PATCH,  /api/v1/internal/users/u1/email,                resident-management-service, identity-access-service",
            "GET,    /api/v1/internal/roles/r1,                      community-service,           identity-access-service",
            "GET,    /api/v1/internal/owners/o1,                     billing-payment-service,     resident-management-service",
            "GET,    /api/v1/internal/units/u1/floor,                operations-service,          property-unit-service",
            "GET,    /api/v1/internal/buildings/b1,                  lease-occupancy-service,     property-unit-service",
            "GET,    /api/v1/internal/leases/l1,                     billing-payment-service,     lease-occupancy-service",
            "GET,    /api/v1/internal/occupancies/o1,                community-service,           lease-occupancy-service",
            "GET,    /api/v1/internal/balance/u1,                    operations-service,          billing-payment-service",
            "GET,    /api/v1/internal/invoices/i1,                   community-service,           billing-payment-service",
            "GET,    /api/v1/internal/utility-charges/c1,            billing-payment-service,     utility-charge-service",
            "GET,    /api/v1/internal/maintenance-requests/m1,       community-service,           operations-service",
            "POST,   /api/v1/internal/bookings,                      community-service,           operations-service",
            "GET,    /api/v1/internal/visitors/v1,                   operations-service,          community-service",
            "DELETE, /api/v1/internal/announcements/a1,              operations-service,          community-service",
            // Sub-resources on shared prefixes still reach the right owner
            "GET,    /api/v1/internal/users/u1/relationships/x,      operations-service,          resident-management-service",
            "GET,    /api/v1/internal/units/u1/occupants/current,    community-service,           lease-occupancy-service",
    })
    void internalRoutes_acceptAnyRegisteredService_andRouteByResourceOwner(String method, String path, String caller, String owner) {
        client.method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceJwt(caller))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo(owner);
        Claims claims = verifyGatewayJwt(bearer(owner));
        assertEquals(caller, claims.getSubject());
        assertEquals("service", claims.get("type", String.class));
    }

    @Test
    void internalRoute_unregisteredService_isStillRejected() {
        String token = Jwts.builder()
                .subject("unknown-service")
                .claim("type", "service")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.generateRsaKeyPair().getPrivate(), Jwts.SIG.RS256)
                .compact();

        client.get().uri("/api/v1/internal/users/u1/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("UNREGISTERED_SERVICE");
        assertTrue(LAST_PATH.isEmpty());
    }

    @ParameterizedTest(name = "not routed: {0} {1}")
    @CsvSource({
            // Obsolete / never-contracted paths and wrong methods
            "POST,   /api/v1/auth/register",
            "GET,    /api/v1/properties",
            "GET,    /api/v1/billing",
            "GET,    /api/v1/utilities",
            "GET,    /api/v1/maintenance",
            "GET,    /api/v1/operations",
            "GET,    /api/v1/community",
            "GET,    /api/v1/admin/users",
            "GET,    /api/v1/leases/validate",
            "GET,    /api/v1/internal/unknown",
            "DELETE, /api/v1/residents/r1",
            "DELETE, /api/v1/buildings",
            "POST,   /api/v1/permissions",
            "GET,    /api/v1/internal/reports/summary",
    })
    void unknownRoutes_return404RouteNotFound(String method, String path) {
        client.method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt())
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "it-404")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("ROUTE_NOT_FOUND")
                .jsonPath("$.requestId").isEqualTo("it-404");
        assertTrue(LAST_PATH.isEmpty());
    }

    // ================= Authentication boundary =================

    @Test
    void login_isPublic_andForwardedWithoutCallerAuthorization() {
        client.post().uri("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer caller.supplied.token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("identity-access-service");
        assertNull(LAST_HEADERS.get("identity-access-service").getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void protectedRoute_withoutToken_returns401MissingToken() {
        client.get().uri("/api/v1/residents")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestTraceFilter.REQUEST_ID_HEADER)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("MISSING_TOKEN")
                .jsonPath("$.requestId").isNotEmpty();
        assertTrue(LAST_PATH.isEmpty());
    }

    @Test
    void userJwt_onInternalRoute_returns403() {
        client.post().uri("/api/v1/internal/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void serviceJwt_onUserRoute_returns403() {
        client.get().uri("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceJwt("operations-service"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error.code").isEqualTo("SERVICE_NOT_ALLOWED");
    }

    @Test
    void userJwt_isReplacedWithGatewayUserJwt_andRequestIdPropagated() {
        String userJwt = userJwt();

        client.get().uri("/api/v1/residents/r1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt)
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "test-request-001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(RequestTraceFilter.REQUEST_ID_HEADER, "test-request-001");

        Headers backend = LAST_HEADERS.get("resident-management-service");
        assertEquals("test-request-001", backend.getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
        assertEquals(1, backend.get(HttpHeaders.AUTHORIZATION).size());
        String forwarded = bearer("resident-management-service");
        assertNotEquals(userJwt, forwarded);

        Claims claims = verifyGatewayJwt(forwarded);
        assertEquals(USER_ID, claims.getSubject());
        assertEquals("user", claims.get("type", String.class));
        assertEquals(List.of("TENANT_RESIDENT"), claims.get("roles", List.class));
        assertEquals(Map.of("sub", "", "type", "", "roles", "", "iat", "", "exp", "").keySet(), claims.keySet(),
                "Gateway JWT must contain only approved claims");
    }

    @Test
    void spoofedIdentityHeadersAndCookies_areNotForwarded() {
        client.get().uri("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt())
                .header("X-User-ID", "administrator")
                .header("X-Role", "SYSTEM_ADMINISTRATOR")
                .header("X-Service-Name", "identity-access-service")
                .header(HttpHeaders.COOKIE, "session=abc")
                .exchange()
                .expectStatus().isOk();

        Headers backend = LAST_HEADERS.get("resident-management-service");
        assertNull(backend.getFirst("X-User-ID"));
        assertNull(backend.getFirst("X-Role"));
        assertNull(backend.getFirst("X-Service-Name"));
        assertNull(backend.getFirst(HttpHeaders.COOKIE));
    }

    // ================= Platform behaviour =================

    @Test
    void corsPreflight_allowsConfiguredOriginOnly() {
        client.options().uri("/api/v1/residents")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");

        client.options().uri("/api/v1/residents")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void health_isUp_andListsEveryService() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.services.status").isEqualTo("UP")
                .jsonPath("$.components.services.details['identity-access-service']").isEqualTo("UP")
                .jsonPath("$.components.services.details['community-service']").isEqualTo("UP")
                .jsonPath("$.components.diskSpace").doesNotExist();
    }

    @Test
    void swagger_servesGatewayRouteDocs_andBackendDocs() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("Project A — API Gateway")
                .jsonPath("$.paths['/api/v1/internal/notifications'].post.summary")
                .isEqualTo("community-internal → community-service")
                .jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty();

        client.get().uri("/api-docs/community-service")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("community-service");
        assertEquals("/v3/api-docs", LAST_PATH.get("community-service"));

        client.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
    }

    // ================= helpers =================

    private static String userJwt() {
        return Jwts.builder()
                .subject(USER_ID)
                .claim("type", "user")
                .claim("roles", List.of("TENANT_RESIDENT"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.IDENTITY_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String serviceJwt(String serviceName) {
        return Jwts.builder()
                .subject(serviceName)
                .claim("type", "service")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(TestKeyUtils.SERVICE_KEYS.get(serviceName).getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String bearer(String service) {
        return LAST_HEADERS.get(service).getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length());
    }

    private static Claims verifyGatewayJwt(String token) {
        return Jwts.parser().verifyWith(TestKeyUtils.GATEWAY_KEYS.getPublic()).build().parseSignedClaims(token).getPayload();
    }

    private static HttpServer startBackend(String serviceName) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (!path.equals("/actuator/health")) {
                    LAST_HEADERS.put(serviceName, exchange.getRequestHeaders());
                    LAST_PATH.put(serviceName, path);
                }
                byte[] body = ("{\"success\":true,\"service\":\"" + serviceName + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub backend " + serviceName, e);
        }
    }
}
