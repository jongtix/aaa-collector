package com.aaa.collector.kis.ranking;

import com.aaa.collector.kis.KisApiExecutor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** KIS 국내 거래량순위 API 클라이언트 (TR_ID=FHPST01710000). */
@Component
@RequiredArgsConstructor
public class KisDomesticRankingClient {

    private static final String TR_ID = "FHPST01710000";

    private final KisApiExecutor kisApiExecutor;

    /**
     * 국내 거래금액 순위 상위 종목 목록을 조회한다.
     *
     * <p>FID_BLNG_CLS_CODE="3" (거래금액순), FID_COND_MRKT_DIV_CODE="J", FID_COND_SCR_DIV_CODE="20171",
     * FID_INPUT_ISCD="0000" (전체 종목)
     *
     * @return 순위별 종목 목록
     */
    public List<KisDomesticRankingResponse.RankedStock> fetchRanking() {
        KisDomesticRankingResponse response =
                kisApiExecutor.executeGet(
                        uri ->
                                uri.path("/uapi/domestic-stock/v1/quotations/volume-rank")
                                        .queryParam("FID_BLNG_CLS_CODE", "3")
                                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                                        .queryParam("FID_INPUT_ISCD", "0000")
                                        .build(),
                        TR_ID,
                        KisDomesticRankingResponse.class);

        List<KisDomesticRankingResponse.RankedStock> output = response.output();
        return output != null ? output : List.of();
    }
}
