package kln.ams.apigateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.RequiredTypeException;
import io.jsonwebtoken.security.SignatureException;
import kln.ams.apigateway.exception.ErrorResponseWriter;
import kln.ams.apigateway.route.RouteAccessPolicy;
import kln.ams.apigateway.route.RouteAccessPolicy.Access;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
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
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates and authorizes every routed request according to its route policy
 * (PROJECT-A-JWT-SECURITY-STANDARD.md, API-GATEWAY.md §9-§11, §22-§24).
 * <ul>
 *   <li>RS256 only; the verification key is selected by token type (Identity Access key for users,
 *       the registered key of the claimed service for services). Claims are trusted only after
 *       the signature is verified.</li>
 *   <li>Required claims: {@code sub}, {@code type}, {@code iat}, {@code exp}; users also need a UUID
 *       {@code sub} and a canonical {@code roles} array.</li>
 *   <li>User routes accept only User JWTs; internal routes accept only Service JWTs from callers on the
 *       route's allow-list.</li>
 * </ul>
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

    /** Canonical Project A roles (PROJECT-A-CONTRACT-DECISIONS.md). */
    public static final Set<String> CANONICAL_ROLES = Set.of(
            "SYSTEM_ADMINISTRATOR", "APARTMENT_MANAGER", "OWNER", "TENANT_RESIDENT", "FINANCE_OFFICER",
            "MAINTENANCE_COORDINATOR", "TECHNICIAN", "SERVICE_STAFF", "SECURITY_OFFICER");

    private final JwtKeyStore keyStore;
    private final ObjectMapper objectMapper;
    private final ErrorResponseWriter errorResponseWriter;

    public JwtAuthenticationFilter(JwtKeyStore keyStore, ObjectMapper objectMapper, ErrorResponseWriter errorResponseWriter) {
        this.keyStore = keyStore;
        this.objectMapper = objectMapper;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        RouteAccessPolicy policy = route != null
                ? RouteAccessPolicy.from(route, path)
                : new RouteAccessPolicy(null, path.startsWith(RouteAccessPolicy.INTERNAL_PREFIX) ? Access.SERVICE : Access.USER, null, Set.of());

        if (policy.access() == Access.BLOCKED) {
            return errorResponseWriter.write(exchange, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Requested API route was not found");
        }
        if (policy.access() == Access.PUBLIC) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                || authHeader.substring(BEARER_PREFIX.length()).isBlank()) {
            return reject(exchange, path, "MISSING_TOKEN", "missing Bearer token");
        }

        AuthenticatedPrincipal principal;
        try {
            principal = authenticate(authHeader.substring(BEARER_PREFIX.length()).trim());
        } catch (AuthenticationFailure e) {
            return reject(exchange, path, e.code, e.getMessage());
        } catch (ExpiredJwtException e) {
            return reject(exchange, path, "TOKEN_EXPIRED", "token expired");
        } catch (SignatureException e) {
            return reject(exchange, path, "INVALID_SIGNATURE", "signature verification failed");
        } catch (RequiredTypeException e) {
            return reject(exchange, path, "INVALID_TOKEN_CLAIMS", "claim has invalid type");
        } catch (JwtException | IllegalArgumentException e) {
            return reject(exchange, path, "INVALID_TOKEN", "malformed token");
        }

        // Authorization (authentication succeeded, so failures are 403)
        if (policy.access() == Access.SERVICE) {
            if (!TYPE_SERVICE.equals(principal.type())) {
                return deny(exchange, path, "FORBIDDEN", "user token on internal route " + policy.routeId());
            }
            if (!policy.allowsCaller(principal.subject())) {
                return deny(exchange, path, "SERVICE_NOT_ALLOWED",
                        principal.subject() + " not allowed on route " + policy.routeId());
            }
        } else if (!TYPE_USER.equals(principal.type())) {
            return deny(exchange, path, "SERVICE_NOT_ALLOWED",
                    "service token " + principal.subject() + " on user route " + policy.routeId());
        }

        exchange.getAttributes().put(ATTR_USER_ID, principal.subject());
        exchange.getAttributes().put(ATTR_TOKEN_TYPE, principal.type());
        if (principal.roles() != null) {
            exchange.getAttributes().put(ATTR_ROLES, principal.roles());
        }
        logger.debug("Authenticated {} '{}' for route {}", principal.type(), principal.subject(), policy.routeId());
        return chain.filter(exchange);
    }

    AuthenticatedPrincipal authenticate(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new AuthenticationFailure("INVALID_TOKEN", "malformed JWT structure");
        }

        // Unverified header/payload are used only to enforce RS256 and select the verification key
        JsonNode header = decodeSegment(parts[0]);
        if (!REQUIRED_ALGORITHM.equals(header.path("alg").asText(null))) {
            throw new AuthenticationFailure("UNSUPPORTED_ALGORITHM", "unsupported JWT algorithm");
        }
        JsonNode unverifiedPayload = decodeSegment(parts[1]);
        String claimedType = unverifiedPayload.path("type").asText(null);
        PublicKey verificationKey = selectVerificationKey(claimedType, unverifiedPayload.path("sub").asText(null));

        Jws<Claims> jws = Jwts.parser().verifyWith(verificationKey).build().parseSignedClaims(token);
        if (!REQUIRED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            throw new AuthenticationFailure("UNSUPPORTED_ALGORITHM", "unsupported JWT algorithm");
        }

        // From here on, claims are signature-verified
        Claims claims = jws.getPayload();
        String type = claims.get("type", String.class);
        if (!claimedType.equals(type)) {
            throw new AuthenticationFailure("INVALID_TOKEN_TYPE", "invalid token type");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "missing sub");
        }
        if (claims.getIssuedAt() == null) {
            throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "missing iat");
        }
        if (claims.getExpiration() == null) {
            throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "missing exp");
        }

        if (TYPE_USER.equals(type)) {
            requireUuid(subject);
            return new AuthenticatedPrincipal(subject, type, requireCanonicalRoles(claims));
        }
        return new AuthenticatedPrincipal(subject, type, null);
    }

    private PublicKey selectVerificationKey(String claimedType, String claimedSubject) {
        if (TYPE_USER.equals(claimedType)) {
            return keyStore.identityPublicKey();
        }
        if (TYPE_SERVICE.equals(claimedType)) {
            if (claimedSubject == null || claimedSubject.isBlank()) {
                throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "missing service sub");
            }
            return keyStore.servicePublicKey(claimedSubject)
                    .orElseThrow(() -> new AuthenticationFailure("UNREGISTERED_SERVICE", "unregistered service"));
        }
        throw new AuthenticationFailure("INVALID_TOKEN_TYPE", "missing or unknown token type");
    }

    private static void requireUuid(String subject) {
        try {
            UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "user sub is not a UUID");
        }
    }

    private static List<String> requireCanonicalRoles(Claims claims) {
        if (!(claims.get("roles") instanceof List<?> rawRoles)) {
            throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "missing or invalid roles claim");
        }
        for (Object role : rawRoles) {
            if (!(role instanceof String name) || !CANONICAL_ROLES.contains(name)) {
                throw new AuthenticationFailure("INVALID_TOKEN_CLAIMS", "non-canonical role in roles claim");
            }
        }
        return rawRoles.stream().map(String.class::cast).toList();
    }

    private JsonNode decodeSegment(String segment) {
        try {
            JsonNode node = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                throw new AuthenticationFailure("INVALID_TOKEN", "malformed JWT");
            }
            return node;
        } catch (AuthenticationFailure e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationFailure("INVALID_TOKEN", "malformed JWT");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, String path, String code, String reason) {
        // Reason is logged for investigation only; clients get a generic message (JWT standard, "Security error behavior")
        logger.warn("JWT_VALIDATION_FAILURE path={} code={} reason={}", path, code, reason);
        String message = "MISSING_TOKEN".equals(code) ? "Authentication required" : "Authentication failed";
        return errorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, code, message);
    }

    private Mono<Void> deny(ServerWebExchange exchange, String path, String code, String reason) {
        logger.warn("SERVICE_AUTHORIZATION_DENIED path={} code={} reason={}", path, code, reason);
        return errorResponseWriter.write(exchange, HttpStatus.FORBIDDEN, code, "Access denied");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    record AuthenticatedPrincipal(String subject, String type, List<String> roles) {
    }

    static final class AuthenticationFailure extends RuntimeException {
        private final String code;

        AuthenticationFailure(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
