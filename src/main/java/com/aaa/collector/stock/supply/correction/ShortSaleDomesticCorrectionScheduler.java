package com.aaa.collector.stock.supply.correction;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code short_sell_vol_rate} 2-트랙 정정(Track 1 + Track 2) cron 진입점
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-030~032, REQ-T0R-043~045, plan.md §M7).
 *
 * <p>{@code FinraCdnShortSaleBackfillScheduler}(SPEC-COLLECTOR-BACKFILL-008 T6) 구조(cron + {@link
 * AtomicBoolean} single-flight 가드)를 그대로 답습한다. 매 실행마다 Track 1({@link
 * ShortSaleVolRateCorrectionService#correctLegacyBacklog(T0rGateState)})을 먼저, Track 2({@link
 * ShortSaleVolRateCorrectionService#verifyRecentInserts(T0rGateState)})를 그 다음 순차 실행한다(REQ-SSVC-031,
 * -034) — Track 1의 원자적 쓰기가 {@code vol_rate_verified_at}도 함께 기록하므로(REQ-SSVC-036), 같은 실행 사이클 안에서
 * Track 2가 방금 정정된 행을 중복 재조회하지 않는다(순서는 성능상 이점, 정합성 필수조건은 아니다).
 *
 * <p>매 실행 시작 시 {@code t0r_correction_status}를 1회 조회해 {@link T0rGateState}를 구성하고, Track 1·Track 2
 * 양쪽에 동일하게 전달한다(REQ-T0R-043~045) — {@code completed_at}이 NULL이면 닫히는 창 구간의 행이 defer된다.
 *
 * <p>{@code daily_ohlcv} 배치({@code BatchCrons.DOMESTIC_DAILY_CHAIN_CRON}, 평일 19:00 KST) 적재 완료 이후
 * 시각에 발화해야 한다(REQ-SSVC-030). 요일 제한 없이 매일 발화한다 — Track 1 레거시 백로그·REVISION_SUSPECTED 자동 재시도는 요일과 무관하게
 * 처리 대상이 존재할 수 있다({@code FinraCdnShortSaleBackfillScheduler}와 동일 근거).
 */
@Slf4j
@Component
@RequiredArgsConstructor
// @MX:NOTE: [AUTO] SSVC 2-트랙 정정 배치 진입점 — Track1→Track2 순차, T0R 게이트 매 실행 조회
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-030~032, REQ-T0R-043~045,
// plan.md §M7
public class ShortSaleDomesticCorrectionScheduler {

    private final ShortSaleVolRateCorrectionService correctionService;
    private final T0rCorrectionStatusRepository t0rCorrectionStatusRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * SSVC 정정 배치 cron 진입점 — 기본 매일 20:00 KST(daily_ohlcv 19:00 KST 적재 완료 이후 여유, REQ-SSVC-030).
     *
     * <p>실행 중(single-flight) 재발화는 스킵하고, 내부 예외는 흡수해 스케줄러 스레드를 보호한다({@code FinraCdn} 패턴 답습).
     */
    @Scheduled(
            cron = "${aaa.shortsale-domestic.correction.cron:0 0 20 * * *}",
            zone = "${aaa.shortsale-domestic.correction.zone:Asia/Seoul}")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[ssvc-correction-scheduler] 이전 실행 중 — 중복 실행 스킵");
            return;
        }
        try {
            T0rGateState gate = resolveGate();
            ShortSaleVolRateCorrectionResult track1Result =
                    correctionService.correctLegacyBacklog(gate);
            ShortSaleVolRateCorrectionResult track2Result =
                    correctionService.verifyRecentInserts(gate);
            log.info(
                    "[ssvc-correction-scheduler] 실행 완료 —" + " gate.active={}, track1={}, track2={}",
                    gate.active(),
                    track1Result,
                    track2Result);
        } catch (Exception e) {
            log.error("[ssvc-correction-scheduler] 정정 배치 실행 오류 — 스케줄러 스레드 보호", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * {@code t0r_correction_status} 1행을 조회해 {@link T0rGateState}를 구성한다(REQ-T0R-043~045).
     *
     * <p>마커 행이 없으면(마이그레이션 미적용 등 방어적 상황) 게이트를 비활성화해 정정 자체가 멈추지 않도록 한다 — 정상 배포 환경에서는 V46이 항상 1행을
     * 시딩하므로 발생하지 않는다.
     */
    private T0rGateState resolveGate() {
        Optional<T0rCorrectionStatus> status =
                t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID);
        if (status.isEmpty()) {
            log.warn("[ssvc-correction-scheduler] t0r_correction_status 행 없음 — 게이트 비활성으로 진행");
            return T0rGateState.inactive();
        }
        T0rCorrectionStatus row = status.get();
        boolean active = row.getCompletedAt() == null;
        return new T0rGateState(active, row.getClosingWindowEndDate());
    }
}
