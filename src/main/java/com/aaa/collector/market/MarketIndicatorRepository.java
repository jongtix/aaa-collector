package com.aaa.collector.market;

import com.aaa.collector.market.enums.IndicatorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 시장 지표 리포지토리 (SPEC-COLLECTOR-MARKETIND-001). */
public interface MarketIndicatorRepository extends JpaRepository<MarketIndicator, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 market_indicators에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // 중복 충돌에서 SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    /**
     * 시장 지표 1건을 멱등 삽입한다 (REQ-030, REQ-031, REQ-032).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_market_indicators (indicator_code,
     * trade_date)} 충돌 시 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE}는 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 UPDATE 권한을 검사하므로, UPDATE
     * 권한이 없는 {@code collector} 사용자에게 SQL 1142를 유발한다(ADR-026 Tier-1).
     *
     * @param e 저장 대상 엔티티
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO market_indicators
                        (indicator_code, trade_date, open_value, high_value, low_value, close_value, source, created_at, updated_at)
                    VALUES
                        (:#{#e.indicatorCode.name()}, :#{#e.tradeDate}, :#{#e.openValue}, :#{#e.highValue}, :#{#e.lowValue}, :#{#e.closeValue}, :#{#e.source}, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") MarketIndicator e);

    /** 지표 코드별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(m) FROM MarketIndicator m WHERE m.indicatorCode = :indicatorCode")
    long countByIndicatorCode(@Param("indicatorCode") IndicatorCode indicatorCode);

    /**
     * 지표 코드별 최소 거래일 조회 — VIX 백필 anchor 결정용 (REQ-042, W-4, MA-02).
     *
     * <p>VIX 전체 이력 수집 후 DB에 저장된 가장 과거 거래일을 anchor(last_collected_date)로 설정한다. 수집 데이터 없으면 {@code
     * Optional.empty()} 반환.
     */
    @Query("SELECT MIN(m.tradeDate) FROM MarketIndicator m WHERE m.indicatorCode = :indicatorCode")
    Optional<LocalDate> findMinTradeDateByIndicatorCode(
            @Param("indicatorCode") IndicatorCode indicatorCode);

    /** 최신 적재 시각 조회 (SPEC-OBSV-WARMSTART-001 warm-start용). */
    @Query("SELECT MAX(m.createdAt) FROM MarketIndicator m")
    Optional<LocalDateTime> findMaxCreatedAt();

    // @MX:NOTE: [AUTO] 지표코드 스코프 신선도 — USDKRW(10:30)/6종(17:05) 스케줄 분리 후 상호 은폐 방지
    // @MX:REASON: [AUTO] SPEC-COLLECTOR-MARKETIND-004 REQ-008 — USDKRW catch-up 전용 유닛(TASK-C5)이
    // 이 쿼리를 사용한다. 비-스코프 findMaxCreatedAt()은 6종 market-indicators 유닛이 계속 사용(무회귀) —
    // 스케줄이 갈라진 상태에서 비-스코프 신선도를 공유하면 한 배치의 성공이 다른 배치의 실패를 은폐한다.
    /**
     * 지표 코드별 최신 적재 시각 조회 (SPEC-COLLECTOR-MARKETIND-004 TASK-C4, REQ-008 — USDKRW 전용 catch-up 신선도용).
     */
    @Query("SELECT MAX(m.createdAt) FROM MarketIndicator m WHERE m.indicatorCode = :indicatorCode")
    Optional<LocalDateTime> findMaxCreatedAtByIndicatorCode(
            @Param("indicatorCode") IndicatorCode indicatorCode);

    /**
     * 지표 코드별 최대 거래일 조회 (SPEC-OBSV-WATERMARK-001 REQ-WM-003 warm-start용 — {@code
     * market-usdkrw}/{@code market-vix}).
     */
    @Query("SELECT MAX(m.tradeDate) FROM MarketIndicator m WHERE m.indicatorCode = :indicatorCode")
    Optional<LocalDate> findMaxTradeDateByIndicatorCode(
            @Param("indicatorCode") IndicatorCode indicatorCode);
}
