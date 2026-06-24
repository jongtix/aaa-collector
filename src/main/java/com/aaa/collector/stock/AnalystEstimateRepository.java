package com.aaa.collector.stock;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 종목 투자의견 리포지토리 (SPEC-COLLECTOR-BATCH-004). */
public interface AnalystEstimateRepository extends JpaRepository<AnalystEstimate, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 analyst_estimates(Tier-1)에 UPDATE 권한이 없어 ON DUPLICATE KEY
    // UPDATE 사용 시
    // 중복 충돌에서 SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-BATCH-004)
    /**
     * 투자의견 1건을 멱등 삽입한다 (REQ-BATCH4-033).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_analyst_estimates (stock_id, trade_date,
     * institution_name)} 충돌 시 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다. 매일 14일 윈도우 재조회 시 중복 행을
     * 재삽입하지 않는다. {@code institution_name}이 빈 문자열인 행도 uk 구성요소로 정상 저장한다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE id = id}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 SET 대상
     * 컬럼의 UPDATE 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142({@code UPDATE command
     * denied})를 유발한다(ADR-026 Tier-1).
     *
     * <p>{@code INSERT IGNORE}는 UPDATE 권한을 요구하지 않아 {@code collector} 최소 권한 (SELECT, INSERT)을
     * 유지한다(TECHSPEC §4 시계열 테이블 원칙).
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — H2 미동작, 반드시 MySQL Testcontainer 통합 테스트로
     * 검증한다.
     *
     * @param e 저장 대상 엔티티 (트랜지언트, stock·trade_date·institution_name 등 매핑 필드 보유)
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO analyst_estimates
                        (stock_id, trade_date, institution_name, opinion, opinion_code, prev_opinion,
                         prev_opinion_code, target_price, prev_close, gap_n_day, gap_rate_n_day,
                         gap_futures, gap_rate_futures, created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.institutionName}, :#{#e.opinion},
                         :#{#e.opinionCode}, :#{#e.prevOpinion}, :#{#e.prevOpinionCode}, :#{#e.targetPrice},
                         :#{#e.prevClose}, :#{#e.gapNDay}, :#{#e.gapRateNDay}, :#{#e.gapFutures},
                         :#{#e.gapRateFutures}, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") AnalystEstimate e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(a) FROM AnalystEstimate a WHERE a.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);

    /** 최신 적재 시각 조회 (SPEC-OBSV-WARMSTART-001 warm-start용). */
    @Query("SELECT MAX(a.createdAt) FROM AnalystEstimate a")
    Optional<LocalDateTime> findMaxCreatedAt();
}
