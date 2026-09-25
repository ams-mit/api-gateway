package com.projecta.apigateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.config.SecurityProperties;
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

import java.security.KeyPair;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtAuthenticationFilter jwtAuthenticationFilter;
    private SecurityProperties securityProperties;
    private KeyResolverService keyResolverService;
    private GatewayFilterChain filterChain;
    private KeyPair testKeyPair;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        securityProperties.setPublicPaths(List.of("/api/v1/auth/login", "/api/v1/auth/register", "/actuator/**"));

        keyResolverService = mock(KeyResolverService.class);
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        testKeyPair = TestKeyUtils.generateRsaKeyPair();

        jwtAuthenticationFilter = new JwtAuthenticationFilter(securityProperties, keyResolverService, new ObjectMapper());
        jwtAuthenticationFilter.setIdentityPublicKey(testKeyPair.getPublic());
    }

    @Test
    void filter_bypassesAuthentication_forPublicPath() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        verify(filterChain).filter(exchange);
    }

    @Test
    void filter_returnsUnauthorized_whenAuthHeaderIsMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        verifyNoInteractions(filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_returnsUnauthorized_whenTokenIsExpired() {
        String expiredToken = Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .issuedAt(new Date(System.currentTimeMillis() - 10000))
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(testKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        verifyNoInteractions(filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_returnsUnauthorized_whenSignatureIsInvalid() {
        KeyPair anotherKeyPair = TestKeyUtils.generateRsaKeyPair();
        String tamperedToken = Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(anotherKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        verifyNoInteractions(filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_authenticatesSuccessfully_forValidUserJwt() {
        String validToken = Jwts.builder()
                .subject("user_123")
                .claim("type", "user")
                .claim("roles", List.of("TENANT"))
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(testKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange captured = captor.getValue();
        assertEquals("user_123", captured.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
        assertEquals("user", captured.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE));
        assertEquals(List.of("TENANT"), captured.getAttribute(JwtAuthenticationFilter.ATTR_ROLES));
    }

    @Test
    void filter_authenticatesSuccessfully_forValidServiceJwt() {
        String validServiceToken = Jwts.builder()
                .subject("resident-management-service")
                .claim("type", "service")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(testKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validServiceToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        jwtAuthenticationFilter.filter(exchange, filterChain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange captured = captor.getValue();
        assertEquals("resident-management-service", captured.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
        assertEquals("service", captured.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE));
    }
}
