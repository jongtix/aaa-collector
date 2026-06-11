package com.aaa.collector.common.retry;

import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유 재시도 실행기.
 *
 * <p>주입된 retryable 판정 함수({@link #isRetryable})가 {@code true}를 반환하는 예외 발생 시 {@link
 * ExponentialBackoff} 지연 후 재시도한다. 판정 함수가 {@code false}를 반환하는 예외는 즉시 전파한다.
 *
 * <p>소진 시 마지막 retryable 예외를 전파한다. skip/SafeMode 등 종단 정책은 호출부 책임이다.
 *
 * <p>{@link InterruptedException}: 인터럽트 플래그를 복원하고 전파한다(REQ-RETRY-016). 사이트별 종단 변환은 호출부가 책임진다.
 */
// @MX:ANCHOR: [AUTO] 공유 재시도 실행기 — 토큰/②단계/①단계 3종 재시도의 단일 경유점 (fan_in=3)
// @MX:REASON: SPEC-COLLECTOR-RETRY-001 — 분산된 3종 재시도를 단일 메커니즘으로 통합; 예외 분류는 사이트별 predicate 주입으로 보존
// @MX:SPEC: SPEC-COLLECTOR-RETRY-001
@Slf4j
public class RetryExecutor {

    /** 최대 시도 횟수 (초기 시도 포함). */
    private final int maxAttempts;

    /** 지수 백오프 기준 지연(ms). */
    private final long baseDelayMs;

    /** 백오프 sleep 추상화 (테스트 시 no-op 주입 가능). */
    private final Sleeper sleeper;

    /** retryable 예외 판정 함수. {@code false} 반환 시 즉시 전파(permanent 처리). {@code true} 반환 시 소진까지 재시도. */
    private final Predicate<RuntimeException> isRetryable;

    /**
     * @param maxAttempts 최대 시도 횟수 (초기 시도 포함, 1 이상)
     * @param baseDelayMs 지수 백오프 기준 지연(ms, 양수)
     * @param sleeper sleep 추상화
     * @param isRetryable retryable 예외 판정 함수
     */
    public RetryExecutor(
            int maxAttempts,
            long baseDelayMs,
            Sleeper sleeper,
            Predicate<RuntimeException> isRetryable) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.sleeper = sleeper;
        this.isRetryable = isRetryable;
    }

    /**
     * 작업을 실행하고 retryable 예외 발생 시 재시도한다.
     *
     * <p>retryable 판정 함수가 {@code true}를 반환하는 예외는 소진까지 재시도 후 마지막 예외를 전파한다. {@code false}를 반환하는 예외는
     * 즉시 전파한다.
     *
     * @param operation 실행할 작업 ({@link ThrowingSupplier#get()}은 {@link InterruptedException}만 선언)
     * @param <T> 작업 반환 타입
     * @return 작업 결과
     * @throws InterruptedException 작업 또는 백오프 sleep 중 인터럽트 수신 시 (플래그 복원 후 전파)
     * @throws RuntimeException retryable 예외 소진 시 마지막 예외, 또는 non-retryable 예외 즉시 전파
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // RetryExecutor는 피처에 무관한 제네릭 재시도 엔진이다. 주입된 predicate로 분류하려면
    // RuntimeException을 포괄적으로 포착해야 한다. 이는 재시도 패턴의 구조적 요구사항이며
    // 개별 callsite의 suppression을 RetryExecutor 한 곳으로 집중시키는 의도적 설계다.
    // [이전] KisTokenService.issueWithRetry에 있던
    // @SuppressWarnings("PMD.AvoidCatchingGenericException")을
    // 이 메서드로 이전한 것이다 — suppression 총량은 변하지 않는다 (REQ-RETRY-031: 신규 suppression 추가 금지).
    public <T> T execute(ThrowingSupplier<T> operation) throws InterruptedException {
        RuntimeException lastException = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (InterruptedException ie) {
                // 작업 자체에서 발생한 InterruptedException — 플래그 복원 후 전파
                Thread.currentThread().interrupt();
                throw ie;
            } catch (RuntimeException ex) {
                // classifyAndHandle: non-retryable이면 throw, retryable이면 ex를 반환
                lastException = classifyAndHandle(ex, attempt);
            }
        }

        // 모든 시도 소진 — 마지막 retryable 예외 전파
        throw lastException;
    }

    /**
     * 예외를 retryable/non-retryable로 분류하고 적절히 처리한다.
     *
     * <p>retryable 예외이면 백오프 sleep 후 해당 예외를 반환한다. non-retryable 예외이면 즉시 전파(throw)한다.
     *
     * @param ex 분류할 예외
     * @param attempt 현재 시도 횟수 (0-based)
     * @return retryable 예외 (백오프 완료), non-retryable 경우 이 메서드가 throw하므로 반환 없음
     * @throws InterruptedException 백오프 sleep 중 인터럽트 수신 시
     */
    private RuntimeException classifyAndHandle(RuntimeException ex, int attempt)
            throws InterruptedException {
        if (!isRetryable.test(ex)) {
            // non-retryable (permanent) — 즉시 전파
            throw ex;
        }
        // retryable 예외 — 로깅 후 백오프
        log.warn(
                "[RetryExecutor] retryable 예외 — attempt={}/{}, exception={}",
                attempt + 1,
                maxAttempts,
                ex.getMessage());
        applyBackoff(attempt);
        return ex;
    }

    /**
     * 지수 백오프 sleep을 수행한다. 마지막 시도 이후에는 sleep 없이 반환한다.
     *
     * @param attempt 현재 시도 횟수 (0-based)
     * @throws InterruptedException sleep 중 인터럽트 수신 시 (플래그 복원 후 전파)
     */
    private void applyBackoff(int attempt) throws InterruptedException {
        if (attempt < maxAttempts - 1) {
            long delayMs = ExponentialBackoff.delay(attempt, baseDelayMs).toMillis();
            try {
                sleeper.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
        }
    }

    /**
     * {@link java.util.function.Supplier}의 {@link InterruptedException} 허용 변형.
     *
     * <p>{@code throws InterruptedException}만 선언한다. 그 외 checked exception은 허용하지 않는다.
     *
     * @param <T> 반환 타입
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws InterruptedException;
    }
}
