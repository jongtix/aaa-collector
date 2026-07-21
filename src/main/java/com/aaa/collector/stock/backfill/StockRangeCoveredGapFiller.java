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
 * <p><b>스텝 폭(REQ-CVR-073/074/075/075a — SPEC-COLLECTOR-BACKFILL-011 v0.5.0 §1.4 스텝 폭 over-claim 정정,
 * TASK-010)</b>: daily_ohlcv·short_sale_domestic={@link #STEP_DAYS_WIDE}(90 달력일 — 진짜 범위 조회라 claimed
 * 구간 = 반환 구간, over-claim 구조적으로 불가능. 마진 충족은 REQ-CVR-075a에 따라 명시 검증됨, TASK-011).
 * investor_trend·credit_balance={@link #SINGLE_ANCHOR_STEP_CALENDAR_DAYS}(35 달력일 — 단일 anchor 1콜이
 * backward 반환하는 최대 30 거래일 용량 이하로 스텝 폭을 제한한 전용 상수, REQ-CVR-075). 이 상수는 각 수집 서비스의 검증 필터 상수 {@code
 * BACKFILL_LOOKBACK_CALENDAR_DAYS}(45, windowStart 계산 전용)와 물리적으로 분리되어 있다(REQ-CVR-074) — 옛 45일 겸용
 * 상수는 최악의 경우(공휴일 0) 32~34 거래일을 담아 30행 용량을 초과했다(db-audit §5.4, credit_balance 88개 날짜 구멍의 근본원인).
 * {@code kept > 0}이면 {@code stepAnchor}까지 전량 커버된 것으로 간주한다 — 이는 신규 가정이 아니라 REQ-CVR-020({@link
 * com.aaa.collector.backfill.CoveredRangeService#advanceIfContinuous})이 이미 확립한 "배치 성공=지정 윈도우 전체 커버"
 * 신뢰 모델을 재사용하되, 이제는 REQ-CVR-073 스텝 폭 상한 불변식 하에서만 유효하다.
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

    /**
     * 단일-anchor 고정 캡 소스(credit_balance·investor_trend) 정방향 갭 walk 전용 스텝 폭(달력일) — REQ-CVR-073, -074,
     * -075 (SPEC-COLLECTOR-BACKFILL-011 v0.5.0 §1.4, plan.md §9a.1, TASK-010).
     *
     * <p>KIS TR {@code FHPST04760000}(credit_balance)·{@code FHPTJ04160001}(investor_trend)은 단일
     * {@code FID_INPUT_DATE_1} anchor 1콜로 <b>정확히 30 거래일만</b> backward 반환한다(api-specs/kis/05·03 실측 +
     * KIS 공식 SDK docstring "한 번의 호출에 최대 30건 확인 가능" 이중 근거, spec.md §1.4·§7). 옛 45일 겸용 상수(검증 필터
     * windowStart 계산과 겸용)는 공휴일이 적은 최악의 경우(worst case) 32~34 거래일을 담아 이 30행 용량을 초과했다 — 앞단 1~2 거래일이
     * 조용히 미포착되며 {@code covered_until_date}가 실제로 커버되지 않은 지점까지 전진했다(db-audit §5.4, credit_balance 88개
     * 날짜 구멍의 근본원인).
     *
     * <p>이 상수는 검증 필터 상수({@link
     * CreditBalanceCollectionService#BACKFILL_LOOKBACK_CALENDAR_DAYS}·{@link
     * InvestorTrendCollectionService#BACKFILL_LOOKBACK_CALENDAR_DAYS}, 둘 다 45 — 반환 행을 부당 폐기하지 않으려면
     * 커야 하는 상한 방향 제약)와 <b>물리적으로 분리</b>된 스텝 폭 전용 상수다(over-claim 방지를 위해 API 용량 이하여야 하는 하한 방향 제약,
     * REQ-CVR-074 — 두 제약은 방향이 반대라 하나의 상수로 겸용 불가). 필터 상수를 바꿔도 이 스텝 폭은 자동으로 바뀌지 않는다.
     *
     * <p><b>값 산정(plan.md §9a.1)</b>: L=stepDays+1=36 연속 달력일의 최대 거래일 수(공휴일 0 worst case)는 26일 — 30
     * 대비 4행 헤드룸. 이론적 상한은 stepDays≤41(L=42, 마진 0) 또는 대안 검산 stepDays≤39 — 채택값 35는 (a) anchor 포함/제외
     * off-by-one, (b) stepAnchor가 주말·공휴일에 착지해 실효 anchor가 당겨지는 경우, (c) 향후 KIS 반환 특성 변동에 안전 여유를 준다.
     *
     * <p><b>[D4] 회차 수 트레이드오프</b>: 스텝 폭 축소(45→35)로 동일 갭을 채우는 데 필요한 정방향 회차 수가 약 45/35≈1.29배 증가한다(예:
     * 900달력일 갭 → 20회차(구)에서 26회차(신)). REQ-CVR-042("신규 캡·throttle 도입 금지")와 상충하지 않는다 — 042가 금지하는 것은 회차
     * 수를 인위적으로 늘리는 신규 캡·throttle이지, over-claim 정정에서 파생되는 정확성 비용이 아니다(§3.5 정정 주석). 증가분은 기존
     * BACKFILL-004 테이블별 공정분배 캡이 이미 여러 cron 회차에 자연 분산하는 구조에 그대로 흡수된다.
     */
    private static final int SINGLE_ANCHOR_STEP_CALENDAR_DAYS = 35;

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
                        persistInvestorTrend(stepAnchor(cursor, SINGLE_ANCHOR_STEP_CALENDAR_DAYS));
                case "credit_balance" ->
                        persistCreditBalance(stepAnchor(cursor, SINGLE_ANCHOR_STEP_CALENDAR_DAYS));
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
