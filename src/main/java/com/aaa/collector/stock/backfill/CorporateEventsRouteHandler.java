package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.RevSplitBackfillFetch;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitBackfillFetch;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code corporate_events} data_table 라우팅 핸들러 (SPEC-COLLECTOR-BACKFILL-ROUTER-001 §B).
 *
 * <p>기존 {@link BackfillWindowExecutor} routeFetch/routePersist의 {@code case "corporate_events"} 본체를
 * 그대로 이관한다. 시장별 소스 분기(국내 RevSplit / 해외 OverseasSplit)는 이 핸들러 내부에서 유지한다(REQ-ROUTER-021).
 *
 * <p>{@code windowAdvancer}를 {@link BackfillWindowExecutor}와 별개로 독립 주입받는다 — anchor 계산용 잔류 보유와 이
 * 핸들러의 fetch 본체 호출은 각자 정당한 이중 보유다(plan.md §B "windowAdvancer 이중 보유 확인").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorporateEventsRouteHandler implements BackfillRouteHandler {

    /** KST 타임존 — to-date 계산 기준 (CLAUDE.md KST 통일, REQ-BACKFILL-095). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 미국 시장 집합 — corporate_events 소스 라우팅에 사용. */
    private static final Set<Market> OVERSEAS_MARKETS =
            Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    private final RevSplitCollectionService revSplitService;
    private final OverseasSplitCollectionService overseasSplitService;
    private final BackfillWindowAdvancer windowAdvancer;

    @Override
    public String dataTable() {
        return "corporate_events";
    }

    // @MX:NOTE SPEC-COLLECTOR-BACKFILL-007 W3 + SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-063 —
    // 종목지정 SPLIT 백필. from-date=고정 플로어(REQ-BACKFILL-094), to-date=today(KST, REQ-BACKFILL-095).
    // 시장별 소스 분기: 미국→CTRGT011R(OverseasSplitCollectionService), 국내→HHKDB669105C0(RevSplit).
    @Override
    public Object fetch(
            BackfillStatus resolved, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        LocalDate floor = windowAdvancer.groupAFromDate();
        LocalDate to = LocalDate.now(KST);
        if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
            return overseasSplitService.fetchWindowForBackfill(stock, session, floor, to);
        }
        return revSplitService.fetchWindowForBackfill(stock, session, floor, to);
    }

    @Override
    public BackfillWindowResult persist(BackfillStatus status, Stock stock, Object fetchDto) {
        if (fetchDto == null) {
            log.warn(
                    "[backfill] corporate_events persistWindow 스킵 (null fetchDto) — symbol={}",
                    status.getTargetCode());
            return BackfillWindowResult.EMPTY;
        }
        return switch (fetchDto) {
            // SPEC-COLLECTOR-BACKFILL-007 W4 — 국내 매핑+CorporateEventInserter INSERT IGNORE → 종료 입력
            case RevSplitBackfillFetch f -> revSplitService.persistWindowForBackfill(f);
            // SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-063 — 미국 SPLIT 매핑+INSERT IGNORE → 종료 입력
            case OverseasSplitBackfillFetch f -> overseasSplitService.persistWindowForBackfill(f);
            default -> {
                log.warn(
                        "[backfill] corporate_events persistWindow 스킵 (알 수 없는 fetchDto 타입) —"
                                + " symbol={}, type={}",
                        status.getTargetCode(),
                        fetchDto.getClass().getSimpleName());
                yield BackfillWindowResult.EMPTY;
            }
        };
    }
}
