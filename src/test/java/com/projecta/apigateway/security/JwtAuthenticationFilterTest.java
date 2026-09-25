package com.projecta.apigateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.config.SecurityProperties;
import com.projecta.apigateway.exception.ErrorResponseWriter;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
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

    private static final String RESIDENT_SERVICE = "resident-management-service";

    private JwtAuthenticationFilter jwtAuthenticationFilter;
    private GatewayFilterChain filterChain;
    private KeyPair identityKeys;
    private KeyPair residentServiceKeys;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setPublicPaths(List.of("/api/v1/auth/login", "/api/v1/auth/register"));

        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        identityKeys = TestKeyUtils.generateRsaKeyPair();
        residentServiceKeys = TestKeyUtils.generateRsaKeyPair();
        JwtKeyStore keyStore = new JwtKeyStore(identityKeys.getPublic(), TestKeyUtils.generateRsaKeyPair().getPrivate(),
                Map.of(RESIDENT_SERVICE, residentServiceKeys.getPublic()));

        ObjectMapper objectMapper = new ObjectMapper();
        jwtAuthenticationFilter = new JwtAuthenticationFilter(securityProperties, keyStore, objectMapper,
                new ErrorResponseWriter(objectMapper));
    }

    @Test
    void filter_bypassesAuthentication_forPublicPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/login").build());

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        verify(filterChain).filter(exchange);
    }

    @Test
    void filter_returnsUnauthorized_whenAuthHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/residents").build());

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        assertRejected(exchange);
    }

    @Test
    void filter_returnsUnauthorized_whenTokenIsMalformed() {
        assertRejected(runWithToken("not-a-jwt"));
        assertRejected(runWithToken("a.b.c"));
    }

    @Test
    void filter_returnsUnauthorized_whenTokenIsExpired() {
        String token = userToken()
                .issuedAt(new Date(System.currentTimeMillis() - 10000))
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(identityKeys.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertRejected(runWithToken(token));
    }

    @Test
    void filter_returnsUnauthorized_whenSignatureIsInvalid() {
        String token = userToken()
                .signWith(TestKeyUtils.generateRsaKeyPair().getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertRejected(runWithToken(token));
    }

    @Test
    void filter_returnsUnauthorized_whenAlgorithmIsNotRs256() {
        String rs512 = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.RS512).compact();
        String ps256 = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.PS256).compact();

        assertRejected(runWithToken(rs512));
        assertRejected(runWithToken(ps256));
    }

    @Test
    void filter_returnsUnauthorized_forUnsignedAlgNoneToken() {
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"sub\":\"user_123\",\"type\":\"user\",\"roles\":[\"ADMIN\"],\"exp\":9999999999}");

        assertRejected(runWithToken(header + "." + payload + "."));
    }

    @Test
    void filter_returnsUnauthorized_whenExpIsMissing() {
        String token = Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .claim("roles", List.of("TENANT"))
                .signWith(identityKeys.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertRejected(runWithToken(token));
    }

    @Test
    void filter_returnsUnauthorized_whenUserRolesMissingOrInvalid() {
        String noRoles = Jwts.builder().subject("user_123").claim("type", "user")
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();
        String stringRoles = Jwts.builder().subject("user_123").claim("type", "user").claim("roles", "ADMIN")
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(runWithToken(noRoles));
        assertRejected(runWithToken(stringRoles));
    }

    @Test
    void filter_returnsUnauthorized_whenTypeIsUnknownOrWrongCase() {
        String admin = Jwts.builder().subject("user_123").claim("type", "admin").claim("roles", List.of("TENANT"))
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();
        String upper = Jwts.builder().subject("user_123").claim("type", "USER").claim("roles", List.of("TENANT"))
                .expiration(inOneMinute()).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(runWithToken(admin));
        assertRejected(runWithToken(upper));
    }

    @Test
    void filter_authenticatesSuccessfully_forValidUserJwt() {
        String token = userToken().signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        MockServerWebExchange exchange = runWithToken(token);

        ServerWebExchange captured = captureForwardedExchange();
        assertEquals("user_123", captured.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
        assertEquals("user", captured.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE));
        assertEquals(List.of("TENANT"), captured.getAttribute(JwtAuthenticationFilter.ATTR_ROLES));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_authenticatesSuccessfully_forServiceJwtSignedWithRegisteredServiceKey() {
        String token = serviceToken(RESIDENT_SERVICE).signWith(residentServiceKeys.getPrivate(), Jwts.SIG.RS256).compact();

        runWithToken(token);

        ServerWebExchange captured = captureForwardedExchange();
        assertEquals(RESIDENT_SERVICE, captured.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
        assertEquals("service", captured.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE));
        assertNull(captured.getAttribute(JwtAuthenticationFilter.ATTR_ROLES));
    }

    @Test
    void filter_returnsUnauthorized_forServiceJwtSignedWithIdentityKey() {
        String token = serviceToken(RESIDENT_SERVICE).signWith(identityKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(runWithToken(token));
    }

    @Test
    void filter_returnsUnauthorized_forUnregisteredService() {
        KeyPair unknownKeys = TestKeyUtils.generateRsaKeyPair();
        String token = serviceToken("unknown-service").signWith(unknownKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(runWithToken(token));
    }

    @Test
    void filter_returnsUnauthorized_whenServiceImpersonatesAnotherRegisteredService() {
        // Signed by an unregistered key but claiming to be resident-management-service
        String token = serviceToken(RESIDENT_SERVICE)
                .signWith(TestKeyUtils.generateRsaKeyPair().getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(runWithToken(token));
    }

    @Test
    void filter_returnsUnauthorized_forUserJwtSignedWithServiceKey() {
        String token = userToken().signWith(residentServiceKeys.getPrivate(), Jwts.SIG.RS256).compact();

        assertRejected(runWithToken(token));
    }

    private JwtBuilder userToken() {
        return Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .claim("roles", List.of("TENANT"))
                .issuedAt(new Date())
                .expiration(inOneMinute());
    }

    private JwtBuilder serviceToken(String serviceName) {
        return Jwts.builder()
                .subject(serviceName)
                .claim("type", "service")
                .issuedAt(new Date())
                .expiration(inOneMinute());
    }

    private static Date inOneMinute() {
        return new Date(System.currentTimeMillis() + 60000);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private MockServerWebExchange runWithToken(String token) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        jwtAuthenticationFilter.filter(exchange, filterChain).block();
        return exchange;
    }

    private void assertRejected(MockServerWebExchange exchange) {
        verifyNoInteractions(filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("\"code\":\"UNAUTHORIZED\""));
    }

    private ServerWebExchange captureForwardedExchange() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        return captor.getValue();
    }
}
