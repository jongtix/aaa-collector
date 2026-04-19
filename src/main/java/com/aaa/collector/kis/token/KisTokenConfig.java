package com.aaa.collector.kis.token;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * KIS 토큰 모듈 빈 설정.
 *
 * <p>프로덕션 환경에서 사용할 {@link LockFactory} 기본 구현체를 등록한다. 테스트에서는 mock {@link
 * java.util.concurrent.locks.Lock}을 반환하는 구현체를 직접 주입하여 락 획득 실패 경로를 실제 대기 없이 검증할 수 있다.
 */
@Configuration
public class KisTokenConfig {

    /**
     * 계좌 alias마다 {@link ReentrantLock}을 생성하는 기본 {@link LockFactory} 빈.
     *
     * @return 프로덕션용 LockFactory 구현체
     */
    @Bean
    LockFactory lockFactory() {
        return key -> new ReentrantLock();
    }
}
