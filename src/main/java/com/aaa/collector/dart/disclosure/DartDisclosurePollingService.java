package com.aaa.collector.dart.disclosure;

import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.dart.external.DartListResponse;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DART 공시 폴링 수집 서비스 (SPEC-COLLECTOR-DART-001 REQ-DART-010~013).
 *
 * <p>corp_code 미지정 → 전일~당일 전체 페이지 순회 → 응답 stock_code를 watchlist 매칭해 필터 저장. 비watchlist(빈 stock_code
 * 포함) 종목은 적재하지 않는다(REQ-DART-003).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DartDisclosurePollingService {

    static final String BATCH_LABEL = "dart-disclosure";
    private static final DateTimeFormatter RCEPT_DT_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartDisclosureClient dartDisclosureClient;
    private final DisclosureRepository disclosureRepository;
    private final StockRepository stockRepository;
    private final BatchMetrics batchMetrics;

    /**
     * 전일~당일 공시를 수집하고 watchlist 활성 종목만 적재한다 (REQ-DART-010).
     *
     * <p>각 행의 stock_code를 watchlist 집합과 매칭하여 매칭 행만 INSERT IGNORE 삽입한다. BatchMetrics 계측
     * 포함(REQ-DART-013).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 항목 단위 예외 격리 — REQ-DART-030
    public void poll() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        log.info("[dart-polling] 공시 폴링 시작 — bgnDe={}, endDe={}", yesterday, today);

        // watchlist 활성 종목을 symbol(=stock_code) → Stock 맵으로 인덱싱 (REQ-DART-003, D9)
        Map<String, Stock> activeByStockCode = buildActiveStockMap();
        if (activeByStockCode.isEmpty()) {
            log.info("[dart-polling] 활성 종목 없음 — 폴링 스킵");
            batchMetrics.recordCompletion(BATCH_LABEL, 0, 0, 0, 0);
            return;
        }

        List<DartListResponse.DisclosureItem> items =
                dartDisclosureClient.fetchAllPages(yesterday, today);
        log.info("[dart-polling] 응답 항목 수={}", items.size());

        int targetCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (DartListResponse.DisclosureItem item : items) {
            targetCount++;
            String stockCode = item.stockCode();

            // 빈 stock_code 즉시 스킵 (비상장사, REQ-DART-003)
            if (stockCode == null || stockCode.isBlank()) {
                skipCount++;
                continue;
            }

            Stock stock = activeByStockCode.get(stockCode);
            if (stock == null) {
                // 비watchlist 종목 스킵
                skipCount++;
                continue;
            }

            // 날짜 파싱 (포맷 오류 시 종목 격리 — REQ-DART-030)
            LocalDate rceptDt;
            try {
                rceptDt = LocalDate.parse(item.rceptDt(), RCEPT_DT_FMT);
            } catch (DateTimeParseException e) {
                log.warn(
                        "[dart-polling] rcept_dt 파싱 실패 — rceptNo={}, rceptDt={}",
                        item.rceptNo(),
                        item.rceptDt());
                failCount++;
                continue;
            }

            try {
                disclosureRepository.insertIgnore(toRow(stock.getId(), stockCode, item, rceptDt));
                successCount++;
            } catch (Exception e) {
                log.warn(
                        "[dart-polling] insertIgnore 실패 — rceptNo={}, error={}",
                        item.rceptNo(),
                        e.getMessage());
                failCount++;
            }
        }

        batchMetrics.recordCompletion(BATCH_LABEL, targetCount, successCount, failCount, skipCount);
        log.info(
                "[dart-polling] 공시 폴링 완료 — target={}, success={}, fail={}, skip={}",
                targetCount,
                successCount,
                failCount,
                skipCount);
    }

    /**
     * 폴링 응답 항목을 {@link DisclosureRow}로 변환한다.
     *
     * <p>pblntf_ty는 폴링 응답에 포함되지 않으므로 null로 설정한다.
     */
    private DisclosureRow toRow(
            Long stockId,
            String stockCode,
            DartListResponse.DisclosureItem item,
            LocalDate rceptDt) {
        return new DisclosureRow(
                stockId,
                item.corpCode(),
                stockCode,
                item.corpCls(),
                item.reportNm(),
                item.rceptNo(),
                item.flrNm(),
                rceptDt,
                item.rm(),
                null);
    }

    /**
     * watchlist 활성 종목을 stock_code(symbol) → {@link Stock} 맵으로 구축한다.
     *
     * <p>국내 KOSPI/KOSDAQ symbol은 6자리 종목코드 = DART stock_code와 동일하다(D9).
     */
    private Map<String, Stock> buildActiveStockMap() {
        return stockRepository.findAllActive().stream()
                .collect(Collectors.toMap(Stock::getSymbol, s -> s, (a, b) -> a));
    }
}
