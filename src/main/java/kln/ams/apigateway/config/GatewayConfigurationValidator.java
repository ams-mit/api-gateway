package kln.ams.apigateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fails startup on invalid non-key configuration.
 * Key material is validated by {@link kln.ams.apigateway.security.JwtKeyStore}.
 */
@Component
public class GatewayConfigurationValidator {

    private static final Logger logger = LoggerFactory.getLogger(GatewayConfigurationValidator.class);

    /** Value of FRONTEND_ALLOWED_ORIGINS that allows browser calls from any origin. */
    public static final String ANY_ORIGIN = "*";

    /** The eight Project A backend services the Gateway must route. */
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
            if (origin != null && ANY_ORIGIN.equals(origin.trim())) {
                // Allowed by explicit project decision; safe here because CORS credentials are disabled
                // and authentication uses the Authorization header, never cookies.
                logger.warn("FRONTEND_ALLOWED_ORIGINS is '*': browser calls are allowed from any origin");
                continue;
            }
            if (origin == null || origin.contains("*") || !isHttpUri(origin.trim(), false)) {
                throw new IllegalStateException("Invalid frontend origin '" + origin
                        + "': use '*' for any origin, or exact origins such as https://ams.example.lk");
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
