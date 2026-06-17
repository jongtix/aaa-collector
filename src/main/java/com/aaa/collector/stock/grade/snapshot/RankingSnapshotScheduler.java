package com.aaa.collector.stock.grade.snapshot;

import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisDomesticRankingResponse;
import com.aaa.collector.kis.ranking.KisOverseasRankingClient;
import com.aaa.collector.kis.ranking.KisOverseasRankingResponse;
import com.aaa.collector.stock.grade.AdtvPercentileCalculator;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 거래대금 순위 스냅샷 스케줄러.
 *
 * <p>KRX 04:00 KST / US 16:00 KST에 각각 시장별 순위를 fetch하여 스냅샷 테이블에 저장한다.
 *
 * <ul>
 *   <li>KRX 잡: 04:00 KST, KRX 완결 윈도우(18:40–05:00 KST) 내. 05:00 리셋 가드 적용.
 *   <li>US 잡: 16:00 KST, US 완결 윈도우 내. KST-anchored (§Decision E).
 * </ul>
 *
 * <p>REQ-GRADE-001, REQ-GRADE-002, REQ-GRADE-007.
 */
// @MX:NOTE: [AUTO] 거래대금 순위 완결 윈도우 내 스냅샷 저장(KRX 04:00 KST / US 16:00 KST).
// withhold-on-empty + reset 가드. US는 KST-anchored(§Decision E).
// 잡별 독립 AtomicBoolean single-flight 가드(REQ-GRADE-007).
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingSnapshotScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** KRX 리셋 가드: 05:00 KST 이후 fetch 금지. */
    private static final LocalTime KRX_RESET_GUARD = LocalTime.of(5, 0);

    private final KisDomesticRankingClient domesticRankingClient;
    private final KisOverseasRankingClient overseasRankingClient;
    private final RankingSnapshotService snapshotService;

    // 테스트 주입 가능한 시계 (기본: 시스템 시계)
    private Clock clock = Clock.system(KST);

    // REQ-GRADE-007: 작업별 독립 single-flight 가드
    private final AtomicBoolean krxRunning = new AtomicBoolean(false);
    private final AtomicBoolean usRunning = new AtomicBoolean(false);

    /**
     * KRX 거래대금 순위 스냅샷 잡 — 매일 04:00 KST.
     *
     * <p>KRX 완결 윈도우(18:40 KST ~ 05:00 KST) 내에서 fetch한다. 05:00 KST 리셋 가드: 이후 시각에는 fetch를 수행하지
     * 않는다(REQ-GRADE-002). single-flight 가드(REQ-GRADE-007).
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 예외 격리 — 다음 cycle 가드 reset 보장
    public void snapshotKrx() {
        if (!krxRunning.compareAndSet(false, true)) {
            log.warn("KRX 스냅샷 잡 이전 실행 중 — 중복 실행 스킵(REQ-GRADE-007)");
            return;
        }
        try {
            // 05:00 KST 리셋 가드 (REQ-GRADE-002)
            LocalTime nowKst = ZonedDateTime.now(clock.withZone(KST)).toLocalTime();
            if (!nowKst.isBefore(KRX_RESET_GUARD)) {
                log.warn("KRX 05:00 리셋 윈도우 초과 — fetch 미수행: nowKst={}", nowKst);
                return;
            }

            log.info("KRX 순위 스냅샷 잡 시작: nowKst={}", nowKst);
            List<KisDomesticRankingResponse.RankedStock> ranked =
                    domesticRankingClient.fetchRanking();
            List<AdtvPercentileCalculator.RankEntry> entries = toDomesticEntries(ranked);
            snapshotService.saveSnapshot("KRX", entries, clock);
        } catch (Exception e) {
            log.error("KRX 순위 스냅샷 잡 실패 — 다음 cycle에 self-heal", e);
        } finally {
            krxRunning.set(false);
        }
    }

    /**
     * US 거래대금 순위 스냅샷 잡 — 매일 16:00 KST (KST-anchored, §Decision E).
     *
     * <p>US 완결 윈도우(ET 20:30 ~ ET 04:00) 내 안전 마진 위치. AUTH 수정 필수(REQ-GRADE-003). single-flight
     * 가드(REQ-GRADE-007).
     */
    @Scheduled(cron = "0 0 16 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 예외 격리 — 다음 cycle 가드 reset 보장
    public void snapshotUs() {
        if (!usRunning.compareAndSet(false, true)) {
            log.warn("US 스냅샷 잡 이전 실행 중 — 중복 실행 스킵(REQ-GRADE-007)");
            return;
        }
        try {
            log.info("US 순위 스냅샷 잡 시작 (16:00 KST)");
            List<KisOverseasRankingResponse.RankedStock> ranked =
                    overseasRankingClient.fetchRanking();
            List<AdtvPercentileCalculator.RankEntry> entries = toOverseasEntries(ranked);
            snapshotService.saveSnapshot("US", entries, clock);
        } catch (Exception e) {
            log.error("US 순위 스냅샷 잡 실패 — 다음 cycle에 self-heal", e);
        } finally {
            usRunning.set(false);
        }
    }

    /**
     * 국내 순위 응답을 AdtvPercentileCalculator 입력용 RankEntry 목록으로 변환한다.
     *
     * <p>dataRank 파싱 실패(0.0) 항목은 제외한다 (GradeClassificationService와 동일 처리).
     */
    private List<AdtvPercentileCalculator.RankEntry> toDomesticEntries(
            List<KisDomesticRankingResponse.RankedStock> ranked) {
        return ranked.stream()
                .map(
                        r ->
                                new AdtvPercentileCalculator.RankEntry(
                                        r.mkscShrnIscd(), parseRankAsValue(r.dataRank())))
                .filter(e -> e.rankValue() > 0.0)
                .toList();
    }

    /**
     * 해외 순위 응답을 AdtvPercentileCalculator 입력용 RankEntry 목록으로 변환한다.
     *
     * <p>invertRank 파싱 실패(0.0) 항목은 제외한다.
     */
    private List<AdtvPercentileCalculator.RankEntry> toOverseasEntries(
            List<KisOverseasRankingResponse.RankedStock> ranked) {
        int total = ranked.size();
        return ranked.stream()
                .map(
                        r ->
                                new AdtvPercentileCalculator.RankEntry(
                                        r.symb(), invertRank(r.rank(), total)))
                .filter(e -> e.rankValue() > 0.0)
                .toList();
    }

    /** 순위 문자열을 역수로 변환(높은 값 = 상위). 파싱 실패 시 0.0 반환. */
    private double parseRankAsValue(String rank) {
        try {
            int rankInt = Integer.parseInt(rank.trim());
            return 1.0 / rankInt;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** 해외 rank를 inverted value로 변환. 파싱 실패 시 0.0 반환. */
    private double invertRank(String rank, int total) {
        try {
            int rankInt = Integer.parseInt(rank.trim());
            return total - rankInt + 1.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
