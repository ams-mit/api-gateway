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

/**
 * Replaces the caller's Authorization header with a Gateway-signed JWT. The original User/Service
 * JWT is never forwarded downstream; on public routes any caller-supplied Authorization is removed.
 */
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
            logger.debug("Unauthenticated public route, forwarding without Authorization header");
            ServerHttpRequest stripped = exchange.getRequest().mutate()
                    .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                    .build();
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String gatewayJwt;
        if (JwtAuthenticationFilter.TYPE_USER.equals(tokenType)) {
            List<String> roles = exchange.getAttribute(JwtAuthenticationFilter.ATTR_ROLES);
            gatewayJwt = gatewayJwtSigner.generateGatewayUserToken(subject, roles);
        } else {
            gatewayJwt = gatewayJwtSigner.generateGatewayServiceToken(subject);
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + gatewayJwt))
                .build();

        logger.debug("Re-signed token for subject '{}' ({}), replacing Bearer header for downstream request", subject, tokenType);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        // Runs immediately after JwtAuthenticationFilter (Ordered.HIGHEST_PRECEDENCE + 10)
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
