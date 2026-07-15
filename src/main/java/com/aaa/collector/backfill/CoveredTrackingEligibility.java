package com.aaa.collector.backfill;

import java.util.Set;

/**
 * covered-range 추적 대상 판별 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-052, -060).
 *
 * <p>§2.0 전체 현황표 기준 "커버 추적 O"로 분류된 **정확히 6종**만 추적 대상이다 — STOCK 범위형 4종({@code daily_ohlcv} / {@code
 * investor_trend} / {@code short_sale_domestic} / {@code credit_balance}, 임의 종목 symbol) + USDKRW(단일
 * 날짜형) + FINRA Daily 전역 앵커(단일 날짜형). 이벤트형({@code corporate_events*} / {@code disclosures} / 뉴스)·단일
 * 호출형({@code MACRO_INDICATOR})·VIX(DP-VIX 미결)는 시딩 여부와 무관하게 제외된다(REQ-CVR-052).
 *
 * <p>{@code MARKET_INDICATOR/market_indicators}는 USDKRW·VIX가 공유하므로 {@code dataTable}만으로는 판별할 수 없다 —
 * {@code targetCode}까지 함께 매칭해야 VIX(DP-VIX, 미편입)를 오배제하지 않는다.
 */
public final class CoveredTrackingEligibility {

    /** 갭 채우기 방식(REQ-CVR-050~051) — {@link CoveredRangeService#walkGapForward}가 루프 전략 선택에 사용한다. */
    public enum Mode {
        /** 기존 기간 조회 윈도우 메커니즘 재사용(REQ-CVR-050). 캘린더 게이트 불필요 — 윈도우가 비거래일을 내재적으로 처리한다. */
        RANGE,
        /** 날짜별 반복 호출(REQ-CVR-051). walk 루프가 시장 캘린더 게이트로 비거래일을 사전 skip한다. */
        SINGLE_DATE
    }

    private static final String STOCK = "STOCK";
    private static final String MARKET_INDICATOR = "MARKET_INDICATOR";
    private static final String OVERSEAS_SHORTSALE = "OVERSEAS_SHORTSALE";

    private static final Set<String> STOCK_RANGE_TABLES =
            Set.of("daily_ohlcv", "investor_trend", "short_sale_domestic", "credit_balance");

    private CoveredTrackingEligibility() {}

    /**
     * (targetType, targetCode, dataTable) 조합이 covered-range 추적 대상인지 판별한다(AC-14).
     *
     * @param targetType 대상 유형
     * @param targetCode 대상 코드
     * @param dataTable 데이터 테이블명
     * @return 추적 대상(§2.0 표 6종 중 하나)이면 {@code true}
     */
    public static boolean isTracked(String targetType, String targetCode, String dataTable) {
        return resolve(targetType, targetCode, dataTable) != null;
    }

    /**
     * 추적 대상의 갭 채우기 방식을 반환한다.
     *
     * @param targetType 대상 유형
     * @param targetCode 대상 코드
     * @param dataTable 데이터 테이블명
     * @return {@link Mode}
     * @throws IllegalArgumentException 추적 대상이 아닌 조합일 때
     */
    public static Mode modeOf(String targetType, String targetCode, String dataTable) {
        Mode mode = resolve(targetType, targetCode, dataTable);
        if (mode == null) {
            throw new IllegalArgumentException(
                    "covered-range 추적 대상이 아님 — targetType="
                            + targetType
                            + ", targetCode="
                            + targetCode
                            + ", dataTable="
                            + dataTable);
        }
        return mode;
    }

    private static Mode resolve(String targetType, String targetCode, String dataTable) {
        if (STOCK.equals(targetType) && STOCK_RANGE_TABLES.contains(dataTable)) {
            return Mode.RANGE;
        }
        if (MARKET_INDICATOR.equals(targetType)
                && "USDKRW".equals(targetCode)
                && "market_indicators".equals(dataTable)) {
            return Mode.SINGLE_DATE;
        }
        if (OVERSEAS_SHORTSALE.equals(targetType)
                && "__GLOBAL__".equals(targetCode)
                && "short_sale_overseas".equals(dataTable)) {
            return Mode.SINGLE_DATE;
        }
        return null;
    }
}
