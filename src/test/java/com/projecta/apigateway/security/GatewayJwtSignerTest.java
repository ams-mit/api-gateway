package com.projecta.apigateway.security;

import com.projecta.apigateway.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayJwtSignerTest {

    private GatewayJwtSigner gatewayJwtSigner;
    private KeyPair gatewayKeyPair;

    @BeforeEach
    void setUp() {
        gatewayKeyPair = TestKeyUtils.generateRsaKeyPair();
        JwtKeyStore keyStore = new JwtKeyStore(TestKeyUtils.generateRsaKeyPair().getPublic(), gatewayKeyPair.getPrivate(), Map.of());

        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getJwt().setGatewayTokenTtl(Duration.ofMinutes(5));
        gatewayJwtSigner = new GatewayJwtSigner(keyStore, securityProperties);
    }

    @Test
    void generateGatewayUserToken_createsValidSignedJwt() {
        String subject = "user_456";
        List<String> roles = List.of("TENANT", "RESIDENT");

        String token = gatewayJwtSigner.generateGatewayUserToken(subject, roles);

        Jws<Claims> jws = Jwts.parser()
                .verifyWith(gatewayKeyPair.getPublic())
                .build()
                .parseSignedClaims(token);
        Claims claims = jws.getPayload();

        assertEquals("RS256", jws.getHeader().getAlgorithm());
        assertEquals("JWT", jws.getHeader().getType());
        assertEquals(subject, claims.getSubject());
        assertEquals("user", claims.get("type", String.class));
        assertEquals(roles, claims.get("roles", List.class));
        assertEquals(300_000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    @Test
    void generateGatewayServiceToken_createsValidSignedServiceJwt() {
        String serviceName = "resident-management-service";

        String token = gatewayJwtSigner.generateGatewayServiceToken(serviceName);

        Claims claims = Jwts.parser()
                .verifyWith(gatewayKeyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals(serviceName, claims.getSubject());
        assertEquals("service", claims.get("type", String.class));
        assertNull(claims.get("roles"));
    }

    @Test
    void constructor_rejectsNonPositiveTtl() {
        JwtKeyStore keyStore = new JwtKeyStore(gatewayKeyPair.getPublic(), gatewayKeyPair.getPrivate(), Map.of());
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setGatewayTokenTtl(Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> new GatewayJwtSigner(keyStore, properties));
    }
}
