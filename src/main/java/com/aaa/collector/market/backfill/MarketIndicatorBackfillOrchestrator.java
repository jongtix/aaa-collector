package com.aaa.collector.market.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시장 지표 백필 오케스트레이터 (SPEC-COLLECTOR-MARKETIND-001, REQ-040~047).
 *
 * <p>cron 시 {@code target_type='MARKET_INDICATOR'} 미완료 항목을 처리한다. VIX: 전체 이력 단일 호출 후 COMPLETED.
 * USDKRW: staleWeekdayCount 기반 날짜 루프(REQ-044). 지표 단위 예외 격리(REQ-045).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorBackfillOrchestrator {

    private static final String TARGET_TYPE = "MARKET_INDICATOR";
    private static final String DATA_TABLE = "market_indicators";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** USDKRW 백필 기본 종료 임계: 연속 N회 평일 빈 결과 (REQ-044). */
    @Value("${aaa.market-indicator.backfill.usdkrw.stale-weekday-threshold:7}")
    private int staleWeekdayThreshold;

    private final BackfillStatusRepository backfillStatusRepository;
    private final VixCollectionService vixCollectionService;
    private final UsdkrwCollectionService usdkrwCollectionService;

    /**
     * 백필 시딩: USDKRW, VIX 2행 {@code insertIgnoreSeed} (REQ-050~052).
     *
     * <p>기존 행 보존(INSERT IGNORE), 누락 행만 생성한다(REQ-051).
     */
    public void seed() {
        backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, "USDKRW", DATA_TABLE);
        backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, "VIX", DATA_TABLE);
        log.info("[market-ind-backfill] 시딩 완료 — USDKRW, VIX");
    }

    /**
     * 백필 실행: 미완료 항목 처리 (REQ-040~047).
     *
     * <p>{@code STOCK} 타입은 미처리(REQ-047). 지표 단위 예외 격리(REQ-045).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 지표 단위 예외 격리 (REQ-045)
    public void runBackfill() {
        List<BackfillStatus> targets =
                backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                        List.of("PENDING", "IN_PROGRESS"), TARGET_TYPE);

        if (targets.isEmpty()) {
            log.info("[market-ind-backfill] 처리 대상 없음");
            return;
        }

        for (BackfillStatus target : targets) {
            try {
                processTarget(target);
            } catch (Exception e) {
                log.error(
                        "[market-ind-backfill] 지표 예외 — code={}, 다음 회차 재개",
                        target.getTargetCode(),
                        e);
                backfillStatusRepository.updateError(
                        target.getId(), "IN_PROGRESS", truncate(e.getMessage(), 512));
            }
        }
    }

    private void processTarget(BackfillStatus target) {
        String code = target.getTargetCode();
        log.info("[market-ind-backfill] 처리 시작 — code={}, status={}", code, target.getStatus());

        switch (code) {
            case "VIX" -> processVix(target);
            case "USDKRW" -> processUsdkrw(target);
            default -> log.warn("[market-ind-backfill] 알 수 없는 지표 코드 — skip: {}", code);
        }
    }

    /**
     * VIX 백필: 전체 이력 단일 호출 후 COMPLETED (REQ-041~043).
     *
     * <p>CBOE/FRED/Yahoo는 단일 호출 지원 소스이므로 1회 수신으로 완료한다.
     */
    private void processVix(BackfillStatus target) {
        int saved = vixCollectionService.collectHistory();
        LocalDate anchor = LocalDate.now(KST); // 최과거 대신 today — 단일 호출이므로 anchor 불필요
        backfillStatusRepository.updateProgress(target.getId(), "COMPLETED", anchor, 0, saved);
        log.info("[market-ind-backfill] VIX 백필 완료 — saved={}", saved);
    }

    /**
     * USDKRW 백필: staleWeekdayCount 기반 날짜 루프 (REQ-044).
     *
     * <p>평일 빈 배열 시 staleWeekdayCount++, 데이터 수신 시 0 리셋. N≥staleWeekdayThreshold → COMPLETED.
     * last_collected_date = 최과거 저장 거래일(anchor).
     */
    private void processUsdkrw(BackfillStatus target) {
        LocalDate cursor = LocalDate.now(KST);
        LocalDate anchor = null;
        int staleWeekdayCount = 0;
        int totalSaved = 0;

        while (staleWeekdayCount < staleWeekdayThreshold) {
            // 주말 skip
            if (isWeekend(cursor)) {
                cursor = cursor.minusDays(1);
                continue;
            }

            int saved = usdkrwCollectionService.collectDailyForBackfill(cursor);
            if (saved > 0) {
                totalSaved += saved;
                staleWeekdayCount = 0;
                anchor = (anchor == null || cursor.isBefore(anchor)) ? cursor : anchor;
                backfillStatusRepository.updateProgress(
                        target.getId(), "IN_PROGRESS", anchor, 0, totalSaved);
                log.debug("[usdkrw-backfill] 수집 완료 — date={}, saved={}", cursor, saved);
            } else {
                staleWeekdayCount++;
                log.debug(
                        "[usdkrw-backfill] 빈 결과 — date={}, stale={}/{}",
                        cursor,
                        staleWeekdayCount,
                        staleWeekdayThreshold);
            }
            cursor = cursor.minusDays(1);
        }

        LocalDate lastCollected = anchor != null ? anchor : LocalDate.now(KST);
        backfillStatusRepository.updateProgress(
                target.getId(), "COMPLETED", lastCollected, 0, totalSaved);
        log.info(
                "[market-ind-backfill] USDKRW 백필 완료 — totalSaved={}, anchor={}",
                totalSaved,
                anchor);
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private String truncate(String msg, int max) {
        if (msg == null) {
            return "";
        }
        return msg.length() > max ? msg.substring(0, max) : msg;
    }
}
