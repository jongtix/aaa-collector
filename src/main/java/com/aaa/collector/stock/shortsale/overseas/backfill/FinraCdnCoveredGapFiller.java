package com.aaa.collector.stock.shortsale.overseas.backfill;

import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.backfill.CoveredGapFiller;
import com.aaa.collector.stock.Stock;
import java.time.LocalDate;
import java.util.Map;

/**
 * FINRA Daily 전역 앵커 정방향 갭 walk 1스텝 실행체 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-051/-051a, AC-12).
 *
 * <p>단일 날짜형 소스({@link com.aaa.collector.backfill.CoveredTrackingEligibility.Mode#SINGLE_DATE}) —
 * {@code cursor} 하루치를 {@link FinraCdnDailyFileClient#fetch(LocalDate)}(CDN 파일 경로)로 취득하고, {@link
 * FinraCdnShortSaleBackfillOrchestrator#loadDate}를 그대로 재사용해 적재한다(REQ-CVR-051, 신규 fetch 메서드 없음). 라이브
 * REST({@code FinraShortSaleClient.fetchRegShoDaily})는 이 클래스가 아예 의존하지 않으므로 구조적으로 호출될 수
 * 없다(REQ-CVR-051a, 소스 이중성 — 백필/갭 walk는 CDN 전용).
 *
 * <p>{@code symbolMap}(활성 미국 tradable 종목)은 호출자(TASK-007 오케스트레이터)가 사이클당 1회 조회해 주입한다 — {@code
 * persistStep} 매 호출마다 재조회하지 않는다(불필요한 DB 조회 방지, 기존 {@code run()}의 사이클당-1회 조회 패턴과 동일).
 */
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
public class FinraCdnCoveredGapFiller implements CoveredGapFiller {

    private final FinraCdnDailyFileClient client;
    private final FinraCdnShortSaleBackfillOrchestrator orchestrator;
    private final Map<String, Stock> symbolMap;

    public FinraCdnCoveredGapFiller(
            FinraCdnDailyFileClient client,
            FinraCdnShortSaleBackfillOrchestrator orchestrator,
            Map<String, Stock> symbolMap) {
        this.client = client;
        this.orchestrator = orchestrator;
        this.symbolMap = Map.copyOf(symbolMap);
    }

    /**
     * {@code cursor} 하루치를 CDN에서 취득·적재하고 kept/raw를 {@link CoveredFillResult}로 매핑한다.
     *
     * <p>{@link FinraCdnFetchResult.Absent}(휴장·미생성·일시적 오류 불문)는 {@code kept=raw=0}으로 매핑한다 — {@link
     * com.aaa.collector.backfill.CoveredRangeService#executeStep}이 이 경우 전진 없이 조용히 종료하므로(REQ-CVR-030
     * kept 확인 필요조건), 일시적 오류도 결과적으로 backward walk의 "앵커 미전진·다음 크론 재시도"와 동일하게 처리된다(별도 예외 처리 불필요, 기존
     * backward walk 경로 무변경).
     *
     * @param cursor 이번 스텝의 거래일
     * @return kept/raw/filledUntil(=cursor, 단일 날짜형이라 스텝=1일)
     */
    @Override
    public CoveredFillResult persistStep(LocalDate cursor) {
        FinraCdnFetchResult fetchResult = client.fetch(cursor);
        if (fetchResult instanceof FinraCdnFetchResult.Found found) {
            FinraCdnShortSaleBackfillOrchestrator.DailyLoadOutcome outcome =
                    orchestrator.loadDate(cursor, found.fileBodies(), symbolMap);
            return new CoveredFillResult(outcome.kept(), outcome.raw(), cursor);
        }
        return new CoveredFillResult(0, 0, cursor);
    }
}
