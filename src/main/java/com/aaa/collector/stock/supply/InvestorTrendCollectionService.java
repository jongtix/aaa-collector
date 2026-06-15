package com.aaa.collector.stock.supply;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.InvestorTrend;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 투자자 매매동향 수집 진입점 — 건강 키 분산·매핑·단위변환·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-010~012,-020,-030~034,-060~063 — 통합 진입점·스케줄러에서 호출
// @MX:SPEC: SPEC-COLLECTOR-BATCH-002
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
    private final InvestorTrendRepository investorTrendRepository;
    private final BatchRestExecutor batchRestExecutor;
    private final HealthyKeyRoundRobinDistributor distributor;

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

        Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(activeStocks);
        int total = activeStocks.size();

        if (allocation.isEmpty()) {
            log.error(
                    "[investor-trend] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new SupplyDemandResult(total, 0, total);
        }

        LocalDate windowStart = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<KisAccountCredential, List<Stock>> entry : allocation.entrySet()) {
                KisAccountCredential credential = entry.getKey();
                for (Stock stock : entry.getValue()) {
                    executor.submit(
                            () ->
                                    collectStock(
                                            stock,
                                            credential,
                                            today,
                                            windowStart,
                                            succeeded,
                                            skipped));
                }
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
            KisAccountCredential credential,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            BatchResult<KisInvestorTrendResponse> batchResult = fetch(credential, symbol, today);

            if (!batchResult.isSuccess()) {
                String reason = batchResult.getSkipReason().orElse("알 수 없음");
                log.warn("[investor-trend] skip (데이터 유실) — symbol={}, reason={}", symbol, reason);
                skipped.incrementAndGet();
                return;
            }

            KisInvestorTrendResponse response = batchResult.getValue().orElseThrow();
            saveValidRows(stock, symbol, response, today, windowStart);
            succeeded.incrementAndGet();

        } catch (KisTokenIssueException e) {
            log.warn(
                    "[investor-trend] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private BatchResult<KisInvestorTrendResponse> fetch(
            KisAccountCredential credential, String symbol, LocalDate today) {
        String date = today.format(DATE_FMT);
        return batchRestExecutor.execute(
                credential,
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", date)
                                .queryParam("FID_ORG_ADJ_PRC", "")
                                .queryParam("FID_ETC_CLS_CODE", "1")
                                .build(),
                TR_ID,
                KisInvestorTrendResponse.class,
                symbol);
    }

    private void saveValidRows(
            Stock stock,
            String symbol,
            KisInvestorTrendResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<LocalDate> tradeDates = new ArrayList<>();
        for (KisInvestorTrendResponse.InvestorTrendRow row : response.output2()) {
            LocalDate tradeDate = insertIfValid(stock, symbol, row, today, windowStart);
            if (tradeDate != null) {
                tradeDates.add(tradeDate);
            }
        }
        // REQ-BATCH2-025: 경계 커버리지 관측 (단일 응답 윈도우 하단 미커버 시 WARN)
        WindowCoverageChecker.check("investor", symbol, tradeDates, windowStart);
    }

    /**
     * 검증 통과 시 행을 멱등 저장한다.
     *
     * @return 파싱된 {@code trade_date}(경계 커버리지 검사용 — 윈도우 밖 행 포함, 파싱/검증 실패 시 null)
     */
    private LocalDate insertIfValid(
            Stock stock,
            String symbol,
            KisInvestorTrendResponse.InvestorTrendRow row,
            LocalDate today,
            LocalDate windowStart) {
        if (row.stckBsopDate() == null || row.stckBsopDate().isBlank()) {
            log.warn("[investor-trend] 검증 실패 (trade_date null) — symbol={}", symbol);
            return null;
        }
        try {
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            if (tradeDate.isBefore(windowStart) || tradeDate.isAfter(today)) {
                // 윈도우 밖 행은 저장하지 않으나, 경계 커버리지 최소 trade_date 산정에는 포함한다.
                return tradeDate;
            }

            long totalVolume = Long.parseLong(row.acmlVol());
            long totalTradingValue = Long.parseLong(row.acmlTrPbmn()) * MILLION_WON_TO_WON;

            // 순매수 수량·거래대금은 매도 우위 시 음수가 정상이므로 음수 검증 제외(R-F).
            // 총거래량·총거래대금은 음수 비정상 — 저장 제외.
            if (SupplyDemandValidator.anyNegative(totalVolume, totalTradingValue)) {
                log.warn(
                        "[investor-trend] 검증 실패 (음수 총거래량/거래대금) — symbol={}, date={}, totalVolume={}, totalTradingValue={}",
                        symbol,
                        row.stckBsopDate(),
                        totalVolume,
                        totalTradingValue);
                return tradeDate;
            }

            InvestorTrend entity =
                    InvestorTrend.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .foreignNetQty(Long.parseLong(row.frgnNtbyQty()))
                            .institutionNetQty(Long.parseLong(row.orgnNtbyQty()))
                            .individualNetQty(Long.parseLong(row.prsnNtbyQty()))
                            .foreignNetValue(
                                    Long.parseLong(row.frgnNtbyTrPbmn()) * MILLION_WON_TO_WON)
                            .institutionNetValue(
                                    Long.parseLong(row.orgnNtbyTrPbmn()) * MILLION_WON_TO_WON)
                            .individualNetValue(
                                    Long.parseLong(row.prsnNtbyTrPbmn()) * MILLION_WON_TO_WON)
                            .totalVolume(totalVolume)
                            .totalTradingValue(totalTradingValue)
                            .build();
            investorTrendRepository.insertIgnoreDuplicate(entity);
            return tradeDate;
        } catch (NumberFormatException e) {
            log.warn(
                    "[investor-trend] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}",
                    symbol,
                    row.stckBsopDate());
            return null;
        }
    }
}
