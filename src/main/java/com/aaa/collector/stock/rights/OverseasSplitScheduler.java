package com.aaa.collector.stock.rights;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService.OverseasSplitCollectionResult;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 해외 액면분할·병합(SPLIT) 수집 스케줄러 (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-002).
 *
 * <p>평일 17:00 ET({@code 0 0 17 * * MON-FRI}, {@code America/New_York}) — 미국 일봉 16:30 ET 슬롯 이후 일 1회
 * 트리거. 해외 배당 스케줄러({@link OverseasRightsScheduler})와 동일 트리거를 쓰되, 관심사 분리·fail-closed 격리를 위해 별도
 * sibling 스케줄러로 둔다(Phase 1 전략 결정 — 배당 배치 예외가 분할 수집을 막지 않는다).
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008). {@code
 * America/New_York} zone이 서머타임(EDT/EST)을 런타임 자동 처리한다.
 *
 * <p>수집 중 예외가 발생해도 흡수하여 스케줄러 스레드가 종료되지 않게 한다. 멱등 저장(INSERT IGNORE)이라 매일 재수집해도 중복이 증가하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverseasSplitScheduler {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * BatchMetrics 배치 라벨 (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-013). warm-start=O(REQ-XR-018).
     */
    private static final String BATCH_LABEL = "overseas-split";

    private final OverseasSplitCollectionService collectionService;
    private final Clock clock;
    private final BatchMetrics batchMetrics;

    /**
     * 해외 분할·병합 수집 배치 진입점 (REQ-OSPLIT-002).
     *
     * <p>예외 흡수: 수집 단계의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(다음 실행 때 재시도). 로그 기준일은 ET 거래일이다 — cron 17:00
     * ET(=서버 KST 익일) 발화 시각을 ET zone으로 환산한다. Clock은 테스트에서 {@code Clock.fixed(...)}로 대체된다. 정상 완료 시
     * {@code overseas-split} 배치 라벨로 실행 신선도를 계측한다 — 예외 종료 시에는 stamp하지 않는다(REQ-XR-013, REQ-XR-009).
     */
    @Scheduled(cron = BatchCrons.OVERSEAS_SPLIT_CRON, zone = BatchCrons.OVERSEAS_SPLIT_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 해외 배당 스케줄러와 동일 패턴
    public void collectSplits() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ET);
        log.info("[overseas-split] 수집 배치 시작 — {}", today);
        try {
            OverseasSplitCollectionResult result = collectionService.collect();
            log.info(
                    "[overseas-split] 수집 배치 완료 — succeededRows={}, prefetchTruncated={}, prefetchFailed={}",
                    result.succeededRows(),
                    result.prefetchTruncated(),
                    result.prefetchFailed());
            // 정상 완료 시에만 stamp — 예외 종료 시 미-stamp 계약(REQ-XR-009). skip=정상 필터, fail=DB 적재 실패 행.
            long skip =
                    (long) result.skippedUnconfirmed()
                            + result.skippedUntracked()
                            + result.skippedUnparsableDate()
                            + result.skippedNoWeekday()
                            + result.skippedInvalidRate();
            long fail = result.skippedDbFailure();
            batchMetrics.recordCompletion(
                    BATCH_LABEL,
                    result.succeededRows() + skip + fail,
                    result.succeededRows(),
                    fail,
                    skip);
        } catch (Exception e) {
            log.error("[overseas-split] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }
}
