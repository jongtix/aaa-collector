package com.aaa.collector.stock;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 배당/증자 일정 API 응답 (TR HHKDB669102C0).
 *
 * <p>{@code output1}(배당 일정 Object Array)을 사용한다. 본 SPEC은 {@code EventType.DIVIDEND}만
 * 수집한다(RIGHTS_ISSUE 제외 — REQ-BATCH3-054). {@link
 * com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 *
 * <p>CTS 페이징: {@code output2}(Object, single)에 다음 페이지 CTS 토큰이 담긴다. {@code output2}가 null 이거나 {@code
 * cts}가 공백이면 마지막 페이지다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisDividendScheduleResponse(
        String rtCd, String msgCd, String msg1, List<DividendRow> output1, CtsPaging output2)
        implements KisApiResponse {

    /** 방어적 복사 — output1 필드를 불변 리스트로 변환. */
    public KisDividendScheduleResponse {
        output1 = output1 != null ? List.copyOf(output1) : List.of();
    }

    /**
     * 다음 페이지 CTS 토큰.
     *
     * @return 공백/null 이면 마지막 페이지
     */
    public String cts() {
        return output2 != null ? output2.cts() : null;
    }

    /** CTS 페이징 오브젝트 (output2). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CtsPaging(String cts) {}

    /**
     * 배당 일정 행 (output1 배열 1건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다. 매핑 대상
     * 필드만 선언한다(REQ-BATCH3-051).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DividendRow(
            /** 단축 종목코드 */
            String shtCd,
            /** 기준일 (yyyyMMdd) → event_date */
            String recordDate,
            /** 배당 종류 → event_subtype */
            String diviKind,
            /** 주당 배당금액 (원) → cash_amount */
            String perStoDiviAmt,
            /** 배당률 (%) → cash_rate */
            String diviRate,
            /** 주식 배당률 (%) → stock_rate */
            String stkDiviRate,
            /** 현금 지급일 (yyyyMMdd) → pay_date */
            String diviPayDt,
            /** 주식 지급일 (yyyyMMdd) → stock_pay_date */
            String stkDivPayDt,
            /** 단주 지급일 (yyyyMMdd) → odd_pay_date */
            String oddPayDt,
            /** 액면가 (원) → face_value */
            String faceVal,
            /** 주식 종류 → stock_kind */
            String stkKind,
            /** 고배당종목여부 → high_dividend_flag */
            String highDiviGb) {}
}
