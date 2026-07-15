package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * {@link CoveredGapFiller#persistStep(LocalDate)} 1회 호출 결과 (SPEC-COLLECTOR-BACKFILL-011
 * REQ-CVR-012, -030).
 *
 * @param kept 검증 통과·저장 행수(§2.6 {@code rowCount()}). 중복 삽입 시도를 포함하며, 순 신규 삽입 여부와 무관하다(REQ-CVR-030 어휘
 *     — INSERT IGNORE 중복 제거 모호성 해소).
 * @param raw 파싱된 원본 응답 행수(§2.6 {@code rawRowCount()}). {@code raw > 0 && kept == 0}이면 검증 전량 실패
 *     이상(#77류 침묵 skip)을 의심한다(REQ-CVR-031).
 * @param filledUntil 이 스텝이 커버한 마지막 날짜. {@code kept > 0}일 때만 {@code covered_until_date} 전진에 사용된다.
 */
public record CoveredFillResult(int kept, int raw, LocalDate filledUntil) {}
