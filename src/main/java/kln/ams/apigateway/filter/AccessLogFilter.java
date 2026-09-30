package kln.ams.apigateway.filter;

import kln.ams.apigateway.exception.ErrorResponseWriter;
import kln.ams.apigateway.security.JwtAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * One access-log line per request (API-GATEWAY.md §67, §138): request ID, route, method, path,
 * status, duration, authenticated user/service and Gateway error code. Never logs headers,
 * tokens or bodies.
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

    private static final Logger accessLog = LoggerFactory.getLogger("kln.ams.apigateway.access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            String type = exchange.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE);
            String subject = exchange.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            String errorCode = exchange.getAttribute(ErrorResponseWriter.ERROR_CODE_ATTR);
            accessLog.info("service=api-gateway requestId={} route={} method={} path={} status={} durationMs={} {}={} errorCode={}",
                    RequestTraceFilter.getRequestId(exchange),
                    route != null ? route.getId() : "-",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(),
                    status != null ? status.value() : "-",
                    (System.nanoTime() - start) / 1_000_000,
                    "service".equals(type) ? "callingService" : "userId",
                    subject != null ? subject : "-",
                    errorCode != null ? errorCode : "-");
        });
    }

    @Override
    public int getOrder() {
        // Just after RequestTraceFilter so the request ID is available
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
