package com.aaa.collector.kis.ranking;

import com.aaa.collector.kis.KisApiExecutor;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KIS 해외 거래대금순위 API 클라이언트 (TR_ID=HHDFS76310010).
 *
 * <p>NYSE(NYS), NASDAQ(NAS), AMEX(AMS)를 각각 호출한 뒤 결과를 합산한다 (REQ-005, REQ-006).
 */
@Component
@RequiredArgsConstructor
public class KisOverseasRankingClient {

    private static final String TR_ID = "HHDFS76310010";
    private static final List<String> EXCHANGES = List.of("NYS", "NAS", "AMS");

    private final KisApiExecutor kisApiExecutor;

    /**
     * NYSE, NASDAQ, AMEX 세 거래소의 거래대금 순위 종목을 조회하여 합산한다.
     *
     * <p>REQ-005: US 종목은 NYSE+NASDAQ+AMEX를 한 모집단으로 백분위를 계산하므로 세 거래소를 모두 조회한다.
     *
     * @return 세 거래소 합산 순위 종목 목록
     */
    public List<KisOverseasRankingResponse.RankedStock> fetchRanking() {
        List<KisOverseasRankingResponse.RankedStock> merged = new ArrayList<>();
        for (String excd : EXCHANGES) {
            final String exchangeCode = excd;
            KisOverseasRankingResponse response =
                    kisApiExecutor.executeGet(
                            uri ->
                                    uri.path("/uapi/overseas-stock/v1/ranking/trade-vol")
                                            .queryParam("EXCD", exchangeCode)
                                            .queryParam("NDAY", "0")
                                            .queryParam("VOL_RANG", "0")
                                            .build(),
                            TR_ID,
                            KisOverseasRankingResponse.class);

            List<KisOverseasRankingResponse.RankedStock> output2 = response.output2();
            if (output2 != null) {
                merged.addAll(output2);
            }
        }
        return merged;
    }
}
