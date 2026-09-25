package io.annona;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Spring Boot 应用上下文能启动（test profile 排除 DB autoconfig）")
class AnnonaApplicationTests {

    @Test
    @DisplayName("contextLoads")
    void contextLoads() {
    }

}
