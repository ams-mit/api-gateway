package com.projecta.apigateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class GatewayTokenRelayFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTokenRelayFilter.class);

    private final GatewayJwtSigner gatewayJwtSigner;

    public GatewayTokenRelayFilter(GatewayJwtSigner gatewayJwtSigner) {
        this.gatewayJwtSigner = gatewayJwtSigner;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String subject = exchange.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String tokenType = exchange.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_TYPE);

        if (subject == null || tokenType == null) {
            logger.debug("Unauthenticated request or public route, passing downstream unchanged");
            return chain.filter(exchange);
        }

        List<?> roles = exchange.getAttribute(JwtAuthenticationFilter.ATTR_ROLES);
        String gatewayJwt;

        if ("user".equalsIgnoreCase(tokenType)) {
            gatewayJwt = gatewayJwtSigner.generateGatewayUserToken(subject, roles);
        } else {
            gatewayJwt = gatewayJwtSigner.generateGatewayServiceToken(subject);
        }

        // Mutate request headers: replace Authorization header with Gateway-signed JWT
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gatewayJwt)
                .build();

        logger.debug("Re-signed token for subject '{}' ({}), replacing Bearer header for downstream request", subject, tokenType);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        // Runs immediately after JwtAuthenticationFilter (Ordered.HIGHEST_PRECEDENCE + 20)
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
