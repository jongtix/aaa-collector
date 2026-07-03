package com.aaa.collector.stock.rights;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 해외주식 권리종합 API 응답 (TR HHDFS78330900, 명세 api-specs/kis/14).
 *
 * <p>{@code output1}(권리 일정 Object Array)을 사용한다. API 호출 자체는 권리종합(현금배당·증자·상장폐지 등 전 권리 유형 반환)이나, 본
 * SPEC은 현금배당({@code ca_title}=현금배당) 행만 저장하고 나머지는 skip한다(RD-8 [확정: A], REQ-OVE-023/023a). {@link
 * com.aaa.collector.stock.KisDividendScheduleResponse} 패턴 답습.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE}로 JSON snake_case 키가 camelCase
 * 필드에 매핑된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisOverseasRightsResponse(
        String rtCd, String msgCd, String msg1, List<RightsRow> output1) implements KisApiResponse {

    /** 방어적 복사 — output1을 불변 리스트로 변환. */
    public KisOverseasRightsResponse {
        output1 = output1 != null ? List.copyOf(output1) : List.of();
    }

    /**
     * 권리 일정 행 (output1 배열 1건).
     *
     * <p>필드명은 명세 14 snake_case 기준. 저장 매핑 대상은 현금배당 행의 {@code recordDt}(→ event_date, 필수)·{@code
     * divLockDt}(→ ex_dividend_date)·{@code payDt}이다. {@code caTitle}은 현금배당 판정 키로만 사용하고, {@code
     * event_subtype}은 CTRGT011R(KIS 해외주식 기간별권리조회, 명세 api-specs/kis/28)의 {@code rght_type_cd}에서
     * 매핑된다(경로 A, REQ-ODA-010). 비현금배당 행에서만 오는 {@code lockDt}/{@code delistDt} 등도 보존하나(원본 12필드 일치) 저장
     * 시 매핑하지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RightsRow(
            /** ICE공시일 (yyyyMMdd) */
            String annoDt,
            /** 현금배당 판정 키 (현금배당="현금배당", 기타=기타 유형). event_subtype은 CTRGT011R 경로로 별도 매핑 */
            String caTitle,
            /** 배당락일 (yyyyMMdd) → ex_dividend_date */
            String divLockDt,
            /** 지급일 (yyyyMMdd) → pay_date */
            String payDt,
            /** 기준일 (yyyyMMdd) → event_date (필수) */
            String recordDt,
            /** 효력일자 (yyyyMMdd) */
            String validityDt,
            /** 현지지시마감일 (yyyyMMdd) */
            String localEndDt,
            /** 권리락일 (yyyyMMdd) — 비현금배당 행 */
            String lockDt,
            /** 상장폐지일 (yyyyMMdd) — 비현금배당 행 */
            String delistDt,
            /** 상환일자 (yyyyMMdd) */
            String redemptDt,
            /** 조기상환일자 (yyyyMMdd) */
            String earlyRedemptDt,
            /** 적용일 (yyyyMMdd) */
            String effectiveDt) {}
}
