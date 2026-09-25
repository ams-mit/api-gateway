package com.projecta.apigateway.security;

import com.projecta.apigateway.config.SecurityProperties;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.util.Date;
import java.util.List;

@Component
public class GatewayJwtSigner {

    private static final Logger logger = LoggerFactory.getLogger(GatewayJwtSigner.class);
    public static final long TOKEN_TTL_MS = 300_000L; // 5 minutes

    private final SecurityProperties securityProperties;
    private final KeyResolverService keyResolverService;

    private PrivateKey gatewayPrivateKey;

    public GatewayJwtSigner(SecurityProperties securityProperties, KeyResolverService keyResolverService) {
        this.securityProperties = securityProperties;
        this.keyResolverService = keyResolverService;
    }

    public void setGatewayPrivateKey(PrivateKey gatewayPrivateKey) {
        this.gatewayPrivateKey = gatewayPrivateKey;
    }

    public String generateGatewayUserToken(String subject, List<?> roles) {
        PrivateKey privateKey = getOrLoadGatewayPrivateKey();
        Date now = new Date();
        Date expiration = new Date(now.getTime() + TOKEN_TTL_MS);

        var builder = Jwts.builder()
                .header().add("typ", "JWT").and()
                .subject(subject)
                .claim("type", "user")
                .issuedAt(now)
                .expiration(expiration);

        if (roles != null && !roles.isEmpty()) {
            builder.claim("roles", roles);
        }

        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        logger.debug("Generated Gateway User JWT for subject: {}", subject);
        return token;
    }

    public String generateGatewayServiceToken(String serviceName) {
        PrivateKey privateKey = getOrLoadGatewayPrivateKey();
        Date now = new Date();
        Date expiration = new Date(now.getTime() + TOKEN_TTL_MS);

        String token = Jwts.builder()
                .header().add("typ", "JWT").and()
                .subject(serviceName)
                .claim("type", "service")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        logger.debug("Generated Gateway Service JWT for subject: {}", serviceName);
        return token;
    }

    private synchronized PrivateKey getOrLoadGatewayPrivateKey() {
        if (gatewayPrivateKey == null) {
            String keyPath = securityProperties.getJwt().getGatewayPrivateKeyPath();
            if (keyPath != null && !keyPath.isBlank()) {
                gatewayPrivateKey = keyResolverService.loadPrivateKeyFromLocation(keyPath);
            }
        }
        if (gatewayPrivateKey == null) {
            throw new IllegalStateException("Gateway private key is not configured or could not be loaded");
        }
        return gatewayPrivateKey;
    }
}
