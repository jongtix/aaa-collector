package com.aaa.collector.common.startup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * collector DB 권한 기대치 검증기.
 *
 * <p>ADR-026 Tier-2 권한 모델 기준으로 아래 두 가지를 검증한다:
 *
 * <ol>
 *   <li>스키마 레벨: {@code SELECT}, {@code INSERT} on {@code aaa.*}
 *   <li>테이블 레벨: {@link #TIER2_TABLES} 7개 테이블에 {@code UPDATE}
 * </ol>
 *
 * <p>I/O가 없는 순수 로직 컴포넌트다. 권한 집합을 인자로 받아 검증하므로 단위 테스트가 용이하다.
 *
 * @see DbGrantLoader
 * @see DbGrantCheckRunner
 */
public class DbGrantVerifier {

    /**
     * ADR-026 결정 2 — Tier-2 테이블 집합 (UPDATE 권한이 요구되는 마스터/상태 테이블).
     *
     * <p>Tier-1(INSERT 전용) 테이블은 이 목록에 포함되지 않는다. 목록 변경은 ADR-026 개정과 함께 이루어져야 한다.
     *
     * <p>{@code backfill_status}는 SPEC-COLLECTOR-BACKFILL-001(CR-01)에서 추가됐다. 시딩은 INSERT
     * IGNORE(Tier-1로 충분)지만 백필 진행점 전진은 {@code UPDATE}를 사용하므로 Tier-2다. 이 집합에서 누락하면 root 수동 GRANT가 빠져도
     * 기동 self-check가 통과(PASS)하고 진행점 UPDATE만 SQL 1142로 침묵 실패해 침묵 무한루프가 발생한다(REQ-BACKFILL-035a).
     *
     * <p>{@code market_calendar}는 SPEC-COLLECTOR-CALENDAR-001(REQ-CAL-020/-021)에서 추가됐다. 일일 갱신 배치가
     * 우선순위 판정 후 기존 행을 in-place UPDATE하므로(REQ-CAL-004) Tier-2다.
     *
     * <p>{@code short_sale_domestic}은 SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001(ADR-026
     * 2026-08-06 개정)에서 추가됐다. 두 독립적 근본원인(aaa-infra#61 분할·병합 왜곡 상시 정정, aaa-infra#133 T+0 예비치 리비전 소급
     * 정정)의 공통 정정 파이프라인이 {@code short_sell_vol_rate}/{@code short_sell_qty}/{@code acml_vol}/{@code
     * vol_rate_verified_at}를 평이한 {@code UPDATE ... WHERE}로 in-place 정정하므로 Tier-2다. {@code
     * daily_ohlcv}/{@code investor_trend}는 같은 SPEC의 정정 대상이지만 재단(backbone) 테이블 blast-radius 회피 + 닫히는
     * 구간(오퍼레이터 수동 SQL로 처리)이라는 근거로 이 SPEC에서는 Tier-2에 포함하지 않는다(ADR-026 2026-08-06 개정 참고).
     */
    static final Set<String> TIER2_TABLES =
            Set.of(
                    "stocks",
                    "stock_grades",
                    "short_sale_overseas",
                    "etf_metadata",
                    "backfill_status",
                    "market_calendar",
                    "short_sale_domestic");

    private static final Set<String> REQUIRED_SCHEMA_PRIVS = Set.of("SELECT", "INSERT");

    /**
     * 주어진 권한 집합이 기대치를 충족하는지 검증한다.
     *
     * @param schemaPrivileges {@code aaa.*}에 부여된 스키마 레벨 권한 집합 (대소문자 무관, 내부에서 정규화)
     * @param tier2TablesWithUpdate {@code UPDATE} 권한이 확인된 Tier-2 테이블명 집합
     * @throws DbGrantMissingException 기대 권한 중 하나라도 누락된 경우. 메시지에 누락 항목 전체가 나열된다.
     */
    public void verify(Set<String> schemaPrivileges, Set<String> tier2TablesWithUpdate) {
        List<String> missing = new ArrayList<>();

        for (String required : REQUIRED_SCHEMA_PRIVS) {
            if (!containsIgnoreCase(schemaPrivileges, required)) {
                missing.add("schema privilege '" + required + "' on aaa.*");
            }
        }

        for (String table : TIER2_TABLES) {
            if (!tier2TablesWithUpdate.contains(table)) {
                missing.add("UPDATE privilege on table '" + table + "'");
            }
        }

        if (!missing.isEmpty()) {
            throw new DbGrantMissingException(
                    "collector DB 권한 누락 — 다음 권한이 없습니다: " + String.join(", ", missing));
        }
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        return set.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }
}
