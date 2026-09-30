package kln.ams.apigateway;

import kln.ams.apigateway.security.TestKeyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ApiGatewayApplicationTests {

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        TestKeyUtils.registerGatewayProperties(registry, service -> "http://localhost:1");
    }

    @Test
    void contextLoads() {
    }

}
