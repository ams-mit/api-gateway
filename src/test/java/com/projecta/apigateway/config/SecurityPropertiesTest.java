package com.projecta.apigateway.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPropertiesTest {

    @Test
    void isPublicPath_returnsTrue_forMatchingPublicPatterns() {
        SecurityProperties properties = new SecurityProperties();
        properties.setPublicPaths(List.of("/api/v1/auth/login", "/api/v1/auth/register", "/actuator/**"));

        assertTrue(properties.isPublicPath("/api/v1/auth/login"));
        assertTrue(properties.isPublicPath("/api/v1/auth/register"));
        assertTrue(properties.isPublicPath("/actuator/health"));
        assertTrue(properties.isPublicPath("/actuator/metrics"));
    }

    @Test
    void isPublicPath_returnsFalse_forProtectedPaths() {
        SecurityProperties properties = new SecurityProperties();
        properties.setPublicPaths(List.of("/api/v1/auth/login", "/api/v1/auth/register", "/actuator/**"));

        assertFalse(properties.isPublicPath("/api/v1/residents"));
        assertFalse(properties.isPublicPath("/api/v1/users"));
        assertFalse(properties.isPublicPath("/api/v1/owners"));
    }
}
