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
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvFetch;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceFetch;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendFetch;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import com.aaa.collector.stock.supply.ShortSaleFetch;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백필 윈도우 1구간 실행기 (SPEC-COLLECTOR-BACKFILL-001 T6, SPEC-COLLECTOR-TXBOUNDARY-001 T7).
 *
 * <p>T7에서 트랜잭션 경계를 분리한다:
 *
 * <ul>
 *   <li>{@link #fetchWindow} — 비트랜잭션 fetch (KIS HTTP 호출, DB 미접촉, REQ-TXB-020)
 *   <li>{@link #persistWindow} — 트랜잭션 소유 persist (INSERT + status UPDATE 원자적 커밋, REQ-TXB-030)
 * </ul>
 *
 * <p>패키지 위치: {@code stock.backfill} — {@code stock} 피처 패키지가 {@code backfill} 피처 패키지에 의존하는 기존 방향을
 * 유지한다.
 */
// @MX:ANCHOR: [AUTO] 백필 윈도우 실행 진입점 — INSERT IGNORE+status UPDATE 동일 트랜잭션 묶음 담당
// @MX:REASON: [AUTO] AC-4.1/4.2 부분 커밋 방지. T7에서 fetchWindow(비tx)/persistWindow(tx)로 경계 분리.
// @MX:SPEC: SPEC-COLLECTOR-TXBOUNDARY-001
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
     * [T7] 비트랜잭션 fetch 단계 — 해당 서비스의 fetchWindow를 라우팅한다 (REQ-TXB-020).
     *
     * <p>@Transactional 없음 — DB 커넥션을 점유하지 않는다. 서비스의 fetchWindow는 {@code
     * status.getLastCollectedDate()}를 앵커로 사용하므로, 호출자는 null이 아닌 유효한 lastCollectedDate를 갖는 status를
     * 전달해야 한다. {@link #executeWindow}는 {@link #resolveAnchor}로 보정한 비영속 복사본(resolved status)을 전달한다.
     *
     * @param status 백필 상태 (lastCollectedDate == anchor, non-null 필수)
     * @param stock 대상 종목 엔티티
     * @param session per-run 헬스 스냅샷 세션
     * @return 서비스별 fetch DTO ({@link DomesticDailyOhlcvFetch} 등), unknown dataTable이면 {@code null}
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:ANCHOR: [AUTO] 비tx fetch 진입점 — T8 오케스트레이터·executeWindow 양쪽에서 호출
    // @MX:REASON: [AUTO] REQ-TXB-020. @Transactional 부재 보장 필수; 실수로 추가 시 fetch 중 커넥션 점유 발생.
    // @MX:SPEC: SPEC-COLLECTOR-TXBOUNDARY-001
    public Object fetchWindow(BackfillStatus status, Stock stock, LeaseSession session)
            throws InterruptedException {
        String dataTable = status.getDataTable();
        LocalDate anchor = status.getLastCollectedDate();
        return switch (dataTable) {
            case "daily_ohlcv" -> {
                if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
                    yield overseasOhlcvService.fetchWindow(anchor, stock, session);
                }
                LocalDate from = windowAdvancer.groupASpanFromDate(anchor);
                yield domesticOhlcvService.fetchWindow(from, anchor, stock, session);
            }
            case "short_sale_domestic" -> shortSaleService.fetchWindow(status, stock, session);
            case "investor_trend" -> investorTrendService.fetchWindow(anchor, stock, session);
            case "credit_balance" -> creditBalanceService.fetchWindow(status, stock, session);
            default -> {
                log.warn(
                        "[backfill] 알 수 없는 data_table — symbol={}, table={}",
                        stock.getSymbol(),
                        dataTable);
                yield null;
            }
        };
    }

    /**
     * [T7] 트랜잭션 소유 persist 단계 — INSERT + status UPDATE를 원자적으로 수행한다 (REQ-TXB-030, AC-1).
     *
     * <p>{@code fetchDto} 타입에 따라 서비스의 {@code persistWindow}로 라우팅한 뒤, 동일 트랜잭션에서 {@link
     * BackfillStatusRepository#updateProgress}를 호출하여 부분 커밋을 방지한다(REQ-BACKFILL-011).
     *
     * @param status 처리할 BackfillStatus 항목 (원본, resolvedStatus 아님)
     * @param stock 대상 종목 엔티티
     * @param fetchDto {@link #fetchWindow}가 반환한 서비스별 fetch DTO
     * @return 윈도우 수집 결과 (rowCount, oldestTradeDate)
     */
    // @MX:ANCHOR: [AUTO] tx 소유 persist 진입점 — INSERT+UPDATE 원자성 경계
    // @MX:REASON: [AUTO] REQ-TXB-030/REQ-BACKFILL-011. 이 메서드 외부에서 tx를 열면 원자성 깨짐.
    // @MX:SPEC: SPEC-COLLECTOR-TXBOUNDARY-001
    @Transactional
    public BackfillWindowResult persistWindow(BackfillStatus status, Stock stock, Object fetchDto) {
        String dataTable = status.getDataTable();
        String symbol = status.getTargetCode();

        BackfillWindowResult result = routePersist(dataTable, status, stock, fetchDto);

        BackfillGroup group = BackfillGroup.ofDataTable(dataTable);
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
        return result;
    }

    /**
     * 한 백필 항목의 윈도우 1구간을 수집하고, 동일 트랜잭션에서 status를 갱신한다.
     *
     * <p>T8 오케스트레이터 도입 전 호환성 유지를 위해 잔류한다. 내부적으로 {@link #fetchWindow} → {@link #persistWindow}를
     * 호출한다. {@link #resolveAnchor}로 null anchor(→ 어제)·nextAnchor를 보정한 resolved status를 fetchWindow에
     * 전달한다(REQ-BACKFILL-060).
     *
     * @param status 처리할 BackfillStatus 항목
     * @param stock 대상 종목 엔티티 (활성, REQ-BACKFILL-006)
     * @param session per-run 헬스 스냅샷 세션
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    @Transactional
    public void executeWindow(BackfillStatus status, Stock stock, LeaseSession session)
            throws InterruptedException {
        // resolveAnchor: null → 어제, non-null → nextAnchor(lastCollectedDate) (REQ-BACKFILL-060)
        // 서비스의 fetchWindow는 status.getLastCollectedDate()를 anchor로 사용하므로
        // 비영속 복사본(resolvedStatus)에 보정된 anchor를 주입해 전달한다.
        LocalDate anchor = resolveAnchor(status);
        BackfillStatus resolved = resolvedStatus(status, anchor);
        Object fetchDto = fetchWindow(resolved, stock, session);
        persistWindow(status, stock, fetchDto);
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

    /**
     * [T8] 오케스트레이터용: {@link #fetchWindow} 교차 빈 호출 전 anchor를 보정한 비영속 복사본을 반환한다 (REQ-BACKFILL-060,
     * REQ-TXB-020).
     *
     * <p>{@code null} lastCollectedDate(PENDING 초기 상태)는 어제로 초기화하고, non-null이면 {@link
     * BackfillWindowAdvancer#nextAnchor}로 전진한다. 반환된 resolved status는 {@link #fetchWindow}에 전달하며,
     * {@link #persistWindow}에는 원본 status를 사용한다.
     *
     * @param status 원본 BackfillStatus (lastCollectedDate는 null 허용)
     * @return anchor가 보정된 비영속 BackfillStatus 복사본
     */
    public BackfillStatus resolveStatusForFetch(BackfillStatus status) {
        return resolvedStatus(status, resolveAnchor(status));
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    /**
     * fetchDto 타입에 따라 서비스의 persistWindow로 라우팅한다.
     *
     * <p>fetchDto가 {@code null}이면 알 수 없는 dataTable로 간주해 {@link BackfillWindowResult#EMPTY}를 반환한다.
     */
    private BackfillWindowResult routePersist(
            String dataTable, BackfillStatus status, Stock stock, Object fetchDto) {
        if (fetchDto == null) {
            log.warn(
                    "[backfill] persistWindow 스킵 (null fetchDto) — symbol={}, table={}",
                    status.getTargetCode(),
                    dataTable);
            return BackfillWindowResult.EMPTY;
        }
        return switch (fetchDto) {
            case DomesticDailyOhlcvFetch f -> domesticOhlcvService.persistWindow(stock, f);
            case OverseasDailyOhlcvFetch f -> overseasOhlcvService.persistWindow(stock, f);
            case ShortSaleFetch f -> shortSaleService.persistWindow(status, stock, f);
            case InvestorTrendFetch f -> investorTrendService.persistWindow(stock, f);
            case CreditBalanceFetch f -> creditBalanceService.persistWindow(status, stock, f);
            default -> {
                log.warn(
                        "[backfill] 알 수 없는 fetchDto 타입 — type={}",
                        fetchDto.getClass().getSimpleName());
                yield BackfillWindowResult.EMPTY;
            }
        };
    }

    private LocalDate resolveAnchor(BackfillStatus status) {
        if (status.getLastCollectedDate() == null) {
            // 오늘 날짜는 KIS API TIME LIMIT(00:00~15:40) 대상 — 어제(과거)로 초기화 (REQ-BACKFILL-060)
            return LocalDate.now().minusDays(1);
        }
        return windowAdvancer.nextAnchor(status.getLastCollectedDate());
    }

    /**
     * {@code status}의 lastCollectedDate를 {@code anchor}로 교체한 비영속 복사본을 반환한다.
     *
     * <p>서비스의 fetchWindow는 {@code status.getLastCollectedDate()}를 anchor로 직접 사용하므로, null anchor(최초
     * 실행)·nextAnchor 보정을 반영하기 위해 JPA 관리 대상이 아닌 일회성 복사본을 생성한다. 이 인스턴스는 영속화되지 않는다.
     */
    private BackfillStatus resolvedStatus(BackfillStatus original, LocalDate anchor) {
        return BackfillStatus.builder()
                .targetType(original.getTargetType())
                .targetCode(original.getTargetCode())
                .dataTable(original.getDataTable())
                .status(original.getStatus())
                .lastCollectedDate(anchor)
                .staleCount(original.getStaleCount())
                .lastRowCount(original.getLastRowCount())
                .attemptCount(original.getAttemptCount())
                .lastError(original.getLastError())
                .build();
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
