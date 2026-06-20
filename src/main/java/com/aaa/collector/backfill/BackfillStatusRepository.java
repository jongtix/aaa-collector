package com.aaa.collector.backfill;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BackfillStatus} 영속성 리포지토리 (SPEC-COLLECTOR-BACKFILL-001).
 *
 * <p>T2에서 cron 진입부 lazy 시딩용 INSERT IGNORE 메서드를 추가한다(REQ-BACKFILL-007/-008). 시딩은 Tier-1
 * 권한(INSERT)만으로 동작하며, 진행점 전진(UPDATE, Tier-2)은 후속 Task(T6)의 책임이라 이 리포지토리에 추가하지 않는다.
 */
public interface BackfillStatusRepository extends JpaRepository<BackfillStatus, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] 시딩은 Tier-1(INSERT) 권한만 사용. ON DUPLICATE KEY UPDATE 사용 시 중복 충돌에서 UPDATE
    // 경로를 밟아 SQL 1142 발생 가능 (ADR-026, REQ-BACKFILL-007). DailyOhlcvRepository.insertIgnoreDuplicate
    // 선례.
    /**
     * 백필 진행 상태 행 1건을 멱등 시딩한다 (REQ-BACKFILL-007/-008, AC-8.1~8.5).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_backfill_status (target_type, target_code,
     * data_table)} 충돌 시 해당 행을 무시한다 — 이미 존재하는(진행 중 {@code last_collected_date} 보유) 행은 변경하지 않고, 누락 행만
     * 생성한다(AC-8.3). 초기값 {@code status='PENDING'}, {@code last_collected_date=NULL}, {@code
     * stale_count=0}, {@code attempt_count=0}은 SQL이 직접 지정한다(시딩 시점 고정값).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE}가 아닌 {@code INSERT IGNORE}이므로 UPDATE 권한이 없는 {@code
     * collector} 계정(Tier-1)에서 SQL 1142 없이 성공한다(AC-8.5). 진행점 UPDATE(Tier-2)는 별도 권한이며 이 메서드는 UPDATE를
     * 발생시키지 않는다.
     *
     * <p>{@link BackfillStatusSeeder}가 cron 진입부에서 단일 커넥션·순차로 호출한다(REQ-BACKFILL-008b — Virtual
     * Thread 팬아웃 미사용, DB 풀 점유 1 제한, DBPOOL-001 회피).
     *
     * <p>네이티브 MySQL 쿼리 — H2 미동작, 반드시 MySQL Testcontainer 통합 테스트로 검증.
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO backfill_status
                        (target_type, target_code, data_table, status, last_collected_date,
                         stale_count, last_row_count, attempt_count, last_error, created_at, updated_at)
                    VALUES
                        (:targetType, :targetCode, :dataTable, 'PENDING', NULL,
                         0, NULL, 0, NULL, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreSeed(
            @Param("targetType") String targetType,
            @Param("targetCode") String targetCode,
            @Param("dataTable") String dataTable);
}
