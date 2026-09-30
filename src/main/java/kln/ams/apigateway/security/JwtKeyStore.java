package kln.ams.apigateway.security;

import io.jsonwebtoken.Jwts;
import kln.ams.apigateway.config.DownstreamServicesProperties;
import kln.ams.apigateway.config.SecurityProperties;
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
import java.util.function.Supplier;

/**
 * Holds the Gateway's trust material. Everything is loaded and validated at startup so a
 * misconfigured deployment fails fast instead of starting in an insecure or broken state:
 * <ul>
 *   <li>Identity Access public key and Gateway private key are required;</li>
 *   <li>every backend service in {@code projecta.services} must have a registered public key;</li>
 *   <li>if a Gateway public key is configured it must match the Gateway private key.</li>
 * </ul>
 */
@Component
public class JwtKeyStore {

    private static final Logger logger = LoggerFactory.getLogger(JwtKeyStore.class);

    private final PublicKey identityPublicKey;
    private final PrivateKey gatewayPrivateKey;
    private final Map<String, PublicKey> servicePublicKeys;

    @Autowired
    public JwtKeyStore(SecurityProperties securityProperties, DownstreamServicesProperties servicesProperties,
                       KeyResolverService keyResolverService) {
        SecurityProperties.Jwt jwt = securityProperties.getJwt();
        this.identityPublicKey = load("identity public key", () -> keyResolverService.resolvePublicKey(jwt.getIdentityPublicKey()));
        this.gatewayPrivateKey = load("gateway private key", () -> keyResolverService.resolvePrivateKey(jwt.getGatewayPrivateKey()));

        String gatewayPublicKey = jwt.getGatewayPublicKey();
        if (gatewayPublicKey != null && !gatewayPublicKey.isBlank()) {
            PublicKey publicKey = load("gateway public key", () -> keyResolverService.resolvePublicKey(gatewayPublicKey));
            requireMatchingPair(gatewayPrivateKey, publicKey);
        }

        Map<String, PublicKey> services = new LinkedHashMap<>();
        for (String serviceName : servicesProperties.getServices().keySet()) {
            String keyValue = jwt.getServicePublicKeys().get(serviceName);
            if (keyValue == null || keyValue.isBlank()) {
                throw new IllegalStateException("No service public key configured for registered service '" + serviceName + "'");
            }
            services.put(serviceName, load("service public key for " + serviceName, () -> keyResolverService.resolvePublicKey(keyValue)));
        }
        for (String keyOwner : jwt.getServicePublicKeys().keySet()) {
            if (!services.containsKey(keyOwner)) {
                throw new IllegalStateException("Service public key configured for unknown service '" + keyOwner + "'");
            }
        }
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

    private static void requireMatchingPair(PrivateKey privateKey, PublicKey publicKey) {
        try {
            String probe = Jwts.builder().subject("key-check").signWith(privateKey, Jwts.SIG.RS256).compact();
            Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(probe);
        } catch (RuntimeException e) {
            throw new IllegalStateException("GATEWAY_PUBLIC_KEY does not match GATEWAY_PRIVATE_KEY", e);
        }
    }

    private static <T> T load(String description, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to load " + description + ": " + e.getMessage(), e);
        }
    }
}
