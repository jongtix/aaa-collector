package com.aaa.collector.dart.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.disclosure.DisclosureInserter;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.dart.disclosure.DisclosureRow;
import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.dart.external.DartListResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DART 공시 백필 윈도우 단건 실행 서비스 (SPEC-COLLECTOR-DART-001 REQ-DART-020~023).
 *
 * <p>단일 {@code backfill_status} 항목에 대해 1 윈도우를 실행하고 anchor를 갱신한다. stale-window(신규 0건 또는 무전진) 시
 * COMPLETED로 전이한다(REQ-DART-023).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DartDisclosureBackfillWindowService {

    static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String DATA_TABLE = "disclosures";
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartDisclosureClient dartDisclosureClient;
    private final DisclosureRepository disclosureRepository;
    private final DisclosureInserter disclosureInserter;
    private final CorpCodeMappingRepository corpCodeMappingRepository;
    private final BackfillStatusRepository backfillStatusRepository;

    /**
     * 단일 종목에 대해 1 윈도우(기간 조회 + INSERT IGNORE)를 실행한다.
     *
     * <p>윈도우 크기: anchor 전날~anchor 30 달력일 이전(단순 30일 단위). stale-window 종료: 조회 결과 0건이면 COMPLETED.
     *
     * @param status 처리할 백필 상태
     * @param stockId 종목 stock_id (stocks.id)
     * @param symbol 종목 심볼(=stock_code)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 윈도우 단위 예외 격리 — REQ-DART-030
    public void executeWindow(BackfillStatus status, Long stockId, String symbol) {
        // corp_code lookup
        String corpCode = corpCodeMappingRepository.findCorpCodeByStockCode(symbol).orElse(null);
        if (corpCode == null) {
            log.warn("[dart-backfill] corp_code 매핑 없음 — symbol={}", symbol);
            backfillStatusRepository.updateError(
                    status.getId(), STATUS_IN_PROGRESS, "corp_code 매핑 없음: " + symbol);
            return;
        }

        // 윈도우 계산: anchor가 null이면 오늘부터 시작
        LocalDate endDe =
                status.getLastCollectedDate() != null
                        ? status.getLastCollectedDate().minusDays(1)
                        : LocalDate.now();
        LocalDate bgnDe = endDe.minusDays(30);

        log.debug("[dart-backfill] 윈도우 실행 — symbol={}, bgnDe={}, endDe={}", symbol, bgnDe, endDe);

        // DART API 호출 — 종목별 corp_code 지정
        List<DartListResponse.DisclosureItem> items;
        try {
            items = dartDisclosureClient.fetchAllPages(bgnDe, endDe, corpCode);
        } catch (Exception e) {
            log.warn("[dart-backfill] API 호출 실패 — symbol={}, error={}", symbol, e.getMessage());
            backfillStatusRepository.updateError(
                    status.getId(), STATUS_IN_PROGRESS, truncate(e.getMessage(), 512));
            return;
        }

        // 필터: 해당 종목만 (corp_code 지정 시 API가 필터하지만 방어적 처리)
        // REQ-INSERT-011: 유효 행 누적 후 격리 삽입
        List<DisclosureRow> batch = new ArrayList<>();
        for (DartListResponse.DisclosureItem item : items) {
            if (!symbol.equals(item.stockCode())) {
                continue;
            }
            LocalDate rceptDt = parseRceptDt(item.rceptDt());
            if (rceptDt == null) {
                continue;
            }
            batch.add(toRow(stockId, item, rceptDt));
        }

        // REQ-INSERT-011: 격리 삽입 — 독성 행 skip·잔여 행 계속·커넥션 중단 없음
        int inserted = 0;
        if (!batch.isEmpty()) {
            AtomicInteger dbFailures = new AtomicInteger();
            disclosureInserter.insertBatchIsolated(
                    batch,
                    (row, ex) -> {
                        log.warn(
                                "[dart-backfill] insertIgnore 실패 — rceptNo={}, error={}",
                                row.rceptNo(),
                                ex.getMessage());
                        dbFailures.incrementAndGet();
                    });
            inserted = batch.size() - dbFailures.get();
        }

        // anchor 전진 또는 stale-window COMPLETED 전이 (REQ-DART-023)
        // stale-window: 해당 기간 신규 공시 0건 → 최대 과거 도달로 판정
        if (inserted == 0) {
            log.info("[dart-backfill] stale-window — COMPLETED. symbol={}", symbol);
            backfillStatusRepository.updateProgress(
                    status.getId(), STATUS_COMPLETED, bgnDe, 0, inserted);
        } else {
            // anchor 전진 — bgnDe를 새 anchor로 갱신
            backfillStatusRepository.updateProgress(
                    status.getId(), STATUS_IN_PROGRESS, bgnDe, 0, inserted);
        }
    }

    private DisclosureRow toRow(
            Long stockId, DartListResponse.DisclosureItem item, LocalDate rceptDt) {
        return new DisclosureRow(
                stockId,
                item.corpCode(),
                item.stockCode(),
                item.corpCls(),
                // report_nm VARCHAR(512): 펀드명 접미가 붙은 집합투자증권 등 장문 보고서명 대응 — MySQL strict 모드
                // DataTruncationException 방지
                truncate(item.reportNm(), 512),
                item.rceptNo(),
                item.flrNm(),
                rceptDt,
                item.rm(),
                null);
    }

    private LocalDate parseRceptDt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw, DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String truncate(String msg, int maxLen) {
        if (msg == null) {
            return "";
        }
        return msg.length() > maxLen ? msg.substring(0, maxLen) : msg;
    }
}
