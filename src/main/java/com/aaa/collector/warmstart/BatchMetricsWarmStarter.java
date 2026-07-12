package com.aaa.collector.warmstart;

import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.news.overseas.OverseasNewsHeadlineRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 DB max 타임스탬프로 {@code aaa_collector_batch_last_load_seconds} gauge를 초기화한다
 * (SPEC-OBSV-WARMSTART-001).
 *
 * <p>컨테이너 재시작 시 lazy 등록으로 인한 gauge absent 문제를 해소한다. 비차단(non-blocking) — 한 배치 조회 실패 시 warn 로깅 후 나머지를
 * 계속 처리한다.
 *
 * <p>{@code domestic-news}·{@code overseas-news}는 REQ-XR-018(a)로 편입됐다(실행-앵커 모델에서 sub-daily 오발 소멸).
 * {@code watchlist-sync-krx}/{@code watchlist-sync-us}는 SPEC-COLLECTOR-EXPECTED-RUN-001 §13 O-3
 * 결정으로 {@link WatchlistSyncWarmSource}가 seed를 해석한다.
 */
// @MX:ANCHOR: [AUTO] 부팅 시 BatchMetrics last-load gauge warm-start 진입점
// @MX:REASON: SPEC-OBSV-WARMSTART-001 — 12개 배치 리포지토리에서 fan_in >= 3; vmalert 룰 무력화 방지
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetricsWarmStarter implements ApplicationRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final List<Market> DOMESTIC_MARKETS =
            List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);
    private static final List<Market> OVERSEAS_MARKETS =
            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US);

    private final BatchMetrics batchMetrics;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final InvestorTrendRepository investorTrendRepository;
    private final CreditBalanceRepository creditBalanceRepository;
    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final AnalystEstimateRepository analystEstimateRepository;
    private final FinancialRepository financialRepository;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository;
    private final DisclosureRepository disclosureRepository;
    private final CorporateEventRepository corporateEventRepository;
    private final CorpCodeMappingRepository corpCodeMappingRepository;
    private final DomesticNewsHeadlineRepository domesticNewsHeadlineRepository;
    private final OverseasNewsHeadlineRepository overseasNewsHeadlineRepository;
    private final ExtendedHoursWarmSource extendedHoursWarmSource;
    private final WatchlistSyncWarmSource watchlistSyncWarmSource;

    @Override
    public void run(ApplicationArguments args) {
        log.info("BatchMetrics warm-start 시작 (SPEC-OBSV-WARMSTART-001)");

        warm(
                "domestic-daily",
                () -> dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(DOMESTIC_MARKETS));
        warm(
                "overseas-daily",
                () -> dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(OVERSEAS_MARKETS));
        warm("domestic-supply-investor", investorTrendRepository::findMaxCreatedAt);
        warm("domestic-supply-credit-balance", creditBalanceRepository::findMaxCreatedAt);
        warm("domestic-supply-short-sale", shortSaleDomesticRepository::findMaxCreatedAt);
        warm("overseas-shortsale-daily", shortSaleOverseasRepository::findMaxDailyCollectedAt);
        warm(
                "overseas-shortsale-interest",
                shortSaleOverseasRepository::findMaxInterestCollectedAt);
        // REQ-XR-017(DP-5): 실행/도착 분리 — last_load seed는 위에서 그대로 유지하고, 동일 쿼리로 last_data도 seed한다.
        warmData(
                "overseas-shortsale-interest",
                shortSaleOverseasRepository::findMaxInterestCollectedAt);
        warm("domestic-invest-opinion", analystEstimateRepository::findMaxCreatedAt);
        warm("domestic-financial-ratio", financialRepository::findMaxCreatedAt);
        warm("macro-external", macroIndicatorRepository::findMaxCreatedAt);
        warm("market-indicators", marketIndicatorRepository::findMaxCreatedAt);
        warm(
                "domestic-etf-representative",
                etfRepresentativeHistoryRepository::findMaxEffectiveFrom);

        // SPEC-OBSV-WATERMARK-001 REQ-WM-014: dart-disclosure(현행 암묵 누락) + 신규 라벨 중 warm-start=O 3종
        warm("dart-disclosure", disclosureRepository::findMaxCreatedAt);
        // SPEC-COLLECTOR-EXPECTED-RUN-001: REQ-WM-013 표가 dart-backfill을 warm-start=O로 지정했으나 최초 구현
        // (a82ef81)에서 배선이 누락됐다 — dart-disclosure와 동일 쿼리 재사용(REQ-XR-018(b)와 동일 근거: 이중 writer라도
        // forward-only에 안전, 첫 실제 실행이 즉시 덮어써 seed 정밀도는 비임계).
        warm("dart-backfill", disclosureRepository::findMaxCreatedAt);
        warm(
                "overseas-rights",
                () -> corporateEventRepository.findMaxCreatedAtByMarketsIn(OVERSEAS_MARKETS));
        // corp-code: corp_code_mapping이 BaseEntity.createdAt(per-run 삽입 시각)을 보유하므로 편입(MI-06)
        warm("corp-code", corpCodeMappingRepository::findMaxCreatedAt);

        // REQ-XR-018(a): news 2종 warm-start 편입. 기존 제외 근거(sub-daily 상수 임계 부팅 오발)는 새 실행-앵커 모델에서
        // 무효 — 주말-복원 값이 expected_run 주말 무전진과 정합해 오발하지 않는다. 각 서비스가 이미 보유한 최신 게시 시각
        // (findMaxPublishedAt, SPEC-OBSV-WATERMARK-001 REQ-WM-003 자산)을 재사용한다.
        warm("domestic-news", domesticNewsHeadlineRepository::findMaxPublishedAt);
        warm("overseas-news", overseasNewsHeadlineRepository::findMaxPublishedAt);

        // REQ-XR-018(b): overseas-split warm-start 신규 배선(현행 전무). 전용 split-only 쿼리는 신설하지 않는다 —
        // 분할은 희소해 split-only 조회가 대개 empty를 반환해 warm-start의 목적(부팅 후 absent-gauge 회피)을 무산시킨다.
        // overseas-rights와 동일한 시장 필터 쿼리로 corporate_events 최신 적재 시각을 안정적으로 seed하고, 첫 실제 실행이
        // recordCompletion으로 split 고유값을 덮어쓴다(실행-앵커 모델이라 seed 정밀도는 비임계).
        warm(
                "overseas-split",
                () -> corporateEventRepository.findMaxCreatedAtByMarketsIn(OVERSEAS_MARKETS));

        // REQ-XR-018(b): extended-hours 단일 라벨 warm을 pre/after 두 갈래로 분할(Module E 라벨 분리와 정합).
        // session 컬럼 기준 최신 거래일로 PRE/AFTER를 물리적으로 구분한다 — 변환/세션 참조는 ExtendedHoursWarmSource가
        // 캡슐화한다(warm-starter의 import 결합도 상한 회피 + findMaxTradeDateBySession 재사용).
        warm("extended-hours-pre", extendedHoursWarmSource::preLastLoad);
        warm("extended-hours-after", extendedHoursWarmSource::afterLastLoad);

        // SPEC-COLLECTOR-EXPECTED-RUN-001 §13 O-3: watchlist-sync-krx/us seed. 시장 필터·graded_at 해석은
        // WatchlistSyncWarmSource로 추출(ExtendedHoursWarmSource와 동일 이유 — import/coupling 상한 회피).
        warm("watchlist-sync-krx", watchlistSyncWarmSource::krxLastLoad);
        warm("watchlist-sync-us", watchlistSyncWarmSource::usLastLoad);

        log.info("BatchMetrics warm-start 완료");
    }

    private void warm(String batch, TimestampQuery query) {
        try {
            Optional<LocalDateTime> result = query.findMax();
            if (result.isEmpty()) {
                log.debug("BatchMetrics warm-start skip — {} 테이블 데이터 없음", batch);
                return;
            }
            Instant instant = toInstant(result.get());
            batchMetrics.warmLastLoad(batch, instant);
            log.info("BatchMetrics warm-start 완료 — batch={} lastLoad={}", batch, instant);
        } catch (DataAccessException e) {
            log.warn(
                    "BatchMetrics warm-start 실패 — batch={}, 무시하고 계속 진행. error={}",
                    batch,
                    e.getMessage());
        }
    }

    /**
     * {@code last_data} gauge seed 전용 warm 헬퍼 (REQ-XR-017, DP-5).
     *
     * <p>{@link #warm(String, TimestampQuery)}가 {@code last_load}를 seed하는 것과 대칭이며, 도착-측 게이지만
     * retarget한다 — 기존 {@code last_load} 배선은 불변.
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
