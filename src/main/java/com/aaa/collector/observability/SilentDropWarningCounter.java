package com.aaa.collector.observability;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INSERT IGNORE 실행 후 MySQL JDBC 경고 체인에서 침묵 드롭(중복 외 사유로 흡수된 행)을 판정하고, 유실된 행마다 구조화 WARN 로그를 남긴다
 * (REQ-OBSV-023/024, AC-5).
 *
 * <p>근본 원인 정합: INSERT IGNORE는 영향 행 수(affected-rows)로 정상 중복(0행)과 오류 드롭(0행)을 구분할 수 없다. 대신 실제 MySQL이
 * 발생시킨 경고(SQLWarning) 체인을 검사하여, 중복 키 경고({@value #ER_DUP_ENTRY}, ER_DUP_ENTRY)를 제외한 비-중복 경고만 침묵 드롭으로
 * 센다 — FK 위반(1452)·데이터 절단(1265)·잘못된 값(1366) 등 진짜 데이터 유실을 가시화한다.
 *
 * <p><b>경고 출처 — Statement.getWarnings() + 행별 executeUpdate (실측 근거)</b>: MySQL Connector/J 8.x를
 * mysql:8.4에서 실측한 결과(SPEC-COLLECTOR-OBSV-001 검증), INSERT IGNORE가 강등한 경고는 다음 두 제약을 동시에 만족해야 캡처된다.
 *
 * <ol>
 *   <li>{@link java.sql.Connection#getWarnings()}로는 전파되지 않고 오직 실행 {@link PreparedStatement}의 {@link
 *       PreparedStatement#getWarnings()}에만 보고된다(JDBC 명세상 Connection 경고와 Statement 경고는 별개 체인).
 *   <li>{@code executeBatch()}는 강등 경고를 statement에 보존하지 않는다(드라이버가 배치 실행 후 경고를 폐기 — 실측상 FK 위반 행도 경고
 *       0). 행별 {@code executeUpdate()} 직후에만 {@code ps.getWarnings()}로 노출된다.
 * </ol>
 *
 * <p>따라서 침묵 드롭을 신뢰성 있게 세려면 행을 배치가 아닌 {@code executeUpdate()}로 하나씩 실행하고, 각 행 직후 {@code
 * ps.getWarnings()}를 읽어 누적해야 한다. {@link #countDropsPerRow}가 이 실행·누적 루프를 공유 제공하고, {@link
 * #countGenuineDrops}가 한 체인의 비-1062 경고를 세는 순수 분류기다. 두 메서드 모두 상태 없음.
 *
 * <p><b>행별 구조화 로그 (REQ-OBSV-024)</b>: 실행·누적 루프({@link #countDropsPerRow}/{@link
 * #countDropsPerRowIsolated})는 비-1062 경고를 발견할 때마다 대상 테이블명·경고 errorCode·경고 메시지·행 식별자를 담은 WARN 로그를 1건
 * 남긴다(경고 1건 = 로그 1건). 행 식별자는 {@link RowDescriber}로 위임하며, 비-1062 경고를 발견한 행에서만 그 행의 첫 경고에서 한 번
 * 호출·메모이즈해 정상 경로 오버헤드를 0으로 유지한다(REQ-OBSV-026). describer 호출이 예외를 던지면 그 경고의 로깅만 생략하고 카운트는 반영한 채 루프를
 * 계속한다(REQ-OBSV-035).
 */
// @MX:ANCHOR: [AUTO] 침묵 드롭 분류·행별 실행 공유 엔진 — 14개 Tier-1 인서터가 호출(fan_in 14)
// 기존 4(daily/short-sale/investor/credit) + 신규 10(infra#9:
// macro/market/news2/financials/analyst/event/exthours/disclosure/corpcode)
// @MX:WARN: [AUTO] countDropsPerRow를 executeBatch로 "최적화"하지 말 것 — executeBatch는 INSERT IGNORE 강등 경고를
// statement에 보존하지 않아(실측, 클래스 Javadoc 참조) 침묵 드롭 캡처가 영구 0이 된다. 행별 executeUpdate 유지가 정확성의 전제다.
// @MX:SPEC: SPEC-COLLECTOR-OBSV-001
// @MX:SPEC: SPEC-COLLECTOR-OBSV-002
public final class SilentDropWarningCounter {

    /** MySQL 중복 키 경고 코드 (ER_DUP_ENTRY) — 정상 멱등 중복이므로 드롭으로 세지 않는다. */
    static final int ER_DUP_ENTRY = 1062;

    private static final Logger log = LoggerFactory.getLogger(SilentDropWarningCounter.class);

    private SilentDropWarningCounter() {}

    /**
     * 경고 체인에서 중복 키(1062)를 제외한 비-중복 경고 수를 센다.
     *
     * @param head INSERT IGNORE 직후의 SQLWarning 체인 헤드(없으면 {@code null})
     * @return 침묵 드롭으로 판정된 비-중복 경고 수(중복뿐이거나 경고가 없으면 0)
     */
    public static long countGenuineDrops(SQLWarning head) {
        long count = 0L;
        for (SQLWarning w = head; w != null; w = w.getNextWarning()) {
            if (w.getErrorCode() != ER_DUP_ENTRY) {
                count++;
            }
        }
        return count;
    }

    /**
     * 한 종목의 행들을 {@link PreparedStatement}로 하나씩 INSERT IGNORE 실행하고, 각 행 직후 statement 경고에서 침묵 드롭을
     * 누적한다.
     *
     * <p>행별 {@code executeUpdate()} 직후에만 강등 경고가 노출되는 드라이버 제약(클래스 Javadoc 참조) 때문에 배치 대신 행 단위로 실행한다.
     * 각 행 실행 전 {@code ps.clearWarnings()}로 이전 경고를 비워 행별로 정확히 분류한다.
     *
     * @param ps 바인딩 가능한 INSERT IGNORE PreparedStatement(호출자가 SQL을 상수로 준비)
     * @param rows 삽입할 행 목록
     * @param binder 한 행을 {@code ps}에 바인딩하는 콜백(파라미터 인덱스 1부터 설정)
     * @param tableName 로그에 남길 대상 테이블명(INSERT IGNORE SQL의 대상 테이블)
     * @param describer 비-1062 경고 발견 행의 로그용 공개 식별자 위임(지연 호출 — REQ-OBSV-027)
     * @param <T> 행 타입
     * @return 전체 행에서 누적된 침묵 드롭(비-1062 경고) 수
     * @throws SQLException JDBC 바인딩/실행 실패
     */
    public static <T> long countDropsPerRow(
            PreparedStatement ps,
            List<T> rows,
            RowBinder<T> binder,
            String tableName,
            RowDescriber<T> describer)
            throws SQLException {
        long drops = 0L;
        for (T row : rows) {
            ps.clearWarnings();
            binder.bind(ps, row);
            ps.executeUpdate();
            drops += countAndLog(ps.getWarnings(), tableName, row, describer);
        }
        return drops;
    }

    /**
     * 한 종목의 행들을 {@link PreparedStatement}로 하나씩 INSERT IGNORE 실행하고, 각 행의 예외를 격리하여 독성 행을 skip하고 잔여 행을
     * 계속 처리한다 (REQ-INSERT-007).
     *
     * <p>{@link #countDropsPerRow}와 동일한 행별 {@code executeUpdate()} + 경고 누적 방식을 사용하되, {@link
     * SQLException}을 행별로 catch하여 {@link RowFailureHandler#onFailure}로 통지하고 루프를 중단하지 않는다. 독성 행은 드롭
     * 카운트에 포함되지 않는다(경고 체인을 읽기 전에 예외가 발생하므로).
     *
     * <p><b>주의</b>: {@code bind} 중 예외가 발생하면 해당 행의 {@code executeUpdate()}는 실행되지 않는다 — 따라서 준비된
     * statement 상태가 오염될 수 있다. 각 행 시작 시 {@code clearWarnings()}를 호출하여 이전 경고를 비운다.
     *
     * @param ps 바인딩 가능한 INSERT IGNORE PreparedStatement
     * @param rows 삽입할 행 목록
     * @param binder 한 행을 {@code ps}에 바인딩하는 콜백
     * @param onFailure 행별 SQLException 발생 시 통지 콜백 (예외를 전파하지 않음)
     * @param tableName 로그에 남길 대상 테이블명(INSERT IGNORE SQL의 대상 테이블)
     * @param describer 비-1062 경고 발견 행의 로그용 공개 식별자 위임(지연 호출 — REQ-OBSV-027)
     * @param <T> 행 타입
     * @return 성공 행에서 누적된 침묵 드롭(비-1062 경고) 수
     * @throws SQLException JDBC 레벨에서 bind/execute 외 시스템 오류 발생 시
     */
    public static <T> long countDropsPerRowIsolated(
            PreparedStatement ps,
            List<T> rows,
            RowBinder<T> binder,
            RowFailureHandler<T> onFailure,
            String tableName,
            RowDescriber<T> describer)
            throws SQLException {
        long drops = 0L;
        for (T row : rows) {
            ps.clearWarnings();
            try {
                binder.bind(ps, row);
                ps.executeUpdate();
                drops += countAndLog(ps.getWarnings(), tableName, row, describer);
            } catch (SQLException e) {
                onFailure.onFailure(row, e);
            }
        }
        return drops;
    }

    /**
     * 경고 체인의 비-1062 경고를 세면서, 발견한 경고마다 대상 테이블명·errorCode·경고 메시지·행 식별자를 담은 WARN 로그를 1건 남긴다
     * (REQ-OBSV-024, 경고 1건 = 로그 1건).
     *
     * <p>행 식별자는 첫 비-1062 경고에서 {@code describer}를 한 번 호출·메모이즈해 지연 조립한다(정상 경로 오버헤드 0 — REQ-OBSV-026).
     * describer가 예외를 던지면 그 경고의 로깅만 생략하고 카운트는 반영한다(REQ-OBSV-035).
     *
     * @return 이 체인의 비-1062 경고 수(반환 카운트와 방출 로그 건수는 항상 일치)
     */
    private static <T> long countAndLog(
            SQLWarning head, String tableName, T row, RowDescriber<T> describer) {
        long count = 0L;
        String rowDescription = null;
        boolean described = false;
        for (SQLWarning w = head; w != null; w = w.getNextWarning()) {
            if (w.getErrorCode() == ER_DUP_ENTRY) {
                continue;
            }
            count++;
            if (!described) {
                rowDescription = describeSafely(tableName, w.getErrorCode(), row, describer);
                described = true;
            }
            if (rowDescription != null) {
                log.warn(
                        "silent_drop table={} errorCode={} warning={} row={}",
                        tableName,
                        w.getErrorCode(),
                        escapeNewlines(w.getMessage()),
                        escapeNewlines(rowDescription));
            }
        }
        return count;
    }

    /**
     * 행 식별자 위임을 예외 안전하게 호출한다 (REQ-OBSV-035). describer가 예외를 던지면 진단 WARN을 남기고 {@code null}을 반환해 해당
     * 경고의 구조화 로깅만 생략한다 — 삽입 루프는 중단하지 않는다.
     */
    private static <T> String describeSafely(
            String tableName, int errorCode, T row, RowDescriber<T> describer) {
        try {
            return describer.describe(row);
        } catch (RuntimeException e) { // NOPMD.AvoidCatchingGenericException
            // REQ-OBSV-035: describer 예외를 흡수 — 삽입 루프 미중단, 카운트 반영, 해당 경고 로깅만 생략
            log.warn(
                    "silent_drop_row_describe_failed table={} errorCode={} cause={}",
                    tableName,
                    errorCode,
                    e.toString());
            return null;
        }
    }

    /**
     * 구조화 로그 필드에 삽입하기 전 개행문자를 이스케이프한다 (CR-01, 로그 인젝션 방지).
     *
     * <p>{@code warning}(MySQL 서버가 생성한 SQLWarning 메시지)과 {@code row}(describer가 조립한 행 식별자)는 외부 유래
     * 값이므로 개행({@code \n}, {@code \r})이 섞이면 key=value 로그 한 줄이 여러 줄로 쪼개져 LogsQL 필드 파싱이 깨진다. 리터럴 백슬래시
     * 시퀀스로 치환해 항상 단일 라인을 보장한다.
     *
     * @param value 원본 값(null 가능)
     * @return 개행이 이스케이프된 문자열, {@code value}가 {@code null}이면 리터럴 {@code "null"}
     */
    private static String escapeNewlines(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    /** 한 행을 {@link PreparedStatement}에 바인딩하는 콜백(인서터별 컬럼 매핑을 위임). */
    @FunctionalInterface
    public interface RowBinder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }
}
