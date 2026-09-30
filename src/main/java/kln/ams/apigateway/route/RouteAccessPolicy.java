package kln.ams.apigateway.route;

import org.springframework.cloud.gateway.route.Route;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Access rules declared on a Gateway route through its {@code metadata} (see application.yml).
 * <p>
 * Missing or unknown {@code access} values fall back to {@link Access#USER}, so a route can never
 * become public by omission. Any path under {@code /api/v1/internal/} is always treated as a
 * service route, even if its metadata says otherwise (API-GATEWAY.md §23).
 */
public record RouteAccessPolicy(String routeId, Access access, String targetService, Set<String> allowedCallers) {

    public static final String INTERNAL_PREFIX = "/api/v1/internal/";

    public enum Access {
        /** No authentication (only {@code POST /api/v1/auth/login} and backend OpenAPI documents). */
        PUBLIC,
        /** User JWT required. */
        USER,
        /** Service JWT required and the caller must be in {@code allowed-callers}. */
        SERVICE,
        /** Explicitly retired path that would otherwise match a broader route; answered with 404. */
        BLOCKED
    }

    public static RouteAccessPolicy from(Route route, String path) {
        Map<String, Object> metadata = route.getMetadata();
        Access access = parseAccess(metadata.get("access"));
        if (access != Access.BLOCKED && path != null && path.startsWith(INTERNAL_PREFIX)) {
            access = Access.SERVICE;
        }
        Object service = metadata.get("service");
        return new RouteAccessPolicy(route.getId(), access,
                service != null ? service.toString() : null,
                parseCallers(metadata.get("allowed-callers")));
    }

    public boolean allowsCaller(String serviceName) {
        return allowedCallers.contains(serviceName);
    }

    private static Access parseAccess(Object value) {
        if (value != null) {
            for (Access access : Access.values()) {
                if (access.name().equalsIgnoreCase(value.toString().trim())) {
                    return access;
                }
            }
        }
        return Access.USER;
    }

    private static Set<String> parseCallers(Object value) {
        if (value == null) {
            return Set.of();
        }
        return Arrays.stream(value.toString().split(","))
                .map(s -> s.trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
