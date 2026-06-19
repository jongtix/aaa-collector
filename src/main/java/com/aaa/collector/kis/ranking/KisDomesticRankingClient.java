package com.aaa.collector.kis.ranking;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KIS 국내 거래량순위 API 클라이언트 (TR_ID=FHPST01710000).
 *
 * <p>모든 호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다 (SPEC-COLLECTOR-KISGATE-001
 * REQ-KISGATE-001). 키 lease·throttle·재시도(retryable: {@code KisRateLimitException} ∪ {@code
 * RestClientException})는 게이트가 단일 위치에서 수행한다 — 기존 3-arg 맨몸 호출(throttle❌·재시도❌·isa 단일키)에서 바뀐 의도된 동작
 * 변경(Behavior:Changed, 패턴 A).
 *
 * <p>단발 호출이므로 자체 {@link LeaseSession}을 1회 열어 사용한다(per-batch 스냅샷 1회, REQ-KISGATE-006a). 소진/전 키 사망 시
 * 게이트가 예외를 전파하며, 상위 호출부({@code GradeClassificationService})가 시장별 독립 보류 정책을 적용한다(REQ-KISGATE-022, 패턴
 * A = 예외 전파).
 */
@Component
@RequiredArgsConstructor
public class KisDomesticRankingClient {

    private static final String TR_ID = "FHPST01710000";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/volume-rank";

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 국내 거래금액 순위 상위 종목 목록을 조회한다.
     *
     * <p>FID_BLNG_CLS_CODE="3" (거래금액순), FID_COND_MRKT_DIV_CODE="J", FID_COND_SCR_DIV_CODE="20171",
     * FID_INPUT_ISCD="0000" (전체 종목). 단발 호출 = 1 batch(REQ-KISGATE-006a) — 자체 세션을 1회 연다.
     *
     * @return 순위별 종목 목록
     */
    public List<KisDomesticRankingResponse.RankedStock> fetchRanking() {
        LeaseSession session = keyLeaseRegistry.openSession();
        try {
            KisDomesticRankingResponse response =
                    guardedKisExecutor.execute(
                            session,
                            uri ->
                                    uri.path(PATH)
                                            .queryParam("FID_BLNG_CLS_CODE", "3")
                                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                            .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                                            .queryParam("FID_INPUT_ISCD", "0000")
                                            .build(),
                            TR_ID,
                            KisDomesticRankingResponse.class);

            List<KisDomesticRankingResponse.RankedStock> output = response.output();
            return output != null ? output : List.of();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetchRanking(국내) 중 인터럽트", ie);
        }
    }
}
