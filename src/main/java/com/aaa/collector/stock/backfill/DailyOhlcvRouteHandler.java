package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvFetch;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code daily_ohlcv} data_table 라우팅 핸들러 (SPEC-COLLECTOR-BACKFILL-ROUTER-001 §B).
 *
 * <p>기존 {@link BackfillWindowExecutor} routeFetch/routePersist의 {@code case "daily_ohlcv"} 본체를 그대로
 * 이관한다. 시장별 분기(국내/해외)는 이 핸들러 내부에서 유지한다(REQ-ROUTER-021).
 *
 * <p>GROUP_A 종료 확인 게이트({@code buildEnvelope}, {@code rawRowCount < 100} 판정)는 이 핸들러로 이관하지 않는다 —
 * {@link BackfillWindowExecutor#fetchWindow}에 그대로 잔류한다(plan.md §B).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyOhlcvRouteHandler implements BackfillRouteHandler {

    /** 미국 시장 집합 — daily_ohlcv 수집 서비스 라우팅에 사용. */
    private static final Set<Market> OVERSEAS_MARKETS =
            Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    private final DomesticDailyOhlcvCollectionService domesticOhlcvService;
    private final OverseasDailyOhlcvCollectionService overseasOhlcvService;
    private final BackfillWindowAdvancer windowAdvancer;

    @Override
    public String dataTable() {
        return "daily_ohlcv";
    }

    @Override
    public Object fetch(
            BackfillStatus resolved, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
            return overseasOhlcvService.fetchWindow(anchor, stock, session);
        }
        // @MX:NOTE SPEC-COLLECTOR-BACKFILL-005 고정 플로어 — 상폐 종목 초기 윈도우 오종료 해소
        LocalDate from = windowAdvancer.groupAFromDate();
        return domesticOhlcvService.fetchWindow(from, anchor, stock, session);
    }

    @Override
    public BackfillWindowResult persist(BackfillStatus status, Stock stock, Object fetchDto) {
        if (fetchDto == null) {
            log.warn(
                    "[backfill] daily_ohlcv persistWindow 스킵 (null fetchDto) — symbol={}",
                    status.getTargetCode());
            return BackfillWindowResult.EMPTY;
        }
        return switch (fetchDto) {
            case DomesticDailyOhlcvFetch f -> domesticOhlcvService.persistWindow(stock, f);
            case OverseasDailyOhlcvFetch f -> overseasOhlcvService.persistWindow(stock, f);
            default -> {
                log.warn(
                        "[backfill] daily_ohlcv persistWindow 스킵 (알 수 없는 fetchDto 타입) —"
                                + " symbol={}, type={}",
                        status.getTargetCode(),
                        fetchDto.getClass().getSimpleName());
                yield BackfillWindowResult.EMPTY;
            }
        };
    }
}
