package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.DividendBackfillFetch;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.Stock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code corporate_events_dividend} data_table 라우팅 핸들러 (SPEC-COLLECTOR-BACKFILL-ROUTER-001 §B).
 *
 * <p>기존 {@link BackfillWindowExecutor} routeFetch/routePersist의 {@code case
 * "corporate_events_dividend"} 본체를 그대로 이관한다.
 *
 * <p>{@code windowAdvancer}를 {@link BackfillWindowExecutor}와 별개로 독립 주입받는다 — anchor 계산용 잔류 보유와 이
 * 핸들러의 fetch 본체 호출은 각자 정당한 이중 보유다(plan.md §B "windowAdvancer 이중 보유 확인").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorporateEventsDividendRouteHandler implements BackfillRouteHandler {

    private final DividendScheduleCollectionService dividendService;
    private final BackfillWindowAdvancer windowAdvancer;

    @Override
    public String dataTable() {
        return "corporate_events_dividend";
    }

    // @MX:NOTE SPEC-COLLECTOR-BACKFILL-009 W2 — 종목지정 현금배당 백필(SPLIT과 별도 data_table 논리 키).
    // from-date=고정 플로어(REQ-BACKFILL-126). SPLIT(rev-split) 분기 불변(REQ-BACKFILL-144).
    // SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-011: to-date=today 고정 버그를 anchor(윈도우 진행점)로
    // 교체 — GROUP_A 이월 워크가 실제로 전진하도록 복구.
    @Override
    public Object fetch(
            BackfillStatus resolved, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        return dividendService.fetchWindowForBackfill(
                stock, session, windowAdvancer.groupAFromDate(), anchor);
    }

    @Override
    public BackfillWindowResult persist(BackfillStatus status, Stock stock, Object fetchDto) {
        if (fetchDto == null) {
            log.warn(
                    "[backfill] corporate_events_dividend persistWindow 스킵 (null fetchDto) —"
                            + " symbol={}",
                    status.getTargetCode());
            return BackfillWindowResult.EMPTY;
        }
        if (fetchDto instanceof DividendBackfillFetch f) {
            return dividendService.persistWindowForBackfill(f);
        }
        log.warn(
                "[backfill] corporate_events_dividend persistWindow 스킵 (알 수 없는 fetchDto 타입) —"
                        + " symbol={}, type={}",
                status.getTargetCode(),
                fetchDto.getClass().getSimpleName());
        return BackfillWindowResult.EMPTY;
    }
}
