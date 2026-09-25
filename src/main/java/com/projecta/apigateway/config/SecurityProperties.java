package com.projecta.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "projecta.security")
public class SecurityProperties {

    private List<String> publicPaths = new ArrayList<>();
    private Jwt jwt = new Jwt();

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public boolean isPublicPath(String path) {
        if (path == null || publicPaths == null) {
            return false;
        }
        return publicPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    public static class Jwt {
        /** Identity Access public key (location or PEM) used to verify User JWTs. */
        private String identityPublicKey;
        /** Gateway private key (location or PEM) used to sign Gateway JWTs. */
        private String gatewayPrivateKey;
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
