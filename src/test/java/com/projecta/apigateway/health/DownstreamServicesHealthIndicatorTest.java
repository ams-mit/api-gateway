package com.projecta.apigateway.health;

import com.projecta.apigateway.config.DownstreamServicesProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DownstreamServicesHealthIndicatorTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/up/actuator/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/down/actuator/health", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/slow/actuator/health", exchange -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void health_isUp_whenAllServicesAreUp() {
        Health health = indicator(Map.of("identity-access", baseUrl + "/up/")).health().block();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("UP", health.getDetails().get("identity-access-service"));
    }

    @Test
    void health_isDegraded_andReportsEachService_whenSomeAreDown() {
        Map<String, String> services = new LinkedHashMap<>();
        services.put("identity-access", baseUrl + "/up");
        services.put("resident-management", baseUrl + "/down");
        services.put("property-unit", "http://localhost:" + unusedPort());
        services.put("operations", baseUrl + "/slow");

        Health health = indicator(services).health().block();

        assertEquals(DownstreamServicesHealthIndicator.DEGRADED, health.getStatus());
        assertEquals("UP", health.getDetails().get("identity-access-service"));
        assertEquals("DOWN", health.getDetails().get("resident-management-service"));
        assertEquals("DOWN", health.getDetails().get("property-unit-service"));
        assertEquals("DOWN", health.getDetails().get("operations-service"));
    }

    private DownstreamServicesHealthIndicator indicator(Map<String, String> services) {
        DownstreamServicesProperties properties = new DownstreamServicesProperties();
        properties.setServices(services);
        properties.getHealth().setTimeout(Duration.ofMillis(1500));
        return new DownstreamServicesHealthIndicator(properties);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
