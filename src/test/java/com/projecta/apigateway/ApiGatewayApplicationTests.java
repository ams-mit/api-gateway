package com.projecta.apigateway;

import com.projecta.apigateway.security.TestKeyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ApiGatewayApplicationTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeyUtils.registerKeyProperties(registry);
    }

    @Test
    void contextLoads() {
    }

}
