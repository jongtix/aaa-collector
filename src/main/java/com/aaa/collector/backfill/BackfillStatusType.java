package com.aaa.collector.backfill;

/** BackfillStatus 엔티티의 status 필드에 사용하는 enum. */
public enum BackfillStatusType {
    /** 대기 중 */
    PENDING,
    /** 진행 중 */
    IN_PROGRESS,
    /** 완료 */
    COMPLETED,
    /** 실패 */
    FAILED
}
