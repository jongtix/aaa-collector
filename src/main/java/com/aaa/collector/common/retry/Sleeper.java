package com.aaa.collector.common.retry;

/**
 * 지연(sleep) 동작을 추상화하는 함수형 인터페이스.
 *
 * <p>프로덕션 환경에서는 {@link Thread#sleep(long)}을 주입하고, 테스트 환경에서는 no-op 구현체를 주입하여 불필요한 대기 없이 재시도 로직을 검증할
 * 수 있다.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * 지정된 시간(밀리초)만큼 현재 스레드를 대기시킨다.
     *
     * @param millis 대기 시간 (밀리초)
     * @throws InterruptedException 대기 중 스레드가 인터럽트된 경우
     */
    void sleep(long millis) throws InterruptedException;
}
