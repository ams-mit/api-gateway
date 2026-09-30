package kln.ams.apigateway.config;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fails startup on invalid non-key configuration (API-GATEWAY.md §121-§122, §153-§154).
 * Key material is validated by {@link kln.ams.apigateway.security.JwtKeyStore}.
 */
@Component
public class GatewayConfigurationValidator {

    /** The eight Project A backend services the Gateway must route (API-GATEWAY.md §18). */
    public static final Set<String> REQUIRED_SERVICES = Set.of(
            "identity-access-service", "resident-management-service",
            "property-unit-service", "lease-occupancy-service",
            "billing-payment-service", "utility-charge-service",
            "operations-service", "community-service");

    public GatewayConfigurationValidator(DownstreamServicesProperties servicesProperties, SecurityProperties securityProperties) {
        validateServices(servicesProperties.getServices());
        validateOrigins(securityProperties.getAllowedOrigins());
    }

    static void validateServices(Map<String, String> services) {
        for (String required : REQUIRED_SERVICES) {
            if (!services.containsKey(required)) {
                throw new IllegalStateException("Missing URI for required service '" + required + "'");
            }
        }
        services.forEach((name, value) -> {
            if (!REQUIRED_SERVICES.contains(name)) {
                throw new IllegalStateException("Unknown service '" + name + "' in projecta.services");
            }
            if (!isHttpUri(value, true)) {
                throw new IllegalStateException("Invalid URI for service '" + name + "': expected an absolute http(s) URI");
            }
        });
    }

    static void validateOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException("FRONTEND_ALLOWED_ORIGINS must list at least one origin");
        }
        for (String origin : origins) {
            if (origin == null || origin.contains("*") || !isHttpUri(origin.trim(), false)) {
                throw new IllegalStateException("Invalid frontend origin '" + origin + "': wildcards are not allowed");
            }
        }
    }

    private static boolean isHttpUri(String value, boolean allowPath) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            boolean pathOk = allowPath || uri.getPath() == null || uri.getPath().isEmpty();
            return http && uri.getHost() != null && pathOk && uri.getQuery() == null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
