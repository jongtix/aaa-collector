package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * {@link CoveredGapFiller#persistStep(LocalDate)} 1회 호출 결과 (SPEC-COLLECTOR-BACKFILL-011
 * REQ-CVR-012, -030, -076).
 *
 * @param kept 검증 통과·저장 행수(§2.6 {@code rowCount()}). 중복 삽입 시도를 포함하며, 순 신규 삽입 여부와 무관하다(REQ-CVR-030 어휘
 *     — INSERT IGNORE 중복 제거 모호성 해소).
 * @param raw 파싱된 원본 응답 행수(§2.6 {@code rawRowCount()}). {@code raw > 0 && kept == 0}이면 검증 전량 실패
 *     이상(#77류 침묵 skip)을 의심한다(REQ-CVR-031).
 * @param filledUntil 이 스텝이 커버한 마지막 날짜. {@code kept > 0}일 때만 {@code covered_until_date} 전진에 사용된다.
 * @param oldest API가 실제 반환한 최소(가장 과거) 거래일자. {@code oldest}가 스텝 시작 cursor보다 늦으면(앞단 미도달) 스텝 폭 산정이
 *     잘못됐거나 API 반환 특성이 변한 것으로 의심해 anomaly 경보를 발생시킨다(REQ-CVR-076, 심층 방어) — 단 {@code
 *     covered_until_date} 전진은 억제하지 않는다(라이브락 방지).
 */
public record CoveredFillResult(int kept, int raw, LocalDate filledUntil, LocalDate oldest) {

    /**
     * 3-인자 호환 생성자 — {@code oldest} 신호를 제공하지 않는 호출자(USDKRW·FINRA 단일 날짜형 filler)를 위해 {@code oldest =
     * filledUntil}로 기본 설정한다. 단일 날짜형은 {@code cursor == filledUntil}이 항상 성립하므로 이 기본값은 {@code oldest
     * <= cursor} 불변식을 자동으로 만족해 REQ-CVR-076 anomaly가 절대 발화하지 않는다(TASK-012 tripwire는 STOCK 범위형 대상,
     * SPEC-COLLECTOR-BACKFILL-011).
     *
     * @param kept 검증 통과·저장 행수
     * @param raw 파싱된 원본 응답 행수
     * @param filledUntil 이 스텝이 커버한 마지막 날짜
     */
    public CoveredFillResult(int kept, int raw, LocalDate filledUntil) {
        this(kept, raw, filledUntil, filledUntil);
    }
}
