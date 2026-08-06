package com.aaa.collector.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 국내 공매도 일별추이 리포지토리. */
public interface ShortSaleDomesticRepository extends JpaRepository<ShortSaleDomestic, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 short_sale_domestic에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // 중복 충돌에서
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    /**
     * 공매도 일별추이 1건을 멱등 삽입한다 (REQ-BATCH2-021, -024).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_short_sale_domestic (stock_id, trade_date)} 충돌
     * 시 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE id = id}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 UPDATE
     * 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142({@code UPDATE command denied to
     * user 'collector'@'%' for table 'short_sale_domestic'})를 유발한다(ADR-026 Tier-1, ADR-025 §맥락 1).
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
                    INSERT IGNORE INTO short_sale_domestic
                        (stock_id, trade_date, short_sell_qty, short_sell_vol_rate, short_sell_amt,
                         short_sell_amt_rate, short_sell_acc_qty, short_sell_acc_qty_rate,
                         short_sell_acc_amt, short_sell_acc_amt_rate, created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.shortSellQty}, :#{#e.shortSellVolRate}, :#{#e.shortSellAmt},
                         :#{#e.shortSellAmtRate}, :#{#e.shortSellAccQty}, :#{#e.shortSellAccQtyRate},
                         :#{#e.shortSellAccAmt}, :#{#e.shortSellAccAmtRate}, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") ShortSaleDomestic e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(s) FROM ShortSaleDomestic s WHERE s.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);

    /** 최신 적재 시각 조회 (SPEC-OBSV-WARMSTART-001 warm-start용). */
    @Query("SELECT MAX(s.createdAt) FROM ShortSaleDomestic s")
    Optional<LocalDateTime> findMaxCreatedAt();

    /** 최대 거래일 조회 (SPEC-OBSV-WATERMARK-001 REQ-WM-003 warm-start용). */
    @Query("SELECT MAX(s.tradeDate) FROM ShortSaleDomestic s")
    Optional<LocalDate> findMaxTradeDate();

    /**
     * Track 1(레거시·{@code acml_vol} 결측 가드) 대상 배치 조회 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001
     * REQ-SSVC-031, -032).
     *
     * <p>{@code afterId} 커서 기반 순방향 페이지네이션을 사용한다 — REVISION_SUSPECTED로 스킵된 행({@code acml_vol}이 여전히
     * NULL)이 같은 실행 안에서 무한 반복 조회되지 않도록 항상 id를 전진시킨다. 정정에 성공한 행은 {@code acml_vol}이 채워져 이 조회 조건에서 자연히
     * 제외된다(다음 페이지에서 재등장하지 않음).
     *
     * @param afterId 이전 페이지 마지막 id(첫 페이지는 0)
     * @param pageable 페이지 크기만 사용(정렬은 쿼리에 고정)
     * @return id 오름차순 정렬된 대상 행 목록(빈 목록이면 이번 실행에서 더 이상 대상 없음)
     */
    @Query(
            "SELECT s FROM ShortSaleDomestic s JOIN FETCH s.stock"
                    + " WHERE s.shortSellQty > 0 AND s.acmlVol IS NULL AND s.id > :afterId"
                    + " ORDER BY s.id ASC")
    List<ShortSaleDomestic> findTrack1LegacyBacklogBatch(
            @Param("afterId") long afterId, Pageable pageable);

    /**
     * Track 2(상시 재계산 스윕) 대상 배치 조회 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-034).
     *
     * <p>{@code afterId} 커서 기반 순방향 페이지네이션 — Track 1과 동일한 근거(REQ-SSVC-032). Track 2는 처리한 행마다 {@code
     * vol_rate_verified_at}을 항상 기록하므로(REQ-SSVC-035) 처리된 행은 자연히 다음 조회에서 제외된다.
     *
     * @param afterId 이전 페이지 마지막 id(첫 페이지는 0)
     * @param pageable 페이지 크기만 사용(정렬은 쿼리에 고정)
     * @return id 오름차순 정렬된 대상 행 목록(빈 목록이면 이번 실행에서 더 이상 대상 없음)
     */
    @Query(
            "SELECT s FROM ShortSaleDomestic s JOIN FETCH s.stock"
                    + " WHERE s.shortSellQty > 0 AND s.acmlVol IS NOT NULL"
                    + " AND s.volRateVerifiedAt IS NULL AND s.id > :afterId"
                    + " ORDER BY s.id ASC")
    List<ShortSaleDomestic> findTrack2PendingVerificationBatch(
            @Param("afterId") long afterId, Pageable pageable);

    /**
     * M6 레거시 {@code acml_vol} 백필(종목×기간 윈도우 청킹) 전용 — Track 1 대상 행이 있는 서로 다른 종목 id를 오름차순 커서 페이지네이션으로
     * 조회한다 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 plan.md §M6, REQ-SSVC-031/032 재사용).
     *
     * <p>{@link #findTrack1LegacyBacklogBatch}(id 순 페이지네이션, M4 — 일일 증분 처리용)와는 별도 조회다. M6은 TR04 재조회
     * 호출 횟수를 줄이기 위해 종목별로 묶어 기간 윈도우 조회를 수행해야 하므로, id가 아닌 종목 단위로 커서를 전진시킨다.
     *
     * @param afterStockId 이전 페이지 마지막 종목 id(첫 페이지는 0)
     * @param pageable 페이지 크기만 사용(정렬은 쿼리에 고정)
     * @return 종목 id 오름차순 정렬된 목록(빈 목록이면 더 이상 대상 종목 없음)
     */
    @Query(
            "SELECT DISTINCT s.stock.id FROM ShortSaleDomestic s"
                    + " WHERE s.shortSellQty > 0 AND s.acmlVol IS NULL AND s.stock.id > :afterStockId"
                    + " ORDER BY s.stock.id ASC")
    List<Long> findTrack1LegacyBacklogStockIds(
            @Param("afterStockId") long afterStockId, Pageable pageable);

    /**
     * M6 레거시 {@code acml_vol} 백필 전용 — 특정 종목의 Track 1 대상 행 전체를 거래일 오름차순으로 조회한다
     * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 plan.md §M6).
     *
     * <p>거래일 오름차순 정렬은 {@code
     * com.aaa.collector.stock.supply.correction.AcmlVolLegacyBackfillRunner}의 종목×기간 윈도우 그리디 청킹(90일,
     * {@code ShortSaleCollectionService.BACKFILL_LOOKBACK_CALENDAR_DAYS} 재사용)이 인접 날짜를 하나의 TR04 기간
     * 조회로 묶기 위한 전제조건이다.
     *
     * @param stockId 대상 종목 PK
     * @return 거래일 오름차순 정렬된 대상 행 목록(빈 목록이면 해당 종목은 이미 전량 처리됨)
     */
    @Query(
            "SELECT s FROM ShortSaleDomestic s JOIN FETCH s.stock"
                    + " WHERE s.shortSellQty > 0 AND s.acmlVol IS NULL AND s.stock.id = :stockId"
                    + " ORDER BY s.tradeDate ASC")
    List<ShortSaleDomestic> findTrack1LegacyBacklogByStock(@Param("stockId") Long stockId);

    /**
     * Track 1 원자적 정정 쓰기 — {@code acml_vol}·{@code short_sell_vol_rate}·{@code
     * vol_rate_verified_at}을 단일 UPDATE 문으로 함께 기록한다 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001
     * REQ-SSVC-002, -036).
     *
     * <p>단일 행을 대상으로 하는 단일 UPDATE 문이므로 세 컬럼의 쓰기는 DB 엔진 수준에서 원자적이다(AC-1). {@code ON DUPLICATE KEY
     * UPDATE}가 아닌 평이한 {@code UPDATE ... WHERE}를 사용해(REQ-SSVC-061) {@link
     * com.aaa.collector.arch.Tier1InsertIgnoreGuardTest}의 {@code TIER2_TABLE_ALLOWLIST}(4개)를 무수정으로
     * 유지한다(AC-18) — {@code short_sale_domestic}은 M1에서 {@code DbGrantVerifier.TIER2_TABLES}에 이미
     * 편입됐다.
     *
     * <p>네이티브 UPDATE는 영속성 컨텍스트(1차 캐시)를 우회하므로 {@code clearAutomatically = true}로 실행 직후 컨텍스트를 비운다 —
     * 그렇지 않으면 같은 트랜잭션 안에서 이 UPDATE 이전에 로드된 관리 상태 엔티티가 남아, 이후 조회가 새로 반영된 값 대신 오래된 캐시 인스턴스를 반환한다(M8
     * 활성화 시 통합 테스트로 실측 확인).
     *
     * @param id 대상 행 PK
     * @param acmlVol 가드 판정으로 채택된 {@code acml_vol}(MATCHED 재조회값 또는 EVENT_ADJUSTED 역산값)
     * @param shortSellVolRate REQ-SSVC-011 공식({@code daily_ohlcv} 조인)으로 재계산된 값
     * @param verifiedAt 정정 완료 시각
     * @return 영향 행 수(정상 케이스 1, 대상 행이 이미 없으면 0)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE short_sale_domestic
                    SET acml_vol = :acmlVol,
                        short_sell_vol_rate = :shortSellVolRate,
                        vol_rate_verified_at = :verifiedAt
                    WHERE id = :id
                    """,
            nativeQuery = true)
    int updateTrack1Correction(
            @Param("id") Long id,
            @Param("acmlVol") long acmlVol,
            @Param("shortSellVolRate") BigDecimal shortSellVolRate,
            @Param("verifiedAt") LocalDateTime verifiedAt);

    /**
     * Track 2 원자적 검증 쓰기 — {@code short_sell_vol_rate}·{@code vol_rate_verified_at}을 단일 UPDATE 문으로
     * 함께 기록한다 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-035).
     *
     * <p>재계산값이 저장값과 같아도(no-op에 가까운 값 재기록) 동일하게 호출한다 — {@code vol_rate_verified_at}은 두 경우 모두 반드시
     * 기록되어야 한다(AC-9d, "UPDATE 문은 실행되지 않거나 no-op UPDATE로 실행되며(구현 선택)"의 no-op UPDATE 선택).
     *
     * <p>{@link #updateTrack1Correction}와 동일한 이유로 {@code clearAutomatically = true}를 지정한다 — 네이티브
     * UPDATE는 영속성 컨텍스트를 우회한다.
     *
     * @param id 대상 행 PK
     * @param shortSellVolRate REQ-SSVC-011 공식으로 재계산된 값
     * @param verifiedAt 검증 완료 시각
     * @return 영향 행 수(정상 케이스 1, 대상 행이 이미 없으면 0)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE short_sale_domestic
                    SET short_sell_vol_rate = :shortSellVolRate,
                        vol_rate_verified_at = :verifiedAt
                    WHERE id = :id
                    """,
            nativeQuery = true)
    int updateTrack2Verification(
            @Param("id") Long id,
            @Param("shortSellVolRate") BigDecimal shortSellVolRate,
            @Param("verifiedAt") LocalDateTime verifiedAt);

    /**
     * T+0 예비치 소급 정정(근본원인 B, aaa-infra#133) 대상 후보 배치 조회
     * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-010~012).
     *
     * <p>{@code DATE(created_at) = trade_date}(당일 수집 시그니처 — T+0 예비치 오염 대상)이고 {@code trade_date}가
     * {@code [from, to]} 구간(REQ-T0R-011 — 하한 2026-06-29 리터럴, 상한은 REQ-T0R-001 실배포일)에 속하는 행만 대상이다.
     * {@code afterId} 커서 기반 순방향 페이지네이션 — Track 1/Track 2와 동일 근거(REQ-SSVC-032 유사 패턴).
     *
     * @param from 하한(inclusive, REQ-T0R-011 — 2026-06-29 리터럴)
     * @param to 상한(inclusive, REQ-T0R-001 실배포일 — 호출자가 매 실행 재계산해 전달)
     * @param afterId 이전 페이지 마지막 id(첫 페이지는 0)
     * @param pageable 페이지 크기만 사용(정렬은 쿼리에 고정)
     * @return id 오름차순 정렬된 대상 행 목록(빈 목록이면 이번 실행에서 더 이상 대상 없음)
     */
    @Query(
            "SELECT s FROM ShortSaleDomestic s JOIN FETCH s.stock"
                    + " WHERE FUNCTION('DATE', s.createdAt) = s.tradeDate"
                    + " AND s.tradeDate BETWEEN :from AND :to AND s.id > :afterId"
                    + " ORDER BY s.id ASC")
    List<ShortSaleDomestic> findT0RevisionCandidateBatch(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("afterId") long afterId,
            Pageable pageable);

    /**
     * T+0 예비치 소급 정정 원자적 쓰기 — {@code short_sell_qty}·{@code short_sell_vol_rate}를 KIS TR04 라이브 재조회
     * 확정치로 갱신한다 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-020, -021, -030).
     *
     * <p>REQ-T0R-020 — 다른 컬럼으로부터 재계산(recompute)하지 않는다. 이 서비스는 M3의 {@code
     * AcmlVolReconciliationGuard} 3분기 판정을 거치지 않고 TR04 라이브 재조회 값을 그대로 채택한다(Track 1과의 차이점).
     *
     * <p>M1이 편입한 Tier-2 UPDATE 경로를 재사용한다 — 별도 GRANT 불요(REQ-T0R-021). {@code ON DUPLICATE KEY
     * UPDATE}가 아닌 평이한 {@code UPDATE ... WHERE}를 사용해(REQ-SSVC-061과 동일 근거) {@link
     * com.aaa.collector.arch.Tier1InsertIgnoreGuardTest}의 {@code TIER2_TABLE_ALLOWLIST}를 무수정으로
     * 유지한다.
     *
     * <p>Track 1/Track 2와 동일한 이유로 네이티브 UPDATE는 영속성 컨텍스트(1차 캐시)를 우회하므로 {@code clearAutomatically =
     * true}로 실행 직후 컨텍스트를 비운다.
     *
     * @param id 대상 행 PK
     * @param shortSellQty TR04 라이브 재조회 확정 공매도 체결 수량
     * @param shortSellVolRate TR04 라이브 재조회 확정 공매도 거래량 비중
     * @return 영향 행 수(정상 케이스 1, 대상 행이 이미 없으면 0)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE short_sale_domestic
                    SET short_sell_qty = :shortSellQty,
                        short_sell_vol_rate = :shortSellVolRate
                    WHERE id = :id
                    """,
            nativeQuery = true)
    int updateT0RevisionCorrection(
            @Param("id") Long id,
            @Param("shortSellQty") long shortSellQty,
            @Param("shortSellVolRate") BigDecimal shortSellVolRate);
}
