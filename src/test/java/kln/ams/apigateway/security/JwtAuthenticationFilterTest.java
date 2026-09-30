package kln.ams.apigateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import kln.ams.apigateway.exception.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OPERATIONS = "operations-service";
    private static final String RESIDENT = "resident-management-service";

    private static final Route PUBLIC_LOGIN = route("identity-auth", Map.of("access", "public", "service", "identity-access-service"));
    private static final Route USER_ROUTE = route("resident-residents", Map.of("access", "user", "service", RESIDENT));
    private static final Route NO_METADATA_ROUTE = route("no-metadata", Map.of());
    private static final Route INTERNAL_NOTIFICATIONS = route("community-internal-notifications", Map.of(
            "access", "service", "service", "community-service",
            "allowed-callers", "resident-management-service,operations-service"));

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain filterChain;
    private KeyPair identityKeys;
    private KeyPair operationsKeys;
    private KeyPair residentKeys;
    private KeyPair unregisteredKeys;

    @BeforeEach
    void setUp() {
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        identityKeys = TestKeyUtils.generateRsaKeyPair();
        operationsKeys = TestKeyUtils.generateRsaKeyPair();
        residentKeys = TestKeyUtils.generateRsaKeyPair();
        unregisteredKeys = TestKeyUtils.generateRsaKeyPair();
        JwtKeyStore keyStore = new JwtKeyStore(identityKeys.getPublic(), TestKeyUtils.generateRsaKeyPair().getPrivate(),
                Map.of(OPERATIONS, operationsKeys.getPublic(), RESIDENT, residentKeys.getPublic(),
                        "billing-payment-service", unregisteredKeys.getPublic()));

        ObjectMapper objectMapper = new ObjectMapper();
        filter = new JwtAuthenticationFilter(keyStore, objectMapper, new ErrorResponseWriter(objectMapper));
    }

    // ---------------- public routes ----------------

    @Test
    void publicRoute_bypassesAuthentication() {
        MockServerWebExchange exchange = exchange(PUBLIC_LOGIN, "/api/v1/auth/login", null);

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(exchange);
    }

    @Test
    void routeWithoutAccessMetadata_requiresAuthentication() {
        assertRejected(exchange(NO_METADATA_ROUTE, "/api/v1/residents", null), HttpStatus.UNAUTHORIZED, "MISSING_TOKEN");
    }

    @Test
    void internalPath_isServiceOnly_evenIfMetadataSaysPublic() {
        Route mislabelled = route("bad", Map.of("access", "public"));
        String token = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(mislabelled, "/api/v1/internal/notifications", token), HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    // ---------------- authentication failures (401) ----------------

    @Test
    void missingToken_returns401MissingToken() {
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", null), HttpStatus.UNAUTHORIZED, "MISSING_TOKEN");
    }

    @Test
    void malformedToken_returns401InvalidToken() {
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", "not-a-jwt"), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN");
    }

    @Test
    void expiredToken_returns401TokenExpired() {
        String token = userToken()
                .issuedAt(new Date(System.currentTimeMillis() - 10000))
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", token), HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }

    @Test
    void wrongSigningKey_returns401InvalidSignature() {
        String token = userToken().signWith(TestKeyUtils.generateRsaKeyPair().getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", token), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE");
    }

    @Test
    void nonRs256Algorithms_return401UnsupportedAlgorithm() {
        String rs512 = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.RS512).compact();
        String algNone = b64("{\"alg\":\"none\"}") + "." + b64("{\"sub\":\"" + USER_ID + "\",\"type\":\"user\"}") + ".x";

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", rs512), HttpStatus.UNAUTHORIZED, "UNSUPPORTED_ALGORITHM");
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", algNone), HttpStatus.UNAUTHORIZED, "UNSUPPORTED_ALGORITHM");
    }

    @Test
    void missingOrUnknownType_returns401InvalidTokenType() {
        String noType = Jwts.builder().subject(USER_ID).claim("roles", List.of("OWNER")).issuedAt(new Date())
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();
        String upperCase = Jwts.builder().subject(USER_ID).claim("type", "USER").claim("roles", List.of("OWNER"))
                .issuedAt(new Date()).expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", noType), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_TYPE");
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", upperCase), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_TYPE");
    }

    @Test
    void missingRequiredClaims_return401InvalidTokenClaims() {
        String noExp = Jwts.builder().subject(USER_ID).claim("type", "user").claim("roles", List.of("OWNER"))
                .issuedAt(new Date()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();
        String noIat = Jwts.builder().subject(USER_ID).claim("type", "user").claim("roles", List.of("OWNER"))
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();
        String noRoles = Jwts.builder().subject(USER_ID).claim("type", "user").issuedAt(new Date())
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", noExp), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_CLAIMS");
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", noIat), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_CLAIMS");
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", noRoles), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_CLAIMS");
    }

    @Test
    void nonUuidSubjectOrLegacyRole_return401InvalidTokenClaims() {
        String nonUuid = Jwts.builder().subject("user_123").claim("type", "user").claim("roles", List.of("OWNER"))
                .issuedAt(new Date()).expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();
        String legacyRole = Jwts.builder().subject(USER_ID).claim("type", "user").claim("roles", List.of("TENANT"))
                .issuedAt(new Date()).expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", nonUuid), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_CLAIMS");
        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", legacyRole), HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_CLAIMS");
    }

    @Test
    void unregisteredService_returns401UnregisteredService() {
        String token = serviceToken("unknown-service").signWith(TestKeyUtils.generateRsaKeyPair().getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(INTERNAL_NOTIFICATIONS, "/api/v1/internal/notifications", token), HttpStatus.UNAUTHORIZED, "UNREGISTERED_SERVICE");
    }

    @Test
    void serviceImpersonation_returns401InvalidSignature() {
        // Claims to be operations-service but is signed with resident-management-service's key
        String token = serviceToken(OPERATIONS).signWith(residentKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(INTERNAL_NOTIFICATIONS, "/api/v1/internal/notifications", token), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE");
    }

    // ---------------- authorization (403) ----------------

    @Test
    void userToken_onInternalRoute_returns403Forbidden() {
        String token = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(INTERNAL_NOTIFICATIONS, "/api/v1/internal/notifications", token), HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @Test
    void registeredServiceNotOnAllowList_returns403ServiceNotAllowed() {
        String token = serviceToken("billing-payment-service").signWith(unregisteredKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(INTERNAL_NOTIFICATIONS, "/api/v1/internal/notifications", token), HttpStatus.FORBIDDEN, "SERVICE_NOT_ALLOWED");
    }

    @Test
    void serviceToken_onUserRoute_returns403ServiceNotAllowed() {
        String token = serviceToken(RESIDENT).signWith(residentKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(exchange(USER_ROUTE, "/api/v1/residents", token), HttpStatus.FORBIDDEN, "SERVICE_NOT_ALLOWED");
    }

    // ---------------- success ----------------

    @Test
    void validUserToken_onUserRoute_isForwardedWithPrincipal() {
        String token = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        filter.filter(exchange(USER_ROUTE, "/api/v1/residents", token), filterChain).block();

        ServerWebExchange forwarded = captureForwarded();
        assertEquals(USER_ID, forwarded.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
        assertEquals("user", forwarded.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE));
        assertEquals(List.of("TENANT_RESIDENT"), forwarded.getAttribute(JwtAuthenticationFilter.ATTR_ROLES));
    }

    @Test
    void allowedService_onInternalRoute_isForwarded() {
        String token = serviceToken(OPERATIONS).signWith(operationsKeys.getPrivate(), Jwts.SIG.RS256).compact();

        filter.filter(exchange(INTERNAL_NOTIFICATIONS, "/api/v1/internal/notifications", token), filterChain).block();

        ServerWebExchange forwarded = captureForwarded();
        assertEquals(OPERATIONS, forwarded.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
        assertEquals("service", forwarded.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE));
        assertNull(forwarded.getAttribute(JwtAuthenticationFilter.ATTR_ROLES));
    }

    // ---------------- helpers ----------------

    private static Route route(String id, Map<String, Object> metadata) {
        return Route.async().id(id).uri("http://localhost:1").predicate(e -> true).metadata(metadata).build();
    }

    private JwtBuilder userToken() {
        return Jwts.builder().subject(USER_ID).claim("type", "user").claim("roles", List.of("TENANT_RESIDENT"))
                .issuedAt(new Date()).expiration(inOneMinute());
    }

    private JwtBuilder serviceToken(String serviceName) {
        return Jwts.builder().subject(serviceName).claim("type", "service").issuedAt(new Date()).expiration(inOneMinute());
    }

    private static Date inOneMinute() {
        return new Date(System.currentTimeMillis() + 60000);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockServerWebExchange exchange(Route route, String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private void assertRejected(MockServerWebExchange exchange, HttpStatus status, String code) {
        filter.filter(exchange, filterChain).block();
        verify(filterChain, never()).filter(any());
        assertEquals(status, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        assertTrue(body.contains("\"code\":\"" + code + "\""), () -> "expected " + code + " in " + body);
        assertFalse(body.contains("Bearer"));
    }

    private ServerWebExchange captureForwarded() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        return captor.getValue();
    }
}
