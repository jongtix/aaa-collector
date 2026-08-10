package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.rights.OverseasDividendBackfillFetch;
import com.aaa.collector.stock.rights.OverseasDividendBackfillService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code corporate_events_dividend_overseas} data_table 라우팅 핸들러 (SPEC-COLLECTOR-BACKFILL-ROUTER-001
 * §B).
 *
 * <p>기존 {@link BackfillWindowExecutor} routeFetch/routePersist의 {@code case
 * "corporate_events_dividend_overseas"} 본체를 그대로 이관한다.
 *
 * <p>{@code windowAdvancer}를 {@link BackfillWindowExecutor}와 별개로 독립 주입받는다 — anchor 계산용 잔류 보유와 이
 * 핸들러의 fetch 본체 호출은 각자 정당한 이중 보유다(plan.md §B "windowAdvancer 이중 보유 확인").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorporateEventsDividendOverseasRouteHandler implements BackfillRouteHandler {

    /** KST 타임존 — to-date 계산 기준 (CLAUDE.md KST 통일, REQ-BACKFILL-095). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OverseasDividendBackfillService overseasDividendBackfillService;
    private final BackfillWindowAdvancer windowAdvancer;

    @Override
    public String dataTable() {
        return "corporate_events_dividend_overseas";
    }

    // @MX:NOTE SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 REQ-ODW-080 — 종목지정 해외 현금배당 백필.
    // "corporate_events"(SPLIT) case와 구조적으로 대칭(plan.md §C-4): from-date=고정 플로어,
    // to-date=today(KST). 국내 대응 항목이 없어 시장 분기 없이 단일 서비스로 라우팅한다.
    // 코드리뷰 W-2a: 고정 플로어(1950-01-01)를 모든 종목에 그대로 적용하면 대부분의 청크가 상장일 이전 빈
    // 구간을 조회해 종목당 ~39회 순차 청크 호출이 발생한다(GROUP_B GROUP_B_GLOBAL_FLOOR 클램프 선례와 동일
    // 아이디어). listedDate가 anchor(고정 플로어)보다 최근이면 listedDate를 사용하고, listedDate가 anchor보다
    // 과거(비정상 데이터)이거나 null이면 기존 anchor를 그대로 유지한다.
    @Override
    public Object fetch(
            BackfillStatus resolved, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        LocalDate fixedFloor = windowAdvancer.groupAFromDate();
        LocalDate floor =
                stock.getListedDate() != null && stock.getListedDate().isAfter(fixedFloor)
                        ? stock.getListedDate()
                        : fixedFloor;
        LocalDate to = LocalDate.now(KST);
        return overseasDividendBackfillService.fetchWindowForBackfill(stock, session, floor, to);
    }

    @Override
    public BackfillWindowResult persist(BackfillStatus status, Stock stock, Object fetchDto) {
        if (fetchDto == null) {
            log.warn(
                    "[backfill] corporate_events_dividend_overseas persistWindow 스킵 (null"
                            + " fetchDto) — symbol={}",
                    status.getTargetCode());
            return BackfillWindowResult.EMPTY;
        }
        if (fetchDto instanceof OverseasDividendBackfillFetch f) {
            return overseasDividendBackfillService.persistWindowForBackfill(f);
        }
        log.warn(
                "[backfill] corporate_events_dividend_overseas persistWindow 스킵 (알 수 없는"
                        + " fetchDto 타입) — symbol={}, type={}",
                status.getTargetCode(),
                fetchDto.getClass().getSimpleName());
        return BackfillWindowResult.EMPTY;
    }
}
