package com.projecta.apigateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RequestTraceFilterTest {

    private RequestTraceFilter requestTraceFilter;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        requestTraceFilter = new RequestTraceFilter();
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    void filter_generatesNewRequestId_whenHeaderIsMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        requestTraceFilter.filter(exchange, filterChain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange capturedExchange = captor.getValue();
        String requestId = capturedExchange.getRequest().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER);

        assertNotNull(requestId);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(requestId, capturedExchange.getResponse().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
    }

    @Test
    void filter_propagatesExistingRequestId_whenHeaderIsPresent() {
        String existingRequestId = "custom-request-id-12345";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, existingRequestId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        requestTraceFilter.filter(exchange, filterChain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange capturedExchange = captor.getValue();
        String requestId = capturedExchange.getRequest().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER);

        assertEquals(existingRequestId, requestId);
        assertEquals(existingRequestId, capturedExchange.getResponse().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
    }
}
