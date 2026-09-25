package com.projecta.apigateway.security;

import com.projecta.apigateway.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the trust material the Gateway needs. All keys are loaded eagerly at startup so a
 * misconfigured deployment fails fast instead of rejecting every request with 401.
 */
@Component
public class JwtKeyStore {

    private static final Logger logger = LoggerFactory.getLogger(JwtKeyStore.class);

    private final PublicKey identityPublicKey;
    private final PrivateKey gatewayPrivateKey;
    private final Map<String, PublicKey> servicePublicKeys;

    @Autowired
    public JwtKeyStore(SecurityProperties securityProperties, KeyResolverService keyResolverService) {
        SecurityProperties.Jwt jwt = securityProperties.getJwt();
        this.identityPublicKey = load("identity public key",
                () -> keyResolverService.resolvePublicKey(jwt.getIdentityPublicKey()));
        this.gatewayPrivateKey = load("gateway private key",
                () -> keyResolverService.resolvePrivateKey(jwt.getGatewayPrivateKey()));

        Map<String, PublicKey> services = new LinkedHashMap<>();
        jwt.getServicePublicKeys().forEach((serviceName, keyValue) -> {
            if (keyValue == null || keyValue.isBlank()) {
                logger.info("No public key configured for service '{}'; its Service JWTs will be rejected", serviceName);
                return;
            }
            services.put(serviceName, load("service public key for " + serviceName,
                    () -> keyResolverService.resolvePublicKey(keyValue)));
        });
        this.servicePublicKeys = Collections.unmodifiableMap(services);
        logger.info("Loaded JWT trust keys; registered services: {}", servicePublicKeys.keySet());
    }

    public JwtKeyStore(PublicKey identityPublicKey, PrivateKey gatewayPrivateKey, Map<String, PublicKey> servicePublicKeys) {
        this.identityPublicKey = Objects.requireNonNull(identityPublicKey);
        this.gatewayPrivateKey = Objects.requireNonNull(gatewayPrivateKey);
        this.servicePublicKeys = Map.copyOf(servicePublicKeys);
    }

    public PublicKey identityPublicKey() {
        return identityPublicKey;
    }

    public PrivateKey gatewayPrivateKey() {
        return gatewayPrivateKey;
    }

    public Optional<PublicKey> servicePublicKey(String serviceName) {
        return Optional.ofNullable(servicePublicKeys.get(serviceName));
    }

    private static <T> T load(String description, java.util.function.Supplier<T> loader) {
        try {
            return loader.get();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to load " + description + ": " + e.getMessage(), e);
        }
    }
}
