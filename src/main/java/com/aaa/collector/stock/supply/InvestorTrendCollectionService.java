package com.aaa.collector.stock.supply;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.InvestorTrend;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * 종목별 투자자 매매동향 수집 서비스 (TR FHPTJ04160001).
 *
 * <p>{@link com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService} 패턴 답습:
 * findAllActive→distributor.distribute→VT executor→종목별 단일 호출→14일 윈도우 필터·검증·멱등 저장→집계.
 *
 * <p>매핑(REQ-BATCH2-031/032): 외국인/기관계/개인 3분류만 저장(11분류 중). 단위(REQ-BATCH2-033): {@code acml_tr_pbmn}
 * 백만원→원 ×1,000,000. 순매수 거래대금(REQ-BATCH2-034/OI-1): 단위 미명시이나 기본 백만원 가정(×1,000,000) — Run 단계 실데이터 자릿수
 * 검증 게이트(AC-4 S4-3) 통과 후 변환 계수 확정 필요.
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

    /** 수집 윈도우 캘린더 일수 (≈최근 5거래일, 연휴 대비 14일). */
    static final int LOOKBACK_CALENDAR_DAYS = 14;

    /**
     * 백만원 → 원 변환 계수 (×1,000,000).
     *
     * <p>{@code acml_tr_pbmn}(REQ-BATCH2-033 확정 "백만원")과 순매수 거래대금(OI-1 미확정, 기본 가정)에 공통 적용.
     *
     * <p>TODO(OI-1, REQ-BATCH2-034, T9): 순매수 거래대금(`*_ntby_tr_pbmn`) 단위는 api-specs 공란으로 미확정이다. 본 계수는
     * 구현 진행용 기본 가정값이며, Run 단계 실데이터 1건으로 일봉 거래대금(원) 대비 자릿수 정합(AC-4 S4-3)을 검증한 후 확정해야 한다.
     */
    static final long MILLION_WON_TO_WON = 1_000_000L;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHPTJ04160001";
    private static final String PATH =
            "/uapi/domestic-stock/v1/quotations/investor-trade-by-stock-daily";

    private final StockRepository stockRepository;
    private final InvestorTrendInserter inserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

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
        LeaseSession session = keyLeaseRegistry.openSession();
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

    private void collectStock(
            Stock stock,
            LeaseSession session,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            KisInvestorTrendResponse response = fetch(session, symbol, today);
            saveValidRows(stock, symbol, response, today, windowStart);
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

    private KisInvestorTrendResponse fetch(LeaseSession session, String symbol, LocalDate today)
            throws InterruptedException {
        String date = today.format(DATE_FMT);
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", date)
                                .queryParam("FID_ORG_ADJ_PRC", "")
                                .queryParam("FID_ETC_CLS_CODE", "1")
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, TR_ID, KisInvestorTrendResponse.class);
    }

    /**
     * 검증 통과 행만 수집하여 배치 삽입한다.
     *
     * <p>{@code tradeDates}는 경계 커버리지 산정에 사용 — 파싱 성공한 날짜만 포함(NumberFormatException 제외, 윈도우 밖·검증 실패
     * 포함).
     */
    private void saveValidRows(
            Stock stock,
            String symbol,
            KisInvestorTrendResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<InvestorTrend> validEntities = new ArrayList<>();
        List<LocalDate> tradeDates = new ArrayList<>();
        for (KisInvestorTrendResponse.InvestorTrendRow row : response.output2()) {
            if (row.stckBsopDate() == null || row.stckBsopDate().isBlank()) {
                log.warn("[investor-trend] 검증 실패 (trade_date null) — symbol={}", symbol);
                continue;
            }
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            tradeDates.add(tradeDate);
            if (tradeDate.isBefore(windowStart) || tradeDate.isAfter(today)) {
                // 윈도우 밖 행은 저장하지 않으나, 경계 커버리지 최소 trade_date 산정에는 포함한다.
                continue;
            }
            try {
                InvestorTrend entity = buildEntity(stock, symbol, tradeDate, row);
                if (entity != null) {
                    validEntities.add(entity);
                }
            } catch (NumberFormatException e) {
                log.warn(
                        "[investor-trend] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}",
                        symbol,
                        row.stckBsopDate());
            }
        }
        // REQ-BATCH2-025: 경계 커버리지 관측 (단일 응답 윈도우 하단 미커버 시 WARN)
        WindowCoverageChecker.check("investor", symbol, tradeDates, windowStart);
        if (validEntities.isEmpty()) {
            return;
        }
        inserter.insertBatch(validEntities);
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
        long totalTradingValue = Long.parseLong(row.acmlTrPbmn()) * MILLION_WON_TO_WON;

        // 순매수 수량·거래대금은 매도 우위 시 음수가 정상이므로 음수 검증 제외(R-F).
        // 총거래량·총거래대금은 음수 비정상 — 저장 제외.
        if (SupplyDemandValidator.anyNegative(totalVolume, totalTradingValue)) {
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
