package com.aaa.collector.kis.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T22) — {@link KisOverseasRankingClient} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·3거래소 루프 = 1 batch이므로 세션 1회 open·예외
 * 전파)과 기존 3거래소(NYS/NAS/AMS) 합산 동작을 함께 검증한다. 세 호출은 동일 per-batch 스냅샷을 공유한다(REQ-KISGATE-006a).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KisOverseasRankingClient — 게이트 경유 단위 테스트")
class KisOverseasRankingClientTest {

    private static final String TR_ID = "HHDFS76310010";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private LeaseSession session;

    private KisOverseasRankingClient client;

    @BeforeEach
    void setUp() {
        client = new KisOverseasRankingClient(guardedKisExecutor, keyLeaseRegistry);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    private KisOverseasRankingResponse response(String... symbols) {
        List<KisOverseasRankingResponse.RankedStock> rows =
                java.util.stream.IntStream.range(0, symbols.length)
                        .mapToObj(
                                i ->
                                        new KisOverseasRankingResponse.RankedStock(
                                                symbols[i], String.valueOf(i + 1)))
                        .toList();
        return new KisOverseasRankingResponse("0", "MCA00000", "조회되었습니다.", rows);
    }

    @Nested
    @DisplayName("fetchRanking — NYS, NAS, AMS 각각 호출 후 머지 (게이트 경유)")
    class FetchRanking {

        @Test
        @DisplayName("3거래소 루프 = 1 batch — 세션 1회 open, 게이트 3회 경유(NYS/NAS/AMS), 결과를 모두 합산하여 반환")
        void fetchRanking_threeExchanges_oneSession_mergedResults() throws Exception {
            // Arrange: EXCHANGES 순서(NYS, NAS, AMS)로 연속 반환
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisOverseasRankingResponse.class)))
                    .thenReturn(response("AAPL", "MSFT"))
                    .thenReturn(response("NVDA", "TSLA"))
                    .thenReturn(response("SPY"));

            // Act
            List<KisOverseasRankingResponse.RankedStock> result = client.fetchRanking();

            // Assert: 합산 5건 + per-batch 스냅샷 1회 + 게이트 3회 경유(한 세션 공유)
            assertThat(result).hasSize(5);
            assertThat(result.stream().map(KisOverseasRankingResponse.RankedStock::symb).toList())
                    .containsExactlyInAnyOrder("AAPL", "MSFT", "NVDA", "TSLA", "SPY");
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(3))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisOverseasRankingResponse.class));
        }

        @Test
        @DisplayName("한 거래소 결과가 비어도 나머지 합산")
        void fetchRanking_oneExchangeEmpty_othersIncluded() throws Exception {
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisOverseasRankingResponse.class)))
                    .thenReturn(response("AAPL"))
                    .thenReturn(response())
                    .thenReturn(response("SPY"));

            List<KisOverseasRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("요청 URI에 AUTH·KEYB·PRC1·PRC2 빈 파라미터 포함 (REQ-GRADE-003 회귀 방지)")
        void fetchRanking_uriIncludesAuthParams() throws Exception {
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisOverseasRankingResponse.class)))
                    .thenReturn(response("AAPL"));

            client.fetchRanking();

            // 게이트 mock은 uri 빌더 람다를 any()로 받으므로, 람다를 캡처해 실제 URI로 빌드하여
            // AUTH(빈) 누락 회귀를 방지한다 — 누락 시 OPSQ2001 INPUT FIELD NOT FOUND [AUTH]로 호출 전면 실패.
            ArgumentCaptor<Function<UriBuilder, URI>> uriFnCaptor = ArgumentCaptor.captor();
            verify(guardedKisExecutor, atLeastOnce())
                    .execute(
                            eq(session),
                            uriFnCaptor.capture(),
                            eq(TR_ID),
                            eq(KisOverseasRankingResponse.class));

            URI uri = uriFnCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(uri.getRawQuery())
                    .contains("AUTH=")
                    .contains("KEYB=")
                    .contains("PRC1=")
                    .contains("PRC2=");
        }
    }

    @Nested
    @DisplayName("종단 동작 — 패턴 A = 예외 전파 (REQ-KISGATE-022)")
    class Terminal {

        @Test
        @DisplayName("게이트 소진(EGW00201) — 예외가 상위로 전파(첫 거래소에서 중단)")
        void fetchRanking_gateExhausted_propagates() throws Exception {
            KisRateLimitException egw = new KisRateLimitException("test", "EGW00201 소진");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisOverseasRankingResponse.class)))
                    .thenThrow(egw);

            assertThatThrownBy(client::fetchRanking).isSameAs(egw);
        }

        @Test
        @DisplayName("게이트 인터럽트 — 플래그 복원 후 IllegalStateException 전파")
        void fetchRanking_interrupted_restoresFlagAndWraps() throws Exception {
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisOverseasRankingResponse.class)))
                    .thenThrow(new InterruptedException());

            assertThatThrownBy(client::fetchRanking)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("인터럽트");
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
