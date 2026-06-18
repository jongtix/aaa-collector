package com.aaa.collector.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 종목 투자의견 리포지토리 (SPEC-COLLECTOR-BATCH-004). */
public interface AnalystEstimateRepository extends JpaRepository<AnalystEstimate, Long> {

    /**
     * 투자의견 1건을 멱등 삽입한다 (REQ-BATCH4-033).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code uk_analyst_estimates
     * (stock_id, trade_date, institution_name)} 충돌 시 행 수 미증가·UPDATE 미발생 (TECHSPEC §4 시계열 규칙). 매일
     * 14일 윈도우 재조회 시 중복 행을 재삽입하지 않는다. {@code institution_name}이 빈 문자열인 행도 uk 구성요소로 정상 저장한다.
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티 (트랜지언트, stock·trade_date·institution_name 등 매핑 필드 보유)
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO analyst_estimates
                        (stock_id, trade_date, institution_name, opinion, opinion_code, prev_opinion,
                         prev_opinion_code, target_price, prev_close, gap_n_day, gap_rate_n_day,
                         gap_futures, gap_rate_futures, created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.institutionName}, :#{#e.opinion},
                         :#{#e.opinionCode}, :#{#e.prevOpinion}, :#{#e.prevOpinionCode}, :#{#e.targetPrice},
                         :#{#e.prevClose}, :#{#e.gapNDay}, :#{#e.gapRateNDay}, :#{#e.gapFutures},
                         :#{#e.gapRateFutures}, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") AnalystEstimate e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(a) FROM AnalystEstimate a WHERE a.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
