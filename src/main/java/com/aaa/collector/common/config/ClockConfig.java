package com.aaa.collector.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시스템 시계 빈 설정.
 *
 * <p>테스트에서 고정 {@link Clock}을 주입할 수 있도록 {@code Clock}을 빈으로 등록한다. 시스템 기본 시간대 대신 KST(Asia/Seoul)를
 * 명시적으로 지정하여 시간대 설정과 무관하게 항상 KST로 동작함을 보장한다.
 */
@Configuration
public class ClockConfig {

    /**
     * KST(Asia/Seoul) 시스템 {@link Clock} 빈.
     *
     * @return Asia/Seoul 시간대로 고정된 시스템 시계
     */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
