package com.aaa.collector.common.retry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 재시도 관련 빈 설정.
 *
 * <p>프로덕션 환경에서 사용할 {@link Sleeper} 기본 구현체를 등록한다. 테스트에서는 no-op 구현체를 직접 주입하여 실제 대기 없이 재시도 로직을 검증할 수
 * 있다.
 */
@Configuration
public class RetryConfig {

    /**
     * {@link Thread#sleep(long)}을 위임하는 기본 {@link Sleeper} 빈.
     *
     * @return 프로덕션용 Sleeper 구현체
     */
    @Bean
    Sleeper sleeper() {
        return Thread::sleep;
    }
}
