package com.aaa.collector.market;

import com.aaa.collector.macro.CompInterestCollectionService;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MarketFundsCollectionService;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
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

    private final SectorIndexCollectionService sectorIndexCollectionService;
    private final CompInterestCollectionService compInterestCollectionService;
    private final MarketFundsCollectionService marketFundsCollectionService;
    private final DividendScheduleCollectionService dividendScheduleCollectionService;
    private final RevSplitCollectionService revSplitCollectionService;
    private final UsdkrwCollectionService usdkrwCollectionService;
    private final VixCollectionService vixCollectionService;

    /**
     * 시장지표 묶음 배치 진입점 (REQ-BATCH3-001, REQ-BATCH3-005, REQ-BATCH3-006).
     *
     * <p>예외 흡수: 각 종의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다. 각 종 예외는 다음 종 수집을 막지 않는다(REQ-BATCH3-004).
     */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Seoul")
    public void collectMarket() {
        LocalDate today = LocalDate.now(KST);
        String baseDate = today.format(DATE_FMT);
        log.info("[market-batch] 시장지표 묶음 배치 시작 — {}", today);

        collectSectorIndex(today);
        collectCompInterest();
        collectMarketFunds(baseDate);
        collectDividendSchedule(today);
        collectRevSplit(today);
        collectUsdkrw(today);
        collectVix(today);

        log.info("[market-batch] 시장지표 묶음 배치 완료 — {}", today);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectSectorIndex(LocalDate today) {
        try {
            SectorIndexCollectionResult result = sectorIndexCollectionService.collect(today);
            log.info(
                    "[sector-index] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[sector-index] 수집 예외 — 다음 종으로 계속", e);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectCompInterest() {
        try {
            MacroCollectionResult result = compInterestCollectionService.collect();
            log.info(
                    "[comp-interest] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[comp-interest] 수집 예외 — 다음 종으로 계속", e);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectMarketFunds(String baseDate) {
        try {
            MacroCollectionResult result = marketFundsCollectionService.collect(baseDate);
            log.info(
                    "[market-funds] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[market-funds] 수집 예외 — 다음 종으로 계속", e);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectDividendSchedule(LocalDate today) {
        try {
            String fromDate = today.minusDays(DIVIDEND_RANGE_DAYS).format(DATE_FMT);
            String toDate = today.plusDays(DIVIDEND_RANGE_DAYS).format(DATE_FMT);
            DividendCollectionResult result =
                    dividendScheduleCollectionService.collect(fromDate, toDate);
            log.info(
                    "[dividend-schedule] 수집 완료 — attempted={}, succeeded={}, skippedNonWatchlist={}, skippedValidation={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skippedNonWatchlist(),
                    result.skippedValidation());
        } catch (Exception e) {
            log.error("[dividend-schedule] 수집 예외 — 스케줄러 스레드 보호", e);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectUsdkrw(LocalDate today) {
        try {
            usdkrwCollectionService.collectDaily(today);
            log.info("[usdkrw] 수집 완료 — {}", today);
        } catch (Exception e) {
            log.error("[usdkrw] 수집 예외 — 다음 종으로 계속", e);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectVix(LocalDate today) {
        try {
            vixCollectionService.collectDaily(today);
            log.info("[vix] 수집 완료 — {}", today);
        } catch (Exception e) {
            log.error("[vix] 수집 예외 — 다음 종으로 계속", e);
        }
    }

    /**
     * 5번째 종 — 액면교체 수집 (REQ-BATCH5-001/003/005, D-NEED-CRON Resolved).
     *
     * <p>윈도우: today-14 ~ today+60 (PROBE-4 확정). 종 단위 예외 격리(REQ-BATCH5-003): 이 종의 예외가 묶음의 다른 종·스케줄러
     * 스레드를 막지 않는다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    private void collectRevSplit(LocalDate today) {
        try {
            LocalDate from = today.minusDays(REV_SPLIT_LOOKBACK_DAYS);
            LocalDate to = today.plusDays(REV_SPLIT_FUTURE_DAYS);
            RevSplitCollectionResult result = revSplitCollectionService.collect(from, to);
            log.info(
                    "[rev-split] 수집 완료 — attempted={}, succeeded={}, skippedNonWatchlist={}, skippedValidation={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skippedNonWatchlist(),
                    result.skippedValidation());
        } catch (Exception e) {
            log.error("[rev-split] 수집 예외 — 스케줄러 스레드 보호", e);
        }
    }
}
