package com.aaa.collector.macro.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.ecos.EcosSeriesConfig;
import com.aaa.collector.macro.fred.FredCollectionService;
import com.aaa.collector.macro.fred.FredSeriesConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 거시경제 지표 백필 오케스트레이터 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-051~060).
 *
 * <p>진입부에서 13개 시리즈 lazy 시딩 후 PENDING/IN_PROGRESS 항목을 처리한다. 단일 전체 호출(DP1) — ECOS는 startNo=1,
 * endNo=99999, FRED는 limit=100000으로 전체 이력을 1회 수신한다.
 *
 * <p>STOCK/MARKET_INDICATOR는 {@link BackfillStatusRepository#findByStatusInAndTargetTypeOrderById}의
 * target_type 필터로 처리하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroIndicatorBackfillOrchestrator {

    private static final String TARGET_TYPE = "MACRO_INDICATOR";
    private static final String DATA_TABLE = "macro_indicators";

    /** ECOS indicator_code 집합 — 빠른 판별용. */
    private static final Set<String> ECOS_CODES =
            EcosSeriesConfig.ALL.stream()
                    .map(EcosSeriesConfig.Series::indicatorCode)
                    .collect(Collectors.toUnmodifiableSet());

    private final BackfillStatusRepository backfillStatusRepository;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final EcosCollectionService ecosCollectionService;
    private final FredCollectionService fredCollectionService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 백필 실행 진입점.
     *
     * <ol>
     *   <li>13개 시리즈 lazy 시딩 (INSERT IGNORE)
     *   <li>PENDING/IN_PROGRESS 항목 순차 처리
     *   <li>항목별 전체 이력 수집 후 COMPLETED 갱신 또는 예외 시 FAILED 갱신
     * </ol>
     */
    public void run() {
        seedAll();

        List<BackfillStatus> pending =
                backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                        List.of(BackfillStatusType.PENDING, BackfillStatusType.IN_PROGRESS),
                        TARGET_TYPE);

        log.info("[macro-backfill] 처리 대상 {} 건", pending.size());

        for (BackfillStatus status : pending) {
            processEntry(status);
        }

        log.info("[macro-backfill] 완료");
    }

    /** 13개 시리즈(8 ECOS + 5 FRED)를 lazy 시딩한다. */
    private void seedAll() {
        for (EcosSeriesConfig.Series s : EcosSeriesConfig.ALL) {
            backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, s.indicatorCode(), DATA_TABLE);
        }
        for (FredSeriesConfig.Series s : FredSeriesConfig.ALL) {
            backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, s.indicatorCode(), DATA_TABLE);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void processEntry(BackfillStatus status) {
        String indicatorCode = status.getTargetCode();
        log.info("[macro-backfill] 처리 시작 — code={}", indicatorCode);

        try {
            MacroCollectionResult result = collectAll(indicatorCode);
            LocalDate minDate =
                    macroIndicatorRepository
                            .findMinTradeDateByIndicatorCode(indicatorCode)
                            .orElse(LocalDate.now());

            transactionTemplate.executeWithoutResult(
                    tx -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(status.getId()).orElseThrow();
                        managed.advance(
                                BackfillStatusType.COMPLETED, minDate, 0, result.succeeded());
                    });

            log.info(
                    "[macro-backfill] 완료 — code={}, attempted={}, succeeded={}",
                    indicatorCode,
                    result.attempted(),
                    result.succeeded());
        } catch (Exception e) {
            log.error("[macro-backfill] 예외 — code={}", indicatorCode, e);
            final String errMsg = truncate(e.getMessage(), 512);
            transactionTemplate.executeWithoutResult(
                    tx -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(status.getId()).orElseThrow();
                        managed.fail(BackfillStatusType.FAILED, errMsg);
                    });
        }
    }

    private MacroCollectionResult collectAll(String indicatorCode) {
        if (ECOS_CODES.contains(indicatorCode)) {
            return ecosCollectionService.collectAll();
        } else {
            return fredCollectionService.collectAll();
        }
    }

    private static String truncate(String msg, int maxLen) {
        if (msg == null) {
            return "";
        }
        return msg.length() > maxLen ? msg.substring(0, maxLen) : msg;
    }
}
