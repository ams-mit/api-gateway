package com.projecta.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts or generates {@code X-Request-ID} for every request. Implemented as a {@link WebFilter}
 * (not a Gateway GlobalFilter) so it also covers unmatched routes and actuator endpoints.
 */
@Component
public class RequestTraceFilter implements WebFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(RequestTraceFilter.class);
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REQUEST_ID_ATTR = RequestTraceFilter.class.getName() + ".requestId";

    // Caller-supplied IDs are echoed into logs and headers, so restrict them to a safe shape
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public static String getRequestId(ServerWebExchange exchange) {
        String requestId = exchange.getAttribute(REQUEST_ID_ATTR);
        return requestId != null ? requestId : exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = request.getHeaders().getFirst(REQUEST_ID_HEADER);

        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
            logger.debug("Generated new X-Request-ID: {}", requestId);
        } else {
            logger.debug("Propagating existing X-Request-ID: {}", requestId);
        }

        String finalRequestId = requestId;
        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, finalRequestId))
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        mutatedExchange.getAttributes().put(REQUEST_ID_ATTR, finalRequestId);

        // Set now and again before commit, so a downstream echo cannot duplicate the header
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, finalRequestId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, finalRequestId);
            return Mono.empty();
        });

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
