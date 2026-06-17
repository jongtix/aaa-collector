package com.aaa.collector.kis.ranking;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KIS 해외 거래대금순위 API 클라이언트 (TR_ID=HHDFS76310010).
 *
 * <p>NYSE(NYS), NASDAQ(NAS), AMEX(AMS)를 각각 호출한 뒤 결과를 합산한다 (REQ-005, REQ-006).
 *
 * <p>모든 호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다 (SPEC-COLLECTOR-KISGATE-001
 * REQ-KISGATE-001). 키 lease·throttle·재시도(retryable: {@code KisRateLimitException} ∪ {@code
 * RestClientException})는 게이트가 단일 위치에서 수행한다 — 기존 3-arg 맨몸 호출(throttle❌·재시도❌·isa 단일키)에서 바뀐 의도된 동작
 * 변경(Behavior:Changed, 패턴 A).
 *
 * <p><strong>3거래소 루프 = 1 batch(REQ-KISGATE-006a):</strong> {@code fetchRanking()} 1회가 세 거래소를 모두
 * 조회하는 한 작업 단위다. 따라서 {@link LeaseSession}을 루프 시작 전 <b>정확히 1회</b> 열어 세 호출이 동일 per-batch 헬스 스냅샷을
 * 공유한다(selectHealthy 단위당 1회). 소진/전 키 사망 시 게이트가 예외를 전파하며, 상위 호출부({@code
 * GradeClassificationService})가 시장별 독립 보류 정책을 적용한다(REQ-KISGATE-022, 패턴 A = 예외 전파).
 */
@Component
@RequiredArgsConstructor
public class KisOverseasRankingClient {

    private static final String TR_ID = "HHDFS76310010";
    private static final String PATH = "/uapi/overseas-stock/v1/ranking/trade-vol";
    private static final List<String> EXCHANGES = List.of("NYS", "NAS", "AMS");
    // KIS 해외 순위 요청의 페이지네이션/가격필터 파라미터 — 전체 순위 조회 시 빈 문자열로 전달(REQ-GRADE-003).
    private static final String EMPTY_PARAM = "";

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * NYSE, NASDAQ, AMEX 세 거래소의 거래대금 순위 종목을 조회하여 합산한다.
     *
     * <p>REQ-005: US 종목은 NYSE+NASDAQ+AMEX를 한 모집단으로 백분위를 계산하므로 세 거래소를 모두 조회한다. 세 호출은 루프 시작 전 1회 연 동일
     * {@link LeaseSession}을 공유한다(REQ-KISGATE-006a — 한 스냅샷이 3호출 전체를 커버).
     *
     * @return 세 거래소 합산 순위 종목 목록
     */
    // @MX:NOTE: [AUTO] AUTH(빈 문자열) 필수 — 누락 시 OPSQ2001 INPUT FIELD NOT FOUND [AUTH]로 호출 전면
    // 실패(REQ-GRADE-003).
    // 스펙 충실성을 위해 KEYB/PRC1/PRC2(빈)도 포함. 권위 예시: api-specs/kis/20-해외주식거래량순위.md.
    public List<KisOverseasRankingResponse.RankedStock> fetchRanking() {
        // REQ-KISGATE-006a: 3거래소 루프 = 1 batch — 루프 전 per-batch 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        List<KisOverseasRankingResponse.RankedStock> merged = new ArrayList<>();
        try {
            for (String excd : EXCHANGES) {
                final String exchangeCode = excd;
                KisOverseasRankingResponse response =
                        guardedKisExecutor.execute(
                                session,
                                uri ->
                                        uri.path(PATH)
                                                .queryParam("EXCD", exchangeCode)
                                                .queryParam("NDAY", "0")
                                                .queryParam("VOL_RANG", "0")
                                                // REQ-GRADE-003: AUTH 빈 문자열 필수(라이브 프로브 실증).
                                                // KEYB/PRC1/PRC2는 스펙 충실성.
                                                .queryParam("KEYB", EMPTY_PARAM)
                                                .queryParam("AUTH", EMPTY_PARAM)
                                                .queryParam("PRC1", EMPTY_PARAM)
                                                .queryParam("PRC2", EMPTY_PARAM)
                                                .build(),
                                TR_ID,
                                KisOverseasRankingResponse.class);

                List<KisOverseasRankingResponse.RankedStock> output2 = response.output2();
                if (output2 != null) {
                    merged.addAll(output2);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetchRanking(해외) 중 인터럽트", ie);
        }
        return merged;
    }
}
