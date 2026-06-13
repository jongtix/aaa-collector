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
 *   <li>테이블 레벨: {@link #TIER2_TABLES} 4개 테이블에 {@code UPDATE}
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
     */
    static final Set<String> TIER2_TABLES =
            Set.of("stocks", "stock_grades", "short_sale_overseas", "etf_metadata");

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
