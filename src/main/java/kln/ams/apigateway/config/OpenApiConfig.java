package kln.ams.apigateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gateway OpenAPI document (API-GATEWAY.md §76-§77). It documents Gateway behaviour only: every
 * configured route with its target service, access type, allowed callers and failure behaviour.
 * Request/response schemas remain owned by each backend's OpenAPI, linked from the Swagger UI.
 * The route list is generated from the live route configuration, so it cannot drift from it.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    @Bean
    public OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Project A — API Gateway")
                        .version("v1")
                        .description("""
                                Central entry point for Project A. Routes /api/v1 requests to the owning service, \
                                verifies User JWTs (Identity Access key) and Service JWTs (registered service keys), \
                                enforces the internal-route allow-list, and forwards a new short-lived Gateway JWT. \
                                Request/response schemas belong to each backend's OpenAPI (select it in the top-right list). \
                                Gateway errors: 401 MISSING_TOKEN/INVALID_TOKEN/INVALID_SIGNATURE/UNSUPPORTED_ALGORITHM/\
                                TOKEN_EXPIRED/INVALID_TOKEN_TYPE/INVALID_TOKEN_CLAIMS/UNREGISTERED_SERVICE; \
                                403 FORBIDDEN/SERVICE_NOT_ALLOWED; 404 ROUTE_NOT_FOUND; 503 DEPENDENCY_UNAVAILABLE."""))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    public OpenApiCustomizer gatewayRoutesCustomizer(GatewayProperties gatewayProperties) {
        return openApi -> {
            Paths paths = new Paths();
            List<RouteDefinition> routes = new ArrayList<>(gatewayProperties.getRoutes());
            routes.sort((a, b) -> a.getId().compareTo(b.getId()));
            for (RouteDefinition route : routes) {
                if (route.getId().startsWith("docs-") || "blocked".equals(route.getMetadata().get("access"))) {
                    continue;
                }
                List<String> methods = predicateValues(route, "Method");
                for (String path : predicateValues(route, "Path")) {
                    PathItem item = paths.computeIfAbsent(path, p -> new PathItem());
                    for (String method : methods) {
                        item.operation(PathItem.HttpMethod.valueOf(method.trim().toUpperCase()), operation(route, path));
                    }
                }
            }
            openApi.setPaths(paths);
        };
    }

    private static Operation operation(RouteDefinition route, String path) {
        Map<String, Object> metadata = route.getMetadata();
        String access = String.valueOf(metadata.getOrDefault("access", "user"));
        String service = String.valueOf(metadata.getOrDefault("service", "unknown"));

        StringBuilder description = new StringBuilder()
                .append("**Target service:** `").append(service).append("`  \n")
                .append("**Type:** ").append(path.startsWith("/api/v1/internal/") ? "internal" : "external").append("  \n")
                .append("**Authentication:** ").append(switch (access) {
                    case "public" -> "none";
                    case "service" -> "Service JWT (re-signed as Gateway Service JWT)";
                    default -> "User JWT (re-signed as Gateway User JWT)";
                }).append("  \n");
        if (metadata.containsKey("allowed-callers")) {
            description.append("**Allowed callers:** ").append(metadata.get("allowed-callers")).append("  \n");
        }
        if (metadata.containsKey("api-ids")) {
            description.append("**Provider API IDs:** ").append(metadata.get("api-ids")).append("  \n");
        }
        description.append("**Failure behaviour:** backend responses and business errors pass through unchanged; ")
                .append("unreachable/timed-out backend returns 503 DEPENDENCY_UNAVAILABLE.");

        Operation operation = new Operation()
                .operationId(route.getId() + "-" + Integer.toHexString(path.hashCode()))
                .summary(route.getId() + " → " + service)
                .description(description.toString())
                .addTagsItem(service)
                .responses(responses(access));
        Matcher matcher = PATH_VARIABLE.matcher(path);
        while (matcher.find()) {
            operation.addParametersItem(new PathParameter().name(matcher.group(1)).required(true).schema(new StringSchema()));
        }
        operation.setSecurity("public".equals(access) ? List.of() : List.of(new SecurityRequirement().addList(BEARER)));
        return operation;
    }

    private static ApiResponses responses(String access) {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("2XX", new ApiResponse().description("Backend response, passed through unchanged"))
                .addApiResponse("404", new ApiResponse().description("ROUTE_NOT_FOUND"))
                .addApiResponse("503", new ApiResponse().description("DEPENDENCY_UNAVAILABLE"));
        if (!"public".equals(access)) {
            responses.addApiResponse("401", new ApiResponse().description("Authentication failed (see error.code)"))
                    .addApiResponse("403", new ApiResponse().description("FORBIDDEN / SERVICE_NOT_ALLOWED"));
        }
        return responses;
    }

    private static List<String> predicateValues(RouteDefinition route, String name) {
        List<String> values = new ArrayList<>();
        for (PredicateDefinition predicate : route.getPredicates()) {
            if (predicate.getName().equals(name)) {
                predicate.getArgs().values().forEach(v -> {
                    for (String part : v.split(",")) {
                        if (!part.isBlank()) {
                            values.add(part.trim());
                        }
                    }
                });
            }
        }
        return values;
    }
}
