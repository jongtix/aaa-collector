package com.aaa.collector.market;

import com.aaa.collector.macro.CompInterestCollectionService;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MarketFundsCollectionService;
import com.aaa.collector.stock.DividendCollectionResult;
import com.aaa.collector.stock.DividendScheduleCollectionService;
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
 * <p>업종지수(T3)→금리(T4)→증시자금(T5)→배당증자(T6) 고정 순서 순차 호출.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-BATCH3-003). 종
 * 단위 예외 격리 — 한 종 실패가 다음 종·스케줄러 스레드를 막지 않음(REQ-BATCH3-004). {@code stream:daily:complete}
 * 미발행(REQ-BATCH3-011).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketBatchScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 배당 일정 기본 수집 범위: 기준일로부터 과거 60일 → 미래 60일. */
    private static final int DIVIDEND_RANGE_DAYS = 60;

    private final SectorIndexCollectionService sectorIndexCollectionService;
    private final CompInterestCollectionService compInterestCollectionService;
    private final MarketFundsCollectionService marketFundsCollectionService;
    private final DividendScheduleCollectionService dividendScheduleCollectionService;

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
}
