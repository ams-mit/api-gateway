package com.projecta.apigateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.config.SecurityProperties;
import com.projecta.apigateway.filter.RequestTraceFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.PublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    public static final String ATTR_USER_ID = "authenticatedSubject";
    public static final String ATTR_TOKEN_TYPE = "authenticatedType";
    public static final String ATTR_ROLES = "authenticatedRoles";
    public static final String ATTR_CLAIMS = "authenticatedClaims";

    private final SecurityProperties securityProperties;
    private final KeyResolverService keyResolverService;
    private final ObjectMapper objectMapper;

    // Cached public key loaded at initialization or lazily resolved
    private PublicKey identityPublicKey;

    public JwtAuthenticationFilter(SecurityProperties securityProperties,
                                   KeyResolverService keyResolverService,
                                   ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.keyResolverService = keyResolverService;
        this.objectMapper = objectMapper;
    }

    public void setIdentityPublicKey(PublicKey identityPublicKey) {
        this.identityPublicKey = identityPublicKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (securityProperties.isPublicPath(path)) {
            logger.debug("Public path accessed: {}, bypassing JWT authentication", path);
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("Authentication failed for path {}: Missing or invalid Authorization header", path);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            logger.warn("Authentication failed for path {}: Empty Bearer token", path);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bearer token cannot be empty");
        }

        try {
            PublicKey publicKey = getOrLoadIdentityPublicKey();
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenType = claims.get("type", String.class);
            if (!"user".equalsIgnoreCase(tokenType) && !"service".equalsIgnoreCase(tokenType)) {
                logger.warn("Authentication failed for path {}: Invalid token type {}", path, tokenType);
                return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid token type");
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                logger.warn("Authentication failed for path {}: Subject (sub) is missing or empty", path);
                return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid token subject");
            }

            // Store attributes in ServerWebExchange for downstream filters
            exchange.getAttributes().put(ATTR_USER_ID, subject);
            exchange.getAttributes().put(ATTR_TOKEN_TYPE, tokenType);
            exchange.getAttributes().put(ATTR_CLAIMS, claims);

            List<?> roles = claims.get("roles", List.class);
            if (roles != null) {
                exchange.getAttributes().put(ATTR_ROLES, roles);
            }

            logger.debug("Successfully authenticated {} token for subject: {}", tokenType, subject);
            return chain.filter(exchange);

        } catch (ExpiredJwtException e) {
            logger.warn("Authentication failed for path {}: Token expired", path);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "JWT token has expired");
        } catch (JwtException e) {
            logger.warn("Authentication failed for path {}: Invalid signature or token format ({})", path, e.getMessage());
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid JWT signature or token format");
        } catch (Exception e) {
            logger.error("Authentication error for path {}: {}", path, e.getMessage(), e);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_ERROR", "Authentication failed");
        }
    }

    private synchronized PublicKey getOrLoadIdentityPublicKey() {
        if (identityPublicKey == null) {
            String keyPath = securityProperties.getJwt().getIdentityPublicKeyPath();
            if (keyPath != null && !keyPath.isBlank()) {
                identityPublicKey = keyResolverService.loadPublicKeyFromLocation(keyPath);
            }
        }
        if (identityPublicKey == null) {
            throw new IllegalStateException("Identity public key is not configured or could not be loaded");
        }
        return identityPublicKey;
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status, String errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String requestId = exchange.getRequest().getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER);
        if (requestId == null) {
            requestId = response.getHeaders().getFirst(RequestTraceFilter.REQUEST_ID_HEADER);
        }

        Map<String, Object> errorEnvelope = new HashMap<>();
        errorEnvelope.put("success", false);
        errorEnvelope.put("message", message);

        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("code", errorCode);
        errorDetails.put("details", null);

        errorEnvelope.put("error", errorDetails);
        errorEnvelope.put("timestamp", Instant.now().toString());
        errorEnvelope.put("requestId", requestId != null ? requestId : "");

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorEnvelope);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            logger.error("Failed to write error JSON response: {}", e.getMessage());
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
