package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.backfill.CoveredGapFiller;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
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

/**
 * STOCK 범위형(daily_ohlcv·investor_trend·credit_balance·short_sale_domestic) 정방향 갭 walk 1스텝 실행체
 * (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-050, AC-13/AC-14).
 *
 * <p><b>설계 근거(REQ-CVR-012 "범위형=covered_until_date 다음부터 시작하는 단일 윈도우")</b> — 이 클래스는 {@link
 * BackfillWindowExecutor}의 backward anchor 로직({@code resolveAnchor}/{@code nextAnchor}/하단 전진)을 전혀
 * 호출하지 않는 독립 경로다. 각 수집 서비스의 {@code fetchWindow}/{@code persistWindow}만 그대로 재사용한다(REQ-CVR-050, 신규
 * fetch 메서드 없음).
 *
 * <ul>
 *   <li><b>daily_ohlcv(국내)</b> — {@code fetchWindow(from, anchor, stock, session)}가 진짜 (from, to)
 *       범위를 받으므로, {@code from=cursor}, {@code anchor=stepAnchor}(=min(today, cursor+STEP))를 직접
 *       공급한다.
 *   <li><b>daily_ohlcv(해외)·investor_trend</b> — {@code fetchWindow(anchor, stock, session)}가 anchor
 *       1개만 받고 내부에서 트레일링 lookback을 뺀 windowStart를 계산한다. anchor를 {@code cursor} 그대로 넘기면
 *       [cursor-lookback, cursor] 구간만 재조회해 정방향 진행이 사실상 하루 단위로 퇴화한다 — 대신 {@code
 *       anchor=stepAnchor}(cursor보다 앞선 지점)를 공급해 내부 lookback이 [stepAnchor-lookback, stepAnchor] ⊇
 *       [cursor, stepAnchor]를 커버하게 한다.
 *   <li><b>credit_balance·short_sale_domestic</b> — {@code fetchWindow(BackfillStatus, stock,
 *       session)}가 {@code status.getLastCollectedDate()}를 anchor로 내부 사용한다. 영속화하지 않는 임시(transient)
 *       {@link BackfillStatus} 복사본을 만들어 anchor 자리에 {@code stepAnchor}를 주입한다 — {@link
 *       BackfillWindowExecutor#resolvedStatus}가 backward walk에서 이미 쓰는 것과 동일한 확립된 기법이다(REQ-CVR-072,
 *       기존 backward 의미 불변 — 이 사본은 절대 영속화되지 않는다).
 * </ul>
 *
 * <p><b>스텝 폭(REQ-CVR-042 — 신규 캡 아님, 기존 100건-cap을 안전하게 하회하는 호출 단위 폭)</b>: daily_ohlcv·
 * short_sale_domestic=90 달력일(각각 실측 무제한 범위·90일 트레일링), investor_trend·credit_balance=45 달력일(실측 45일
 * 트레일링과 일치). {@code kept > 0}이면 {@code stepAnchor}까지 전량 커버된 것으로 간주한다 — 이는 신규 가정이 아니라
 * REQ-CVR-020({@link com.aaa.collector.backfill.CoveredRangeService#advanceIfContinuous})이 이미 확립한
 * "배치 성공=지정 윈도우 전체 커버" 신뢰 모델을 그대로 재사용한다.
 *
 * <p><b>kept/raw 구조적 한계</b> — daily_ohlcv만 {@link BackfillWindowResult#rawRowCount()}가 실측 원본 응답 행수와
 * 독립적이다. investor_trend·credit_balance·short_sale_domestic은 {@link BackfillWindowResult}의 2-인자 생성자로
 * {@code rawRowCount := rowCount}가 항상 성립해(§2.6 실측), 이 3개 테이블에서는 REQ-CVR-031(raw>0 && kept==0
 * anomaly) 분기가 구조적으로 도달 불가능하다 — 검증 로직 결함이 아니라 원본 서비스가 raw/kept를 애초에 구분해 노출하지 않기 때문이다.
 */
// @MX:NOTE: [AUTO] backward anchor 로직(resolveAnchor/nextAnchor)과 격리된 독립 경로 — 상세 근거는 클래스 Javadoc
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
public class StockRangeCoveredGapFiller implements CoveredGapFiller {

    private static final Set<Market> OVERSEAS_MARKETS =
            Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    /**
     * daily_ohlcv 스텝 폭(달력일) — 100건-cap 안전 여유.
     *
     * <p>daily_ohlcv는 (국내=진짜 (from,to) 범위, 해외=BYMD 단일앵커) 양쪽 다 서비스 내부에 참조 가능한 트레일링 lookback 상수가
     * 없다(daily_ohlcv는 GROUP_A라 100건-cap 종료 게이트로 별도 관리되는 구조이지 고정 lookback 구조가 아님) — {@link
     * ShortSaleCollectionService#BACKFILL_LOOKBACK_CALENDAR_DAYS}(90)와 동일 근거(100건-cap 안전 여유)를 공유하는
     * 로컬 상수로 유지한다.
     */
    private static final int STEP_DAYS_WIDE = 90;

    private final BackfillStatus status;
    private final Stock stock;
    private final LocalDate today;
    private final LeaseSession session;
    private final DomesticDailyOhlcvCollectionService domesticOhlcvService;
    private final OverseasDailyOhlcvCollectionService overseasOhlcvService;
    private final InvestorTrendCollectionService investorTrendService;
    private final CreditBalanceCollectionService creditBalanceService;
    private final ShortSaleCollectionService shortSaleService;

    public StockRangeCoveredGapFiller(
            BackfillStatus status,
            Stock stock,
            LocalDate today,
            LeaseSession session,
            DomesticDailyOhlcvCollectionService domesticOhlcvService,
            OverseasDailyOhlcvCollectionService overseasOhlcvService,
            InvestorTrendCollectionService investorTrendService,
            CreditBalanceCollectionService creditBalanceService,
            ShortSaleCollectionService shortSaleService) {
        this.status = status;
        this.stock = stock;
        this.today = today;
        this.session = session;
        this.domesticOhlcvService = domesticOhlcvService;
        this.overseasOhlcvService = overseasOhlcvService;
        this.investorTrendService = investorTrendService;
        this.creditBalanceService = creditBalanceService;
        this.shortSaleService = shortSaleService;
    }

    /**
     * {@code cursor}부터 시작하는 단일 윈도우를 채운다 — data_table별로 알맞은 기존 서비스에 라우팅한다.
     *
     * @param cursor 이번 스텝의 시작 지점({@code covered_until_date} 다음 날짜)
     * @return kept/raw/filledUntil(=stepAnchor)
     */
    @Override
    public CoveredFillResult persistStep(LocalDate cursor) {
        try {
            return switch (status.getDataTable()) {
                case "daily_ohlcv" -> persistDailyOhlcv(cursor, stepAnchor(cursor, STEP_DAYS_WIDE));
                case "investor_trend" ->
                        persistInvestorTrend(
                                stepAnchor(
                                        cursor,
                                        InvestorTrendCollectionService
                                                .BACKFILL_LOOKBACK_CALENDAR_DAYS));
                case "credit_balance" ->
                        persistCreditBalance(
                                stepAnchor(
                                        cursor,
                                        CreditBalanceCollectionService
                                                .BACKFILL_LOOKBACK_CALENDAR_DAYS));
                case "short_sale_domestic" ->
                        persistShortSaleDomestic(
                                stepAnchor(
                                        cursor,
                                        ShortSaleCollectionService
                                                .BACKFILL_LOOKBACK_CALENDAR_DAYS));
                default ->
                        throw new IllegalStateException(
                                "커버-추적 비대상 STOCK data_table: " + status.getDataTable());
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "STOCK 정방향 갭 walk 인터럽트 — symbol=" + stock.getSymbol(), e);
        }
    }

    /** {@code min(today, cursor + stepDays)} — 오늘을 넘지 않는 이번 스텝의 목표 상한. */
    private LocalDate stepAnchor(LocalDate cursor, int stepDays) {
        LocalDate candidate = cursor.plusDays(stepDays);
        return candidate.isAfter(today) ? today : candidate;
    }

    private CoveredFillResult persistDailyOhlcv(LocalDate cursor, LocalDate stepAnchor)
            throws InterruptedException {
        if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
            OverseasDailyOhlcvFetch fetch =
                    overseasOhlcvService.fetchWindow(stepAnchor, stock, session);
            BackfillWindowResult result = overseasOhlcvService.persistWindow(stock, fetch);
            return new CoveredFillResult(result.rowCount(), result.rawRowCount(), stepAnchor);
        }
        DomesticDailyOhlcvFetch fetch =
                domesticOhlcvService.fetchWindow(cursor, stepAnchor, stock, session);
        BackfillWindowResult result = domesticOhlcvService.persistWindow(stock, fetch);
        return new CoveredFillResult(result.rowCount(), result.rawRowCount(), stepAnchor);
    }

    private CoveredFillResult persistInvestorTrend(LocalDate stepAnchor)
            throws InterruptedException {
        InvestorTrendFetch fetch = investorTrendService.fetchWindow(stepAnchor, stock, session);
        BackfillWindowResult result = investorTrendService.persistWindow(stock, fetch);
        return new CoveredFillResult(result.rowCount(), result.rawRowCount(), stepAnchor);
    }

    private CoveredFillResult persistCreditBalance(LocalDate stepAnchor)
            throws InterruptedException {
        BackfillStatus transientStatus = transientStatus(stepAnchor);
        CreditBalanceFetch fetch =
                creditBalanceService.fetchWindow(transientStatus, stock, session);
        BackfillWindowResult result =
                creditBalanceService.persistWindow(transientStatus, stock, fetch);
        return new CoveredFillResult(result.rowCount(), result.rawRowCount(), stepAnchor);
    }

    private CoveredFillResult persistShortSaleDomestic(LocalDate stepAnchor)
            throws InterruptedException {
        BackfillStatus transientStatus = transientStatus(stepAnchor);
        ShortSaleFetch fetch = shortSaleService.fetchWindow(transientStatus, stock, session);
        BackfillWindowResult result = shortSaleService.persistWindow(transientStatus, stock, fetch);
        return new CoveredFillResult(result.rowCount(), result.rawRowCount(), stepAnchor);
    }

    /**
     * anchor 자리에 {@code stepAnchor}를 주입한 비영속 {@link BackfillStatus} 복사본 — {@link
     * BackfillWindowExecutor#resolvedStatus}와 동일 기법(REQ-CVR-072, 절대 영속화되지 않는다).
     */
    private BackfillStatus transientStatus(LocalDate stepAnchor) {
        return BackfillStatus.builder()
                .targetType(status.getTargetType())
                .targetCode(status.getTargetCode())
                .dataTable(status.getDataTable())
                .status(status.getStatus())
                .lastCollectedDate(stepAnchor)
                .staleCount(status.getStaleCount())
                .lastRowCount(status.getLastRowCount())
                .attemptCount(status.getAttemptCount())
                .lastError(status.getLastError())
                .build();
    }
}
