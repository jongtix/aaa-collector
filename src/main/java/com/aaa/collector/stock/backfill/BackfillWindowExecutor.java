package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillGroup;
import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowOutcome;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.backfill.TerminationDecision;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DividendBackfillFetch;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitBackfillFetch;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvFetch;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitBackfillFetch;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceFetch;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendFetch;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import com.aaa.collector.stock.supply.ShortSaleFetch;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
@SuppressWarnings("PMD.ExcessiveImports") // 다중 수집 서비스 라우팅 구조상 불가피한 import 수
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillWindowExecutor {

    private static final int MAX_ERROR_LENGTH = 512;

    /** KST 타임존 — to-date 계산 기준 (CLAUDE.md KST 통일, REQ-BACKFILL-095). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 미국 시장 집합 — daily_ohlcv 수집 서비스 라우팅에 사용. */
    private static final Set<Market> OVERSEAS_MARKETS =
            Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    /**
     * 미국 KIS 일봉 데이터 고정 하한 벽 (SPEC-COLLECTOR-BACKFILL-010 §1.3, api-specs/kis/22).
     *
     * <p>{@code HHDFS76240000} BYMD를 이 날짜 이전으로 지정하면 어떤 종목도 빈 배열을 반환한다(06-28/07-01/07-08 3회 실측 동일 경계
     * = 롤링 아닌 고정 날짜). 미국 기대 최과거일 = {@code max(listed_date, 2007-08-20)}, {@code listed_date} NULL이면
     * 이 벽이다. 벽 상수 자체는 신뢰 가능한 하한이다(REQ-150).
     */
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    private static final LocalDate US_DATA_WALL = LocalDate.of(2007, 8, 20);

    /** 게이트 대상 테이블 — corporate_events*는 GROUP_A이나 below-100 완료가 정상이라 제외(§Exclusions D4). */
    private static final String DAILY_OHLCV = "daily_ohlcv";

    /** GROUP_A daily_ohlcv 단일 호출 잠정 종료 임계(거래일). decideGroupA 임계값 100·{@code <} 비교와 동일(불변). */
    private static final int SINGLE_CALL_ROW_CAP = 100;

    private final BackfillStatusRepository backfillStatusRepository;
    private final DomesticDailyOhlcvCollectionService domesticOhlcvService;
    private final OverseasDailyOhlcvCollectionService overseasOhlcvService;
    private final ShortSaleCollectionService shortSaleService;
    private final InvestorTrendCollectionService investorTrendService;
    private final CreditBalanceCollectionService creditBalanceService;
    private final RevSplitCollectionService revSplitService;
    private final DividendScheduleCollectionService dividendService;
    private final OverseasSplitCollectionService overseasSplitService;
    private final BackfillTerminationPolicy terminationPolicy;
    private final BackfillWindowAdvancer windowAdvancer;
    private final BackfillMetrics backfillMetrics;
    private final TransactionTemplate transactionTemplate;
    private final BackfillProperties backfillProperties;

    /**
     * [T7] 비트랜잭션 fetch 단계 — 해당 서비스의 fetchWindow를 라우팅한다 (REQ-TXB-020).
     *
     * <p>@Transactional 없음 — DB 커넥션을 점유하지 않는다. 내부적으로 {@link #resolveAnchor}를 호출하여 null
     * lastCollectedDate(PENDING 초기 상태)를 어제로 보정한 뒤, 비영속 복사본(resolved status)을 생성하여 서비스에 전달한다
     * (REQ-BACKFILL-060). 호출자는 원본 status를 그대로 전달하면 된다.
     *
     * @param status 백필 상태 (lastCollectedDate null 허용 — 내부에서 보정)
     * @param stock 대상 종목 엔티티
     * @param session per-run 헬스 스냅샷 세션
     * @return 서비스별 fetch DTO ({@link DomesticDailyOhlcvFetch} 등), unknown dataTable이면 {@code null}
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:ANCHOR: [AUTO] 비tx fetch 진입점 — T8 오케스트레이터·executeWindow 양쪽에서 호출
    // @MX:REASON: [AUTO] REQ-TXB-020. @Transactional 부재 보장 필수; 실수로 추가 시 fetch 중 커넥션 점유 발생.
    // @MX:SPEC: SPEC-COLLECTOR-TXBOUNDARY-001
    // @MX:NOTE: [AUTO] GROUP_A daily_ohlcv 종료 검증 게이트 — 케이스① 검증완료·②③ anomaly-FAILED·정상경로 프로브
    // @MX:REASON: SPEC-COLLECTOR-BACKFILL-010 §4.1 — 프로브 HTTP는 비tx fetch 단계에서만, probeOutcome만
    // persist로 전달
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    public FetchEnvelope fetchWindow(BackfillStatus status, Stock stock, LeaseSession session)
            throws InterruptedException {
        // [R5-CR-01] FAILED 종목 조기 단락 — 통상 윈도우 KIS fetch 생략(persist가 규범적 no-op, §5.2)
        if (status.getStatus() == BackfillStatusType.FAILED) {
            return FetchEnvelope.notApplicable(null);
        }
        BackfillStatus resolved = resolvedStatus(status, resolveAnchor(status));
        String dataTable = resolved.getDataTable();
        LocalDate anchor = resolved.getLastCollectedDate();
        Object dto = routeFetch(dataTable, resolved, anchor, stock, session);
        return buildEnvelope(resolved, stock, session, dataTable, dto);
    }

    /** 서비스별 fetchWindow로 라우팅한다 (기존 dataTable 분기 — 시그니처·동작 불변). */
    private Object routeFetch(
            String dataTable,
            BackfillStatus resolved,
            LocalDate anchor,
            Stock stock,
            LeaseSession session)
            throws InterruptedException {
        return switch (dataTable) {
            case "daily_ohlcv" -> {
                if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
                    yield overseasOhlcvService.fetchWindow(anchor, stock, session);
                }
                // @MX:NOTE SPEC-COLLECTOR-BACKFILL-005 고정 플로어 — 상폐 종목 초기 윈도우 오종료 해소
                LocalDate from = windowAdvancer.groupAFromDate();
                yield domesticOhlcvService.fetchWindow(from, anchor, stock, session);
            }
            case "short_sale_domestic" -> shortSaleService.fetchWindow(resolved, stock, session);
            case "investor_trend" -> investorTrendService.fetchWindow(anchor, stock, session);
            case "credit_balance" -> creditBalanceService.fetchWindow(resolved, stock, session);
            // @MX:NOTE SPEC-COLLECTOR-BACKFILL-007 W3 + SPEC-COLLECTOR-OVERSEAS-SPLIT-001
            // REQ-OSPLIT-063 —
            // 종목지정 SPLIT 백필. from-date=고정 플로어(REQ-BACKFILL-094), to-date=today(KST,
            // REQ-BACKFILL-095).
            // 시장별 소스 분기: 미국→CTRGT011R(OverseasSplitCollectionService), 국내→HHKDB669105C0(RevSplit).
            case "corporate_events" -> {
                LocalDate floor = windowAdvancer.groupAFromDate();
                LocalDate to = LocalDate.now(KST);
                if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
                    yield overseasSplitService.fetchWindowForBackfill(stock, session, floor, to);
                }
                yield revSplitService.fetchWindowForBackfill(stock, session, floor, to);
            }
            // @MX:NOTE SPEC-COLLECTOR-BACKFILL-009 W2 — 종목지정 현금배당 백필(SPLIT과 별도 data_table 논리 키).
            // from-date=고정 플로어(REQ-BACKFILL-126), to-date=today(KST). SPLIT(rev-split) 분기
            // 불변(REQ-BACKFILL-144).
            case "corporate_events_dividend" ->
                    dividendService.fetchWindowForBackfill(
                            stock, session, windowAdvancer.groupAFromDate(), LocalDate.now(KST));
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
     * GROUP_A {@code daily_ohlcv} 종료 확인 게이트를 적용해 probeOutcome을 산정하고 봉투로 감싼다 (§4.1).
     *
     * <p>게이트는 {@code dataTable == "daily_ohlcv"}에만 적용한다(corporate_events*·GROUP_B 제외 →
     * NOT_APPLICABLE). 프로브 HTTP는 각 서비스의 비tx {@code confirmExhaustionProbe}에서만 발생한다.
     */
    private FetchEnvelope buildEnvelope(
            BackfillStatus resolved,
            Stock stock,
            LeaseSession session,
            String dataTable,
            Object dto)
            throws InterruptedException {
        if (!DAILY_OHLCV.equals(dataTable)) {
            return FetchEnvelope.notApplicable(dto);
        }
        int rawRowCount = dailyRawRowCount(dto);
        LocalDate oldest = dailyOldest(dto);
        if (rawRowCount >= SINGLE_CALL_ROW_CAP) {
            return FetchEnvelope.of(dto, ProbeOutcome.NOT_APPLICABLE); // 잠정 종료 미성립
        }

        boolean overseas = OVERSEAS_MARKETS.contains(stock.getMarket());
        Floor floor = computeFloor(stock, resolved, overseas);

        if (oldest != null) {
            if (floor.known() && floor.trusted() && !oldest.isAfter(floor.value())) {
                return FetchEnvelope.of(dto, ProbeOutcome.FLOOR_ALREADY_MET); // 프로브 0회 (REQ-150)
            }
            return probeBelow(dto, oldest, stock, session, overseas);
        }
        // oldest == null — rawRowCount로 케이스 분기
        if (rawRowCount == 0) {
            // 케이스 ① 진짜 빈 응답 — [R5-MA-01] 교차검증(anchor > floor면 허위 빈응답 → 강등)
            LocalDate anchor = resolved.getLastCollectedDate();
            boolean falseEmpty = floor.known() && anchor != null && anchor.isAfter(floor.value());
            return FetchEnvelope.of(
                    dto, falseEmpty ? ProbeOutcome.EMPTY_ANOMALY : ProbeOutcome.EMPTY_EXHAUSTED);
        }
        // 케이스 ②③ zdiv 가드/전량 거부 (rawRowCount>0) — 이상
        return FetchEnvelope.of(dto, ProbeOutcome.EMPTY_ANOMALY);
    }

    /** 정상 경로 확인 프로브 1회 발행(비tx HTTP) — 오류는 DEFERRED로 분류한다. */
    private FetchEnvelope probeBelow(
            Object dto, LocalDate oldest, Stock stock, LeaseSession session, boolean overseas)
            throws InterruptedException {
        try {
            boolean hasData =
                    overseas
                            ? overseasOhlcvService.confirmExhaustionProbe(oldest, stock, session)
                            : domesticOhlcvService.confirmExhaustionProbe(
                                    windowAdvancer.groupAFromDate(), oldest, stock, session);
            return FetchEnvelope.of(
                    dto,
                    hasData ? ProbeOutcome.MORE_DATA_EXISTS : ProbeOutcome.CONFIRMED_EXHAUSTED);
        } catch (RuntimeException e) {
            return FetchEnvelope.deferred(dto, e.getMessage(), isRetryable(e));
        }
    }

    /** {@code daily_ohlcv} fetch DTO에서 GROUP_A 종료 판정 입력(rawRowCount)을 추출한다. */
    private static int dailyRawRowCount(Object dto) {
        if (dto instanceof DomesticDailyOhlcvFetch f) {
            return f.rawRowCount();
        }
        if (dto instanceof OverseasDailyOhlcvFetch f) {
            return f.rawRowCount();
        }
        return SINGLE_CALL_ROW_CAP; // 방어적 — 알 수 없는 DTO는 게이트 비대상
    }

    /** {@code daily_ohlcv} fetch DTO에서 도달 최과거일(oldest)을 추출한다(0 유효행이면 null). */
    private static LocalDate dailyOldest(Object dto) {
        if (dto instanceof DomesticDailyOhlcvFetch f) {
            return f.oldestTradeDate();
        }
        if (dto instanceof OverseasDailyOhlcvFetch f) {
            return f.oldestTradeDate();
        }
        return null;
    }

    /**
     * 기대 최과거일(floor)과 신뢰 여부를 산정한다 (REQ-150/-154).
     *
     * <ul>
     *   <li>미국: {@code floor = max(listed_date, 2007-08-20 벽)}. 벽 자체(listed_date NULL 또는 ≤ 벽)=신뢰
     *       가능. {@code listed_date > 벽}이면 floor = listed_date(KIS lstg_dt=현 거래소 상장일이라 과대평가 가능) = 신뢰
     *       불가.
     *   <li>국내(분기 B): 검증 기준선(verified_at IS NOT NULL 완료의 last_collected_date)만 신뢰 하한. 없으면 신뢰 하한
     *       부재(unknown).
     * </ul>
     */
    private Floor computeFloor(Stock stock, BackfillStatus resolved, boolean overseas) {
        if (overseas) {
            LocalDate listed = stock.getListedDate();
            if (listed == null || !listed.isAfter(US_DATA_WALL)) {
                return new Floor(US_DATA_WALL, true, true); // 신뢰 가능한 벽
            }
            return new Floor(listed, false, true); // listed_date 유래 = 신뢰 불가
        }
        Optional<LocalDate> baseline =
                backfillStatusRepository.findVerifiedBaseline(
                        resolved.getTargetType(),
                        resolved.getTargetCode(),
                        resolved.getDataTable());
        return baseline.map(d -> new Floor(d, true, true)).orElse(Floor.UNKNOWN);
    }

    /** 기대 최과거일(floor) — 값·신뢰 여부·존재 여부. */
    private record Floor(LocalDate value, boolean trusted, boolean known) {
        static final Floor UNKNOWN = new Floor(null, false, false);
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
    public BackfillWindowResult persistWindow(
            BackfillStatus status, Stock stock, FetchEnvelope envelope) {
        BackfillStatus managed = backfillStatusRepository.findById(status.getId()).orElseThrow();

        // [R5-CR-01] FAILED 멱등 종단 가드 — 카운터·fail()·advance() 전부 생략(같은 run 잔여 윈도우 재발화 차단)
        if (managed.getStatus() == BackfillStatusType.FAILED) {
            return BackfillWindowResult.EMPTY;
        }

        ProbeOutcome outcome = envelope.probeOutcome();
        if (outcome == ProbeOutcome.NOT_APPLICABLE) {
            return persistLegacy(status, stock, envelope.serviceFetch(), managed);
        }
        return persistGated(status, stock, envelope, managed);
    }

    /** 게이트 비대상(GROUP_B·corporate_events*·rawRowCount≥100) — 기존 decide 경로 무변경 [R5-MI-02]. */
    private BackfillWindowResult persistLegacy(
            BackfillStatus status, Stock stock, Object fetchDto, BackfillStatus managed) {
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

        BackfillStatusType newStatus =
                decision.completed()
                        ? BackfillStatusType.COMPLETED
                        : BackfillStatusType.IN_PROGRESS;
        LocalDate newDate =
                result.oldestTradeDate() != null
                        ? result.oldestTradeDate()
                        : status.getLastCollectedDate();

        managed.advance(
                newStatus,
                newDate,
                decision.nextStaleCount(),
                resolveNewRowCount(result.rowCount(), status.getLastRowCount()));

        log.debug(
                "[backfill] 윈도우 완료 — symbol={}, table={}, status={}, oldest={}",
                symbol,
                dataTable,
                newStatus,
                newDate);
        return result;
    }

    /**
     * GROUP_A {@code daily_ohlcv} 게이트 판정(probeOutcome)을 소비해 status 전이·{@code verified_at}·{@code
     * stale_count}를 결정한다 (§4.1). 신규 KIS 호출은 하지 않는다(프로브는 이미 fetch 단계에서 발행됨, REQ-TXB-020).
     */
    private BackfillWindowResult persistGated(
            BackfillStatus status, Stock stock, FetchEnvelope envelope, BackfillStatus managed) {
        ProbeOutcome outcome = envelope.probeOutcome();
        return switch (outcome) {
            case DEFERRED -> {
                // 검증 보류·무전진 — 재시도 가능=IN_PROGRESS / 비재시도=FAILED, verified_at 미설정 (REQ-149)
                managed.fail(
                        envelope.retryable()
                                ? BackfillStatusType.IN_PROGRESS
                                : BackfillStatusType.FAILED,
                        truncate(envelope.probeError(), MAX_ERROR_LENGTH));
                yield BackfillWindowResult.EMPTY;
            }
            case EMPTY_ANOMALY -> persistEmptyAnomaly(managed);
            default -> persistGatedCompletion(status, stock, envelope, managed, outcome);
        };
    }

    /** FLOOR_ALREADY_MET·CONFIRMED_EXHAUSTED·EMPTY_EXHAUSTED(완료)·MORE_DATA_EXISTS(전진) 처리. */
    private BackfillWindowResult persistGatedCompletion(
            BackfillStatus status,
            Stock stock,
            FetchEnvelope envelope,
            BackfillStatus managed,
            ProbeOutcome outcome) {
        BackfillWindowResult result =
                routePersist(status.getDataTable(), status, stock, envelope.serviceFetch());
        backfillMetrics.recordWindow(result.rowCount());
        Integer newRowCount = resolveNewRowCount(result.rowCount(), status.getLastRowCount());

        if (outcome == ProbeOutcome.MORE_DATA_EXISTS) {
            // 조기 완료 차단 — IN_PROGRESS 유지, 진행점=비-프로브 윈도우 oldest(무변경 전진, R5-CR-02), staleCount=0
            managed.advance(
                    BackfillStatusType.IN_PROGRESS, result.oldestTradeDate(), 0, newRowCount);
            backfillMetrics.recordEarlyCompletionSuspect();
            return result;
        }
        // 완료 — COMPLETED + verified_at (both-null은 진행점 NULL 유지)
        LocalDate newDate =
                result.oldestTradeDate() != null
                        ? result.oldestTradeDate()
                        : status.getLastCollectedDate();
        managed.advance(BackfillStatusType.COMPLETED, newDate, 0, newRowCount);
        managed.markVerified(LocalDateTime.now(KST));
        return result;
    }

    /**
     * 케이스 ②③(및 강등된 ①) 이상 — 완료 금지, staleCount 누적, 정확히 N 사이클에 terminal FAILED (REQ-146b).
     *
     * <p>anomaly persist 자체는 통상 persist라 {@code recordWindow(0)}로 {@code windows_total}을
     * 증가시킨다(§4.1). 종결 시 {@code windows_total}·{@code pending_slots}와 무관한 독립 anomaly-FAILED 카운터를
     * status당 1회 올린다.
     */
    private BackfillWindowResult persistEmptyAnomaly(BackfillStatus managed) {
        backfillMetrics.recordWindow(0);
        int next = managed.getStaleCount() + 1;
        if (next >= backfillProperties.getStaleWindowThreshold()) {
            managed.fail(
                    BackfillStatusType.FAILED,
                    "GROUP_A empty-anomaly: zdiv guard / all rows rejected");
            backfillMetrics.recordAnomalyFailed();
        } else {
            // 진행점 불변 — 다음 사이클 재평가 (staleCount 누적)
            managed.advance(
                    BackfillStatusType.IN_PROGRESS,
                    managed.getLastCollectedDate(),
                    next,
                    managed.getLastRowCount());
        }
        return BackfillWindowResult.EMPTY;
    }

    /**
     * 한 백필 항목의 윈도우 1구간을 수집하고, 동일 트랜잭션에서 status를 갱신한다.
     *
     * <p>T8 오케스트레이터 도입 전 호환성 유지를 위해 잔류한다. 내부적으로 {@link #fetchWindow} → {@link #persistWindow}를
     * 호출한다. anchor 보정은 {@link #fetchWindow} 내부에서 수행된다(REQ-BACKFILL-060).
     *
     * @param status 처리할 BackfillStatus 항목
     * @param stock 대상 종목 엔티티 (활성, REQ-BACKFILL-006)
     * @param session per-run 헬스 스냅샷 세션
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    @Transactional
    public void executeWindow(BackfillStatus status, Stock stock, LeaseSession session)
            throws InterruptedException {
        FetchEnvelope envelope = fetchWindow(status, stock, session);
        persistWindow(status, stock, envelope);
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
        BackfillStatusType newStatus =
                retryable ? BackfillStatusType.IN_PROGRESS : BackfillStatusType.FAILED;
        String truncated = truncate(errorMsg, MAX_ERROR_LENGTH);
        try {
            transactionTemplate.executeWithoutResult(
                    tx -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(status.getId()).orElseThrow();
                        managed.fail(newStatus, truncated);
                    });
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
            // SPEC-COLLECTOR-BACKFILL-007 W4 — 국내 매핑+CorporateEventInserter INSERT IGNORE → 종료 입력
            case RevSplitBackfillFetch f -> revSplitService.persistWindowForBackfill(f);
            // SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-063 — 미국 SPLIT 매핑+INSERT IGNORE → 종료 입력
            case OverseasSplitBackfillFetch f -> overseasSplitService.persistWindowForBackfill(f);
            // SPEC-COLLECTOR-BACKFILL-009 W2 — DividendRowAccumulator 저장 정책+INSERT IGNORE → 종료 입력.
            // 별도 DTO 타입이라 SPLIT(RevSplitBackfillFetch) 분기와 오염 없이 분리(REQ-BACKFILL-144).
            case DividendBackfillFetch f -> dividendService.persistWindowForBackfill(f);
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
                    // SPEC-COLLECTOR-BACKFILL-006: GROUP_A 종료 입력 = rawRowCount(원본 행수).
                    // recordWindow / last_row_count는 rowCount(저장 행수) 그대로 사용(무변경).
                    BackfillWindowOutcome.groupA(
                            result.rowCount(), result.rawRowCount(), result.oldestTradeDate());
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
