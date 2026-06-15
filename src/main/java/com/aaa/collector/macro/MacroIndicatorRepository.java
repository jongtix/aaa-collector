package com.aaa.collector.macro;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 거시경제 지표 리포지토리. */
public interface MacroIndicatorRepository extends JpaRepository<MacroIndicator, Long> {

    /**
     * 거시경제 지표 1건을 멱등 삽입한다 (REQ-BATCH3-032, -043).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code uk_macro_indicators
     * (indicator_code, trade_date)} 충돌 시 행 수 미증가·UPDATE 미발생(TECHSPEC 4절).
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
                    INSERT INTO macro_indicators
                        (indicator_code, source, trade_date, value, created_at, updated_at)
                    VALUES
                        (:#{#e.indicatorCode}, :#{#e.source.name()}, :#{#e.tradeDate}, :#{#e.value}, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") MacroIndicator e);

    /** 지표 코드별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(m) FROM MacroIndicator m WHERE m.indicatorCode = :indicatorCode")
    long countByIndicatorCode(@Param("indicatorCode") String indicatorCode);
}
