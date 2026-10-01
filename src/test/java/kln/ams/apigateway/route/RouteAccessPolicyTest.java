package kln.ams.apigateway.route;

import kln.ams.apigateway.route.RouteAccessPolicy.Access;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteAccessPolicyTest {

    @Test
    void readsAccessServiceAndCallersFromMetadata() {
        RouteAccessPolicy policy = RouteAccessPolicy.from(route(Map.of(
                "access", "service", "service", "community-service",
                "allowed-callers", " operations-service , billing-payment-service,")), "/api/v1/internal/notifications");

        assertEquals(Access.SERVICE, policy.access());
        assertEquals("community-service", policy.targetService());
        assertEquals(Set.of("operations-service", "billing-payment-service"), policy.allowedCallers());
        assertTrue(policy.allowsCaller("operations-service"));
        assertFalse(policy.allowsCaller("property-unit-service"));
    }

    @Test
    void missingOrUnknownAccess_defaultsToUser() {
        assertEquals(Access.USER, RouteAccessPolicy.from(route(Map.of()), "/api/v1/residents").access());
        assertEquals(Access.USER, RouteAccessPolicy.from(route(Map.of("access", "anonymous")), "/api/v1/residents").access());
    }

    @Test
    void internalPaths_areAlwaysServiceOnly() {
        RouteAccessPolicy policy = RouteAccessPolicy.from(route(Map.of("access", "public")), "/api/v1/internal/users/1/validate");

        assertEquals(Access.SERVICE, policy.access());
        assertTrue(policy.allowedCallers().isEmpty());
        assertTrue(policy.allowsCaller("any-registered-service"), "no allow-list means any registered service");
    }

    private static Route route(Map<String, Object> metadata) {
        return Route.async().id("r").uri("http://localhost:1").predicate(e -> true).metadata(metadata).build();
    }
}
