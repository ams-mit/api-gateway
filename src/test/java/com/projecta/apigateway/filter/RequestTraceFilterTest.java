package com.projecta.apigateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RequestTraceFilterTest {

    private RequestTraceFilter requestTraceFilter;
    private WebFilterChain filterChain;

    @BeforeEach
    void setUp() {
        requestTraceFilter = new RequestTraceFilter();
        filterChain = mock(WebFilterChain.class);
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    void filter_generatesNewRequestId_whenHeaderIsMissing() {
        ServerWebExchange captured = run(MockServerHttpRequest.get("/api/v1/residents").build());

        String requestId = captured.getRequest().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(requestId, captured.getResponse().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
        assertEquals(requestId, RequestTraceFilter.getRequestId(captured));
    }

    @Test
    void filter_propagatesExistingRequestId_whenHeaderIsPresent() {
        String existingRequestId = "custom-request-id-12345";
        ServerWebExchange captured = run(MockServerHttpRequest.get("/api/v1/residents")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, existingRequestId)
                .build());

        assertEquals(existingRequestId, captured.getRequest().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
        assertEquals(existingRequestId, captured.getResponse().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER));
    }

    @Test
    void filter_replacesUnsafeRequestId() {
        String unsafe = "abc\" injected <script>";
        ServerWebExchange captured = run(MockServerHttpRequest.get("/api/v1/residents")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, unsafe)
                .build());

        String requestId = captured.getRequest().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER);
        assertNotEquals(unsafe, requestId);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
    }

    private ServerWebExchange run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        requestTraceFilter.filter(exchange, filterChain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        return captor.getValue();
    }
}
