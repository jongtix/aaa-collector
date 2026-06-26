package com.aaa.collector.stock.exthours;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 미국 시간외(Pre/After-Hours) 가격 스냅샷 수집 서비스 (SPEC-COLLECTOR-EXTHOURS-001).
 *
 * <p>종목 순차 처리 — {@code parallelStream} 금지(ADR-008/Virtual Threads 주의). 종목 간 Yahoo 비공식 API 부하 분산을 위해
 * {@link ExtendedHoursSleeper} 인터페이스로 딜레이를 주입한다. Thread.sleep 값 직접 변경이 아닌 인터페이스 추상화로 테스트 가능성을 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtendedHoursCollectionService {

    private final StockRepository stockRepository;
    private final YahooExtendedHoursClient yahooClient;
    private final ExtendedHoursRepository extendedHoursRepository;
    private final ExtendedHoursInserter extendedHoursInserter;
    private final ExtendedHoursSleeper sleeper;

    @Value("${aaa.extended-hours.request-delay-ms:2500}")
    private long requestDelayMs;

    /**
     * 지정 세션의 미국 시간외 가격 스냅샷을 전 종목에 대해 수집한다 (SPEC-COLLECTOR-EXTHOURS-001 REQ-EXTH-010, 011).
     *
     * <p>대상: 활성 미국 매매 가능 종목 ({@code StockRepository.findAllActiveOverseasTradable()}). 종목 단위
     * try/catch skip 격리 — 개별 오류가 전체 배치를 중단하지 않는다. Redis Streams 발행 없음(REQ-EXTH-052).
     *
     * @param session 수집 세션 (PRE 또는 AFTER)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 종목 단위 예외 격리 — 배치 중단 방지
    public void collect(Session session) {
        List<Stock> stocks = stockRepository.findAllActiveOverseasTradable();
        if (stocks.isEmpty()) {
            log.info("[extended-hours] {} 수집 대상 없음 — 종료", session);
            return;
        }

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        // REQ-INSERT-011: 유효 행 누적 후 격리 삽입
        List<ExtendedHours> batch = new ArrayList<>();

        for (Stock stock : stocks) {
            if (attempted > 0) {
                applyDelay(stock.getSymbol(), session);
            }
            attempted++;

            try {
                Optional<ExtendedHoursRow> rowOpt = yahooClient.fetch(stock, session);
                if (rowOpt.isEmpty()) {
                    skipped++;
                    continue;
                }

                ExtendedHoursRow row = rowOpt.get();

                // ext_price 또는 reference_close ≤ 0 → skip (REQ-EXTH-032)
                if (row.extPrice().signum() <= 0 || row.referenceClose().signum() <= 0) {
                    log.warn(
                            "[extended-hours] {} {} 가격 0 이하 — skip: extPrice={}, refClose={}",
                            stock.getSymbol(),
                            session,
                            row.extPrice(),
                            row.referenceClose());
                    skipped++;
                    continue;
                }

                batch.add(buildEntity(stock, row));
                succeeded++;
            } catch (Exception e) {
                log.warn(
                        "[extended-hours] {} {} 처리 중 예외 — skip: {}",
                        stock.getSymbol(),
                        session,
                        e.getMessage() != null ? e.getMessage() : "[no message]");
                skipped++;
            }
        }

        // REQ-INSERT-011: 격리 삽입 — 독성 행 skip·잔여 행 계속·커넥션 중단 없음
        int failures = insertIsolated(batch);
        succeeded -= failures;
        skipped += failures;

        log.info(
                "[extended-hours] {} 수집 완료 — attempted={}, succeeded={}, skipped={}",
                session,
                attempted,
                succeeded,
                skipped);
    }

    /** 시간외 가격 배치를 격리 삽입하고 실패 건수를 반환한다. */
    private int insertIsolated(List<ExtendedHours> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        AtomicInteger failures = new AtomicInteger();
        extendedHoursInserter.insertBatchIsolated(
                batch,
                (entity, ex) -> {
                    log.warn(
                            "[extended-hours] {} {} 저장 실패 — skip: {}",
                            entity.getStock().getSymbol(),
                            entity.getSession(),
                            ex.getMessage());
                    failures.incrementAndGet();
                });
        return failures.get();
    }

    private ExtendedHours buildEntity(Stock stock, ExtendedHoursRow row) {
        return ExtendedHours.builder()
                .stock(stock)
                .session(row.session())
                .tradeDate(row.tradeDate())
                .extPrice(row.extPrice())
                .referenceClose(row.referenceClose())
                .source(row.source())
                .collectedAt(LocalDateTime.now())
                .build();
    }

    private void applyDelay(String symbol, Session session) {
        try {
            sleeper.sleep(requestDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[extended-hours] {} {} 딜레이 인터럽트 — 계속 진행", symbol, session);
        }
    }
}
