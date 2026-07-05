package com.aaa.collector.backfill;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * cron 진입부 lazy 시더 (SPEC-COLLECTOR-BACKFILL-001 T2, REQ-BACKFILL-007/-008/-008b).
 *
 * <p>매 백필 cron 진입부에서 <b>현재 활성 관심종목</b>(국내·해외) × <b>시장별 data_table 집합</b>을 {@code backfill_status}와
 * 대조해 누락 행을 INSERT IGNORE로 자동 생성한다(AC-8.1~8.5). 활성 종목의 시장별 분류는 {@link BackfillSeedTargetProvider}
 * 포트에 위임한다(의존성 역전 — {@code backfill → stock} 순환 의존 회피).
 *
 * <ul>
 *   <li>국내 종목(KOSPI/KOSDAQ) → {@code daily_ohlcv}/{@code investor_trend}/{@code
 *       short_sale_domestic}/{@code credit_balance} 4행.
 *   <li>미국 종목(NYSE/NASDAQ/AMEX) → {@code daily_ohlcv} 1행만(수급 3종은 국내 J-market 전용, AC-7.3).
 * </ul>
 *
 * <p>활성 국내 집합은 시장 무관 STOCK+ETF에서 미국 종목을 제외해 4행 시딩이 미국 종목에 적용되지 않게 하고, 미국 집합은 별도로 선별해 {@code
 * daily_ohlcv} 1행만 시딩한다 — 이 분류 책임은 {@link BackfillSeedTargetProvider} 구현(어댑터)이 진다.
 *
 * <p>[HARD] REQ-BACKFILL-008b: 시딩은 <b>단일 커넥션 순차 INSERT IGNORE</b>로 수행한다 — Virtual Thread 팬아웃을 쓰지 않고
 * 단일 {@code @Transactional} 경계 안에서 한 커넥션으로 모든 행을 순차 적재해 DB 풀 점유를 1개로 제한한다(DBPOOL-001 풀 고갈 회피).
 *
 * <p>시딩은 INSERT IGNORE만 사용하므로(Tier-1) 기존 행(진행 중 행 포함)은 변경하지 않고 누락 행만 생성한다 — UPDATE를 발생시키지
 * 않는다(AC-8.3/8.5). 진행점 전진(UPDATE, Tier-2)은 T6 오케스트레이터의 책임이며 이 시더는 호출하지 않는다.
 *
 * <p>T2 범위에서는 컴포넌트와 시딩 메서드만 제공한다 — 스케줄러/오케스트레이터 배선은 후속 Task(T6/T7)에서 진입부에 연결한다.
 */
// @MX:ANCHOR: [AUTO] cron 진입부 lazy 시딩 진입점 — T6 오케스트레이터가 매 회차 호출 (REQ-BACKFILL-008)
// @MX:REASON: [AUTO] backfill 진행 행의 단일 생성 지점. 시장별 data_table 분기·INSERT IGNORE 멱등성이 여기 고정된다.
@Component
@RequiredArgsConstructor
public class BackfillStatusSeeder {

    /** {@code backfill_status.target_type} — 본 SPEC은 종목만 시딩(forward-compat: MACRO/FX). */
    private static final String TARGET_TYPE_STOCK = "STOCK";

    /**
     * 국내 종목 시딩 data_table 집합(REQ-BACKFILL-008).
     *
     * <p>[SPEC-COLLECTOR-BACKFILL-007 REQ-BACKFILL-090] {@code corporate_events}(SPLIT 과거 백필) 편입 —
     * 국내 5종. 미국 종목은 {@link #OVERSEAS_DATA_TABLES}로 분기되며, SPLIT 소스 확보 후(REQ-OSPLIT-063) 미국도 {@code
     * corporate_events}를 시딩한다 — 수급 3종은 여전히 국내 전용이라 미국은 시딩하지 않는다.
     *
     * <p>[SPEC-COLLECTOR-BACKFILL-009 REQ-BACKFILL-143] {@code corporate_events_dividend}(DIVIDEND
     * 과거 백필) 편입 — 국내 6종. SPLIT과 구분되는 별도 {@code data_table} 논리 키로 활성 국내 관심종목을 {@code
     * backfill_status}에 시딩해 진행 상태를 분리한다(RD-1). 미국 종목은 대상 아님(국내 배당 전용).
     */
    private static final List<String> DOMESTIC_DATA_TABLES =
            List.of(
                    "daily_ohlcv",
                    "investor_trend",
                    "short_sale_domestic",
                    "credit_balance",
                    "corporate_events",
                    "corporate_events_dividend");

    /**
     * 미국 종목 시딩 data_table 집합 — {@code daily_ohlcv}·{@code corporate_events} 2종(수급 3종은 국내 J-market
     * 전용이라 비대상, AC-7.3).
     *
     * <p>[SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-063, RD-4] {@code corporate_events} 편입 —
     * BACKFILL-007 REQ-BACKFILL-092("미국 corporate_events 백필 제외")를 SPLIT에 한해 개정한다. 미국 종목은 별도 소스 TR
     * {@code CTRGT011R}(해외주식 기간별권리조회 14/15)을 확보했으므로 국내 전용 제약이 소멸했다. {@code daily_ohlcv} 항목은 그대로 두므로
     * 해외 일봉 백필 시딩·GROUP_A 종료·윈도우 전진은 불변(비회귀). 백필 윈도우 실행 시 시장별 소스 분기는 {@code
     * BackfillWindowExecutor}가 담당한다(미국→CTRGT011R, 국내→HHKDB669105C0).
     */
    private static final List<String> OVERSEAS_DATA_TABLES =
            List.of("daily_ohlcv", "corporate_events");

    private final BackfillSeedTargetProvider seedTargetProvider;
    private final BackfillStatusRepository backfillStatusRepository;

    /**
     * 현재 활성 관심종목 × 시장별 data_table 집합의 누락 {@code backfill_status} 행을 시딩한다(REQ-BACKFILL-008).
     *
     * <p>단일 {@code @Transactional} 경계 — 모든 INSERT IGNORE가 한 커넥션에서 순차 실행되어 DB 풀 점유를 1개로
     * 제한한다(REQ-BACKFILL-008b, DBPOOL-001 회피). 이미 존재하는 행은 INSERT IGNORE로 보존되고 누락 행만 생성된다(AC-8.3).
     */
    @Transactional
    public void seedActiveStocks() {
        for (String symbol : seedTargetProvider.activeDomesticSymbols()) {
            seedRows(symbol, DOMESTIC_DATA_TABLES);
        }
        for (String symbol : seedTargetProvider.activeOverseasSymbols()) {
            seedRows(symbol, OVERSEAS_DATA_TABLES);
        }
    }

    private void seedRows(String symbol, List<String> dataTables) {
        for (String dataTable : dataTables) {
            backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE_STOCK, symbol, dataTable);
        }
    }
}
