package com.aaa.collector.stock.rights;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 해외주식 기간별권리조회 API 응답 (TR CTRGT011R, 명세 api-specs/kis/28).
 *
 * <p>SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001의 <b>금액 보강 전용</b> 프리페치 소스다. {@code output}(권리
 * Object Array)과 {@code ctx_area_nk50}/{@code ctx_area_fk50}(다음 페이지 커서, REQ-ODA-011)을 최상위에서 반환한다.
 * 날짜 소스가 아니므로(지급일 필드 없음) rights-by-ice({@link KisOverseasRightsResponse})를 대체하지 않는다.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE}로 JSON snake_case 키가 camelCase
 * 필드에 매핑된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisPeriodRightsResponse(
        String rtCd,
        String msgCd,
        String msg1,
        List<PeriodRightsRow> output,
        String ctxAreaNk50,
        String ctxAreaFk50)
        implements KisApiResponse {

    /** 방어적 복사 — output을 불변 리스트로 변환. */
    public KisPeriodRightsResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /**
     * 기간별권리 행 (output 배열 1건).
     *
     * <p>필드명은 명세28 snake_case 기준. 매칭 키는 {@code acplBassDt}(현지기준일자)이며 {@code bassDt}(KST 기준일)를 매칭에
     * 사용하지 않는다(REQ-ODA-015 [HARD], §1.4-1 실측). 금액은 {@code alctFrcrUnpr}(배정외화단가), 통화는 {@code
     * crcyCd}(다중통화 {@code crcyCd2~4}는 무시, D7).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodRightsRow(
            /** 기준일자 (yyyyMMdd, KST) — 매칭 키로 사용하지 않음 */
            String bassDt,
            /** 권리유형코드 — 요청한 RGHT_TYPE_CD 그대로(03/75) */
            String rghtTypeCd,
            /** 종목코드 → Stock.symbol과 매칭(RD-9, AC-8) */
            String pdno,
            /** 상품명 */
            String prdtName,
            /** 상품유형코드 (예: 512=나스닥) */
            String prdtTypeCd,
            /** 표준상품번호 (ISIN) — pdno 불일치 시 대체 매칭 후보(RD-9) */
            String stdPdno,
            /** 현지기준일자 (yyyyMMdd) → 매칭 키(REQ-ODA-015 [HARD]) */
            String acplBassDt,
            /** 청약시작일자 */
            String sbscStrtDt,
            /** 청약종료일자 */
            String sbscEndDt,
            /** 현금배정율(%) → cash_rate 후보(율 파서, scale 4) */
            String cashAlctRt,
            /** 주식배정율 → stock_rate 후보(율 파서, scale 4) */
            String stckAlctRt,
            /** 통화코드1 — 사용 대상(D7) */
            String crcyCd,
            /** 통화코드2 — 다중통화 부가 필드, 미사용(D7) */
            String crcyCd2,
            /** 통화코드3 — 다중통화 부가 필드, 미사용(D7) */
            String crcyCd3,
            /** 통화코드4 — 다중통화 부가 필드, 미사용(D7) */
            String crcyCd4,
            /** 배정외화단가(주당 현금배당금, 외화, 통화=crcyCd) → cash_amount 후보(금액 파서, scale 5) */
            String alctFrcrUnpr,
            /** 주식배당외화금액2 (74 배당옵션 전용, 본 SPEC 미사용) */
            String stkpDvdnFrcrAmt2,
            /** 주식배당외화금액3 (미사용) */
            String stkpDvdnFrcrAmt3,
            /** 주식배당외화금액4 (미사용) */
            String stkpDvdnFrcrAmt4,
            /** 확정여부 — Y만 채택(REQ-ODA-030) */
            String dfntYn) {}
}
