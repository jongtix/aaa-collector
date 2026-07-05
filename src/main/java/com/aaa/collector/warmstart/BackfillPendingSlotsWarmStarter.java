package com.aaa.collector.warmstart;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 기존 리포지토리 상태 카운트로 {@code aaa_collector_backfill_pending_slots} 게이지를 warm-start한다
 * (SPEC-OBSV-WATERMARK-001 REQ-WM-029, MI-02).
 *
 * <p>재시작~첫 오케스트레이터 실행 전까지 pending_slots가 0(사전 등록값)에 머물러 정체를 감지하지 못하는 창을 제거한다. 조회 실패 시 warn 로깅 후 계속
 * 진행한다(비차단).
 */
// @MX:NOTE: [AUTO] 부팅 시 backfill_pending_slots 게이지 warm-start 진입점
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-029, MI-02
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillPendingSlotsWarmStarter implements ApplicationRunner {

    private static final String TARGET_TYPE_STOCK = "STOCK";
    private static final List<BackfillStatusType> PENDING_STATUSES =
            List.of(BackfillStatusType.PENDING, BackfillStatusType.IN_PROGRESS);

    private final BackfillMetrics backfillMetrics;
    private final BackfillStatusRepository backfillStatusRepository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            long pending =
                    backfillStatusRepository.countByStatusInAndTargetType(
                            PENDING_STATUSES, TARGET_TYPE_STOCK);
            backfillMetrics.setPendingSlots(pending);
            log.info("backfill_pending_slots warm-start 완료 — pending={}", pending);
        } catch (DataAccessException e) {
            log.warn("backfill_pending_slots warm-start 실패 — 0 유지, error={}", e.getMessage());
        }
    }
}
