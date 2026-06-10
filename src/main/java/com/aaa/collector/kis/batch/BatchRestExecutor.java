package com.aaa.collector.kis.batch;

import com.aaa.collector.common.retry.ExponentialBackoff;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.KisRateLimiterRegistry;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.net.URI;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriBuilder;

/**
 * 배치 멀티키 경로 전용 REST 실행기.
 *
 * <p>호출 단위: {@code limiter.consume() → executeGet(credential, ...) → finally limiter.release()}.
 * {@link KisRateLimitException}(EGW00201) 발생 시 rate limiter를 재경유(토큰 재획득)하여 최대 {@value
 * #MAX_RETRIES}회 재시도한다. 재시도 소진 시 {@link BatchResult#skip(String)}을 반환(배치 전체 실패 금지). 영구 오류는 재시도 없이
 * 전파한다.
 *
 * <p>재시도 루프는 {@code consume()}/{@code release()} 바깥(상위)에 위치하여 매 시도마다 rate limiter를 새로 경유한다 — retry
 * storm 방지(REQ-BATCH-021).
 */
@Slf4j
@Component
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 배치 멀티키 경로 재시도 실행기 — 모든 배치 REST 호출의 단일 경유점
// @MX:REASON: SPEC-COLLECTOR-BATCH-001 REQ-BATCH-021,-022,-023 — limiter 재경유 retry loop
// @MX:SPEC: SPEC-COLLECTOR-BATCH-001
public class BatchRestExecutor {

    /** EGW00201 재시도 상한 (배치 경로 확정값 — REQ-BATCH-022). */
    static final int MAX_RETRIES = 2;

    private static final long BACKOFF_BASE_DELAY_MS = 500L;

    private final KisApiExecutor kisApiExecutor;
    private final KisRateLimiterRegistry kisRateLimiterRegistry;
    private final Sleeper sleeper;

    /**
     * 배치 REST 호출을 실행하고 결과 또는 skip 신호를 반환한다.
     *
     * <p>EGW00201 발생 시 rate limiter 재경유 재시도(최대 {@value #MAX_RETRIES}회). 소진 시 {@link
     * BatchResult#skip(String)} 반환(배치 전체 미실패). 영구 오류는 재시도 없이 전파.
     *
     * @param credential 호출할 앱키 자격증명
     * @param uriCustomizer URI 커스터마이저
     * @param trId KIS TR ID
     * @param responseType 응답 타입
     * @param symbol 로그용 종목 코드 (skip 시 메타에 기록됨)
     * @param <T> 응답 타입
     * @return 성공 시 {@link BatchResult#success(Object)}, EGW00201 소진 시 {@link
     *     BatchResult#skip(String)}
     * @throws RuntimeException 영구 오류(EGW00201 아닌 예외) 시 전파
     */
    public <T extends KisApiResponse> BatchResult<T> execute(
            KisAccountCredential credential,
            Function<UriBuilder, URI> uriCustomizer,
            String trId,
            Class<T> responseType,
            String symbol) {

        String alias = credential.alias();
        KisRateLimiter limiter = kisRateLimiterRegistry.forAlias(alias);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                limiter.consume();
                try {
                    T response =
                            kisApiExecutor.executeGet(
                                    credential, uriCustomizer, trId, responseType);
                    return BatchResult.success(response);
                } finally {
                    limiter.release();
                }
            } catch (KisRateLimitException e) {
                log.warn(
                        "[{}] EGW00201 rate-limit — symbol={}, attempt={}/{}",
                        alias,
                        symbol,
                        attempt + 1,
                        MAX_RETRIES + 1);
                if (attempt < MAX_RETRIES) {
                    applyBackoff(alias, symbol, attempt);
                } else {
                    log.warn(
                            "[{}] EGW00201 재시도 소진({}) — symbol={} skip",
                            alias,
                            MAX_RETRIES,
                            symbol);
                    return BatchResult.skip(symbol);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}] 인터럽트 — symbol={} skip", alias, symbol);
                return BatchResult.skip(symbol);
            }
        }

        // unreachable — 루프는 항상 return 또는 throw로 종료됨
        return BatchResult.skip(symbol);
    }

    private void applyBackoff(String alias, String symbol, int attempt) {
        long delayMs = ExponentialBackoff.delay(attempt, BACKOFF_BASE_DELAY_MS).toMillis();
        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("[{}] 백오프 인터럽트 — symbol={}", alias, symbol);
        }
    }
}
