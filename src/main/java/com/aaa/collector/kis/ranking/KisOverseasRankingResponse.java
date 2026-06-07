package com.aaa.collector.kis.ranking;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** KIS 해외 거래대금순위 API 응답. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisOverseasRankingResponse(
        String rtCd, String msgCd, String msg1, List<RankedStock> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2 필드를 불변 리스트로 변환. */
    public KisOverseasRankingResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /** 순위 종목 정보. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RankedStock(
            /** 종목코드 */
            String symb,
            /** 순위 */
            String rank) {}
}
