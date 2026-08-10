package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceFetch;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code credit_balance} data_table 라우팅 핸들러 (SPEC-COLLECTOR-BACKFILL-ROUTER-001 §B).
 *
 * <p>기존 {@link BackfillWindowExecutor} routeFetch/routePersist의 {@code case "credit_balance"} 본체를
 * 그대로 이관한다. 시장별 분기 없이 단일 서비스로 단순 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditBalanceRouteHandler implements BackfillRouteHandler {

    private final CreditBalanceCollectionService creditBalanceService;

    @Override
    public String dataTable() {
        return "credit_balance";
    }

    @Override
    public Object fetch(
            BackfillStatus resolved, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException {
        return creditBalanceService.fetchWindow(resolved, stock, session);
    }

    @Override
    public BackfillWindowResult persist(BackfillStatus status, Stock stock, Object fetchDto) {
        if (fetchDto == null) {
            log.warn(
                    "[backfill] credit_balance persistWindow 스킵 (null fetchDto) — symbol={}",
                    status.getTargetCode());
            return BackfillWindowResult.EMPTY;
        }
        if (fetchDto instanceof CreditBalanceFetch f) {
            return creditBalanceService.persistWindow(status, stock, f);
        }
        log.warn(
                "[backfill] credit_balance persistWindow 스킵 (알 수 없는 fetchDto 타입) —"
                        + " symbol={}, type={}",
                status.getTargetCode(),
                fetchDto.getClass().getSimpleName());
        return BackfillWindowResult.EMPTY;
    }
}
