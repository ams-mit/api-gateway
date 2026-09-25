package com.projecta.apigateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projecta.apigateway.config.SecurityProperties;
import com.projecta.apigateway.exception.ErrorResponseWriter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

/**
 * Authenticates incoming User and Service JWTs following 03-JWT-AUTHENTICATION-STANDARD §16/§20:
 * RS256 only, key selected by token type (and, for services, by the claimed service name as a
 * key-selection hint), signature verified before any claim is trusted, then type/exp/claims checked.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    public static final String ATTR_USER_ID = "authenticatedSubject";
    public static final String ATTR_TOKEN_TYPE = "authenticatedType";
    public static final String ATTR_ROLES = "authenticatedRoles";

    static final String TYPE_USER = "user";
    static final String TYPE_SERVICE = "service";
    private static final String REQUIRED_ALGORITHM = "RS256";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecurityProperties securityProperties;
    private final JwtKeyStore keyStore;
    private final ObjectMapper objectMapper;
    private final ErrorResponseWriter errorResponseWriter;

    public JwtAuthenticationFilter(SecurityProperties securityProperties,
                                   JwtKeyStore keyStore,
                                   ObjectMapper objectMapper,
                                   ErrorResponseWriter errorResponseWriter) {
        this.securityProperties = securityProperties;
        this.keyStore = keyStore;
        this.objectMapper = objectMapper;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (securityProperties.isPublicPath(path)) {
            logger.debug("Public path accessed: {}, bypassing JWT authentication", path);
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return reject(exchange, path, "missing or non-Bearer Authorization header");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return reject(exchange, path, "empty Bearer token");
        }

        AuthenticatedPrincipal principal;
        try {
            principal = authenticate(token);
        } catch (AuthenticationFailure e) {
            return reject(exchange, path, e.getMessage());
        } catch (ExpiredJwtException e) {
            return reject(exchange, path, "token expired");
        } catch (JwtException | IllegalArgumentException e) {
            return reject(exchange, path, "invalid signature or token format");
        }

        exchange.getAttributes().put(ATTR_USER_ID, principal.subject());
        exchange.getAttributes().put(ATTR_TOKEN_TYPE, principal.type());
        if (principal.roles() != null) {
            exchange.getAttributes().put(ATTR_ROLES, principal.roles());
        }

        logger.debug("Authenticated {} token for subject: {}", principal.type(), principal.subject());
        return chain.filter(exchange);
    }

    private AuthenticatedPrincipal authenticate(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[2].isEmpty()) {
            throw new AuthenticationFailure("malformed JWT");
        }

        // Unverified header/payload are used only to enforce RS256 and select the verification key
        JsonNode header = decodeSegment(parts[0]);
        if (!REQUIRED_ALGORITHM.equals(header.path("alg").asText(null))) {
            throw new AuthenticationFailure("unsupported JWT algorithm");
        }

        JsonNode unverifiedPayload = decodeSegment(parts[1]);
        String claimedType = unverifiedPayload.path("type").asText(null);
        PublicKey verificationKey = selectVerificationKey(claimedType, unverifiedPayload.path("sub").asText(null));

        Jws<Claims> jws = Jwts.parser()
                .verifyWith(verificationKey)
                .build()
                .parseSignedClaims(token);

        if (!REQUIRED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            throw new AuthenticationFailure("unsupported JWT algorithm");
        }

        // From here on, claims are signature-verified
        Claims claims = jws.getPayload();
        String type = claims.get("type", String.class);
        if (!claimedType.equals(type)) {
            throw new AuthenticationFailure("invalid token type");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AuthenticationFailure("missing subject");
        }
        if (claims.getExpiration() == null) {
            throw new AuthenticationFailure("missing exp claim");
        }

        List<String> roles = TYPE_USER.equals(type) ? requireRoles(claims) : null;
        return new AuthenticatedPrincipal(subject, type, roles);
    }

    private PublicKey selectVerificationKey(String claimedType, String claimedSubject) {
        if (TYPE_USER.equals(claimedType)) {
            return keyStore.identityPublicKey();
        }
        if (TYPE_SERVICE.equals(claimedType)) {
            if (claimedSubject == null || claimedSubject.isBlank()) {
                throw new AuthenticationFailure("missing service subject");
            }
            return keyStore.servicePublicKey(claimedSubject)
                    .orElseThrow(() -> new AuthenticationFailure("unregistered service"));
        }
        throw new AuthenticationFailure("invalid token type");
    }

    private List<String> requireRoles(Claims claims) {
        Object rolesClaim = claims.get("roles");
        if (!(rolesClaim instanceof List<?> rawRoles)) {
            throw new AuthenticationFailure("missing or invalid roles claim");
        }
        for (Object role : rawRoles) {
            if (!(role instanceof String)) {
                throw new AuthenticationFailure("missing or invalid roles claim");
            }
        }
        return rawRoles.stream().map(String.class::cast).toList();
    }

    private JsonNode decodeSegment(String segment) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(segment);
            JsonNode node = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                throw new AuthenticationFailure("malformed JWT");
            }
            return node;
        } catch (AuthenticationFailure e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationFailure("malformed JWT");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, String path, String reason) {
        // Reason is logged for investigation only; clients get a generic message (08 §6)
        logger.warn("Authentication failed for path {}: {}", path, reason);
        return errorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication failed");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    record AuthenticatedPrincipal(String subject, String type, List<String> roles) {
    }

    static final class AuthenticationFailure extends RuntimeException {
        AuthenticationFailure(String message) {
            super(message, null, false, false);
        }
    }
}
