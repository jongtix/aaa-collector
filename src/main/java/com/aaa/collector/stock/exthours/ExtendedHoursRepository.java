package com.aaa.collector.stock.exthours;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 미국 시간외 가격 스냅샷 리포지토리 (SPEC-COLLECTOR-EXTHOURS-001). */
public interface ExtendedHoursRepository extends JpaRepository<ExtendedHours, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 extended_hours에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시 SQL
    // 1142 발생 (ADR-026, SPEC-COLLECTOR-EXTHOURS-001)
    /**
     * 시간외 가격 스냅샷 1건을 멱등 삽입한다 (SPEC-COLLECTOR-EXTHOURS-001 REQ-EXTH-041).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_extended_hours (stock_id, session,
     * trade_date)} 충돌 시 해당 행을 무시하여 UPDATE 권한 없이 멱등성을 보장한다(ADR-026).
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO extended_hours
                        (stock_id, session, trade_date, ext_price, reference_close, source, collected_at, created_at, updated_at)
                    VALUES
                        (:stockId, :session, :tradeDate, :extPrice, :referenceClose, :source, :collectedAt, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(
            @Param("stockId") Long stockId,
            @Param("session") String session,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("extPrice") BigDecimal extPrice,
            @Param("referenceClose") BigDecimal referenceClose,
            @Param("source") String source,
            @Param("collectedAt") LocalDateTime collectedAt);

    /**
     * 세션별 최대 거래일 조회 (SPEC-OBSV-WATERMARK-001 REQ-WM-003 warm-start용 — {@code
     * extended-hours-pre}/{@code extended-hours-after}).
     */
    @Query("SELECT MAX(e.tradeDate) FROM ExtendedHours e WHERE e.session = :session")
    Optional<LocalDate> findMaxTradeDateBySession(@Param("session") Session session);

    /**
     * 최신 수집 시각 조회 (SPEC-OBSV-WATERMARK-001 REQ-WM-014 warm-start용 — {@code extended-hours} 실행 신선도,
     * PRE/AFTER 공유).
     */
    @Query("SELECT MAX(e.collectedAt) FROM ExtendedHours e")
    Optional<LocalDateTime> findMaxCollectedAt();
}
