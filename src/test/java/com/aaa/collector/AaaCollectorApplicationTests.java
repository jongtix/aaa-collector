package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AaaCollectorApplicationTests {

    @Autowired private ApplicationContext context;

    @MockBean
    @SuppressWarnings({"unused", "removal"})
    private StringRedisTemplate redisTemplate;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
