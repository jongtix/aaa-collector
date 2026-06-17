package com.aaa.collector.stock.grade.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래대금 순위 스냅샷 엔티티.
 *
 * <p>시장별·스냅샷일별 RAW 순위 엔트리를 저장한다. percentile은 저장하지 않으며 classify 시점에 재계산한다 (REQ-GRADE-001).
 * snapshot_date는 시장 기준 zone의 캡처 cycle 일자다 (C1).
 *
 * <p>UNIQUE(market, snapshot_date, symbol) 제약으로 동일 cycle 재실행이 멱등하게 보장된다 (C2).
 */
// @MX:NOTE: [AUTO] 등급 입력 중 유일하게 테이블에 없던 거래대금 순위를 raw로 영속화.
// percentile은 classify 시점 재계산 — 라이브 윈도우 의존 제거(GRADE-003 v2.0.0).
@Entity
@Table(
        name = "ranking_snapshots",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_ranking_snapshots_market_date_symbol",
                        columnNames = {"market", "snapshot_date", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RankingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 시장 구분 (KRX/US). */
    @Column(name = "market", nullable = false, length = 10)
    private String market;

    /**
     * 시장 기준 zone의 캡처 cycle 일자 (C1).
     *
     * <ul>
     *   <li>KRX: 04:00 KST captured_at → Asia/Seoul 날짜
     *   <li>US: 16:00 KST captured_at → America/New_York 날짜
     * </ul>
     */
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** 종목 코드. */
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    /**
     * 순위 결정 값.
     *
     * <ul>
     *   <li>KRX: 1/rankInt (높을수록 상위)
     *   <li>US: total - rankInt + 1 (높을수록 상위)
     * </ul>
     */
    @Column(name = "rank_value", nullable = false)
    private double rankValue;

    /** 원본 순위 위치. */
    @Column(name = "rank_position", nullable = false)
    private int rankPosition;

    /** 스냅샷 캡처 시각 (UTC). */
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    private RankingSnapshot(
            String market,
            LocalDate snapshotDate,
            String symbol,
            double rankValue,
            int rankPosition,
            Instant capturedAt) {
        this.market = market;
        this.snapshotDate = snapshotDate;
        this.symbol = symbol;
        this.rankValue = rankValue;
        this.rankPosition = rankPosition;
        this.capturedAt = capturedAt;
    }

    /**
     * 팩토리 메서드.
     *
     * @param market 시장 구분
     * @param snapshotDate 시장 기준 cycle 일자
     * @param symbol 종목 코드
     * @param rankValue 순위 결정 값
     * @param rankPosition 원본 순위 위치
     * @param capturedAt 캡처 시각
     * @return 새 RankingSnapshot 인스턴스
     */
    public static RankingSnapshot of(
            String market,
            LocalDate snapshotDate,
            String symbol,
            double rankValue,
            int rankPosition,
            Instant capturedAt) {
        return new RankingSnapshot(
                market, snapshotDate, symbol, rankValue, rankPosition, capturedAt);
    }
}
