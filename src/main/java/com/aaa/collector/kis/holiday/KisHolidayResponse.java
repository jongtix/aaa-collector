package com.aaa.collector.kis.holiday;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 국내휴장일조회 API 응답 (TR_ID=CTCA0903R).
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정으로 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다(예: {@code ctx_area_nk} → {@code ctxAreaNk}). 장중/개장 판정의 권위 필드는 {@code
 * output[].opnd_yn}(개장일여부 Y/N)이다 — {@code bzdy_yn}(영업일)이 아니다(SPEC-COLLECTOR-OBSV-001 §1.6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisHolidayResponse(
        String rtCd,
        String msgCd,
        String msg1,
        String ctxAreaNk,
        String ctxAreaFk,
        List<HolidayRow> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisHolidayResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /** 일자별 휴장/개장 정보. 모든 여부 필드는 {@code Y}/{@code N} 문자열. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HolidayRow(
            /** 기준일자 (YYYYMMDD) */
            String bassDt,
            /** 요일구분코드 (01:일 ~ 07:토) */
            String wdayDvsnCd,
            /** 영업일여부 (Y/N) — 판정에 사용하지 않음 */
            String bzdyYn,
            /** 거래일여부 (Y/N) — 판정에 사용하지 않음 */
            String trDayYn,
            /** 개장일여부 (Y/N) — 증시 개장 판정의 권위 필드 */
            String opndYn,
            /** 결제일여부 (Y/N) — 판정에 사용하지 않음 */
            String sttlDayYn) {}
}
