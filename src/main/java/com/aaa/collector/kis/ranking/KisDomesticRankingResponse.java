package com.aaa.collector.kis.ranking;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** KIS 국내 거래량순위 API 응답. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisDomesticRankingResponse(
        String rtCd, String msgCd, String msg1, List<RankedStock> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisDomesticRankingResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /** 순위 종목 정보. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RankedStock(
            /** 단축종목코드 */
            String mkscShrnIscd,
            /** 데이터 순위 */
            String dataRank,
            /** 종목명 */
            String htsKorIsnm) {}
}
