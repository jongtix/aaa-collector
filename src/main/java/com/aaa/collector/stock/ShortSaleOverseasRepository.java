package com.aaa.collector.stock;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미국 공매도 일별({@code short_sale_overseas}) 리포지토리.
 *
 * <p><b>Tier-2 테이블</b>(ADR-017·ADR-026 허용목록) — Tier-1 INSERT IGNORE 원칙의 명시적 예외로 {@code INSERT ...
 * ON DUPLICATE KEY UPDATE}(UPSERT)가 허용된다. 단, 두 수집 소스(Daily / Short Interest)가 한 행을 다른 시점에 쓰므로
 * <b>SET 절은 소스별 전용 컬럼만</b> 갱신해 다른 소스 컬럼을 NULL로 덮지 않는다(REQ-SSO-022):
 *
 * <ul>
 *   <li>{@link #upsertDaily}: {@code short_volume}/{@code total_volume}/{@code daily_collected_at}
 *       (+ forward 매칭된 {@code short_interest}/{@code short_interest_date}가 non-null이면 함께) SET.
 *   <li>{@link #upsertInterest}: {@code short_interest}/{@code short_interest_date}/{@code
 *       interest_collected_at}만 SET. Daily 컬럼은 SET 절에서 제외(보존), {@code daily_collected_at}은 NULL
 *       유지(SI-origin phantom 0거래량 구분 — REQ-SSO-007).
 * </ul>
 *
 * <p>{@code created_at}/{@code updated_at}은 {@code BaseEntity} JPA auditing이 네이티브 SQL에 걸리지 않으므로
 * SQL에서 {@code NOW()}로 명시한다.
 */
public interface ShortSaleOverseasRepository extends JpaRepository<ShortSaleOverseas, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — short_sale_overseas는 Tier-2라 ON DUPLICATE KEY UPDATE 허용
    // @MX:REASON: [AUTO] ADR-026 Tier-2 허용목록(SELECT/INSERT/UPDATE). SET 절은 Daily 전용 컬럼만,
    // interest는 COALESCE로 forward null 시 기존값 보존 (REQ-SSO-013a/-022)
    /**
     * Daily 수집 결과를 멱등 UPSERT한다(Tier-2). forward 매칭값({@code shortInterest}/{@code
     * shortInterestDate})이 non-null이면 함께 적재하고, null이면 기존 interest 값을 보존한다(REQ-SSO-013/-013a).
     *
     * @param stockId 종목 PK
     * @param tradeDate 거래일
     * @param shortVolume 합산 공매도 거래량(Σ shortParQuantity)
     * @param totalVolume 합산 전체 거래량(Σ totalParQuantity)
     * @param dailyCollectedAt Daily 수집 시각
     * @param shortInterest forward 매칭 공매도 잔고(없으면 {@code null})
     * @param shortInterestDate forward 매칭 잔고 기준일(없으면 {@code null})
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO short_sale_overseas
                        (stock_id, trade_date, short_volume, total_volume, short_interest,
                         short_interest_date, daily_collected_at, created_at, updated_at)
                    VALUES
                        (:stockId, :tradeDate, :shortVolume, :totalVolume, :shortInterest,
                         :shortInterestDate, :dailyCollectedAt, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE
                        short_volume = VALUES(short_volume),
                        total_volume = VALUES(total_volume),
                        daily_collected_at = VALUES(daily_collected_at),
                        short_interest = COALESCE(VALUES(short_interest), short_interest),
                        short_interest_date = COALESCE(VALUES(short_interest_date), short_interest_date),
                        updated_at = NOW()
                    """,
            nativeQuery = true)
    void upsertDaily(
            @Param("stockId") Long stockId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("shortVolume") long shortVolume,
            @Param("totalVolume") long totalVolume,
            @Param("dailyCollectedAt") LocalDateTime dailyCollectedAt,
            @Param("shortInterest") Long shortInterest,
            @Param("shortInterestDate") LocalDate shortInterestDate);

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — short_sale_overseas는 Tier-2라 ON DUPLICATE KEY UPDATE 허용
    // @MX:REASON: [AUTO] ADR-026 Tier-2. SET 절은 interest 전용 컬럼만 — Daily 컬럼/daily_collected_at은
    // 제외해 보존 (REQ-SSO-007/-014b/-022). short_volume/total_volume은 V7 NOT NULL DEFAULT 0
    /**
     * Short Interest 수집 결과를 {@code trade_date = settlementDate} 행으로 멱등 UPSERT한다(Tier-2). interest
     * 전용 컬럼만 SET하여 Daily 컬럼({@code short_volume}/{@code total_volume}/{@code daily_collected_at})은
     * 보존하며, {@code revisionFlag="R"} 재적재 시 잔고를 갱신한다(REQ-SSO-014b). {@code float_shares}/{@code
     * si_pct_float}는 적재하지 않아 NULL로 남는다(REQ-SSO-004).
     *
     * @param stockId 종목 PK
     * @param settlementDate settlementDate(= trade_date = short_interest_date)
     * @param shortInterest 당기 공매도 잔고({@code currentShortPositionQuantity})
     * @param interestCollectedAt Short Interest 수집 시각
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO short_sale_overseas
                        (stock_id, trade_date, short_interest, short_interest_date,
                         interest_collected_at, created_at, updated_at)
                    VALUES
                        (:stockId, :settlementDate, :shortInterest, :settlementDate,
                         :interestCollectedAt, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE
                        short_interest = VALUES(short_interest),
                        short_interest_date = VALUES(short_interest_date),
                        interest_collected_at = VALUES(interest_collected_at),
                        updated_at = NOW()
                    """,
            nativeQuery = true)
    void upsertInterest(
            @Param("stockId") Long stockId,
            @Param("settlementDate") LocalDate settlementDate,
            @Param("shortInterest") Long shortInterest,
            @Param("interestCollectedAt") LocalDateTime interestCollectedAt);

    /**
     * 종목 집합 + 기준 거래일에 대해 {@code short_interest IS NOT NULL AND short_interest_date ≤ tradeDate}인 최신
     * 잔고 1건을 종목별로 반환한다(LOCF forward 조회). {@code ROW_NUMBER()} 윈도우 함수로 종목당 1행만 추출해 종목별 단건 루프(N+1)를
     * 회피한다(D5).
     *
     * <p>Spring Data가 {@code Map} 반환을 직접 지원하지 않으므로 native 투영을 {@link
     * #findLatestShortInterestByStockIds} default 메서드에서 {@code Map}으로 조립한다.
     *
     * @param stockIds 조회 종목 PK 집합
     * @param tradeDate 기준 거래일
     * @return 종목별 최신 잔고 투영 행
     */
    @Query(
            value =
                    """
                    SELECT stock_id AS stockId, short_interest AS shortInterest,
                           short_interest_date AS shortInterestDate
                    FROM (
                        SELECT stock_id, short_interest, short_interest_date,
                               ROW_NUMBER() OVER (
                                   PARTITION BY stock_id
                                   ORDER BY short_interest_date DESC
                               ) AS rn
                        FROM short_sale_overseas
                        WHERE stock_id IN (:stockIds)
                          AND short_interest IS NOT NULL
                          AND short_interest_date <= :tradeDate
                    ) ranked
                    WHERE ranked.rn = 1
                    """,
            nativeQuery = true)
    List<LatestShortInterestRow> findLatestShortInterestRows(
            @Param("stockIds") Collection<Long> stockIds, @Param("tradeDate") LocalDate tradeDate);

    /**
     * 종목별 최신 forward 잔고를 {@code Map<stockId, ShortInterestSnapshot>}으로 반환한다. 종목 집합이 비어 있으면 빈 Map을
     * 반환한다(IN 빈 절 회피).
     *
     * @param stockIds 조회 종목 PK 집합
     * @param tradeDate 기준 거래일
     * @return 종목 PK → 최신 잔고 스냅샷
     */
    default Map<Long, ShortInterestSnapshot> findLatestShortInterestByStockIds(
            Collection<Long> stockIds, LocalDate tradeDate) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        return findLatestShortInterestRows(stockIds, tradeDate).stream()
                .collect(
                        Collectors.toMap(
                                LatestShortInterestRow::getStockId,
                                row ->
                                        new ShortInterestSnapshot(
                                                row.getShortInterest(),
                                                row.getShortInterestDate())));
    }

    /**
     * 주어진 종목 집합 + 범위 내 이미 적재된 {@code (stock_id, short_interest_date)} 쌍을 {@code java.sql.Date}로
     * 조회한다. 공개 API는 {@link #findExistingInterestPairsByStockIds} default 메서드가 {@code Map<Long,
     * Set<LocalDate>>}로 조립해 노출한다(native DATE → LocalDate 컨버터 미등록 회피).
     *
     * <p>종목 집합이 비어 있으면 호출하지 않는다(IN 빈 절 회피, 호출처 책임).
     */
    @Query(
            value =
                    """
                    SELECT stock_id AS stockId, short_interest_date AS settlementDate
                    FROM short_sale_overseas
                    WHERE stock_id IN (:stockIds)
                      AND short_interest_date IS NOT NULL
                      AND short_interest_date BETWEEN :from AND :to
                    """,
            nativeQuery = true)
    List<StockSettlementPairRow> findExistingInterestPairRows(
            @Param("stockIds") Collection<Long> stockIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * 주어진 종목 집합 + 범위 내 이미 적재된 {@code (stock_id, short_interest_date)} 쌍을 {@code Map<stockId,
     * Set<settlementDate>>}로 반환한다(미적재 차집합 산출용, REQ-SSO-014a). 종목×날짜 쌍 단위로 존재를 판정하여 교차 종목 침묵 드롭을
     * 방지한다.
     *
     * <p>종목 집합이 비어 있으면 빈 Map을 반환한다(IN 빈 절 회피).
     *
     * @param stockIds 조회 종목 PK 집합
     * @param from 범위 시작(포함)
     * @param to 범위 끝(포함)
     * @return 종목 PK → 이미 적재된 settlementDate 집합
     */
    default Map<Long, Set<LocalDate>> findExistingInterestPairsByStockIds(
            Collection<Long> stockIds, LocalDate from, LocalDate to) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        return findExistingInterestPairRows(stockIds, from, to).stream()
                .collect(
                        Collectors.groupingBy(
                                StockSettlementPairRow::getStockId,
                                Collectors.mapping(
                                        row -> row.getSettlementDate().toLocalDate(),
                                        Collectors.toCollection(HashSet::new))));
    }

    /**
     * overseas-shortsale-daily warm-start용: 가장 최근 daily_collected_at을 조회한다
     * (SPEC-OBSV-WARMSTART-001).
     *
     * @return MAX(dailyCollectedAt) — 한 건도 없으면 {@link Optional#empty()}
     */
    @Query("SELECT MAX(s.dailyCollectedAt) FROM ShortSaleOverseas s")
    Optional<LocalDateTime> findMaxDailyCollectedAt();

    /**
     * overseas-shortsale-interest warm-start용: 가장 최근 interest_collected_at을 조회한다
     * (SPEC-OBSV-WARMSTART-001).
     *
     * @return MAX(interestCollectedAt) — 한 건도 없으면 {@link Optional#empty()}
     */
    @Query("SELECT MAX(s.interestCollectedAt) FROM ShortSaleOverseas s")
    Optional<LocalDateTime> findMaxInterestCollectedAt();

    /** {@link #findLatestShortInterestRows} native 투영 인터페이스. */
    interface LatestShortInterestRow {
        Long getStockId();

        Long getShortInterest();

        LocalDate getShortInterestDate();
    }

    /** {@link #findExistingInterestPairRows} native 투영 인터페이스. */
    interface StockSettlementPairRow {
        Long getStockId();

        Date getSettlementDate();
    }

    /**
     * 종목별 forward 조회 결과 — {@code short_interest_date ≤ tradeDate}인 가장 최근 공매도 잔고 1건(LOCF 출처).
     *
     * @param shortInterest 당기 공매도 잔고(주식 수)
     * @param shortInterestDate 잔고 기준 settlementDate
     */
    record ShortInterestSnapshot(Long shortInterest, LocalDate shortInterestDate) {}
}
