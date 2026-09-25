package com.projecta.apigateway.health;

import com.projecta.apigateway.config.DownstreamServicesProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reports each downstream service as UP or DOWN by calling its {@code /actuator/health}.
 * <p>
 * Shown as the {@code services} component of {@code /actuator/health}. A down backend makes this
 * component {@code DEGRADED}, which is ordered below UP so the Gateway's own status stays UP
 * (the Gateway itself is still healthy and should not be restarted by Docker).
 */
@Component("servicesHealthIndicator")
public class DownstreamServicesHealthIndicator implements ReactiveHealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED", "One or more downstream services are unavailable");

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServicesHealthIndicator.class);
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final Map<String, String> services;
    private final String healthPath;
    private final Duration timeout;
    private final WebClient webClient;

    public DownstreamServicesHealthIndicator(DownstreamServicesProperties properties) {
        this.services = properties.getServices();
        this.healthPath = properties.getHealth().getPath();
        this.timeout = properties.getHealth().getTimeout();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
                .responseTimeout(timeout);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<Health> health() {
        return Flux.fromIterable(services.entrySet())
                .flatMap(entry -> check(entry.getValue())
                        .map(status -> Map.entry(serviceName(entry.getKey()), status)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, TreeMap::new)
                .map(statuses -> {
                    boolean allUp = statuses.values().stream().allMatch(UP::equals);
                    return Health.status(allUp ? Status.UP : DEGRADED).withDetails(statuses).build();
                });
    }

    private Mono<String> check(String baseUrl) {
        return webClient.get()
                .uri(stripTrailingSlash(baseUrl) + healthPath)
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().is2xxSuccessful() ? UP : DOWN))
                .timeout(timeout)
                .onErrorResume(e -> {
                    logger.debug("Health check failed for {}: {}", baseUrl, e.toString());
                    return Mono.just(DOWN);
                });
    }

    // Keys under projecta.services are short names; report the Project A service name
    private static String serviceName(String key) {
        return key.endsWith("-service") ? key : key + "-service";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
