package com.aaa.collector.warmstart;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.indicator.MarketIndicatorMetrics;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 Redis에 영속된 마지막 성공 시각으로 {@code aaa_collector_market_indicator_source_last_success_seconds}
 * gauge를 초기화한다 (SPEC-COLLECTOR-WARMSTART-REDIS-003).
 *
 * <p>컨테이너 재시작마다 게이지가 0으로 리셋되어 재시작을 가로지르는 기본 소스 장애가 영구히 감지되지 않는 결함(#88)을 해소한다. {@link
 * BatchMetricsWarmStarter}와는 별개 클래스다 — 서로 다른 게이지·레지스트리·의존이라 응집도상 분리가 명확하다(DP-3).
 *
 * <p>{@code source_last_success} seed의 유일 소스는 {@link MarketIndicatorLastSuccessRepository}(Redis)다
 * (REQ-WSR-022). Redis 값이 없거나 손상됐으면 게이지를 seed하지 않고 {@link MarketIndicatorMetrics#init()}이 사전 등록한
 * 0.0을 그대로 둔다 — DB 프록시 폴백은 도입하지 않는다.
 *
 * <p>warm-start 반복 대상은 {@link MarketIndicatorMetrics#knownSources()} 단일 정본 열거에서만 유래한다(REQ-WSR-025,
 * 목록 복제 금지). 조합별 조회 실패는 격리되어 나머지 조합의 seed를 막지 않는다(REQ-WSR-024). {@code active_source}는 절대 복원하지
 * 않는다(REQ-WSR-023) — 다음 수집 사이클이 즉시 재설정하고 어떤 알림도 소비하지 않는다.
 */
// @MX:ANCHOR: [AUTO] 부팅 시 MarketIndicatorMetrics source_last_success gauge warm-start 진입점
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-003 REQ-WSR-021 —
// Redis(MarketIndicatorLastSuccessRepository)
// 단일 소스, 재시작을 가로지르는 기본 소스 장애 무알림 결함(#88) 해소
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-003
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorMetricsWarmStarter implements ApplicationRunner {

    private final MarketIndicatorMetrics marketIndicatorMetrics;
    private final MarketIndicatorLastSuccessRepository lastSuccessRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("MarketIndicatorMetrics warm-start 시작 (SPEC-COLLECTOR-WARMSTART-REDIS-003)");

        MarketIndicatorMetrics.knownSources()
                .forEach(
                        (indicator, sources) -> sources.forEach(source -> warm(indicator, source)));

        log.info("MarketIndicatorMetrics warm-start 완료");
    }

    /**
     * Redis에 영속된 마지막 성공 epoch로 {@code source_last_success} gauge를 seed한다 (REQ-WSR-021/022/024).
     *
     * <p>Redis 값이 없거나(손상 포함) 조회가 실패하면 게이지를 seed하지 않고 사전 등록된 0.0을 그대로 둔다 — DB 프록시 대체는 없다.
     *
     * @param indicator 지표 식별자
     * @param source 소스 이름
     */
    private void warm(String indicator, String source) {
        try {
            Optional<Instant> lastSuccess = lastSuccessRepository.find(indicator, source);
            if (lastSuccess.isEmpty()) {
                log.debug(
                        "MarketIndicatorMetrics warm-start skip — indicator={}, source={} Redis 값 없음(0.0 유지)",
                        indicator,
                        source);
                return;
            }
            Instant instant = lastSuccess.get();
            marketIndicatorMetrics.warmLastSuccess(indicator, source, instant);
            log.info(
                    "MarketIndicatorMetrics warm-start 완료(Redis) — indicator={}, source={},"
                            + " lastSuccess={}",
                    indicator,
                    source,
                    instant);
        } catch (DataAccessException e) {
            log.warn(
                    "MarketIndicatorMetrics warm-start Redis 조회 실패 — indicator={}, source={}, 무시하고"
                            + " 계속 진행. error={}",
                    indicator,
                    source,
                    e.getMessage());
        }
    }
}
