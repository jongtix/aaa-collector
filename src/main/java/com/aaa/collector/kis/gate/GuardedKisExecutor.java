package com.aaa.collector.kis.gate;

import com.aaa.collector.common.retry.RetryExecutor;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.KisRateLimiterRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * KIS REST 호출의 단일 보호 진입점 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001/002/020/021).
 *
 * <p>세 갈래(패턴 A 맨몸 / 패턴 B watchlist / 패턴 C 배치)로 분산돼 있던 lease·throttle·retry를 하나로 수렴한다. 호출부는 수집 작업 단위
 * 시작 시 {@code KeyLeaseRegistry.openSession()}으로 per-batch 헬스 스냅샷을 1회 고정(DP3)하고, 그 세션을 본 게이트에 넘겨 매
 * 호출을 보호받는다.
 *
 * <p><strong>매 시도 lease+throttle 재경유(REQ-KISGATE-020/021):</strong> {@link RetryExecutor} 람다 안에서 각
 * 시도마다 {@code session.lease()} → {@code limiter.consume()} → {@code executeGet(4-arg)} → {@code
 * finally release}를 수행한다. retryable 실패 시 직전 막힌 키를 회피해 다른 한가한 키로 재선택한다(re-lease, AC-3).
 *
 * <p><strong>retryable 단일 선언(REQ-KISGATE-003a/004):</strong> {@link
 * KisRateLimitException}(EGW00201) ∪ {@link RestClientException} 하위타입만 retryable. 그 외(비즈니스/응답 검증
 * 오류)는 즉시 전파(AC-9). 기존 {@link RetryExecutor} /{@link KisRateLimiter}/{@link
 * KisRateLimiterRegistry}/{@link Sleeper}를 재사용한다 — 신규 재시도·rate-limit 엔진 도입 없음.
 *
 * <p><strong>경로별 미세차는 파라미터로 흡수(REQ-KISGATE-003b/040):</strong> throttle on/off를 {@code boolean
 * throttle} 인자로 받아 게이트 변형 클래스를 만들지 않는다. throttle=false면 rate limiter를 경유하지 않고 키만 lease한다(패턴 B
 * {@code fetchGroups} 1-shot 보존).
 *
 * <p>소진 시 마지막 retryable 예외를 전파한다 — 패턴별 종단 변환(BatchResult.skip / 그룹 skip / 전파)은 호출부(어댑터) 책임이다
 * (REQ-KISGATE-022). {@link InterruptedException}은 플래그 복원 후 전파한다(호출부가 사이트별 종단 변환).
 */
@Slf4j
@Component
// @MX:ANCHOR: [AUTO] KIS REST 단일 보호 진입점 — 패턴 A/B/C 전 소비자가 lease+throttle+retry를 이 게이트로 수렴
// @MX:REASON: SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001/002/020 — 분산된 3종 호출 패턴의 단일 경유점 (fan_in≥3)
// @MX:SPEC: SPEC-COLLECTOR-KISGATE-001
public class GuardedKisExecutor {

    /** EGW00201/네트워크 재시도 상한 (패턴 C BatchRestExecutor와 동일 — 총 {@code MAX_RETRIES + 1}회 시도). */
    static final int MAX_RETRIES = 2;

    private static final long BACKOFF_BASE_DELAY_MS = 500L;

    /**
     * retryable 단일 선언(REQ-KISGATE-003a): EGW00201(KisRateLimitException) ∪ RestClientException
     * 하위타입. 그 외(KisApiBusinessException·KisApiResponseException·NoHealthyKeyException 등)는 false →
     * 즉시 전파.
     */
    private static final Predicate<RuntimeException> RETRYABLE =
            ex -> ex instanceof KisRateLimitException || ex instanceof RestClientException;

    private final KisRateLimiterRegistry kisRateLimiterRegistry;
    private final KisApiExecutor kisApiExecutor;
    private final Sleeper sleeper;

    /**
     * @param kisRateLimiterRegistry alias별 rate limiter 레지스트리(기존 재사용)
     * @param kisApiExecutor KIS HTTP 어댑터(4-arg 멀티키 경로)
     * @param sleeper 백오프 sleep 추상화(테스트 시 no-op 주입)
     */
    public GuardedKisExecutor(
            KisRateLimiterRegistry kisRateLimiterRegistry,
            KisApiExecutor kisApiExecutor,
            Sleeper sleeper) {
        this.kisRateLimiterRegistry = kisRateLimiterRegistry;
        this.kisApiExecutor = kisApiExecutor;
        this.sleeper = sleeper;
    }

    /**
     * throttle을 적용하여 보호된 KIS REST GET을 실행한다(기본 경로).
     *
     * @see #execute(LeaseSession, Function, String, Class, boolean)
     */
    public <T extends KisApiResponse> T execute(
            LeaseSession session,
            Function<UriBuilder, URI> uriCustomizer,
            String trId,
            Class<T> responseType)
            throws InterruptedException {
        return execute(session, uriCustomizer, trId, responseType, true);
    }

    /**
     * 보호된 KIS REST GET을 실행한다.
     *
     * <p>매 시도: per-batch 스냅샷에서 키 lease(직전 막힌 키 회피) → (throttle 시) limiter.consume() →
     * executeGet(4-arg) → finally limiter.release() + lease.release(). retryable 실패 시 백오프 후 재시도.
     *
     * @param session per-batch 헬스 스냅샷 lease 세션(호출부가 작업 단위당 1회 open)
     * @param uriCustomizer URI 빌더 커스터마이저
     * @param trId KIS TR ID
     * @param responseType 응답 역직렬화 대상 클래스
     * @param throttle {@code true}면 rate limiter 경유, {@code false}면 생략(throttle-off,
     *     REQ-KISGATE-040)
     * @param <T> 응답 타입
     * @return 검증 완료된 응답 객체
     * @throws InterruptedException 작업 또는 백오프 sleep 중 인터럽트 수신 시(플래그 복원 후 전파)
     * @throws NoHealthyKeyException 스냅샷에 건강 키가 0개일 때(전 키 사망 신호, 즉시 전파)
     * @throws RuntimeException retryable 소진 시 마지막 예외, 또는 non-retryable 즉시 전파
     */
    public <T extends KisApiResponse> T execute(
            LeaseSession session,
            Function<UriBuilder, URI> uriCustomizer,
            String trId,
            Class<T> responseType,
            boolean throttle)
            throws InterruptedException {

        RetryExecutor retryExecutor =
                new RetryExecutor(MAX_RETRIES + 1, BACKOFF_BASE_DELAY_MS, sleeper, RETRYABLE);

        // 직전 시도에서 막힌 키 alias — 재시도 시 회피해 다른 한가한 키로 전환(REQ-KISGATE-021, AC-3).
        // AtomicReference: 람다 캡처 가변 상태 (재시도 간 alias 전달).
        AtomicReference<String> lastAlias = new AtomicReference<>();

        return retryExecutor.execute(
                () -> attempt(session, uriCustomizer, trId, responseType, throttle, lastAlias));
    }

    /** 한 시도: lease(막힌 키 회피) → throttle → executeGet → finally release. */
    private <T extends KisApiResponse> T attempt(
            LeaseSession session,
            Function<UriBuilder, URI> uriCustomizer,
            String trId,
            Class<T> responseType,
            boolean throttle,
            AtomicReference<String> lastAlias)
            throws InterruptedException {

        KeyLease lease = session.lease(lastAlias.get()).orElseThrow(NoHealthyKeyException::new);
        lastAlias.set(lease.alias());

        KisRateLimiter limiter = throttle ? kisRateLimiterRegistry.forAlias(lease.alias()) : null;
        if (limiter != null) {
            limiter.consume();
        }
        try {
            return kisApiExecutor.executeGet(lease.credential(), uriCustomizer, trId, responseType);
        } finally {
            if (limiter != null) {
                limiter.release();
            }
            lease.release();
        }
    }
}
