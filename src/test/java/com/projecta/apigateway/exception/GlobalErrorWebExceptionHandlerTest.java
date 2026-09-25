package com.projecta.apigateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.filter.RequestTraceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalErrorWebExceptionHandlerTest {

    private GlobalErrorWebExceptionHandler exceptionHandler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        exceptionHandler = new GlobalErrorWebExceptionHandler(objectMapper);
    }

    @Test
    void handle_mapsResponseStatusException_toNotFoundEnvelope() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/unknown")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "req-test-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found");

        exceptionHandler.handle(exchange, ex).block();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
    }

    @Test
    void handle_mapsConnectException_toServiceUnavailableEnvelope() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "req-test-456")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ConnectException ex = new ConnectException("Connection refused");

        exceptionHandler.handle(exchange, ex).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }

    @Test
    void handle_mapsTimeoutException_toGatewayTimeoutEnvelope() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "req-test-789")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        TimeoutException ex = new TimeoutException("Response timeout");

        exceptionHandler.handle(exchange, ex).block();

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exchange.getResponse().getStatusCode());
    }

    @Test
    void handle_mapsUnhandledException_toInternalServerErrorEnvelope() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/residents").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        RuntimeException ex = new RuntimeException("Unexpected null pointer");

        exceptionHandler.handle(exchange, ex).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
    }
}
