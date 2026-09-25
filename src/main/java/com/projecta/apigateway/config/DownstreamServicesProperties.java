package com.projecta.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Downstream service base URLs ({@code projecta.services.*}) and the settings used to check their health.
 */
@Component
@ConfigurationProperties(prefix = "projecta")
public class DownstreamServicesProperties {

    /** Logical service key (e.g. {@code resident-management}) -> base URL. */
    private Map<String, String> services = new LinkedHashMap<>();
    private Health health = new Health();

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }

    public Health getHealth() {
        return health;
    }

    public void setHealth(Health health) {
        this.health = health;
    }

    public static class Health {
        /** Health path exposed by every service (04-API-STANDARD §30). */
        private String path = "/actuator/health";
        /** Upper bound for a single service health check. */
        private Duration timeout = Duration.ofSeconds(2);

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
