package com.aaa.collector.market.indicator.usdkrw;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * KOREAEXIM USDKRW 백필 호출 빈도를 제한하는 rate limiter (SPEC-COLLECTOR-MARKETIND-007, aaa-infra#113).
 *
 * <p>{@link com.aaa.collector.kis.KisRateLimiter}를 그대로 미러한 형제 컴포넌트 — bucket4j {@code refillGreedy}
 * 토큰 버킷 + fair semaphore, 가상 시간 테스트 생성자. KOREAEXIM 백필 전용 호출 경로({@code
 * MarketIndicatorBackfillOrchestrator#processUsdkrwDay})에서만 사용되며, VIX 백필·라이브 단건 호출 경로는 관여하지 않는다
 * (REQ-015, REQ-016 — diff 0).
 */
@Component
public class KoreaeximBackfillRateLimiter {

    private final Bucket bucket;
    private final Semaphore semaphore;

    // @Autowired 명시 — 패키지-프라이빗 테스트 생성자(TimeMeter 주입)와 공존 시 Spring이 생성자를 모호하게 판단해
    // "No default constructor found"로 실패하는 것을 방지한다(KisRateLimiter는 @Component가 아니라 이 문제가 없었음).
    @Autowired
    public KoreaeximBackfillRateLimiter(KoreaeximBackfillRateLimitProperties config) {
        this(config, TimeMeter.SYSTEM_MILLISECONDS);
    }

    // @MX:NOTE: [AUTO] 테스트용 생성자 — TimeMeter 주입으로 가상 시간 테스트 가능 (KisRateLimiter 미러, bucket4j 공식 패턴)
    KoreaeximBackfillRateLimiter(KoreaeximBackfillRateLimitProperties config, TimeMeter timeMeter) {
        this.bucket =
                Bucket.builder()
                        .addLimit(
                                Bandwidth.builder()
                                        .capacity(config.capacity())
                                        // refillGreedy: 1초를 refillPerSecond 등분하여 토큰을 연속·분산 충전.
                                        // 임의 1초 최대 처리량 = capacity + refillPerSecond.
                                        // capacity=3, refill=15 → peak 18 TPS / 지속 15 TPS
                                        // (kis.rate-limit 프로덕션 설정 verbatim 미러, REQ-009).
                                        .refillGreedy(
                                                config.refillPerSecond(), Duration.ofSeconds(1))
                                        .build())
                        .withCustomTimePrecision(timeMeter)
                        .build();
        // fair=true: FIFO 순서 보장(KisRateLimiter 미러). 백필은 단일 스레드 순차 실행이라 경합은 없다.
        this.semaphore = new Semaphore(config.maxConcurrency(), true);
    }

    /** 테스트 전용 — 버킷 내부 상태 검증용. 프로덕션 코드에서 직접 사용 금지. */
    // @MX:TODO: 프로덕션 코드에서 이 메서드를 직접 호출하지 않도록 주의
    Bucket getBucket() {
        return bucket;
    }

    /**
     * 토큰 1개를 소비한 후 세마포어를 획득한다. 토큰이 없으면 채워질 때까지, 세마포어가 없으면 반환될 때까지 블로킹한다.
     *
     * <p>순서: bucket token 먼저, 그 다음 semaphore.acquire() — KisRateLimiter 계약 미러. 이 메서드가 정상 반환되면 세마포어가
     * 반드시 획득된 상태이므로, 호출자는 finally 블록에서 {@link #release()}를 호출해야 한다.
     *
     * @throws InterruptedException 대기 중 스레드가 인터럽트된 경우 — 인터럽트 상태 복원/전파는 호출자 책임(REQ-008)
     */
    // @MX:NOTE: [AUTO] bucket 토큰 소비 후 semaphore 획득 — 순서 변경 금지 (KisRateLimiter 미러, aaa-infra#113 버스트
    // 평탄화)
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-007
    public void consume() throws InterruptedException {
        bucket.asBlocking().consume(1);
        semaphore.acquire();
    }

    /**
     * 세마포어를 반환한다.
     *
     * <p><strong>호출 규약:</strong> 반드시 {@link #consume()} 호출 성공 이후 finally 블록에서만 호출해야 한다. consume()
     * 없이 호출 시 허용 슬롯이 maxConcurrency를 초과하여 동시성 제어가 무력화된다.
     *
     * @see #consume()
     */
    // @MX:WARN: [AUTO] acquire() 없이 release() 호출 시 Semaphore 슬롯이 maxConcurrency 초과 — 동시성 제어 무력화
    // @MX:REASON: 반드시 consume() 정상 반환 이후 finally 블록에서만 호출할 것(KisRateLimiter 계약 계승)
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-007
    public void release() {
        semaphore.release();
    }
}
