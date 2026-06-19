package com.aaa.collector.observability;

import java.sql.SQLWarning;

/**
 * INSERT IGNORE 실행 후 MySQL JDBC 경고 체인에서 침묵 드롭(중복 외 사유로 흡수된 행)을 판정한다 (REQ-OBSV-023, AC-5).
 *
 * <p>근본 원인 정합: INSERT IGNORE는 영향 행 수(affected-rows)로 정상 중복(0행)과 오류 드롭(0행)을 구분할 수 없다. 대신 실제 MySQL이
 * 발생시킨 경고(SQLWarning) 체인을 검사하여, 중복 키 경고({@value #ER_DUP_ENTRY}, ER_DUP_ENTRY)를 제외한 비-중복 경고만 침묵 드롭으로
 * 센다 — 데이터 절단(1265)·잘못된 값(1366) 등 진짜 데이터 유실을 가시화한다.
 *
 * <p>순수 유틸 — 상태 없음. INSERT IGNORE 직후 동일 JDBC {@code Connection}/{@code Statement}의 경고 체인을 인자로 받는다.
 */
public final class SilentDropWarningCounter {

    /** MySQL 중복 키 경고 코드 (ER_DUP_ENTRY) — 정상 멱등 중복이므로 드롭으로 세지 않는다. */
    static final int ER_DUP_ENTRY = 1062;

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
}
