package com.aaa.collector.stock.enums;

/**
 * 종목이 거래되는 시장.
 *
 * <p>개별 주식: KOSPI, KOSDAQ, NYSE, NASDAQ, AMEX
 *
 * <p>지수 종목 (코스피지수, S&P500 등 관심 종목에 포함되는 지수): KRX, US
 */
public enum Market {
    /** 한국거래소 유가증권시장 */
    KOSPI,
    /** 한국거래소 코스닥시장 */
    KOSDAQ,
    /** 뉴욕증권거래소 */
    NYSE,
    /** 나스닥 */
    NASDAQ,
    /** 미국증권거래소 */
    AMEX,
    /** 한국 지수 종목 전용 (코스피지수, 코스닥지수 등) */
    KRX,
    /** 미국 지수 종목 전용 (S&P500, 나스닥지수 등) */
    US;

    /**
     * 미국 시장을 KIS 해외주식 기간별시세({@code dailyprice}, HHDFS76240000)의 거래소코드({@code EXCD})로 매핑한다
     * (SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-002).
     *
     * <p>NYSE→{@code NYS}, NASDAQ→{@code NAS}, AMEX→{@code AMS}. AMEX(NYSE American/Arca)는 본 API의
     * EXCD에선 {@code AMS}이나 상품기본정보 API에선 {@code 529}로 컨텍스트마다 다르므로 용도 한정 명칭을 사용한다(generic {@code
     * toKisExchangeCode()} 금지 — MI-04). SPY(NYSE Arca)→{@code AMS} 실측 정합, {@code
     * KisMarketResolver}의 {@code AMS→AMEX} 역매핑과 라운드트립 정합.
     *
     * @return KIS dailyprice EXCD 코드
     * @throws IllegalStateException 미국 시장(NYSE/NASDAQ/AMEX)이 아니면 dailyprice EXCD가 정의되지 않음 — 호출자가
     *     skip 처리한다
     */
    public String toDailyPriceExcd() {
        return switch (this) {
            case NYSE -> "NYS";
            case NASDAQ -> "NAS";
            case AMEX -> "AMS";
            default ->
                    throw new IllegalStateException(
                            "dailyprice EXCD 미정의 시장 — 미국(NYSE/NASDAQ/AMEX)만 지원: " + this);
        };
    }
}
