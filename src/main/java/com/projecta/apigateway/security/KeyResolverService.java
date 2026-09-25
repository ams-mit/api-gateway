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

/**
 * Loads RSA keys from configuration. A configured value may be either a resource location
 * ({@code file:...}, {@code classpath:...}) or the key material itself (PEM text, or the bare
 * base64 body of a PEM), so keys can be mounted as files or injected via environment variables.
 */
@Service
public class KeyResolverService {

    private static final Logger logger = LoggerFactory.getLogger(KeyResolverService.class);
    private static final String PEM_MARKER = "-----BEGIN";

    private final ResourceLoader resourceLoader;

    public KeyResolverService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public PublicKey resolvePublicKey(String value) {
        return parsePublicKey(readKeyMaterial(value));
    }

    public PrivateKey resolvePrivateKey(String value) {
        return parsePrivateKey(readKeyMaterial(value));
    }

    private String readKeyMaterial(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RSA key value is not configured");
        }
        String trimmed = value.trim();
        if (trimmed.contains(PEM_MARKER) || !looksLikeLocation(trimmed)) {
            return trimmed;
        }
        Resource resource = resourceLoader.getResource(trimmed);
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Location is not secret, key content is never logged
            logger.error("Failed to read RSA key from location {}: {}", trimmed, e.getMessage());
            throw new IllegalArgumentException("Unable to read RSA key from location: " + trimmed, e);
        }
    }

    private boolean looksLikeLocation(String value) {
        return value.startsWith("classpath:") || value.startsWith("file:")
                || value.startsWith("/") || value.startsWith("./") || value.endsWith(".pem");
    }

    public PublicKey parsePublicKey(String pemContent) {
        try {
            byte[] encoded = decodePem(pemContent, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            logger.error("Error parsing RSA Public Key PEM: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid RSA Public Key PEM format", e);
        }
    }

    public PrivateKey parsePrivateKey(String pemContent) {
        try {
            byte[] encoded = decodePem(pemContent, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception e) {
            logger.error("Error parsing RSA Private Key PEM: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid RSA Private Key PEM (PKCS#8 expected)", e);
        }
    }

    private byte[] decodePem(String pemContent, String label) {
        String body = pemContent
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                // Env vars often carry PEM newlines as a literal "\n"
                .replace("\\n", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }
}
