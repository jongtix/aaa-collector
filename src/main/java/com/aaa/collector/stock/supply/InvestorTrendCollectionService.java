package com.aaa.collector.stock.supply;

import com.aaa.collector.backfill.AnchorCorrectionResult;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.InvestorTrend;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * 종목별 투자자 매매동향 수집 서비스 (TR FHPTJ04160001).
 *
 * <p>{@link com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService} 패턴 답습:
 * findAllActive→distributor.distribute→VT executor→종목별 단일 호출→14일 윈도우 필터·검증·멱등 저장→집계.
 *
 * <p>매핑(REQ-BATCH2-031/032): 외국인/기관계/개인 3분류만 저장(11분류 중). 단위(REQ-BATCH2-033): {@code acml_tr_pbmn}은
 * 원 단위 직접 저장(포털 명세 "백만원" 오기 — 실측 역산 2026-06-26 정정). 순매수 거래대금(REQ-BATCH2-034/OI-1): 백만원
 * 확정(×1,000,000 변환, api-specs 실측 노트 2026-06-14).
 *
 * <p>검증(REQ-BATCH2-060~063): null 키 필드·총거래량/총거래대금 음수·파싱 실패 건별 skip. 순매수 수량·거래대금은 매도 우위 시 음수 정상이므로
 * 음수 허용(R-F). 14일 윈도우 밖 행 제외. 빈 응답은 0건 succeeded(REQ-063).
 *
 * <p>침묵 드롭(REQ-OBSV-023): 검증 통과 행들을 {@link InvestorTrendInserter}로 종목별 배치 삽입한다 — 인서터가 JDBC 경고 체인에서
 * 비-중복 드롭을 캡처하고 {@link com.aaa.collector.observability.BatchMetrics}에 기록한다. 본 서비스는 도메인 검증·매핑·skip
 * 집계만 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 투자자 매매동향 수집 진입점 — 게이트 경유 키 lease·매핑·단위변환·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-010~012,-030~034,-060~063,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024 — 게이트 경유 통합 진입점
// @MX:SPEC: SPEC-COLLECTOR-BATCH-002, SPEC-COLLECTOR-KISGATE-001
public class InvestorTrendCollectionService {

    /** 당일 배치 수집 윈도우 캘린더 일수 (≈최근 5거래일, 연휴 대비 14일). */
    static final int LOOKBACK_CALENDAR_DAYS = 14;

    /** 백필 수집 윈도우 캘린더 일수 — API 단일 호출이 ~30행(≈45달력일)을 반환하므로 전부 포착. */
    static final int BACKFILL_LOOKBACK_CALENDAR_DAYS = 45;

    /**
     * 백만원 → 원 변환 계수 (×1,000,000).
     *
     * <p>순매수 거래대금({@code *_ntby_tr_pbmn}) 전용. 실측 역산으로 단위 = 백만원 확정(REQ-BATCH2-033, api-specs 실측 노트
     * 2026-06-14). {@code acml_tr_pbmn}(누적 거래 대금)에는 사용하지 않는다 — 해당 필드는 원 단위 반환(포털 명세 오기 정정
     * 2026-06-26, api-specs/kis/03-종목별투자자매매동향.md 참조).
     */
    static final long MILLION_WON_TO_WON = 1_000_000L;

    /** KIS API rt_cd 값 — 비영업일 anchor 거부 코드 (REQ-BACKFILL-016, MA-05 실측 2026-06-20). */
    private static final String RT_CD_NON_BUSINESS_DAY = "2";

    private static final String TR_ID = "FHPTJ04160001";
    private static final String PATH =
            "/uapi/domestic-stock/v1/quotations/investor-trade-by-stock-daily";

    private final StockRepository stockRepository;
    private final InvestorTrendInserter inserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final BackfillWindowAdvancer windowAdvancer;

    /**
     * 투자자 매매동향 수집을 실행하고 집계 결과를 반환한다 (활성종목 자체 조회).
     *
     * @param today 수집 기준일
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today) {
        return collect(today, stockRepository.findAllActiveTradable());
    }

    /**
     * 투자자 매매동향 수집을 실행하고 집계 결과를 반환한다 (활성종목 외부 주입 — 통합 진입점이 1회 조회를 공유).
     *
     * @param today 수집 기준일
     * @param activeStocks 활성 관심종목 목록
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today, List<Stock> activeStocks) {
        if (activeStocks.isEmpty()) {
            log.info("[investor-trend] 수집 대상 없음 — activeStocks=0");
            return new SupplyDemandResult(0, 0, 0);
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정
        KeyLeaseRegistry.LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-KISGATE-024, REQ-KEYDIST-020 보존: 빈 스냅샷 = 전 키 사망 → skip-all + ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[investor-trend] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new SupplyDemandResult(total, 0, total);
        }

        LocalDate windowStart = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션 공유(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () -> collectStock(stock, session, today, windowStart, succeeded, skipped));
            }
        }

        SupplyDemandResult result = new SupplyDemandResult(total, succeeded.get(), skipped.get());
        log.info(
                "[investor-trend] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    /**
     * 백필용 윈도우 1구간 수집 — 당일 수집과 동일한 매핑·검증·INSERT IGNORE 경로를 재사용한다 (REQ-BACKFILL-002).
     *
     * <p>TR FHPTJ04160001은 비영업일 anchor 전달 시 {@code rt_cd="2"}를 반환한다(MA-05 실측 2026-06-20). 이 경우
     * anchor를 −1 calendar day씩 당기며 최대 10회 재시도한다(REQ-BACKFILL-016). 10회 소진 후에도 {@code rt_cd="2"}이면
     * {@link BackfillWindowResult#EMPTY}를 반환하고 WARN을 남긴다. 예외는 호출자(T6)가 상태 전이로 처리하도록 전파한다.
     *
     * @param stock 백필 대상 종목 (활성, REQ-BACKFILL-006)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param anchor 수집 기준일 (비영업일이면 anchor-skip 보정 적용)
     * @return 적재 대상 행의 최소 거래일 + 행 수 (적재 대상 없으면 {@link BackfillWindowResult#EMPTY})
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    public BackfillWindowResult collectWindow(
            Stock stock, KeyLeaseRegistry.LeaseSession session, LocalDate anchor)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        LocalDate effectiveAnchor = anchor;

        KisInvestorTrendResponse response = fetch(session, symbol, effectiveAnchor);
        int attempts = 0;
        while (RT_CD_NON_BUSINESS_DAY.equals(response.rtCd())) {
            AnchorCorrectionResult correction =
                    windowAdvancer.correctRejectedAnchor(effectiveAnchor, attempts);
            effectiveAnchor = correction.correctedAnchor();
            attempts = correction.attempts();
            if (correction.exhausted()) {
                log.warn(
                        "[investor-trend][backfill] anchor-skip 한도 소진 — symbol={}, originalAnchor={}, effectiveAnchor={}, attempts={}",
                        symbol,
                        anchor,
                        effectiveAnchor,
                        attempts);
                return BackfillWindowResult.EMPTY;
            }
            response = fetch(session, symbol, effectiveAnchor);
        }

        LocalDate windowStart = effectiveAnchor.minusDays(LOOKBACK_CALENDAR_DAYS);
        List<InvestorTrend> validEntities =
                saveValidRows(stock, symbol, response, effectiveAnchor, windowStart);
        if (validEntities.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        LocalDate oldest =
                validEntities.stream()
                        .map(InvestorTrend::getTradeDate)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        return new BackfillWindowResult(oldest, validEntities.size());
    }

    private void collectStock(
            Stock stock,
            KeyLeaseRegistry.LeaseSession session,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            KisInvestorTrendResponse response = fetch(session, symbol, today);
            saveValidRows(stock, symbol, response, today, windowStart); // 반환값 무시 — 당일 경로
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip
            log.warn(
                    "[investor-trend] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip
            Thread.currentThread().interrupt();
            log.warn("[investor-trend] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            log.warn("[investor-trend] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            log.warn(
                    "[investor-trend] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private KisInvestorTrendResponse fetch(
            KeyLeaseRegistry.LeaseSession session, String symbol, LocalDate today)
            throws InterruptedException {
        String date = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        return guardedKisExecutor.execute(
                session,
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", date)
                                .queryParam("FID_ORG_ADJ_PRC", "")
                                .queryParam("FID_ETC_CLS_CODE", "1")
                                .build(),
                TR_ID,
                KisInvestorTrendResponse.class);
    }

    /**
     * [T5] fetch 경로 전용 — 검증·매핑·경계 커버리지 관측만 수행하고 INSERT하지 않는다.
     *
     * <p>{@link #fetchWindow}가 사용하는 순수 검증 경로. {@link #saveValidRows}는 이 결과에 적재를
     * 추가한다(REQ-BACKFILL-002).
     *
     * @return 검증 통과 엔티티 목록 (없으면 빈 목록)
     */
    private List<InvestorTrend> collectValidEntities(
            Stock stock,
            String symbol,
            KisInvestorTrendResponse response,
            LocalDate today,
            LocalDate windowStart) {
        // 파싱 성공한 모든 거래일 (윈도우 밖 포함) — 커버리지 관측용
        List<LocalDate> tradeDates =
                response.output2().stream()
                        .filter(
                                row -> {
                                    if (row.stckBsopDate() == null
                                            || row.stckBsopDate().isBlank()) {
                                        log.warn(
                                                "[investor-trend] 검증 실패 (trade_date null) — symbol={}",
                                                symbol);
                                        return false;
                                    }
                                    return true;
                                })
                        .map(
                                row ->
                                        LocalDate.parse(
                                                row.stckBsopDate(),
                                                DateTimeFormatter.BASIC_ISO_DATE))
                        .toList();

        // REQ-BATCH2-025: 경계 커버리지 관측 (단일 응답 윈도우 하단 미커버 시 WARN)
        WindowCoverageChecker.check("investor", symbol, tradeDates, windowStart);

        // 윈도우 내 검증 통과 엔티티만 수집 — mapMulti로 try-catch 인라인 처리
        return response.output2().stream()
                .filter(row -> row.stckBsopDate() != null && !row.stckBsopDate().isBlank())
                .filter(
                        row -> {
                            LocalDate tradeDate =
                                    LocalDate.parse(
                                            row.stckBsopDate(), DateTimeFormatter.BASIC_ISO_DATE);
                            return !tradeDate.isBefore(windowStart) && !tradeDate.isAfter(today);
                        })
                .<InvestorTrend>mapMulti(
                        (row, consumer) -> {
                            LocalDate tradeDate =
                                    LocalDate.parse(
                                            row.stckBsopDate(), DateTimeFormatter.BASIC_ISO_DATE);
                            try {
                                InvestorTrend entity = buildEntity(stock, symbol, tradeDate, row);
                                if (entity != null) {
                                    consumer.accept(entity);
                                }
                            } catch (NumberFormatException e) {
                                log.warn(
                                        "[investor-trend] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}",
                                        symbol,
                                        row.stckBsopDate());
                            }
                        })
                .toList();
    }

    /**
     * 검증 통과 행만 수집하여 배치 삽입하고, 적재한 엔티티 목록을 반환한다.
     *
     * <p>{@code tradeDates}는 경계 커버리지 산정에 사용 — 파싱 성공한 날짜만 포함(NumberFormatException 제외, 윈도우 밖·검증 실패
     * 포함).
     *
     * <p>당일 경로({@link #collectStock})는 반환값을 무시하고, 백필 경로({@link #collectWindow})는 최소 거래일·행 수 도출에
     * 사용한다 — 동일 매핑·검증·적재 경로를 공유한다(REQ-BACKFILL-002).
     *
     * @return 적재한 검증 통과 엔티티 목록 (없으면 빈 목록)
     */
    private List<InvestorTrend> saveValidRows(
            Stock stock,
            String symbol,
            KisInvestorTrendResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<InvestorTrend> validEntities =
                collectValidEntities(stock, symbol, response, today, windowStart);
        if (validEntities.isEmpty()) {
            return validEntities;
        }
        inserter.insertBatch(validEntities);
        return validEntities;
    }

    /**
     * [T5] fetch 단계 — HTTP 호출·비영업일 anchor-skip 보정 루프·검증을 수행하고 INSERT하지 않는다.
     *
     * <p>[HARD] {@code windowAdvancer.correctRejectedAnchor} 호출이 포함된 rt_cd=2 재시도 루프는 이 메서드에 잔류한다
     * (REQ-BACKFILL-016).
     *
     * <p>{@code anchor}를 initial anchor로 삼는다. DB 접촉 없음.
     *
     * @param anchor initial anchor (BackfillWindowExecutor가 resolveStatusForFetch로 계산해서 전달)
     * @param stock 백필 대상 종목
     * @param session 호출자가 고정한 per-run 헬스 스냅샷 세션
     * @return 검증 통과 엔티티 목록 + 최소 거래일 + 행 수 (없으면 rows=빈목록, oldestTradeDate=null, rowCount=0)
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:NOTE: [AUTO] fetchWindow — 비tx HTTP 단계. DB 미접촉. BackfillWindowExecutor가 @Transactional
    // persistWindow와 교차 빈으로 순차 호출.
    public InvestorTrendFetch fetchWindow(
            LocalDate anchor, Stock stock, KeyLeaseRegistry.LeaseSession session)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        LocalDate effectiveAnchor = anchor;

        KisInvestorTrendResponse response = fetch(session, symbol, effectiveAnchor);
        int attempts = 0;
        while (RT_CD_NON_BUSINESS_DAY.equals(response.rtCd())) {
            AnchorCorrectionResult correction =
                    windowAdvancer.correctRejectedAnchor(effectiveAnchor, attempts);
            effectiveAnchor = correction.correctedAnchor();
            attempts = correction.attempts();
            if (correction.exhausted()) {
                log.warn(
                        "[investor-trend][backfill] anchor-skip 한도 소진 — symbol={}, originalAnchor={}, effectiveAnchor={}, attempts={}",
                        symbol,
                        anchor,
                        effectiveAnchor,
                        attempts);
                return new InvestorTrendFetch(List.of(), null, 0);
            }
            response = fetch(session, symbol, effectiveAnchor);
        }

        LocalDate windowStart = effectiveAnchor.minusDays(BACKFILL_LOOKBACK_CALENDAR_DAYS);
        List<InvestorTrend> validEntities =
                collectValidEntities(stock, symbol, response, effectiveAnchor, windowStart);
        if (validEntities.isEmpty()) {
            return new InvestorTrendFetch(List.of(), null, 0);
        }
        LocalDate oldest =
                validEntities.stream()
                        .map(InvestorTrend::getTradeDate)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        return new InvestorTrendFetch(validEntities, oldest, validEntities.size());
    }

    /**
     * [T5] persist 단계 — {@link InvestorTrendFetch}의 엔티티를 INSERT IGNORE 배치 적재한다.
     *
     * <p>@Transactional 없음 — 트랜잭션은 {@code BackfillWindowExecutor}(T7)가 소유한다(REQ-TXBOUNDARY-002).
     *
     * @param stock 백필 대상 종목
     * @param fetch fetchWindow가 반환한 DTO
     * @return 적재 대상 행의 최소 거래일 + 행 수 (fetch가 빈 경우 {@link BackfillWindowResult#EMPTY})
     */
    // @MX:NOTE: [AUTO] persistWindow — BackfillWindowExecutor @Transactional에 MANDATORY 전파로 합류.
    // INSERT + 결과 구성 담당.
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindow(Stock stock, InvestorTrendFetch fetch) {
        if (fetch.rows().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[investor-trend][backfill] persistWindow 스킵 (빈 fetch) — symbol={}",
                        stock.getSymbol());
            }
            return BackfillWindowResult.EMPTY;
        }
        inserter.insertBatch(fetch.rows());
        return new BackfillWindowResult(fetch.oldestTradeDate(), fetch.rowCount());
    }

    /**
     * 검증 통과 시 엔티티를 반환한다. 검증 실패 시 {@code null}(로그 후). 숫자 파싱 실패 시 {@link NumberFormatException} 전파.
     */
    private InvestorTrend buildEntity(
            Stock stock,
            String symbol,
            LocalDate tradeDate,
            KisInvestorTrendResponse.InvestorTrendRow row) {
        long totalVolume = Long.parseLong(row.acmlVol());
        // acml_tr_pbmn은 원 단위로 반환됨 — 포털 명세 "백만원" 표기는 오기(실측 역산 2026-06-26 확인).
        long totalTradingValue = Long.parseLong(row.acmlTrPbmn());

        // 순매수 수량·거래대금은 매도 우위 시 음수가 정상이므로 음수 검증 제외(R-F).
        // 총거래량·총거래대금은 음수 비정상 — 저장 제외.
        // 총거래량·총거래대금은 음수 비정상 — 저장 제외 (순매수 계열 음수는 정상이므로 여기서만 검증, R-F)
        if (totalVolume < 0 || totalTradingValue < 0) {
            log.warn(
                    "[investor-trend] 검증 실패 (음수 총거래량/거래대금) — symbol={}, date={}, totalVolume={}, totalTradingValue={}",
                    symbol,
                    tradeDate,
                    totalVolume,
                    totalTradingValue);
            return null;
        }

        return InvestorTrend.builder()
                .stock(stock)
                .tradeDate(tradeDate)
                .foreignNetQty(Long.parseLong(row.frgnNtbyQty()))
                .institutionNetQty(Long.parseLong(row.orgnNtbyQty()))
                .individualNetQty(Long.parseLong(row.prsnNtbyQty()))
                .foreignNetValue(Long.parseLong(row.frgnNtbyTrPbmn()) * MILLION_WON_TO_WON)
                .institutionNetValue(Long.parseLong(row.orgnNtbyTrPbmn()) * MILLION_WON_TO_WON)
                .individualNetValue(Long.parseLong(row.prsnNtbyTrPbmn()) * MILLION_WON_TO_WON)
                .totalVolume(totalVolume)
                .totalTradingValue(totalTradingValue)
                .build();
    }
}
