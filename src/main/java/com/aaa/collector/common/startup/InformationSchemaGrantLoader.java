package com.aaa.collector.common.startup;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code information_schema}를 통해 {@code CURRENT_USER()} 권한을 조회하는 {@link DbGrantLoader} 구현체.
 *
 * <p>MySQL 8.4 {@code information_schema.SCHEMA_PRIVILEGES} / {@code TABLE_PRIVILEGES}를 사용한다.
 * {@code SHOW GRANTS} 텍스트 파싱 대신 구조화된 테이블을 쿼리하므로 호스트 패턴 차이에 강건하다.
 *
 * <p>grantee는 {@code SELECT CURRENT_USER()}로 동적으로 결정하므로 {@code collector@%} 등 호스트 부분을 하드코딩하지 않는다.
 * 스키마명 {@code aaa}는 상수로 고정한다 — 단일 스키마 환경이며 datasource URL에서 추출하는 것보다 명시적이다.
 */
@Slf4j
@RequiredArgsConstructor
public class InformationSchemaGrantLoader implements DbGrantLoader {

    /** DB 스키마명. ADR-026이 정의한 단일 스키마 환경에서 고정값. */
    static final String SCHEMA_NAME = "aaa";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Set<String> loadSchemaPrivileges() {
        // Derive grantee from current connection — host-agnostic
        String grantee = jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String.class);

        // GRANTEE column in information_schema is stored as 'user'@'host' with quotes
        String quotedGrantee = toQuotedGrantee(grantee);

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT PRIVILEGE_TYPE"
                                + " FROM information_schema.SCHEMA_PRIVILEGES"
                                + " WHERE GRANTEE = ?"
                                + "   AND TABLE_SCHEMA = ?",
                        quotedGrantee,
                        SCHEMA_NAME);

        Set<String> privs = new HashSet<>();
        for (Map<String, Object> row : rows) {
            privs.add((String) row.get("PRIVILEGE_TYPE"));
        }

        log.debug("collector schema privileges on {}: {}", SCHEMA_NAME, privs);
        return privs;
    }

    @Override
    public Set<String> loadTier2UpdateTables() {
        String grantee = jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String.class);
        String quotedGrantee = toQuotedGrantee(grantee);

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME"
                                + " FROM information_schema.TABLE_PRIVILEGES"
                                + " WHERE GRANTEE = ?"
                                + "   AND TABLE_SCHEMA = ?"
                                + "   AND PRIVILEGE_TYPE = 'UPDATE'",
                        quotedGrantee,
                        SCHEMA_NAME);

        Set<String> tables = new HashSet<>();
        for (Map<String, Object> row : rows) {
            tables.add((String) row.get("TABLE_NAME"));
        }

        log.debug("collector UPDATE-privileged tables in {}: {}", SCHEMA_NAME, tables);
        return tables;
    }

    /**
     * MySQL {@code CURRENT_USER()} 결과({@code user@host})를 {@code information_schema.GRANTEE}
     * 형식({@code 'user'@'host'})으로 변환한다.
     */
    private static String toQuotedGrantee(String currentUser) {
        if (currentUser == null) {
            return "''@''";
        }
        int atIdx = currentUser.lastIndexOf('@');
        if (atIdx < 0) {
            return "'" + currentUser + "'@''";
        }
        String user = currentUser.substring(0, atIdx);
        String host = currentUser.substring(atIdx + 1);
        return "'" + user + "'@'" + host + "'";
    }
}
