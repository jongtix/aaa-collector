package com.aaa.collector.stock.shortsale.overseas.backfill;

import com.aaa.collector.stock.Stock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 하루치 CDN 파일 본문을 적재하는 계약 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-051).
 *
 * <p>{@link FinraCdnShortSaleBackfillOrchestrator#loadDate}를 함수형 인터페이스로 노출한다 — {@link
 * FinraCdnCoveredGapFiller}가 오케스트레이터 구체 타입을 직접 참조하지 않고 메서드 참조({@code orchestrator::loadDate})만
 * 주입받도록 해, 오케스트레이터·필러·러너 3자 간 순환 의존 없이 결합도를 낮춘다(코드리뷰 — PMD CouplingBetweenObjects 완화).
 */
@FunctionalInterface
public interface FinraCdnDailyLoader {

    /**
     * 하루치 파일 본문(다중 시설 가능)을 종목별로 합산·매칭·적재한다.
     *
     * @param date 대상 거래일
     * @param fileBodies CNMS 또는 시설 파일 본문 목록
     * @param symbolMap 활성 미국 tradable 종목 심볼 맵
     * @return kept/raw/skipped/unmatched
     */
    FinraCdnDailyLoadOutcome loadDate(
            LocalDate date, List<String> fileBodies, Map<String, Stock> symbolMap);
}
