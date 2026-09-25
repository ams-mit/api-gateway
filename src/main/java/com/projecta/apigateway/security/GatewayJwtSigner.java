package com.projecta.apigateway.security;

import com.projecta.apigateway.config.SecurityProperties;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.List;

@Component
public class GatewayJwtSigner {

    private static final Logger logger = LoggerFactory.getLogger(GatewayJwtSigner.class);

    private final JwtKeyStore keyStore;
    private final Duration tokenTtl;

    public GatewayJwtSigner(JwtKeyStore keyStore, SecurityProperties securityProperties) {
        this.keyStore = keyStore;
        this.tokenTtl = securityProperties.getJwt().getGatewayTokenTtl();
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalStateException("projecta.security.jwt.gateway-token-ttl must be a positive duration");
        }
    }

    public String generateGatewayUserToken(String subject, List<String> roles) {
        String token = baseBuilder(subject, JwtAuthenticationFilter.TYPE_USER)
                .claim("roles", roles != null ? roles : List.of())
                .compact();
        logger.debug("Generated Gateway User JWT for subject: {}", subject);
        return token;
    }

    public String generateGatewayServiceToken(String serviceName) {
        String token = baseBuilder(serviceName, JwtAuthenticationFilter.TYPE_SERVICE).compact();
        logger.debug("Generated Gateway Service JWT for subject: {}", serviceName);
        return token;
    }

    private JwtBuilder baseBuilder(String subject, String type) {
        Date now = new Date();
        return Jwts.builder()
                .header().type("JWT").and()
                .subject(subject)
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + tokenTtl.toMillis()))
                .signWith(keyStore.gatewayPrivateKey(), Jwts.SIG.RS256);
    }
}
