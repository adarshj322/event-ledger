package com.mphasis.eventledger.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "account-service.base-url=http://localhost:19999"
})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
