package kln.ams.apigateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Maps Gateway failures to the shared error envelope.
 * <ul>
 *   <li>Unknown route: 404 {@code ROUTE_NOT_FOUND}.</li>
 *   <li>Backend unreachable or timed out: 503 {@code DEPENDENCY_UNAVAILABLE}, with the logical
 *       service name in {@code details} (never hostnames or exception text).</li>
 *   <li>Anything unexpected: 500 {@code INTERNAL_SERVER_ERROR}.</li>
 * </ul>
 * Backend responses (including backend business errors) are not exceptions and pass through unchanged.
 */
@Component
public class GlobalErrorWebExceptionHandler implements WebExceptionHandler, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(GlobalErrorWebExceptionHandler.class);

    private final ErrorResponseWriter errorResponseWriter;

    public GlobalErrorWebExceptionHandler(ErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        ErrorMapping mapping = map(ex);
        String path = exchange.getRequest().getURI().getPath();
        if (mapping.status().is5xxServerError()) {
            logger.error("Handling error [{} - {}] for path {}: {}", mapping.status().value(), mapping.code(), path, ex.toString());
        } else {
            logger.debug("Handling error [{} - {}] for path {}", mapping.status().value(), mapping.code(), path);
        }

        Object details = null;
        if ("DEPENDENCY_UNAVAILABLE".equals(mapping.code())) {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            Object service = route != null ? route.getMetadata().get("service") : null;
            if (service != null) {
                details = Map.of("service", service.toString());
            }
        }
        return errorResponseWriter.write(exchange, mapping.status(), mapping.code(), mapping.message(), details);
    }

    private ErrorMapping map(Throwable ex) {
        if (isDependencyFailure(ex)) {
            return dependencyUnavailable();
        }
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            return switch (status) {
                case NOT_FOUND -> new ErrorMapping(status, "ROUTE_NOT_FOUND", "Requested API route was not found");
                // Gateway's own response-timeout and upstream availability errors
                case GATEWAY_TIMEOUT, SERVICE_UNAVAILABLE, BAD_GATEWAY -> dependencyUnavailable();
                case BAD_REQUEST -> new ErrorMapping(status, "BAD_REQUEST", "Invalid request");
                case UNAUTHORIZED -> new ErrorMapping(status, "UNAUTHORIZED", "Authentication failed");
                case FORBIDDEN -> new ErrorMapping(status, "FORBIDDEN", "Access denied");
                case METHOD_NOT_ALLOWED -> new ErrorMapping(status, "METHOD_NOT_ALLOWED", "Method not allowed");
                case CONTENT_TOO_LARGE -> new ErrorMapping(status, "PAYLOAD_TOO_LARGE", "Request body is too large");
                case UNSUPPORTED_MEDIA_TYPE -> new ErrorMapping(status, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type");
                case TOO_MANY_REQUESTS -> new ErrorMapping(status, "RATE_LIMITED", "Too many requests");
                default -> status.is4xxClientError()
                        ? new ErrorMapping(status, "BAD_REQUEST", status.getReasonPhrase())
                        : internalError();
            };
        }
        return internalError();
    }

    private boolean isDependencyFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // Any I/O failure while proxying (refused, reset, aborted, closed) is a backend fault, not a Gateway fault
            if (t instanceof IOException
                    || t instanceof UnknownHostException
                    || t instanceof TimeoutException
                    || t.getClass().getSimpleName().contains("Timeout")
                    || t.getClass().getSimpleName().equals("PrematureCloseException")) {
                return true;
            }
        }
        return false;
    }

    private static ErrorMapping dependencyUnavailable() {
        return new ErrorMapping(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Required service is unavailable");
    }

    private static ErrorMapping internalError() {
        return new ErrorMapping(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred processing your request");
    }

    @Override
    public int getOrder() {
        // Ahead of Spring Boot's DefaultErrorWebExceptionHandler (-1)
        return -2;
    }

    private record ErrorMapping(HttpStatus status, String code, String message) {
    }
}
