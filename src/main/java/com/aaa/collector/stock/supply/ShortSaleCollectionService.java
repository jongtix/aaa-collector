package com.aaa.collector.stock.supply;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
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
 * 종목별 공매도 일별추이 수집 서비스 (TR FHPST04830000).
 *
 * <p>{@link InvestorTrendCollectionService}와 동일 골격. {@code FID_INPUT_DATE_1}(시작=today−14일)/{@code
 * FID_INPUT_DATE_2}(종료=today) 기간 조회로 14일 윈도우를 1회 호출에 충족한다(REQ-BATCH2-040).
 *
 * <p>매핑(REQ-BATCH2-041): 공매도 체결/거래대금 + 비율 + 누적. 금액은 원 단위 무변환(REQ-BATCH2-042 / OI-2).
 *
 * <p>검증(REQ-BATCH2-060~063): 비율 절댓값 ≥ 1000(DECIMAL(7,4) 경계) 초과·음수 수량/금액·null·파싱 실패 건별 skip. 14일 윈도우
 * 밖 행 제외. 빈 응답은 0건 succeeded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 공매도 일별추이 수집 진입점 — 기간 조회·매핑·비율 경계 검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-010~012,-020,-040~042,-060~063 — 통합 진입점·스케줄러에서 호출
// @MX:SPEC: SPEC-COLLECTOR-BATCH-002
public class ShortSaleCollectionService {

    static final int LOOKBACK_CALENDAR_DAYS = 14;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHPST04830000";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/daily-short-sale";

    private final StockRepository stockRepository;
    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final BatchRestExecutor batchRestExecutor;
    private final HealthyKeyRoundRobinDistributor distributor;

    /**
     * 공매도 일별추이 수집을 실행하고 집계 결과를 반환한다 (활성종목 자체 조회).
     *
     * @param today 수집 기준일
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today) {
        return collect(today, stockRepository.findAllActiveTradable());
    }

    /**
     * 공매도 일별추이 수집을 실행하고 집계 결과를 반환한다 (활성종목 외부 주입 — 통합 진입점이 1회 조회를 공유).
     *
     * @param today 수집 기준일
     * @param activeStocks 활성 관심종목 목록
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today, List<Stock> activeStocks) {
        if (activeStocks.isEmpty()) {
            log.info("[short-sale] 수집 대상 없음 — activeStocks=0");
            return new SupplyDemandResult(0, 0, 0);
        }

        Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(activeStocks);
        int total = activeStocks.size();

        if (allocation.isEmpty()) {
            log.error(
                    "[short-sale] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
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
                "[short-sale] 수집 완료 — attempted={}, succeeded={}, skipped={}",
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
            BatchResult<KisShortSaleResponse> batchResult =
                    fetch(credential, symbol, windowStart, today);

            if (!batchResult.isSuccess()) {
                String reason = batchResult.getSkipReason().orElse("알 수 없음");
                log.warn("[short-sale] skip (데이터 유실) — symbol={}, reason={}", symbol, reason);
                skipped.incrementAndGet();
                return;
            }

            KisShortSaleResponse response = batchResult.getValue().orElseThrow();
            saveValidRows(stock, symbol, response, today, windowStart);
            succeeded.incrementAndGet();

        } catch (KisTokenIssueException e) {
            log.warn("[short-sale] 토큰 발급 실패로 skip — symbol={}, error={}", symbol, e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private BatchResult<KisShortSaleResponse> fetch(
            KisAccountCredential credential, String symbol, LocalDate from, LocalDate to) {
        String fromDate = from.format(DATE_FMT);
        String toDate = to.format(DATE_FMT);
        return batchRestExecutor.execute(
                credential,
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", fromDate)
                                .queryParam("FID_INPUT_DATE_2", toDate)
                                .build(),
                TR_ID,
                KisShortSaleResponse.class,
                symbol);
    }

    private void saveValidRows(
            Stock stock,
            String symbol,
            KisShortSaleResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<LocalDate> tradeDates = new ArrayList<>();
        for (KisShortSaleResponse.ShortSaleRow row : response.output2()) {
            LocalDate tradeDate = insertIfValid(stock, symbol, row, today, windowStart);
            if (tradeDate != null) {
                tradeDates.add(tradeDate);
            }
        }
        // REQ-BATCH2-025: 경계 커버리지 관측 (단일 응답 윈도우 하단 미커버 시 WARN)
        WindowCoverageChecker.check("short-sale", symbol, tradeDates, windowStart);
    }

    /**
     * 검증 통과 시 행을 멱등 저장한다.
     *
     * @return 파싱된 {@code trade_date}(경계 커버리지 검사용 — 윈도우 밖 행 포함, 파싱/검증 실패 시 null)
     */
    private LocalDate insertIfValid(
            Stock stock,
            String symbol,
            KisShortSaleResponse.ShortSaleRow row,
            LocalDate today,
            LocalDate windowStart) {
        if (row.stckBsopDate() == null || row.stckBsopDate().isBlank()) {
            log.warn("[short-sale] 검증 실패 (trade_date null) — symbol={}", symbol);
            return null;
        }
        try {
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            if (tradeDate.isBefore(windowStart) || tradeDate.isAfter(today)) {
                return tradeDate;
            }

            long shortSellQty = Long.parseLong(row.sstsCntgQty());
            long shortSellAmt = Long.parseLong(row.sstsTrPbmn());
            long shortSellAccQty = Long.parseLong(row.acmlSstsCntgQty());
            long shortSellAccAmt = Long.parseLong(row.acmlSstsTrPbmn());
            BigDecimal shortSellVolRate = new BigDecimal(row.sstsVolRlim());
            BigDecimal shortSellAmtRate = new BigDecimal(row.sstsTrPbmnRlim());
            BigDecimal shortSellAccQtyRate = new BigDecimal(row.acmlSstsCntgQtyRlim());
            BigDecimal shortSellAccAmtRate = new BigDecimal(row.acmlSstsTrPbmnRlim());

            if (SupplyDemandValidator.anyNegative(
                    shortSellQty, shortSellAmt, shortSellAccQty, shortSellAccAmt)) {
                log.warn(
                        "[short-sale] 검증 실패 (음수 수량/금액) — symbol={}, date={}",
                        symbol,
                        row.stckBsopDate());
                return tradeDate;
            }

            if (!SupplyDemandValidator.allRatesWithinBounds(
                    shortSellVolRate, shortSellAmtRate, shortSellAccQtyRate, shortSellAccAmtRate)) {
                log.warn(
                        "[short-sale] 검증 실패 (비율 DECIMAL(7,4) 경계 초과) — symbol={}, date={}",
                        symbol,
                        row.stckBsopDate());
                return tradeDate;
            }

            ShortSaleDomestic entity =
                    ShortSaleDomestic.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .shortSellQty(shortSellQty)
                            .shortSellVolRate(shortSellVolRate)
                            .shortSellAmt(shortSellAmt)
                            .shortSellAmtRate(shortSellAmtRate)
                            .shortSellAccQty(shortSellAccQty)
                            .shortSellAccQtyRate(shortSellAccQtyRate)
                            .shortSellAccAmt(shortSellAccAmt)
                            .shortSellAccAmtRate(shortSellAccAmtRate)
                            .build();
            shortSaleDomesticRepository.insertIgnoreDuplicate(entity);
            return tradeDate;
        } catch (NumberFormatException e) {
            log.warn(
                    "[short-sale] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}",
                    symbol,
                    row.stckBsopDate());
            return null;
        }
    }
}
