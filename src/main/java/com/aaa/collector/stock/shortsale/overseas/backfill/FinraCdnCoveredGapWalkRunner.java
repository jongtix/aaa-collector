package com.aaa.collector.stock.shortsale.overseas.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.stock.Stock;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FINRA Daily 전역 앵커 정방향 갭 walk 실행 협력자 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-011, -070, -071).
 *
 * <p>{@link FinraCdnShortSaleBackfillOrchestrator}로부터 {@code CoveredRangeService}/{@code
 * FinraCdnCoveredGapFiller} 의존성을 분리해 낸 협력 클래스다(코드리뷰 — PMD CouplingBetweenObjects 완화, 억제 주석 대신 구조
 * 추출). 책임 분리: 오케스트레이터=전역 앵커 조회+생명주기, 이 클래스=이미 조회된 상태값 + 필러로 walk 실행.
 *
 * <p>DP-4 트랜잭션 격리: {@link CoveredRangeService#walkGapForward}는 스텝마다 자신의 {@code TransactionTemplate}
 * 으로 별도 트랜잭션을 커밋한다(TASK-003 원자성 단위) — 오케스트레이터의 backward walk 커밋({@code
 * transactionTemplate.executeWithoutResult})과 완전히 독립된 최상위 호출이라 두 갱신이 서로 얽히지 않는다.
 */
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
@Slf4j
@Component
@RequiredArgsConstructor
public class FinraCdnCoveredGapWalkRunner {

    private final CoveredRangeService coveredRangeService;
    private final FinraCdnDailyFileClient client;

    /**
     * 전역 앵커 1행에 대해 정방향 갭 walk를 수행한다 (REQ-CVR-071 — 종목별 신규 행 생성 절대 금지, BACKFILL-008 불변식).
     *
     * <p>갭 walk 예외는 이 메서드 안에서 격리해(REQ-045 정신 재사용) 호출자의 backward walk 결과에 영향을 주지 않는다.
     *
     * @param anchor 이미 조회된 전역 앵커 상태(오케스트레이터가 재조회해 전달)
     * @param today 갭 walk 목표 상한(오늘)
     * @param loader 하루치 적재 위임(오케스트레이터의 {@code loadDate} 메서드 참조)
     * @param symbolMap 활성 미국 tradable 종목 심볼 맵(사이클당 1회 조회분 재사용)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 갭 walk 예외 격리 — 호출자의 백필 사이클을 막지 않음
    public void runFor(
            BackfillStatus anchor,
            LocalDate today,
            FinraCdnDailyLoader loader,
            Map<String, Stock> symbolMap) {
        try {
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, symbolMap);
            coveredRangeService.walkGapForward(anchor, filler, today);
        } catch (Exception e) {
            log.error("[finra-cdn-backfill] 갭 walk 예외 — 다음 회차 재개", e);
        }
    }
}
