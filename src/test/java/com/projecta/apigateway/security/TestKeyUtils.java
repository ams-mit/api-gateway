package com.projecta.apigateway.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public class TestKeyUtils {

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
        String encoded = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    public static String toPemPrivateKey(KeyPair keyPair) {
        String encoded = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}
