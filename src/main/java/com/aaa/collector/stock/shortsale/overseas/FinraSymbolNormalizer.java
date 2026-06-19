package com.aaa.collector.stock.shortsale.overseas;

/**
 * FINRA 심볼을 {@code stocks.symbol} 표기로 정규화한다.
 *
 * <p>FINRA는 클래스주식을 슬래시 표기({@code BRK/B})로 반환하나(실측 2026-06-19 — {@code BRK.B}/{@code BRKB}는 빈 결과),
 * {@code stocks}는 점 표기를 쓰므로 {@code /}를 {@code .}로 치환해 매칭한다(D14, AC-NORM-1).
 *
 * <p><b>단방향 변환 근거</b>: 2026-06-20 DB 실측상 현재 워치리스트 NYSE/NASDAQ/AMEX 종목은 전부 무구분 평문 티커이며 구분자 포함 클래스주식이
 * 0개라 슬래시↔점 동치는 직접 관측되지 않았다. 향후 클래스주식을 워치리스트에 추가하면 {@code stocks.symbol}의 실제 표기를 재확인하고 본 변환식을
 * 검증한다(추측 금지).
 */
public final class FinraSymbolNormalizer {

    private FinraSymbolNormalizer() {}

    /**
     * FINRA 심볼을 {@code stocks.symbol} 매칭용으로 정규화한다.
     *
     * @param finraSymbol FINRA 응답 심볼(슬래시 클래스주식 표기 가능). {@code null}/blank는 그대로 반환한다.
     * @return 슬래시를 점으로 치환한 심볼(평문 티커는 변경 없음)
     */
    public static String normalize(String finraSymbol) {
        if (finraSymbol == null || finraSymbol.isBlank()) {
            return finraSymbol;
        }
        return finraSymbol.replace('/', '.');
    }
}
