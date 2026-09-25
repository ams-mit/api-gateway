package com.projecta.apigateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.filter.RequestTraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the shared Project A error envelope for Gateway-generated failures.
 */
@Component
public class ErrorResponseWriter {

    private static final Logger logger = LoggerFactory.getLogger(ErrorResponseWriter.class);

    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String requestId = RequestTraceFilter.getRequestId(exchange);

        Map<String, Object> errorDetails = new LinkedHashMap<>();
        errorDetails.put("code", errorCode);
        errorDetails.put("details", null);

        Map<String, Object> errorEnvelope = new LinkedHashMap<>();
        errorEnvelope.put("success", false);
        errorEnvelope.put("message", message);
        errorEnvelope.put("error", errorDetails);
        errorEnvelope.put("timestamp", Instant.now().toString());
        errorEnvelope.put("requestId", requestId != null ? requestId : "");

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorEnvelope);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize error response: {}", e.getMessage());
            return response.setComplete();
        }
    }
}
