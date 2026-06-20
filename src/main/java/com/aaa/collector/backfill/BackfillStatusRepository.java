package com.aaa.collector.backfill;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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

    /**
     * 처리 대상(PENDING 또는 IN_PROGRESS) 항목을 targetType 기준으로 id 순으로 조회한다 (T6 오케스트레이터 입력).
     *
     * <p>COMPLETED·FAILED 항목은 반환하지 않는다 — 오케스트레이터가 미완료 항목만 처리한다(AC-6.1/6.4).
     *
     * @param statuses 포함할 상태 집합 (예: {@code List.of("PENDING","IN_PROGRESS")})
     * @param targetType 대상 유형 필터 (예: {@code "STOCK"})
     * @return id 오름차순 정렬된 처리 대상 항목 목록
     */
    List<BackfillStatus> findByStatusInAndTargetTypeOrderById(
            Collection<String> statuses, String targetType);

    /**
     * 윈도우 수집 성공 후 진행점·상태·stale_count·attempt_count를 갱신한다 (T6, REQ-BACKFILL-011, AC-4.1/4.2).
     *
     * <p>last_error를 NULL로 초기화하고 attempt_count를 1 증가시킨다. 데이터 INSERT IGNORE(수집 서비스)와 동일
     * {@code @Transactional} 경계 안에서 호출한다 — 부분 커밋 방지(AC-4.2). Tier-2 UPDATE 권한 대상(ADR-026).
     *
     * @param id 갱신할 backfill_status.id
     * @param status 새 상태 ({@code "IN_PROGRESS"} 또는 {@code "COMPLETED"})
     * @param lastCollectedDate 이번 윈도우 최소 거래일
     * @param staleCount 새 stale_count (전진 시 0, 무전진 시 현재값+1)
     * @param lastRowCount 이번 윈도우 행 수 (0이면 직전값 유지)
     */
    @Modifying
    @Transactional
    @Query(
            """
            UPDATE BackfillStatus b SET
                b.status = :status,
                b.lastCollectedDate = :lastCollectedDate,
                b.staleCount = :staleCount,
                b.lastRowCount = :lastRowCount,
                b.attemptCount = b.attemptCount + 1,
                b.lastError = NULL
            WHERE b.id = :id
            """)
    void updateProgress(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("lastCollectedDate") LocalDate lastCollectedDate,
            @Param("staleCount") int staleCount,
            @Param("lastRowCount") Integer lastRowCount);

    /**
     * 윈도우 수집 실패 시 오류 메시지와 상태를 갱신한다 (T6, REQ-BACKFILL-030).
     *
     * <p>last_collected_date는 갱신하지 않는다 — 실패한 윈도우의 anchor는 다음 cron에서 재시도한다. attempt_count를 1 증가시킨다.
     * Tier-2 UPDATE 권한 대상(ADR-026).
     *
     * @param id 갱신할 backfill_status.id
     * @param status 새 상태 ({@code "IN_PROGRESS"} 또는 {@code "FAILED"})
     * @param lastError 오류 메시지 (최대 512자)
     */
    @Modifying
    @Transactional
    @Query(
            """
            UPDATE BackfillStatus b SET
                b.lastError = :lastError,
                b.status = :status,
                b.attemptCount = b.attemptCount + 1
            WHERE b.id = :id
            """)
    void updateError(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("lastError") String lastError);
}
