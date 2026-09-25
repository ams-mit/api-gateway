package com.projecta.apigateway.security;

import com.projecta.apigateway.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GatewayJwtSignerTest {

    private GatewayJwtSigner gatewayJwtSigner;
    private KeyPair gatewayKeyPair;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        KeyResolverService keyResolverService = mock(KeyResolverService.class);
        gatewayKeyPair = TestKeyUtils.generateRsaKeyPair();

        gatewayJwtSigner = new GatewayJwtSigner(securityProperties, keyResolverService);
        gatewayJwtSigner.setGatewayPrivateKey(gatewayKeyPair.getPrivate());
    }

    @Test
    void generateGatewayUserToken_createsValidSignedJwt() {
        String subject = "user_456";
        List<String> roles = List.of("TENANT", "RESIDENT");

        String token = gatewayJwtSigner.generateGatewayUserToken(subject, roles);

        assertNotNull(token);

        Claims claims = Jwts.parser()
                .verifyWith(gatewayKeyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals(subject, claims.getSubject());
        assertEquals("user", claims.get("type", String.class));
        assertEquals(roles, claims.get("roles", List.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    void generateGatewayServiceToken_createsValidSignedServiceJwt() {
        String serviceName = "resident-management-service";

        String token = gatewayJwtSigner.generateGatewayServiceToken(serviceName);

        assertNotNull(token);

        Claims claims = Jwts.parser()
                .verifyWith(gatewayKeyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals(serviceName, claims.getSubject());
        assertEquals("service", claims.get("type", String.class));
        assertNull(claims.get("roles"));
    }
}
