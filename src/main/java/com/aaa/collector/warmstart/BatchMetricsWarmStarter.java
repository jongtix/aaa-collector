package com.aaa.collector.warmstart;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 Redis에 영속된 마지막 성공 시각으로 {@code aaa_collector_batch_last_load_seconds} gauge를 초기화한다
 * (SPEC-COLLECTOR-WARMSTART-REDIS-002).
 *
 * <p>컨테이너 재시작 시 lazy 등록으로 인한 gauge absent 문제를 해소한다. 비차단(non-blocking) — 한 배치 조회 실패 시 warn 로깅 후 나머지를
 * 계속 처리한다.
 *
 * <p>{@code registry} 라벨 전수에 대해 Redis 조회를 순차 실행한다(파이프라이닝 미적용). 부팅 1회성 이벤트이고 배치별 격리(한 배치 실패가 나머지에
 * 전파되지 않음)가 병렬화보다 우선하는 설계 선택이다 — 전체 지연은 라벨 수 × 단건 조회 지연 수준(일반적으로 수백 ms 이내)이다.
 *
 * <p>{@code last_load} seed의 유일 소스는 {@link BatchLastLoadRepository}(Redis)다(REQ-WSR-010). DB 프록시
 * 폴백은 SPEC-COLLECTOR-WARMSTART-REDIS-002에서 완전히 제거됐다 — Redis 값이 없으면 게이지를 부재로 두고, 다음 실제 실행이 {@code
 * recordCompletion}으로 자가 치유한다(REQ-WSR-012, REQ-WSR-016). {@code last_data} 게이지 ({@code
 * overseas-shortsale-interest})만 예외적으로 {@link ShortSaleOverseasRepository} 프록시를 유지한다 — 실데이터 도착
 * 의미론이라 DB 조회가 정의상 정확하다(REQ-WSR-014).
 */
// @MX:ANCHOR: [AUTO] 부팅 시 BatchMetrics last-load gauge warm-start 진입점
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-002 — Redis(BatchLastLoadRepository) 단일 소스, fan_in >=
// 3;
// vmalert 룰 무력화 방지
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetricsWarmStarter implements ApplicationRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BatchMetrics batchMetrics;
    private final BatchLastLoadRepository batchLastLoadRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("BatchMetrics warm-start 시작 (SPEC-COLLECTOR-WARMSTART-REDIS-002)");

        warm("domestic-daily");
        warm("overseas-daily");
        warm("domestic-supply-investor");
        warm("domestic-supply-credit-balance");
        warm("domestic-supply-short-sale");
        warm("overseas-shortsale-daily");
        warm("overseas-shortsale-interest");
        // REQ-XR-017(DP-5): 실행/도착 분리 — last_load seed는 위에서 그대로 유지하고, 동일 쿼리로 last_data도 seed한다.
        // last_data 경로는 REQ-WSR-014로 불변 — ShortSaleOverseasRepository 프록시를 계속 사용한다.
        warmData(
                "overseas-shortsale-interest",
                shortSaleOverseasRepository::findMaxInterestCollectedAt);
        warm("domestic-invest-opinion");
        warm("domestic-financial-ratio");
        warm("macro-external");
        warm("market-indicators");
        warm("domestic-etf-representative");

        // SPEC-OBSV-WATERMARK-001 REQ-WM-014: dart-disclosure(현행 암묵 누락) + 신규 라벨 중 warm-start=O 3종
        warm("dart-disclosure");
        // SPEC-COLLECTOR-EXPECTED-RUN-001: REQ-WM-013 표가 dart-backfill을 warm-start=O로 지정했으나 최초 구현
        // (a82ef81)에서 배선이 누락됐다 — 첫 실제 실행이 즉시 Redis를 채우므로 seed 정밀도는 비임계.
        warm("dart-backfill");
        warm("overseas-rights");
        // corp-code: corp_code_mapping이 BaseEntity.createdAt(per-run 삽입 시각)을 보유하므로 편입(MI-06)
        warm("corp-code");

        // REQ-XR-018(a): news 2종 warm-start 편입.
        warm("domestic-news");
        warm("overseas-news");

        // REQ-XR-018(b): overseas-split warm-start.
        warm("overseas-split");

        // REQ-XR-018(b): extended-hours 단일 라벨 warm을 pre/after 두 갈래로 분할(Module E 라벨 분리와 정합).
        warm("extended-hours-pre");
        warm("extended-hours-after");

        // SPEC-COLLECTOR-EXPECTED-RUN-001 §13 O-3: watchlist-sync-krx/us seed.
        warm("watchlist-sync-krx");
        warm("watchlist-sync-us");

        log.info("BatchMetrics warm-start 완료");
    }

    /**
     * Redis에 영속된 마지막 성공 epoch로 {@code last_load} gauge를 seed한다 (REQ-WSR-010/011/012).
     *
     * <p>Redis 값이 없거나 조회가 실패하면 게이지를 seed하지 않고 부재로 둔다 — DB 프록시 대체는 없다(REQ-WSR-012,
     * SPEC-COLLECTOR-WARMSTART-REDIS-002).
     *
     * @param batch 배치 라벨
     */
    private void warm(String batch) {
        try {
            Optional<Instant> lastLoad = batchLastLoadRepository.find(batch);
            if (lastLoad.isEmpty()) {
                log.debug("BatchMetrics warm-start skip — {} Redis 값 없음(게이지 부재 유지)", batch);
                return;
            }
            Instant instant = lastLoad.get();
            batchMetrics.warmLastLoad(batch, instant);
            log.info("BatchMetrics warm-start 완료(Redis) — batch={} lastLoad={}", batch, instant);
        } catch (DataAccessException e) {
            log.warn(
                    "BatchMetrics warm-start Redis 조회 실패 — batch={}, 무시하고 계속 진행. error={}",
                    batch,
                    e.getMessage());
        }
    }

    /**
     * {@code last_data} gauge seed 전용 warm 헬퍼 (REQ-XR-017, DP-5, REQ-WSR-014로 불변).
     *
     * <p>{@link #warm(String)}이 {@code last_load}를 Redis에서 seed하는 것과 대칭이며, 도착-측 게이지만 DB 프록시로
     * retarget한다 — 실데이터 도착 의미론이라 DB 조회가 정의상 정확하다.
     */
    private void warmData(String batch, TimestampQuery query) {
        try {
            Optional<LocalDateTime> result = query.findMax();
            if (result.isEmpty()) {
                log.debug("BatchMetrics last_data warm-start skip — {} 테이블 데이터 없음", batch);
                return;
            }
            Instant instant = toInstant(result.get());
            batchMetrics.warmDataArrival(batch, instant);
            log.info("BatchMetrics last_data warm-start 완료 — batch={} lastData={}", batch, instant);
        } catch (DataAccessException e) {
            log.warn(
                    "BatchMetrics last_data warm-start 실패 — batch={}, 무시하고 계속 진행. error={}",
                    batch,
                    e.getMessage());
        }
    }

    private Instant toInstant(LocalDateTime kst) {
        return kst.atZone(KST).toInstant();
    }

    @FunctionalInterface
    private interface TimestampQuery {
        Optional<LocalDateTime> findMax();
    }
}
