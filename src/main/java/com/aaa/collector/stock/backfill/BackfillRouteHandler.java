package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import java.time.LocalDate;

/**
 * {@code data_table} 종류별 fetch/persist 위임 계약 (SPEC-COLLECTOR-BACKFILL-ROUTER-001 §B).
 *
 * <p>{@link BackfillWindowExecutor}는 이 인터페이스의 구현체 컬렉션을 {@code dataTable()} 반환값을 키로 하는 맵으로 구축해
 * {@code routeFetch}/{@code routePersist}의 switch 분기를 대체한다(REQ-ROUTER-001/-002/-003). 각 구현체는 기존
 * switch case 하나의 fetch/persist 본체를 그대로 이관해 소유한다 — 시장별 분기(예: {@code daily_ohlcv}·{@code
 * corporate_events}의 국내/해외)는 구현체 내부에서 유지한다(REQ-ROUTER-021).
 *
 * <p>{@link BackfillWindowExecutor}가 계속 소유하는 교차관심사(트랜잭션 경계·GROUP_A 종료 게이트·TerminationPolicy 디스패치,
 * spec.md §B)는 이 계약 밖이다 — {@code fetch}/{@code persist}는 위임받은 라우팅 로직만 수행하며, 트랜잭션 어노테이션을 선언하지
 * 않는다(REQ-ROUTER-010/-011).
 */
public interface BackfillRouteHandler {

    /**
     * 이 핸들러가 담당하는 {@code data_table} 논리 키.
     *
     * @return {@code data_table} 식별자 (예: {@code "daily_ohlcv"})
     */
    String dataTable();

    /**
     * 비트랜잭션 fetch 단계 위임 — 기존 {@code routeFetch} switch case 본체와 동일한 시그니처 형태(REQ-ROUTER-020).
     *
     * @param resolved anchor가 보정된 비영속 status 복사본
     * @param anchor 이번 윈도우 조회 기준일(={@code resolved.getLastCollectedDate()})
     * @param stock 대상 종목 엔티티
     * @param session per-run 헬스 스냅샷 세션
     * @return 서비스별 fetch DTO
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    Object fetch(BackfillStatus resolved, LocalDate anchor, Stock stock, LeaseSession session)
            throws InterruptedException;

    /**
     * 트랜잭션 소유 persist 단계 위임 — 기존 {@code routePersist} switch case 본체와 동일한 시그니처 형태(REQ-ROUTER-020).
     *
     * @param status 처리할 BackfillStatus 항목(원본)
     * @param stock 대상 종목 엔티티
     * @param fetchDto {@link #fetch}가 반환한 서비스별 fetch DTO
     * @return 윈도우 수집 결과
     */
    BackfillWindowResult persist(BackfillStatus status, Stock stock, Object fetchDto);
}
