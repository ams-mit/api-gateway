package kln.ams.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "projecta.security")
public class SecurityProperties {

    /** Frontend origins allowed by CORS ({@code FRONTEND_ALLOWED_ORIGINS}). */
    private List<String> allowedOrigins = new ArrayList<>();
    private Jwt jwt = new Jwt();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public static class Jwt {
        /** Identity Access public key (location or PEM) used to verify User JWTs. */
        private String identityPublicKey;
        /** Gateway private key (location or PEM) used to sign Gateway JWTs. */
        private String gatewayPrivateKey;
        /** Optional Gateway public key; when set it must match the private key. */
        private String gatewayPublicKey;
        /** Lifetime of Gateway-issued JWTs. */
        private Duration gatewayTokenTtl = Duration.ofMinutes(5);
        /** Registered service name -> public key (location or PEM) used to verify Service JWTs. */
        private Map<String, String> servicePublicKeys = new LinkedHashMap<>();

        public String getIdentityPublicKey() {
            return identityPublicKey;
        }

        public void setIdentityPublicKey(String identityPublicKey) {
            this.identityPublicKey = identityPublicKey;
        }

        public String getGatewayPrivateKey() {
            return gatewayPrivateKey;
        }

        public void setGatewayPrivateKey(String gatewayPrivateKey) {
            this.gatewayPrivateKey = gatewayPrivateKey;
        }

        public String getGatewayPublicKey() {
            return gatewayPublicKey;
        }

        public void setGatewayPublicKey(String gatewayPublicKey) {
            this.gatewayPublicKey = gatewayPublicKey;
        }

        public Duration getGatewayTokenTtl() {
            return gatewayTokenTtl;
        }

        public void setGatewayTokenTtl(Duration gatewayTokenTtl) {
            this.gatewayTokenTtl = gatewayTokenTtl;
        }

        public Map<String, String> getServicePublicKeys() {
            return servicePublicKeys;
        }

        public void setServicePublicKeys(Map<String, String> servicePublicKeys) {
            this.servicePublicKeys = servicePublicKeys;
        }
    }
}
