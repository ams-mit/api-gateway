package com.projecta.apigateway.exception;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalErrorWebExceptionHandlerTest {

    private GlobalErrorWebExceptionHandler exceptionHandler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        exceptionHandler = new GlobalErrorWebExceptionHandler(new ErrorResponseWriter(objectMapper));
    }

    @Test
    void handle_mapsNotFound_toRouteNotFoundEnvelope_withoutLeakingReason() throws Exception {
        MockServerWebExchange exchange = exchange();

        exceptionHandler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND, "No static resource for http://internal")).block();

        JsonNode body = assertEnvelope(exchange, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND");
        assertEquals("API endpoint not found", body.get("message").asText());
        assertEquals("req-test-123", body.get("requestId").asText());
    }

    @Test
    void handle_mapsConnectException_toDependencyUnavailable() throws Exception {
        MockServerWebExchange exchange = exchange();

        exceptionHandler.handle(exchange, new RuntimeException("wrapped", new ConnectException("Connection refused"))).block();

        assertEnvelope(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void handle_mapsUnknownHost_toDependencyUnavailable() throws Exception {
        MockServerWebExchange exchange = exchange();

        exceptionHandler.handle(exchange, new UnknownHostException("resident-management-service")).block();

        assertEnvelope(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void handle_mapsTimeouts_toDependencyUnavailable() throws Exception {
        MockServerWebExchange timeout = exchange();
        exceptionHandler.handle(timeout, new TimeoutException("Response timeout")).block();
        assertEnvelope(timeout, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");

        // Spring Cloud Gateway signals its response-timeout as a 504 ResponseStatusException
        MockServerWebExchange gatewayTimeout = exchange();
        exceptionHandler.handle(gatewayTimeout, new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout")).block();
        assertEnvelope(gatewayTimeout, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void handle_mapsUnhandledException_toInternalServerError_withoutLeakingMessage() throws Exception {
        MockServerWebExchange exchange = exchange();

        exceptionHandler.handle(exchange, new RuntimeException("Unexpected null pointer")).block();

        JsonNode body = assertEnvelope(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
        assertFalse(body.toString().contains("null pointer"));
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/residents")
                .header(RequestTraceFilter.REQUEST_ID_HEADER, "req-test-123")
                .build());
    }

    private JsonNode assertEnvelope(MockServerWebExchange exchange, HttpStatus status, String code) throws Exception {
        assertEquals(status, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertFalse(body.get("success").asBoolean());
        assertEquals(code, body.get("error").get("code").asText());
        assertTrue(body.has("timestamp"));
        return body;
    }
}
