package com.aaa.collector.market;

import com.aaa.collector.macro.CompInterestCollectionService;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MarketFundsCollectionService;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import com.aaa.collector.stock.DividendCollectionResult;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitCollectionResult;
import com.aaa.collector.stock.RevSplitCollectionService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시장지표 묶음 스케줄러 (T8, REQ-BATCH3-001).
 *
 * <p>평일 17:00 KST({@code 0 0 17 * * MON-FRI}) — 16:00 일봉+수급 인라인 체인과 1시간 분리.
 *
 * <p>업종지수(T3)→금리(T4)→증시자금(T5)→배당증자(T6)→액면교체(T7, REQ-BATCH5-001) 고정 순서 순차 호출.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-BATCH3-003). 종
 * 단위 예외 격리 — 한 종 실패가 다음 종·스케줄러 스레드를 막지 않음(REQ-BATCH3-004). {@code stream:daily:complete}
 * 미발행(REQ-BATCH3-011, REQ-BATCH5-012).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketBatchScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 배당 일정 기본 수집 범위: 기준일로부터 과거 60일 → 미래 60일. */
    private static final int DIVIDEND_RANGE_DAYS = 60;

    /**
     * 액면교체 수집 윈도우 (PROBE-4 확정, REQ-BATCH5-001/013).
     *
     * <p>lookback 14일: 당일 정정/철회 공지 흡수용. 미래 60일: 최대 리드타임 today+41일 커버(실측). 합계 ~35건 — 100건 캡 여유 65.
     */
    private static final int REV_SPLIT_LOOKBACK_DAYS = 14;

    private static final int REV_SPLIT_FUTURE_DAYS = 60;

    /** 시장지표 묶음 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "market-indicators";

    private final SectorIndexCollectionService sectorIndexCollectionService;
    private final CompInterestCollectionService compInterestCollectionService;
    private final MarketFundsCollectionService marketFundsCollectionService;
    private final DividendScheduleCollectionService dividendScheduleCollectionService;
    private final RevSplitCollectionService revSplitCollectionService;
    private final UsdkrwCollectionService usdkrwCollectionService;
    private final VixCollectionService vixCollectionService;
    private final BatchMetrics batchMetrics;

    /**
     * 시장지표 묶음 배치 진입점 (REQ-BATCH3-001, REQ-BATCH3-005, REQ-BATCH3-006).
     *
     * <p>예외 흡수: 각 종의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다. 각 종 예외는 다음 종 수집을 막지 않는다(REQ-BATCH3-004).
     *
     * <p>계측 누적 전략: 7종 결과를 collectMarket() 수준에서 합산하여 1회 recordCompletion 호출. void 반환 종(usdkrw/vix)은
     * 성공 시 +1, 예외 시 attempted+1만 증가(fail 자동 계상). REQ-OBSV-020/021.
     */
    @Scheduled(cron = BatchCrons.MARKET_INDICATORS_CRON, zone = BatchCrons.MARKET_INDICATORS_ZONE)
    public void collectMarket() {
        LocalDate today = LocalDate.now(KST);
        log.info("[market-batch] 시장지표 묶음 배치 시작 — {}", today);

        // 7종 결과 누적 카운터 — collectMarket() 수준에서 합산 후 1회 recordCompletion
        long attempted = 0;
        long succeeded = 0;
        long skip = 0;

        // T3: 업종지수 — 결과 객체 반환종
        long[] sectorResult = collectSectorIndex(today);
        attempted += sectorResult[0];
        succeeded += sectorResult[1];
        skip += sectorResult[2];

        // T4: 금리 — 결과 객체 반환종
        long[] compResult = collectCompInterest();
        attempted += compResult[0];
        succeeded += compResult[1];
        skip += compResult[2];

        // T5: 증시자금 — 결과 객체 반환종
        String baseDate = today.format(DATE_FMT);
        long[] fundsResult = collectMarketFunds(baseDate);
        attempted += fundsResult[0];
        succeeded += fundsResult[1];
        skip += fundsResult[2];

        // T6: 배당 일정 — skip = skippedNonWatchlist + skippedValidation
        long[] dividendResult = collectDividendSchedule(today);
        attempted += dividendResult[0];
        succeeded += dividendResult[1];
        skip += dividendResult[2];

        // T7: 액면교체 — skip = skippedNonWatchlist + skippedValidation
        long[] revSplitResult = collectRevSplit(today);
        attempted += revSplitResult[0];
        succeeded += revSplitResult[1];
        skip += revSplitResult[2];

        // T8: USDKRW — void 반환종(성공 시 1/1, 예외 시 1/0)
        long[] usdkrwResult = collectUsdkrw(today);
        attempted += usdkrwResult[0];
        succeeded += usdkrwResult[1];

        // T9: VIX — void 반환종
        long[] vixResult = collectVix(today);
        attempted += vixResult[0];
        succeeded += vixResult[1];

        // REQ-OBSV-020/021: fail = attempted - succeeded - skip
        long fail = Math.max(0L, attempted - succeeded - skip);
        batchMetrics.recordCompletion(BATCH_LABEL, attempted, succeeded, fail, skip);
        log.info("[market-batch] 시장지표 묶음 배치 완료 — {}", today);
    }

    /** 업종지수 수집. [attempted, succeeded, skip] 반환. 예외 시 [1, 0, 0]. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectSectorIndex(LocalDate today) {
        try {
            SectorIndexCollectionResult result = sectorIndexCollectionService.collect(today);
            log.info(
                    "[sector-index] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
            return new long[] {result.attempted(), result.succeeded(), result.skipped()};
        } catch (Exception e) {
            log.error("[sector-index] 수집 예외 — 다음 종으로 계속", e);
            return new long[] {1, 0, 0};
        }
    }

    /** 금리 수집. [attempted, succeeded, skip] 반환. 예외 시 [1, 0, 0]. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectCompInterest() {
        try {
            MacroCollectionResult result = compInterestCollectionService.collect();
            log.info(
                    "[comp-interest] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
            return new long[] {result.attempted(), result.succeeded(), result.skipped()};
        } catch (Exception e) {
            log.error("[comp-interest] 수집 예외 — 다음 종으로 계속", e);
            return new long[] {1, 0, 0};
        }
    }

    /** 증시자금 수집. [attempted, succeeded, skip] 반환. 예외 시 [1, 0, 0]. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectMarketFunds(String baseDate) {
        try {
            MacroCollectionResult result = marketFundsCollectionService.collect(baseDate);
            log.info(
                    "[market-funds] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
            return new long[] {result.attempted(), result.succeeded(), result.skipped()};
        } catch (Exception e) {
            log.error("[market-funds] 수집 예외 — 다음 종으로 계속", e);
            return new long[] {1, 0, 0};
        }
    }

    /** 배당 일정 수집. [attempted, succeeded, skip(nonWatchlist+validation)] 반환. 예외 시 [1, 0, 0]. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectDividendSchedule(LocalDate today) {
        try {
            String fromDate = today.minusDays(DIVIDEND_RANGE_DAYS).format(DATE_FMT);
            String toDate = today.plusDays(DIVIDEND_RANGE_DAYS).format(DATE_FMT);
            DividendCollectionResult result =
                    dividendScheduleCollectionService.collect(fromDate, toDate);
            long totalSkip = (long) result.skippedNonWatchlist() + result.skippedValidation();
            log.info(
                    "[dividend-schedule] 수집 완료 — attempted={}, succeeded={}, skippedNonWatchlist={}, skippedValidation={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skippedNonWatchlist(),
                    result.skippedValidation());
            return new long[] {result.attempted(), result.succeeded(), totalSkip};
        } catch (Exception e) {
            log.error("[dividend-schedule] 수집 예외 — 스케줄러 스레드 보호", e);
            return new long[] {1, 0, 0};
        }
    }

    /**
     * 액면교체 수집. [attempted, succeeded, skip(nonWatchlist+validation)] 반환. 예외 시 [1, 0, 0].
     *
     * <p>윈도우: today-14 ~ today+60 (PROBE-4 확정). 종 단위 예외 격리(REQ-BATCH5-003).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectRevSplit(LocalDate today) {
        try {
            LocalDate from = today.minusDays(REV_SPLIT_LOOKBACK_DAYS);
            LocalDate to = today.plusDays(REV_SPLIT_FUTURE_DAYS);
            RevSplitCollectionResult result = revSplitCollectionService.collect(from, to);
            long totalSkip = (long) result.skippedNonWatchlist() + result.skippedValidation();
            log.info(
                    "[rev-split] 수집 완료 — attempted={}, succeeded={}, skippedNonWatchlist={}, skippedValidation={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skippedNonWatchlist(),
                    result.skippedValidation());
            return new long[] {result.attempted(), result.succeeded(), totalSkip};
        } catch (Exception e) {
            log.error("[rev-split] 수집 예외 — 스케줄러 스레드 보호", e);
            return new long[] {1, 0, 0};
        }
    }

    /** USDKRW 수집. [attempted, succeeded] 반환 — skip 없음, void 반환종. 예외 시 [1, 0]. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectUsdkrw(LocalDate today) {
        try {
            usdkrwCollectionService.collectDaily(today);
            log.info("[usdkrw] 수집 완료 — {}", today);
            return new long[] {1, 1};
        } catch (Exception e) {
            log.error("[usdkrw] 수집 예외 — 다음 종으로 계속", e);
            return new long[] {1, 0};
        }
    }

    /** VIX 수집. [attempted, succeeded] 반환 — skip 없음, void 반환종. 예외 시 [1, 0]. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private long[] collectVix(LocalDate today) {
        try {
            vixCollectionService.collectDaily(today);
            log.info("[vix] 수집 완료 — {}", today);
            return new long[] {1, 1};
        } catch (Exception e) {
            log.error("[vix] 수집 예외 — 다음 종으로 계속", e);
            return new long[] {1, 0};
        }
    }
}
