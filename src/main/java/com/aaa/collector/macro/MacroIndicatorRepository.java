package com.aaa.collector.macro;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 거시경제 지표 리포지토리. */
public interface MacroIndicatorRepository extends JpaRepository<MacroIndicator, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 macro_indicators에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시 중복
    // 충돌에서
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    /**
     * 거시경제 지표 1건을 멱등 삽입한다 (REQ-BATCH3-032, -043).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_macro_indicators (indicator_code, trade_date)}
     * 충돌 시 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE id = id}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 UPDATE
     * 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142({@code UPDATE command denied to
     * user 'collector'@'%' for table 'macro_indicators'})를 유발한다(ADR-026 Tier-1, ADR-025 §맥락 1).
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO macro_indicators
                        (indicator_code, source, trade_date, value, created_at, updated_at)
                    VALUES
                        (:#{#e.indicatorCode}, :#{#e.source.name()}, :#{#e.tradeDate}, :#{#e.value}, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") MacroIndicator e);

    /** 지표 코드별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(m) FROM MacroIndicator m WHERE m.indicatorCode = :indicatorCode")
    long countByIndicatorCode(@Param("indicatorCode") String indicatorCode);

    /** 지표 코드별 최소 거래일 (백필 진행점 갱신용). */
    @Query("SELECT MIN(m.tradeDate) FROM MacroIndicator m WHERE m.indicatorCode = :indicatorCode")
    Optional<LocalDate> findMinTradeDateByIndicatorCode(
            @Param("indicatorCode") String indicatorCode);

    /** 최신 적재 시각 조회 (SPEC-OBSV-WARMSTART-001 warm-start용). */
    @Query("SELECT MAX(m.createdAt) FROM MacroIndicator m")
    Optional<LocalDateTime> findMaxCreatedAt();
}
