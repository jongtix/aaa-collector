package com.aaa.collector.dart.corpcode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** DART corp_code 매핑 캐시 리포지토리 (SPEC-COLLECTOR-DART-001). */
public interface CorpCodeMappingRepository extends JpaRepository<CorpCodeMapping, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 corp_code_mapping에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE
    // 사용 시 중복 충돌에서 SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DART-001). DailyOhlcvRepository 선례.
    /**
     * corp_code 매핑 1건을 멱등 삽입한다 (REQ-DART-002, AC-I2).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_corp_code_mapping_stock_code (stock_code)} 충돌
     * 시 해당 행을 무시한다(Tier-1 append-only, ADR-026).
     *
     * <p>네이티브 MySQL 쿼리 — H2 미동작, 반드시 MySQL Testcontainer 통합 테스트로 검증.
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO corp_code_mapping
                        (stock_code, corp_code, corp_name, modify_date, created_at, updated_at)
                    VALUES
                        (:stockCode, :corpCode, :corpName, :modifyDate, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnore(
            @Param("stockCode") String stockCode,
            @Param("corpCode") String corpCode,
            @Param("corpName") String corpName,
            @Param("modifyDate") LocalDate modifyDate);

    /**
     * stock_code로 corp_code를 조회한다 (백필 lookup, REQ-DART).
     *
     * @param stockCode DART stock_code 6자리
     * @return corp_code — 매핑 없으면 empty
     */
    @Query("SELECT m.corpCode FROM CorpCodeMapping m WHERE m.stockCode = :stockCode")
    Optional<String> findCorpCodeByStockCode(@Param("stockCode") String stockCode);

    /** stock_code로 행 수를 반환한다 (통합 테스트용). */
    @Query("SELECT COUNT(m) FROM CorpCodeMapping m WHERE m.stockCode = :stockCode")
    long countByStockCode(@Param("stockCode") String stockCode);

    /**
     * 최신 적재 시각 조회 (SPEC-OBSV-WATERMARK-001 REQ-WM-014 warm-start용 — {@code corp-code} 실행 신선도).
     * {@code corp_code_mapping}은 {@code BaseEntity}의 {@code created_at}(per-run 삽입 시각)을 보유하므로
     * warm-start 대상에 편입한다(MI-06).
     */
    @Query("SELECT MAX(m.createdAt) FROM CorpCodeMapping m")
    Optional<LocalDateTime> findMaxCreatedAt();
}
