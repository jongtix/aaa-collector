package com.aaa.collector.common.startup;

import java.util.Set;

/**
 * collector DB 권한 정보 로더 인터페이스.
 *
 * <p>실제 DB에서 {@code information_schema}를 조회하는 구현체({@link InformationSchemaGrantLoader})와 테스트용 mock의
 * 경계를 분리한다.
 *
 * @see InformationSchemaGrantLoader
 * @see DbGrantCheckRunner
 */
public interface DbGrantLoader {

    /**
     * {@code CURRENT_USER()}에게 부여된 {@code aaa.*} 스키마 레벨 권한을 반환한다.
     *
     * @return 스키마 레벨 권한명 집합 (예: {@code {"SELECT", "INSERT"}})
     */
    Set<String> loadSchemaPrivileges();

    /**
     * {@code CURRENT_USER()}가 {@code aaa} 스키마에서 {@code UPDATE} 권한을 보유한 테이블명 집합을 반환한다.
     *
     * @return UPDATE 권한이 있는 테이블명 집합 (예: {@code {"stocks", "stock_grades"}})
     */
    Set<String> loadTier2UpdateTables();
}
