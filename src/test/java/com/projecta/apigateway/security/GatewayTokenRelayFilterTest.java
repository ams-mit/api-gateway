package com.projecta.apigateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GatewayTokenRelayFilterTest {

    private GatewayTokenRelayFilter relayFilter;
    private GatewayJwtSigner gatewayJwtSigner;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        gatewayJwtSigner = mock(GatewayJwtSigner.class);
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        relayFilter = new GatewayTokenRelayFilter(gatewayJwtSigner);
    }

    @Test
    void filter_stripsCallerAuthorization_whenNoAuthenticatedSubject() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer caller.supplied.jwt")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        relayFilter.filter(exchange, filterChain).block();

        ServerWebExchange captured = captureForwardedExchange();
        assertNull(captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        verifyNoInteractions(gatewayJwtSigner);
    }

    @Test
    void filter_replacesAuthorizationHeaderWithGatewayUserToken() {
        String originalToken = "original.user.jwt";
        String gatewayToken = "gateway.re-signed.user.jwt";
        when(gatewayJwtSigner.generateGatewayUserToken("user_999", List.of("TENANT"))).thenReturn(gatewayToken);

        MockServerWebExchange exchange = authenticatedExchange(originalToken, "user_999", "user");
        exchange.getAttributes().put(JwtAuthenticationFilter.ATTR_ROLES, List.of("TENANT"));

        relayFilter.filter(exchange, filterChain).block();

        List<String> forwarded = captureForwardedExchange().getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        assertEquals(List.of("Bearer " + gatewayToken), forwarded);
    }

    @Test
    void filter_replacesAuthorizationHeaderWithGatewayServiceToken() {
        when(gatewayJwtSigner.generateGatewayServiceToken("resident-management-service")).thenReturn("gateway.service.jwt");

        MockServerWebExchange exchange = authenticatedExchange("original.service.jwt", "resident-management-service", "service");

        relayFilter.filter(exchange, filterChain).block();

        assertEquals("Bearer gateway.service.jwt",
                captureForwardedExchange().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        verify(gatewayJwtSigner, never()).generateGatewayUserToken(any(), any());
    }

    private MockServerWebExchange authenticatedExchange(String originalToken, String subject, String type) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + originalToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(JwtAuthenticationFilter.ATTR_USER_ID, subject);
        exchange.getAttributes().put(JwtAuthenticationFilter.ATTR_TOKEN_TYPE, type);
        return exchange;
    }

    private ServerWebExchange captureForwardedExchange() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        return captor.getValue();
    }
}
