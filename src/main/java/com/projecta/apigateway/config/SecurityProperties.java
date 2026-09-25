package com.projecta.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

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
        private String identityPublicKeyPath;
        private String gatewayPrivateKeyPath;
        private String gatewayPublicKeyPath;

        public String getIdentityPublicKeyPath() {
            return identityPublicKeyPath;
        }

        public void setIdentityPublicKeyPath(String identityPublicKeyPath) {
            this.identityPublicKeyPath = identityPublicKeyPath;
        }

        public String getGatewayPrivateKeyPath() {
            return gatewayPrivateKeyPath;
        }

        public void setGatewayPrivateKeyPath(String gatewayPrivateKeyPath) {
            this.gatewayPrivateKeyPath = gatewayPrivateKeyPath;
        }

        public String getGatewayPublicKeyPath() {
            return gatewayPublicKeyPath;
        }

        public void setGatewayPublicKeyPath(String gatewayPublicKeyPath) {
            this.gatewayPublicKeyPath = gatewayPublicKeyPath;
        }
    }
}
