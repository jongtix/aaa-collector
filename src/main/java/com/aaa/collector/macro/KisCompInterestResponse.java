package com.aaa.collector.macro;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 금리종합 API 응답 (TR FHPST07020000).
 *
 * <p>{@code output2}(다건 금리 지표 Object Array)만 사용한다. output1은 단건이며 본 배치에서는 불필요하다. T0 실측(v0.5.0):
 * output2 = 국내 한국 채권금리(bcdt_code Y01xx). 파라미터 라벨("해외금리지표")과 무관하게 output2 본문이 국내(MA-01 해소). {@link
 * com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisCompInterestResponse(
        String rtCd, String msgCd, String msg1, List<CompInterestRow> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2 필드를 불변 리스트로 변환. */
    public KisCompInterestResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /**
     * 금리 지표 행 (output2 배열 1건, output2 전용 필드셋).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다. 매핑 대상
     * 필드만 선언한다(REQ-BATCH3-031). output1과 output2는 필드셋이 미세하게 다름(MA-04).
     *
     * <p>malformed 선두 행 허용: {@code hts_kor_isnm}에 bcdt_code가 들어있거나 {@code bond_mnrt_prpr}이 비숫자인 행은
     * 역직렬화는 정상 수행하고 서비스 레이어에서 graceful skip한다(REQ-BATCH3-070/031a).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompInterestRow(
            /** 자료 코드 (예: Y0117) — indicator_code 안정 식별자 기반 */
            String bcdtCode,
            /** HTS 한글 종목명 — malformed 행에서 bcdt_code가 들어오는 필드 */
            String htsKorIsnm,
            /** 채권금리 현재가 (%, 정규화 없음) — malformed 행에서 비숫자일 수 있음 */
            String bondMnrtPrpr,
            /** 전일 대비 부호 */
            String prdyVrssSign,
            /** 채권금리 전일 대비 */
            String bondMnrtPrdyVrss,
            /** 업종 지수 전일 대비율 (output2 전용 필드 — output1은 prdy_ctrt) */
            String bstpNmixPrdyCtrt,
            /** 주식 영업 일자 (yyyyMMdd) */
            String stckBsopDate) {}
}
