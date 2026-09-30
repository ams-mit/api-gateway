package kln.ams.apigateway.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigurationValidatorTest {

    @Test
    void acceptsAllRequiredServicesWithHttpUris() {
        assertDoesNotThrow(() -> GatewayConfigurationValidator.validateServices(allServices()));
    }

    @Test
    void rejectsMissingUnknownOrInvalidServiceUris() {
        Map<String, String> missing = allServices();
        missing.remove("community-service");
        Map<String, String> unknown = allServices();
        unknown.put("reporting-service", "http://reporting:8080");
        Map<String, String> invalid = allServices();
        invalid.put("operations-service", "operations-service:3007");

        assertThrows(IllegalStateException.class, () -> GatewayConfigurationValidator.validateServices(missing));
        assertThrows(IllegalStateException.class, () -> GatewayConfigurationValidator.validateServices(unknown));
        assertThrows(IllegalStateException.class, () -> GatewayConfigurationValidator.validateServices(invalid));
    }

    @Test
    void rejectsWildcardOrEmptyOrigins() {
        assertDoesNotThrow(() -> GatewayConfigurationValidator.validateOrigins(List.of("http://localhost:3000", "https://ams.example.lk")));
        assertThrows(IllegalStateException.class, () -> GatewayConfigurationValidator.validateOrigins(List.of("*")));
        assertThrows(IllegalStateException.class, () -> GatewayConfigurationValidator.validateOrigins(List.of("https://*.example.lk")));
        assertThrows(IllegalStateException.class, () -> GatewayConfigurationValidator.validateOrigins(List.of()));
    }

    private static Map<String, String> allServices() {
        Map<String, String> services = new HashMap<>();
        GatewayConfigurationValidator.REQUIRED_SERVICES.forEach(s -> services.put(s, "http://" + s + ":8080"));
        return services;
    }
}
