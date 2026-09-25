package com.projecta.apigateway.security;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public class TestKeyUtils {

    /** Shared key pairs for Spring context tests, generated once per JVM. */
    public static final KeyPair IDENTITY_KEYS = generateRsaKeyPair();
    public static final KeyPair GATEWAY_KEYS = generateRsaKeyPair();
    public static final KeyPair RESIDENT_SERVICE_KEYS = generateRsaKeyPair();

    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test RSA key pair", e);
        }
    }

    public static String toPemPublicKey(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    public static String toPemPrivateKey(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }

    /** Supplies inline PEM keys to a Spring test context. */
    public static void registerKeyProperties(DynamicPropertyRegistry registry) {
        registry.add("projecta.security.jwt.identity-public-key", () -> toPemPublicKey(IDENTITY_KEYS));
        registry.add("projecta.security.jwt.gateway-private-key", () -> toPemPrivateKey(GATEWAY_KEYS));
        registry.add("projecta.security.jwt.service-public-keys.resident-management-service",
                () -> toPemPublicKey(RESIDENT_SERVICE_KEYS));
    }
}
