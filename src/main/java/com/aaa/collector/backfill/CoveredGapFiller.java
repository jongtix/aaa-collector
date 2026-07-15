package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * 소스별(FINRA/USDKRW/STOCK) 정방향 갭 walk 1스텝 실행 계약 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-011, -050~052).
 *
 * <p>구현체는 {@code cursor}(범위형은 윈도우 시작일, 단일 날짜형은 해당 날짜)의 데이터를 실제로 저장(fetch + validate + persist)하고 그
 * 결과를 {@link CoveredFillResult}로 반환한다. 데이터 저장 자체는 이 메서드가 수행하는 책임이며, {@link
 * CoveredRangeService#executeStep(BackfillStatus, CoveredGapFiller, LocalDate)}가 같은 트랜잭션 경계 안에서 호출해
 * 저장과 {@code covered_until_date} 전진을 원자적으로 커밋한다(결정 1).
 *
 * <p>범위형(REQ-CVR-050)은 기존 기간 조회 윈도우 메커니즘을, 단일 날짜형(REQ-CVR-051)은 기존 백필 경로의 날짜별 데이터 소스를 재사용한다 — 신규
 * fetch 메서드를 만들지 않는다. 구현은 TASK-005~008에서 소스별로 작성된다.
 */
@FunctionalInterface
public interface CoveredGapFiller {

    /**
     * 1스텝 분량(범위형=단일 윈도우, 단일 날짜형=하루)을 저장하고 결과를 반환한다.
     *
     * @param cursor 이번 스텝의 시작 지점({@code covered_until_date} 다음 날짜)
     * @return 이번 스텝의 kept/raw/filledUntil
     */
    CoveredFillResult persistStep(LocalDate cursor);
}
