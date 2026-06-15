package com.aaa.collector.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 기업 이벤트 리포지토리. */
public interface CorporateEventRepository extends JpaRepository<CorporateEvent, Long> {

    /**
     * 기업 이벤트 1건을 멱등 삽입한다 (REQ-BATCH3-053).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code uk_corporate_events
     * (stock_id, event_type, event_date)} 충돌 시 행 수 미증가·UPDATE 미발생(TECHSPEC 4절).
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
                    INSERT INTO corporate_events
                        (stock_id, event_type, event_date, event_subtype,
                         pay_date, stock_pay_date, odd_pay_date,
                         cash_amount, cash_rate, stock_rate,
                         face_value, stock_kind, high_dividend_flag,
                         created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.eventType.name()}, :#{#e.eventDate}, :#{#e.eventSubtype},
                         :#{#e.payDate}, :#{#e.stockPayDate}, :#{#e.oddPayDate},
                         :#{#e.cashAmount}, :#{#e.cashRate}, :#{#e.stockRate},
                         :#{#e.faceValue}, :#{#e.stockKind}, :#{#e.highDividendFlag},
                         NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") CorporateEvent e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(c) FROM CorporateEvent c WHERE c.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
