package com.projecta.apigateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Service
public class KeyResolverService {

    private static final Logger logger = LoggerFactory.getLogger(KeyResolverService.class);
    private final ResourceLoader resourceLoader;

    public KeyResolverService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public PublicKey loadPublicKeyFromLocation(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            try (InputStream is = resource.getInputStream()) {
                String pemContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return parsePublicKey(pemContent);
            }
        } catch (Exception e) {
            logger.error("Failed to load public key from location {}: {}", location, e.getMessage());
            throw new IllegalArgumentException("Unable to load RSA public key from location: " + location, e);
        }
    }

    public PrivateKey loadPrivateKeyFromLocation(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            try (InputStream is = resource.getInputStream()) {
                String pemContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return parsePrivateKey(pemContent);
            }
        } catch (Exception e) {
            logger.error("Failed to load private key from location {}: {}", location, e.getMessage());
            throw new IllegalArgumentException("Unable to load RSA private key from location: " + location, e);
        }
    }

    public PublicKey parsePublicKey(String pemContent) {
        try {
            String publicKeyPEM = pemContent
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");

            byte[] encoded = Base64.getDecoder().decode(publicKeyPEM);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            logger.error("Error parsing RSA Public Key PEM: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid RSA Public Key PEM format", e);
        }
    }

    public PrivateKey parsePrivateKey(String pemContent) {
        try {
            String privateKeyPEM = pemContent
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");

            byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            logger.error("Error parsing RSA Private Key PEM: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid RSA Private Key PEM format", e);
        }
    }
}
