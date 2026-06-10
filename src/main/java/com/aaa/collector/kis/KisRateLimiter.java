package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** KIS API 호출 빈도를 제한하는 rate limiter. */
public class KisRateLimiter {

    private final Bucket bucket;
    private final Semaphore semaphore;

    public KisRateLimiter(KisProperties.RateLimit config) {
        this(config, TimeMeter.SYSTEM_MILLISECONDS);
    }

    // @MX:NOTE: [AUTO] 테스트용 생성자 — TimeMeter 주입으로 가상 시간 테스트 가능 (bucket4j 공식 패턴)
    KisRateLimiter(KisProperties.RateLimit config, TimeMeter timeMeter) {
        this.bucket =
                Bucket.builder()
                        .addLimit(
                                Bandwidth.builder()
                                        .capacity(config.capacity())
                                        // refillGreedy: 1초를 refillPerSecond 등분하여 토큰을 연속·분산 충전.
                                        // 예) refillGreedy(10, 1초) → 100ms마다 1개 추가 → 순간 버스트 적음.
                                        // refillIntervally는 1초 경계에서 전체 토큰을 일괄 충전하므로
                                        // 버스트가 오히려 크다 — greedy가 버스트 억제에 올바른 선택.
                                        //
                                        // 임의 1초 최대 처리량 = capacity + refillPerSecond.
                                        // capacity=3, refill=15 → peak 18 TPS / 지속 15 TPS.
                                        // capacity는 순간 버스트 여유(GC/지터 대응)이므로 작게 유지.
                                        //
                                        // 멀티 인스턴스 전환 시 Redis ProxyManager로 교체 예정 (ADR-023).
                                        .refillGreedy(
                                                config.refillPerSecond(), Duration.ofSeconds(1))
                                        .build())
                        .withCustomTimePrecision(timeMeter)
                        .build();
        // fair=true: FIFO 순서 보장 — 218개 가상 스레드 경합 시 starvation 방지
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
     * <p>순서: bucket token 먼저, 그 다음 semaphore.acquire() — 세마포어 대기 중 토큰이 낭비되지 않도록 보장. 이 메서드가 정상 반환되면
     * 세마포어가 반드시 획득된 상태이므로, 호출자는 finally 블록에서 {@link #release()}를 호출해야 한다.
     *
     * @throws InterruptedException 대기 중 스레드가 인터럽트된 경우
     */
    // @MX:NOTE: [AUTO] bucket 토큰 소비 후 semaphore 획득 — 순서 변경 금지 (토큰 먼저 → 세마포어 대기)
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-005
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
    // @MX:REASON: 반드시 consume() 정상 반환 이후 finally 블록에서만 호출할 것
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-005
    public void release() {
        semaphore.release();
    }
}
