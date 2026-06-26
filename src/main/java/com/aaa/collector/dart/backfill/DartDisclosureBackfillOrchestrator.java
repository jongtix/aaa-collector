package com.aaa.collector.dart.backfill;

import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DART 공시 백필 오케스트레이터 (SPEC-COLLECTOR-DART-001 REQ-DART-020~023).
 *
 * <p>lazy 시딩 → 미완료 항목 조회 → per-table-completion-cap 적용 → 종목별 윈도우 실행.
 */
// @MX:ANCHOR: [AUTO] DART 백필 오케스트레이터 진입점 — lazy 시딩→미완료 선별→cap→종목별 윈도우 실행
// @MX:REASON: [AUTO] SPEC-COLLECTOR-DART-001 REQ-DART-020/021/022/023 — backfill_status 편입,
//             stale-window COMPLETED 전이, per-table-completion-cap 제한(REQ-BACKFILL-064b)
@Slf4j
@Component
@RequiredArgsConstructor
public class DartDisclosureBackfillOrchestrator {

    static final String TARGET_TYPE = "STOCK";
    static final String DATA_TABLE = "disclosures";
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

    private final BackfillStatusRepository backfillStatusRepository;
    private final DartDisclosureBackfillWindowService windowService;
    private final StockRepository stockRepository;
    private final BackfillProperties backfillProperties;

    /**
     * 백필 1 cron 회차를 실행한다.
     *
     * <ol>
     *   <li>활성 종목 lazy 시딩 (INSERT IGNORE)
     *   <li>PENDING/IN_PROGRESS 항목 조회
     *   <li>per-table-completion-cap 적용 (DART는 단일 data_table, REQ-BACKFILL-064b)
     *   <li>종목별 윈도우 실행
     * </ol>
     */
    public void run() {
        log.info("[dart-backfill] 백필 cron 시작");

        // Step 1: 활성 종목 lazy 시딩
        List<Stock> activeStocks = stockRepository.findAllActive();
        for (Stock stock : activeStocks) {
            backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, stock.getSymbol(), DATA_TABLE);
        }
        log.info("[dart-backfill] 시딩 완료 — 활성 종목 수={}", activeStocks.size());

        // Step 2: 미완료 항목 조회 — data_table='disclosures' 한정 (CR-01: 타 도메인 진행점 오염 방지)
        List<BackfillStatus> pending =
                backfillStatusRepository.findByStatusInAndTargetTypeAndDataTableOrderById(
                        List.of(STATUS_PENDING, STATUS_IN_PROGRESS), TARGET_TYPE, DATA_TABLE);

        if (pending.isEmpty()) {
            log.info("[dart-backfill] 처리 대상 없음");
            return;
        }

        // Step 3: 활성 종목 맵 구축 (비활성 스킵)
        Map<String, Stock> activeBySymbol =
                activeStocks.stream().collect(Collectors.toMap(Stock::getSymbol, s -> s));

        // Step 4: per-table-completion-cap 적용 후 종목별 윈도우 실행 (DART는 단일 테이블 처리)
        int cap = backfillProperties.getPerTableCompletionCap();
        int processed = 0;

        for (BackfillStatus status : pending) {
            if (processed >= cap) {
                log.info("[dart-backfill] 완성 캡 도달 — cap={}", cap);
                break;
            }
            String symbol = status.getTargetCode();
            Stock stock = activeBySymbol.get(symbol);
            if (stock == null) {
                continue;
            }
            processed++;
            executeItem(status, stock);
        }

        log.info("[dart-backfill] 백필 cron 완료 — 처리 종목 수={}", processed);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 항목 단위 예외 격리 — REQ-DART-030
    private void executeItem(BackfillStatus status, Stock stock) {
        try {
            windowService.executeWindow(status, stock.getId(), stock.getSymbol());
        } catch (Exception e) {
            log.warn(
                    "[dart-backfill] 윈도우 오류 (항목 격리) — symbol={}, error={}",
                    stock.getSymbol(),
                    e.getMessage());
        }
    }
}
