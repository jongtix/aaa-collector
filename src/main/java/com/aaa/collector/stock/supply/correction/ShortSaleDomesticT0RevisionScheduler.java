package com.aaa.collector.stock.supply.correction;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code short_sale_domestic} T+0 예비치 소급 정정(근본원인 B, aaa-infra#133) cron 진입점
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-030, plan.md §M7).
 *
 * <p>{@code FinraCdnShortSaleBackfillScheduler} 구조(cron + {@link AtomicBoolean} single-flight 가드)를
 * 그대로 답습한다. 매 실행마다 {@code t0r_correction_status.closing_window_end_date}를 조회해(캐싱 없이, "대상 재확인 절차"
 * plan.md §M5) {@link ShortSaleDomesticT0RevisionCorrectionService#correctT0Revisions(LocalDate)}에
 * 그대로 전달한다.
 *
 * <p>REQ-T0R-011의 "닫히는 창"이 확정적으로 닫히면(M2 배포 확인 후) 대상 조회 조건({@code DATE(created_at) = trade_date}인
 * T+0 시그니처 행)이 자연히 소진돼 이 스케줄러는 매 실행 0건 처리(no-op)로 수렴한다 — 별도 해제 로직은 두지 않는다(멱등, 이 SPEC 범위 밖).
 *
 * <p>T0R 완료 마커 게이트({@link T0rGateState})를 참조하지 않는다 — 이 스케줄러 자체가 그 마커를 완료시키는 소급 정정의 주체이므로, SSVC 정정
 * 배치({@code ShortSaleDomesticCorrectionScheduler})와 달리 게이트 검사 대상이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
// @MX:NOTE: [AUTO] T0R 소급 정정 배치 진입점 — 매 실행 closing_window_end_date 재조회(캐싱 없음), 닫히는 창이 닫히면
// 자연 소진(no-op)
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-011, -030, plan.md §M5/§M7
public class ShortSaleDomesticT0RevisionScheduler {

    private final ShortSaleDomesticT0RevisionCorrectionService t0RevisionCorrectionService;
    private final T0rCorrectionStatusRepository t0rCorrectionStatusRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * T0R 소급 정정 배치 cron 진입점 — 기본 매일 20:30 KST.
     *
     * <p>실행 중(single-flight) 재발화는 스킵하고, 내부 예외는 흡수해 스케줄러 스레드를 보호한다({@code FinraCdn} 패턴 답습).
     */
    @Scheduled(
            cron = "${aaa.shortsale-domestic.t0r-revision.cron:0 30 20 * * *}",
            zone = "${aaa.shortsale-domestic.t0r-revision.zone:Asia/Seoul}")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[t0r-revision-scheduler] 이전 실행 중 — 중복 실행 스킵");
            return;
        }
        try {
            Optional<LocalDate> closingWindowEndDate = resolveClosingWindowEndDate();
            if (closingWindowEndDate.isEmpty()) {
                log.warn("[t0r-revision-scheduler] t0r_correction_status 행 없음 — 이번 실행 skip");
                return;
            }
            ShortSaleT0RevisionCorrectionResult result =
                    t0RevisionCorrectionService.correctT0Revisions(closingWindowEndDate.get());
            log.info("[t0r-revision-scheduler] 실행 완료 — result={}", result);
        } catch (Exception e) {
            log.error("[t0r-revision-scheduler] 소급 정정 실행 오류 — 스케줄러 스레드 보호", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * {@code t0r_correction_status.closing_window_end_date}를 매 실행 다시 조회한다(캐싱 없음 — "대상 재확인 절차",
     * plan.md §M5).
     */
    private Optional<LocalDate> resolveClosingWindowEndDate() {
        return t0rCorrectionStatusRepository
                .findById(T0rCorrectionStatus.SINGLETON_ID)
                .map(T0rCorrectionStatus::getClosingWindowEndDate);
    }
}
