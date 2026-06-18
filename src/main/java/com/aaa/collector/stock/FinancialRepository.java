package com.aaa.collector.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 재무비율 리포지토리 (SPEC-COLLECTOR-BATCH-004). */
public interface FinancialRepository extends JpaRepository<Financial, Long> {

    /**
     * 재무비율 1건을 멱등 삽입한다 (REQ-BATCH4-023).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code uk_financials
     * (stock_id, period_type, period_date)} 충돌 시 행 수 미증가·UPDATE 미발생 (TECHSPEC §4 시계열 규칙). 매주 토요일
     * 재실행 시 기존 결산기 행을 재삽입하지 않는다.
     *
     * <p>{@code period_type}은 {@link com.aaa.collector.stock.enums.PeriodType} enum이므로 SpEL에서
     * {@code .name()}으로 VARCHAR 값을 전달한다.
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티 (트랜지언트, stock·period_type·period_date 등 매핑 필드 보유)
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO financials
                        (stock_id, period_type, period_date, revenue_growth, operating_profit_growth,
                         net_income_growth, roe, eps, sps, bps, retention_rate, debt_ratio,
                         created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.periodType.name()}, :#{#e.periodDate}, :#{#e.revenueGrowth},
                         :#{#e.operatingProfitGrowth}, :#{#e.netIncomeGrowth}, :#{#e.roe}, :#{#e.eps},
                         :#{#e.sps}, :#{#e.bps}, :#{#e.retentionRate}, :#{#e.debtRatio}, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") Financial e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(f) FROM Financial f WHERE f.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
