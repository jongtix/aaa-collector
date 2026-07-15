package com.aaa.collector.backfill;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     * @param statuses 포함할 상태 집합 (예: {@code List.of(BackfillStatusType.PENDING,
     *     BackfillStatusType.IN_PROGRESS)})
     * @param targetType 대상 유형 필터 (예: {@code "STOCK"})
     * @return id 오름차순 정렬된 처리 대상 항목 목록
     */
    List<BackfillStatus> findByStatusInAndTargetTypeOrderById(
            Collection<BackfillStatusType> statuses, String targetType);

    /**
     * 처리 대상 항목을 targetType + dataTable 기준으로 id 순으로 조회한다.
     *
     * <p>{@code backfill_status}에는 여러 data_table 값이 공존하므로, 특정 도메인 오케스트레이터는 자신의 {@code data_table}
     * 값으로 범위를 한정해야 한다. data_table 미필터 시 타 도메인의 진행점이 오염될 수 있다 (SPEC-COLLECTOR-DART-001 CR-01 수정).
     *
     * @param statuses 포함할 상태 집합
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @param dataTable 데이터 테이블명 (예: {@code "disclosures"})
     * @return id 오름차순 정렬된 처리 대상 항목 목록
     */
    List<BackfillStatus> findByStatusInAndTargetTypeAndDataTableOrderById(
            Collection<BackfillStatusType> statuses, String targetType, String dataTable);

    /**
     * 대상 유형 기준 전체 항목 수를 반환한다 (T9 BackfillMetrics 진행률 분모).
     *
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @return 전체 항목 수
     */
    long countByTargetType(String targetType);

    /**
     * 상태·대상 유형 기준 항목 수를 반환한다 (T9 BackfillMetrics 진행률 분자).
     *
     * @param status 상태 필터 ({@link BackfillStatusType#COMPLETED} 등)
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @return 해당 상태·유형 항목 수
     */
    long countByStatusAndTargetType(BackfillStatusType status, String targetType);

    /**
     * 상태 집합·대상 유형 기준 항목 수를 반환한다 (SPEC-OBSV-WATERMARK-001 REQ-WM-029 — 백필 pending_slots 게이지,
     * PENDING+IN_PROGRESS 집계용).
     *
     * @param statuses 상태 필터 집합
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @return 해당 상태 집합·유형 항목 수
     */
    long countByStatusInAndTargetType(Collection<BackfillStatusType> statuses, String targetType);

    /**
     * 한 종목의 신뢰 하한 기준선(baseline)을 조회한다 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-152/-153).
     *
     * <p>오직 검증된 완료({@code status = COMPLETED AND verified_at IS NOT NULL})의 {@code
     * last_collected_date}(도달 최과거)만 반환한다 — 미검증 COMPLETED의 도달 최과거는 오염 가능 증거이므로 기준선으로 승격되지 않는다(순환 신뢰
     * 차단, REQ-153). 검증된 완료가 없으면 {@link Optional#empty()}를 반환한다(신뢰 하한 부재).
     *
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @param targetCode 대상 코드 = 종목 symbol
     * @param dataTable 데이터 테이블명 (예: {@code "daily_ohlcv"})
     * @return 검증된 완료의 last_collected_date (없으면 empty)
     */
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    @Query(
            "SELECT b.lastCollectedDate FROM BackfillStatus b"
                    + " WHERE b.targetType = :targetType AND b.targetCode = :targetCode"
                    + " AND b.dataTable = :dataTable"
                    + " AND b.status = com.aaa.collector.backfill.BackfillStatusType.COMPLETED"
                    + " AND b.verifiedAt IS NOT NULL")
    Optional<LocalDate> findVerifiedBaseline(
            @Param("targetType") String targetType,
            @Param("targetCode") String targetCode,
            @Param("dataTable") String dataTable);

    /**
     * {@code (target_type, target_code, data_table)} 단일 항목을 조회한다 (SPEC-COLLECTOR-BACKFILL-010
     * REQ-BACKFILL-160 — {@code listed_date} 하향 보정 종목의 표적 GROUP_A {@code daily_ohlcv} status 리셋 대상
     * 조회).
     *
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @param targetCode 대상 코드 = 종목 symbol
     * @param dataTable 데이터 테이블명 (예: {@code "daily_ohlcv"})
     * @return 해당 항목(없으면 {@link Optional#empty()} — 아직 시딩되지 않은 종목)
     */
    Optional<BackfillStatus> findByTargetTypeAndTargetCodeAndDataTable(
            String targetType, String targetCode, String dataTable);

    /**
     * targetType + dataTable 기준으로 상태 무관 전체 항목을 id 순으로 조회한다 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-072
     * — 커버-추적 조회).
     *
     * <p>{@link #findByStatusInAndTargetTypeAndDataTableOrderById(Collection, String, String)}는
     * {@code PENDING}/{@code IN_PROGRESS}만 반환해 backward walk가 이미 {@code COMPLETED}된 종목을 놓친다. 완료된
     * 종목도 {@code covered_until_date} 이후 구간에 상단 갭이 생길 수 있으므로, 이 조회는 상태를 필터링하지 않고 {@code
     * COMPLETED}·{@code FAILED}를 포함한 전체 항목을 반환한다.
     *
     * @param targetType 대상 유형 (예: {@code "STOCK"})
     * @param dataTable 데이터 테이블명 (예: {@code "daily_ohlcv"})
     * @return id 오름차순 정렬된 전체 항목 목록(상태 무관)
     */
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
    List<BackfillStatus> findByTargetTypeAndDataTableOrderById(String targetType, String dataTable);
}
