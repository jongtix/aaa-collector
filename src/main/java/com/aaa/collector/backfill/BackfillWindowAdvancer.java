package com.aaa.collector.backfill;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 백필 윈도우 anchor 전진기 (SPEC-COLLECTOR-BACKFILL-001 T5, SPEC-COLLECTOR-BACKFILL-005 T2).
 *
 * <p>순수 로직 — KIS/Spring·외부 거래일 캘린더 비의존.
 *
 * <ul>
 *   <li><b>anchor 전진</b>: 다음 anchor = 직전 윈도우 최소 거래일 − 1 달력일(REQ-015, MA-02). 단순 {@code
 *       minusDays(1)} — 영업일 계산이 아니다. KIS가 비거래일을 자동 스킵해 그 이전 거래일부터 반환한다.
 *   <li><b>그룹 A 고정 플로어</b>: from-date는 anchor 무관 고정 플로어({@link #floorDate}, 기본 1950-01-01). 상폐 종목의
 *       초기 윈도우 0건 오종료를 해소한다(SPEC-COLLECTOR-BACKFILL-005). 해외 BYMD는 anchor만 의미가 있으므로 from-date는 국내
 *       기간 윈도우 전용이다.
 *   <li><b>그룹 B anchor 거부(rt_cd=2)</b>: 무전진이 아니라 anchor를 −1 달력일씩 보정해 영업일에 닿을 때까지 한도 ({@code
 *       anchor-skip-max}) 내 재시도한다(REQ-016).
 * </ul>
 *
 * <p>고정 플로어 floorDate(기본 1950-01-01)·anchor-skip-max(기본 10)는 생성자 주입 — T7 {@code
 * BackfillProperties}가 주입하기 전까지 호출자가 직접 전달한다.
 */
public final class BackfillWindowAdvancer {

    /** 그룹 A from-date 고정 플로어 — anchor 무관, KRX 개장일(1956-03-03)보다 이전. */
    private final LocalDate floorDate;

    /** 그룹 B rt_cd=2 anchor 보정 최대 시도 횟수. */
    private final int anchorSkipMax;

    /**
     * GROUP_B {@code short_sale_domestic} lookback 달력일 — {@code
     * ShortSaleCollectionService.BACKFILL_LOOKBACK_CALENDAR_DAYS}와 동일 값(REQ-BACKFILL-174, 재정의 아님).
     */
    private static final int SHORT_SALE_LOOKBACK_DAYS = 90;

    /**
     * GROUP_B {@code investor_trend} lookback 달력일 — {@code
     * InvestorTrendCollectionService.BACKFILL_LOOKBACK_CALENDAR_DAYS}와 동일 값(REQ-BACKFILL-174, 재정의
     * 아님).
     */
    private static final int INVESTOR_TREND_LOOKBACK_DAYS = 45;

    /**
     * GROUP_B {@code credit_balance} lookback 달력일 — {@code
     * CreditBalanceCollectionService.BACKFILL_LOOKBACK_CALENDAR_DAYS}와 동일 값(REQ-BACKFILL-174, 재정의
     * 아님).
     */
    private static final int CREDIT_BALANCE_LOOKBACK_DAYS = 45;

    /**
     * @param floorDate 그룹 A 고정 플로어 from-date (기본 1950-01-01)
     * @param anchorSkipMax 그룹 B anchor 보정 한도(기본 10)
     */
    public BackfillWindowAdvancer(LocalDate floorDate, int anchorSkipMax) {
        this.floorDate = Objects.requireNonNull(floorDate, "floorDate cannot be null");
        this.anchorSkipMax = anchorSkipMax;
    }

    /**
     * 다음 윈도우 anchor = 최소 거래일 − 1 달력일(REQ-015, MA-02).
     *
     * @param oldestTradeDate 직전 윈도우가 반환한 최소 거래일
     * @return 다음 anchor(−1 달력일)
     */
    public LocalDate nextAnchor(LocalDate oldestTradeDate) {
        return oldestTradeDate.minusDays(1);
    }

    /**
     * 그룹 A from-date — anchor 무관 고정 플로어를 반환한다 (SPEC-COLLECTOR-BACKFILL-005).
     *
     * <p>상폐 종목의 초기 윈도우가 anchor 기반 슬라이딩으로 0건 종료되는 오종료를 해소하기 위해 고정 플로어로 전환한다. 해외 daily_ohlcv는 이 메서드를
     * 호출하지 않는다(anchor 단점 방식 유지).
     *
     * @return 고정 플로어 floorDate(기본 1950-01-01)
     */
    public LocalDate groupAFromDate() {
        return floorDate;
    }

    /**
     * 그룹 B anchor 거부(rt_cd=2) 보정 — anchor를 −1 달력일 보정하고 시도 횟수를 1 증가시킨다(REQ-016).
     *
     * <p>무전진으로 집계하지 않는다. 누적 시도가 {@code anchor-skip-max}에 도달하면 {@code exhausted=true} — 호출자는 당 회차
     * skip한다.
     *
     * @param rejectedAnchor rt_cd=2로 거부된 anchor
     * @param attemptsSoFar 지금까지 누적된 보정 시도 횟수
     * @return 보정된 anchor·누적 시도·한도 초과 여부
     */
    public AnchorCorrectionResult correctRejectedAnchor(
            LocalDate rejectedAnchor, int attemptsSoFar) {
        int attempts = attemptsSoFar + 1;
        return new AnchorCorrectionResult(
                rejectedAnchor.minusDays(1), attempts, attempts >= anchorSkipMax);
    }

    /**
     * GROUP_B 첫 probe 구간 전용 backward advance — 아직 수집 이력이 없는 상태에서 0건 윈도우를 받았을 때, anchor를 테이블별
     * lookback stride만큼 과거로 이동하고 floor로 clamp한다 (SPEC-COLLECTOR-BACKFILL-013 REQ-BACKFILL-164).
     *
     * <p>기존 {@link #nextAnchor}(데이터 발견 후 oldest−1일 walk-back, GROUP_A와 공유)는 변경하지 않고 별도 메서드로 분리한다
     * (REQ-BACKFILL-174). GROUP_B per-window lookback 값(short_sale 90 / investor_trend 45 /
     * credit_balance 45)은 각 수집 서비스의 {@code BACKFILL_LOOKBACK_CALENDAR_DAYS} 상수와 동일하게 미러링한다 — 값을
     * 재정의하지 않는다. {@code backfill} 패키지가 {@code stock.supply} 패키지에 의존하는 역방향을 피하기 위해(기존 아키텍처: {@code
     * stock} → {@code backfill} 단방향, {@link BackfillWindowExecutor} 클래스 Javadoc 참조) 상수를 참조가 아닌 값으로
     * 미러링한다.
     *
     * @param dataTable 대상 GROUP_B data_table (short_sale_domestic / investor_trend /
     *     credit_balance)
     * @param currentAnchor 이번 윈도우가 조회한 anchor
     * @param floor probe 하한(종목 listedDate 또는 GROUP_B 전역 플로어)
     * @return 과거로 전진된 anchor, floor 미만이면 floor로 clamp
     */
    // @MX:NOTE: [AUTO] GROUP_B 전용 backward probe stride — floor로 clamp, 무한 walk-back 방지
    // @MX:REASON: 상폐/장기중단 종목 "전결손 위장" 해소 — 첫 probe 구간이 floor까지 유계 전진
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-013
    public LocalDate nextGroupBProbeAnchor(
            String dataTable, LocalDate currentAnchor, LocalDate floor) {
        LocalDate advanced = currentAnchor.minusDays(groupBLookbackStride(dataTable));
        return advanced.isBefore(floor) ? floor : advanced;
    }

    /** GROUP_B data_table별 lookback 달력일 — 값의 단일 정의처는 각 수집 서비스 상수(REQ-BACKFILL-174). */
    private static int groupBLookbackStride(String dataTable) {
        return switch (dataTable) {
            case "short_sale_domestic" -> SHORT_SALE_LOOKBACK_DAYS;
            case "credit_balance" -> CREDIT_BALANCE_LOOKBACK_DAYS;
            default -> INVESTOR_TREND_LOOKBACK_DAYS;
        };
    }
}
