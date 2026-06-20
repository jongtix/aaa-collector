package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillGroup;
import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowOutcome;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.backfill.TerminationDecision;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백필 윈도우 1구간 실행기 (SPEC-COLLECTOR-BACKFILL-001 T6).
 *
 * <p>수집 서비스의 INSERT IGNORE와 {@link BackfillStatusRepository}의 status UPDATE를 <b>동일
 * {@code @Transactional} 경계</b>로 묶어 부분 커밋을 방지한다(REQ-BACKFILL-011, AC-4.1/4.2).
 *
 * <p>패키지 위치: {@code stock.backfill} — {@code stock} 피처 패키지가 {@code backfill} 피처 패키지에 의존하는 기존 방향을
 * 유지한다. 수집 서비스(stock 측)와 상태 갱신(backfill 측)을 이 클래스에서 조율한다.
 */
// @MX:ANCHOR: [AUTO] 백필 윈도우 실행 진입점 — INSERT IGNORE+status UPDATE 동일 트랜잭션 묶음 담당
// @MX:REASON: [AUTO] AC-4.1/4.2 부분 커밋 방지. 오케스트레이터(T6)와 트랜잭션 경계 분리로 각 윈도우가 독립 커밋된다.
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-001
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillWindowExecutor {

    private static final int MAX_ERROR_LENGTH = 512;

    /** 미국 시장 집합 — daily_ohlcv 수집 서비스 라우팅에 사용. */
    private static final Set<Market> OVERSEAS_MARKETS =
            Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    private final BackfillStatusRepository backfillStatusRepository;
    private final DomesticDailyOhlcvCollectionService domesticOhlcvService;
    private final OverseasDailyOhlcvCollectionService overseasOhlcvService;
    private final ShortSaleCollectionService shortSaleService;
    private final InvestorTrendCollectionService investorTrendService;
    private final CreditBalanceCollectionService creditBalanceService;
    private final BackfillTerminationPolicy terminationPolicy;
    private final BackfillWindowAdvancer windowAdvancer;
    private final BackfillMetrics backfillMetrics;

    /**
     * 한 백필 항목의 윈도우 1구간을 수집하고, 동일 트랜잭션에서 status를 갱신한다 (REQ-BACKFILL-011, AC-4.1/4.2).
     *
     * @param status 처리할 BackfillStatus 항목
     * @param stock 대상 종목 엔티티 (활성, REQ-BACKFILL-006)
     * @param session per-run 헬스 스냅샷 세션
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파 (삼키지 않음)
     */
    @Transactional
    public void executeWindow(BackfillStatus status, Stock stock, LeaseSession session)
            throws InterruptedException {

        String dataTable = status.getDataTable();
        String symbol = status.getTargetCode();
        LocalDate anchor = resolveAnchor(status);
        BackfillGroup group = BackfillGroup.ofDataTable(dataTable);

        BackfillWindowResult result = fetchWindow(dataTable, stock, session, anchor);

        BackfillWindowOutcome outcome = buildOutcome(group, result, status);
        TerminationDecision decision = terminationPolicy.decide(outcome);

        if (decision.clampSuspected()) {
            log.warn(
                    "[backfill] 클램프 의심 종료 — symbol={}, table={}, oldest={}",
                    symbol,
                    dataTable,
                    result.oldestTradeDate());
            backfillMetrics.recordClampSuspected();
        }

        backfillMetrics.recordWindow(result.rowCount());

        String newStatus = decision.completed() ? "COMPLETED" : "IN_PROGRESS";
        LocalDate newDate =
                result.oldestTradeDate() != null
                        ? result.oldestTradeDate()
                        : status.getLastCollectedDate();

        Integer newRowCount = resolveNewRowCount(result.rowCount(), status.getLastRowCount());

        backfillStatusRepository.updateProgress(
                status.getId(), newStatus, newDate, decision.nextStaleCount(), newRowCount);

        log.debug(
                "[backfill] 윈도우 완료 — symbol={}, table={}, status={}, oldest={}",
                symbol,
                dataTable,
                newStatus,
                newDate);
    }

    /**
     * 윈도우 수집 실패 시 상태를 오류로 갱신한다 (베스트에포트).
     *
     * @param status 갱신할 항목
     * @param errorMsg 오류 메시지
     * @param retryable {@code true}이면 IN_PROGRESS(다음 cron 재시도), {@code false}이면 FAILED
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 베스트에포트 — DB 장애 포함 모든 예외를 흡수
    public void executeWindowOnError(BackfillStatus status, String errorMsg, boolean retryable) {
        String newStatus = retryable ? "IN_PROGRESS" : "FAILED";
        String truncated = truncate(errorMsg, MAX_ERROR_LENGTH);
        try {
            backfillStatusRepository.updateError(status.getId(), newStatus, truncated);
        } catch (Exception e) {
            log.warn(
                    "[backfill] 오류 상태 갱신 실패 (베스트에포트) — id={}, cause={}",
                    status.getId(),
                    e.getMessage());
        }
    }

    /**
     * 예외를 재시도 가능 여부로 분류한다.
     *
     * <p>KisTokenIssueException만 영구 오류(false). 나머지는 모두 재시도(true) — 보수적 기본값.
     *
     * @param e 분류할 예외
     * @return {@code true}=재시도 가능(IN_PROGRESS 유지), {@code false}=영구 오류(FAILED)
     */
    public boolean isRetryable(Exception e) {
        return !(e instanceof KisTokenIssueException);
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private LocalDate resolveAnchor(BackfillStatus status) {
        if (status.getLastCollectedDate() == null) {
            return LocalDate.now();
        }
        return windowAdvancer.nextAnchor(status.getLastCollectedDate());
    }

    private BackfillWindowResult fetchWindow(
            String dataTable, Stock stock, LeaseSession session, LocalDate anchor)
            throws InterruptedException {
        return switch (dataTable) {
            case "daily_ohlcv" -> fetchOhlcvWindow(stock, session, anchor);
            case "short_sale_domestic" ->
                    shortSaleService.collectWindow(
                            stock, session, windowAdvancer.groupASpanFromDate(anchor), anchor);
            case "investor_trend" -> investorTrendService.collectWindow(stock, session, anchor);
            case "credit_balance" -> creditBalanceService.collectWindow(stock, session, anchor);
            default -> {
                log.warn(
                        "[backfill] 알 수 없는 data_table — symbol={}, table={}",
                        stock.getSymbol(),
                        dataTable);
                yield BackfillWindowResult.EMPTY;
            }
        };
    }

    private BackfillWindowResult fetchOhlcvWindow(
            Stock stock, LeaseSession session, LocalDate anchor) throws InterruptedException {
        if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
            return overseasOhlcvService.collectWindow(stock, session, anchor);
        }
        return domesticOhlcvService.collectWindow(
                stock, session, windowAdvancer.groupASpanFromDate(anchor), anchor);
    }

    private BackfillWindowOutcome buildOutcome(
            BackfillGroup group, BackfillWindowResult result, BackfillStatus status) {
        return switch (group) {
            case GROUP_A ->
                    BackfillWindowOutcome.groupA(result.rowCount(), result.oldestTradeDate());
            case GROUP_B ->
                    BackfillWindowOutcome.groupB(
                            result.rowCount(),
                            result.oldestTradeDate(),
                            status.getLastCollectedDate(),
                            status.getLastRowCount(),
                            status.getStaleCount());
        };
    }

    /**
     * 이번 윈도우 행 수({@code rowCount > 0})이면 그 값, 아니면 직전 저장값을 반환한다.
     *
     * <p>원시 int를 Integer로 명시적 변환해 SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED를 회피한다.
     */
    private static Integer resolveNewRowCount(int rowCount, Integer lastRowCount) {
        if (rowCount > 0) {
            return rowCount;
        }
        return lastRowCount;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
