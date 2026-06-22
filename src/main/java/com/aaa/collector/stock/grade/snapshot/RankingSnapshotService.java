package com.aaa.collector.stock.grade.snapshot;

import com.aaa.collector.stock.grade.AdtvPercentileCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래대금 순위 스냅샷 영속화 서비스.
 *
 * <p>REQ-GRADE-001: raw 순위 엔트리를 단일 트랜잭션으로 원자적으로 저장한다. percentile은 저장하지 않으며 classify 시점에 재계산한다.
 * snapshot_date는 시장 기준 zone의 cycle 일자로 결정적으로 도출된다 (C1).
 *
 * <p>REQ-GRADE-002: 빈 엔트리 또는 degenerate 임계 미만이면 저장 SKIP(직전 스냅샷 보존).
 *
 * <p>멱등성은 {@code INSERT IGNORE} + {@code UNIQUE(market, snapshot_date, symbol)}로 보장된다 (ADR-026
 * Tier-1, collector DELETE 권한 불필요). 동일 cycle 수동 재실행 시 기존 행과의 union이 보존된다. prune은 설계상 폐기 — 이력 무제한
 * 보존(데이터 볼륨 경미).
 */
// @MX:ANCHOR: [AUTO] 스냅샷 저장·조회 단일 진입점 — GradeClassificationService + RankingSnapshotScheduler
// 양방향 의존(fan_in >= 3 예상).
// @MX:REASON: classify와 scheduler 양측에서 호출하는 핵심 영속화 컴포넌트.
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingSnapshotService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** degenerate 절대 하한 — cold start 시 적용 (M1). */
    private static final int N_MIN = 20;

    private final RankingSnapshotRepository snapshotRepository;

    /**
     * snapshot_date 도출 (C1).
     *
     * <ul>
     *   <li>KRX: captured_at을 Asia/Seoul로 변환한 날짜
     *   <li>US: captured_at을 America/New_York으로 변환한 날짜
     * </ul>
     *
     * @param capturedAt 캡처 시각 (UTC)
     * @param market 시장 구분 ("KRX" 또는 "US")
     * @return 시장 기준 cycle 일자
     */
    public LocalDate deriveSnapshotDate(Instant capturedAt, String market) {
        ZoneId zone = "KRX".equals(market) ? KST : ET;
        return ZonedDateTime.ofInstant(capturedAt, zone).toLocalDate();
    }

    /**
     * degenerate 판정 (M1).
     *
     * <ul>
     *   <li>직전 스냅샷 있으면: count < prevCount × 0.5 → true
     *   <li>cold (prevCount == 0): count < N_MIN → true
     * </ul>
     *
     * @param count 현재 fetch 결과 종목 수
     * @param prevCount 직전 정상 스냅샷 종목 수 (cold 시 0)
     * @return degenerate 여부
     */
    public boolean isDegenerate(int count, int prevCount) {
        if (prevCount > 0) {
            return count < prevCount * 0.5;
        }
        // cold
        return count < N_MIN;
    }

    /**
     * 직전 스냅샷 종목 수 조회.
     *
     * @param market 시장 구분
     * @return 직전 정상 스냅샷 종목 수. cold이면 0.
     */
    public int findPrevSnapshotCount(String market) {
        return snapshotRepository
                .findLatestSnapshotDate(market)
                .map(d -> (int) snapshotRepository.countByMarketAndSnapshotDate(market, d))
                .orElse(0);
    }

    /**
     * 시장+날짜로 스냅샷 엔트리 조회.
     *
     * @param market 시장 구분
     * @param snapshotDate 스냅샷 날짜
     * @return 해당 날짜 스냅샷 목록 (없으면 빈 리스트)
     */
    public List<RankingSnapshot> findByMarketAndDate(String market, LocalDate snapshotDate) {
        return snapshotRepository.findByMarketAndSnapshotDate(market, snapshotDate);
    }

    /**
     * 최신 snapshot_date 조회.
     *
     * @param market 시장 구분
     * @return 최신 날짜. 없으면 empty.
     */
    public Optional<LocalDate> findLatestSnapshotDate(String market) {
        return snapshotRepository.findLatestSnapshotDate(market);
    }

    /**
     * 순위 스냅샷 저장 (원자 트랜잭션).
     *
     * <p>빈 엔트리 또는 degenerate 임계 미만이면 저장 SKIP. 멱등성은 {@code INSERT IGNORE} + {@code UNIQUE(market,
     * snapshot_date, symbol)}로 보장한다 (ADR-026 Tier-1). collector는 ranking_snapshots에 UPDATE/DELETE
     * 권한이 없으므로 DELETE-then-reinsert 패턴을 사용하지 않는다. 동일 cycle 수동 재실행 시 기존 행과 신규 행의 union이 보존된다.
     *
     * @param market 시장 구분
     * @param entries AdtvPercentileCalculator 입력용 엔트리 목록
     * @param clock 캡처 시각 결정 시계 (테스트 주입용)
     */
    @Transactional
    public void saveSnapshot(
            String market, List<AdtvPercentileCalculator.RankEntry> entries, Clock clock) {

        if (entries.isEmpty()) {
            log.warn("순위 데이터 공백 — 스냅샷 저장 SKIP(직전 보존): market={}", market);
            return;
        }

        // degenerate 가드 (M1)
        int prevCount = findPrevSnapshotCount(market);
        if (isDegenerate(entries.size(), prevCount)) {
            log.warn(
                    "순위 데이터 degenerate — 저장 SKIP(직전 보존): market={}, count={}, prevCount={}",
                    market,
                    entries.size(),
                    prevCount);
            return;
        }

        Instant capturedAt = clock.instant();
        LocalDate snapshotDate = deriveSnapshotDate(capturedAt, market);

        // INSERT IGNORE로 멱등 삽입 — 중복 행은 무시, 신규 행만 삽입 (ADR-026 Tier-1)
        for (int i = 0; i < entries.size(); i++) {
            AdtvPercentileCalculator.RankEntry e = entries.get(i);
            snapshotRepository.insertIgnore(
                    market, snapshotDate, e.symbol(), e.rankValue(), i + 1, capturedAt);
        }

        log.info(
                "순위 스냅샷 저장 완료: market={}, snapshotDate={}, count={}",
                market,
                snapshotDate,
                entries.size());
    }
}
