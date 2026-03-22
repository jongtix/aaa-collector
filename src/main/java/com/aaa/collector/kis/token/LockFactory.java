package com.aaa.collector.kis.token;

import java.util.concurrent.locks.Lock;

/**
 * {@link Lock} 생성 동작을 추상화하는 함수형 인터페이스.
 *
 * <p>프로덕션 환경에서는 {@code key -> new ReentrantLock()}을 주입하고, 테스트 환경에서는 {@link Lock#tryLock(long,
 * java.util.concurrent.TimeUnit)}이 즉시 {@code false}를 반환하거나 {@link InterruptedException}을 던지는 mock
 * {@link Lock}을 주입하여 락 획득 실패 경로를 검증할 수 있다.
 */
@FunctionalInterface
public interface LockFactory {

    /**
     * 주어진 키에 대응하는 {@link Lock} 인스턴스를 생성한다.
     *
     * @param key 락을 식별하는 키 (예: 계좌 alias)
     * @return 새로 생성된 {@link Lock} 인스턴스
     */
    Lock create(String key);
}
