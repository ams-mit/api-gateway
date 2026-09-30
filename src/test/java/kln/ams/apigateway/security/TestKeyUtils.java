package kln.ams.apigateway.security;

import kln.ams.apigateway.config.GatewayConfigurationValidator;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

public class TestKeyUtils {

    /** Shared key pairs for Spring context tests, generated once per JVM. */
    public static final KeyPair IDENTITY_KEYS = generateRsaKeyPair();
    public static final KeyPair GATEWAY_KEYS = generateRsaKeyPair();
    /** One Service JWT key pair per registered Project A backend service. */
    public static final Map<String, KeyPair> SERVICE_KEYS = new TreeMap<>();

    static {
        GatewayConfigurationValidator.REQUIRED_SERVICES.forEach(s -> SERVICE_KEYS.put(s, generateRsaKeyPair()));
    }

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

    public static Map<String, PublicKey> servicePublicKeys() {
        Map<String, PublicKey> keys = new TreeMap<>();
        SERVICE_KEYS.forEach((name, pair) -> keys.put(name, pair.getPublic()));
        return keys;
    }

    /**
     * Supplies every required Gateway setting (keys inline as PEM, service URIs, CORS origins) to a
     * Spring test context, mirroring what a real deployment must provide.
     */
    public static void registerGatewayProperties(DynamicPropertyRegistry registry, Function<String, String> serviceUri) {
        registry.add("IDENTITY_PUBLIC_KEY", () -> toPemPublicKey(IDENTITY_KEYS));
        registry.add("GATEWAY_PRIVATE_KEY", () -> toPemPrivateKey(GATEWAY_KEYS));
        registry.add("GATEWAY_PUBLIC_KEY", () -> toPemPublicKey(GATEWAY_KEYS));
        registry.add("FRONTEND_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        Map<String, String> envPrefixes = Map.of(
                "identity-access-service", "IDENTITY", "resident-management-service", "RESIDENT",
                "property-unit-service", "PROPERTY", "lease-occupancy-service", "LEASE",
                "billing-payment-service", "BILLING", "utility-charge-service", "UTILITY",
                "operations-service", "OPERATIONS", "community-service", "COMMUNITY");
        envPrefixes.forEach((service, prefix) -> {
            registry.add(prefix + "_SERVICE_URI", () -> serviceUri.apply(service));
            registry.add(prefix + "_SERVICE_PUBLIC_KEY", () -> toPemPublicKey(SERVICE_KEYS.get(service)));
        });
    }
}
