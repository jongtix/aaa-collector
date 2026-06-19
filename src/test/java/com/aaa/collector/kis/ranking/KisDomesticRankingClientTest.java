package com.aaa.collector.kis.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T21) — {@link KisDomesticRankingClient} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·단발 호출당 세션 1회 open·예외 전파)과 기존 응답 매핑을
 * 함께 검증한다. 게이트의 재시도·재경유는 {@code GuardedKisExecutorTest}가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KisDomesticRankingClient — 게이트 경유 단위 테스트")
class KisDomesticRankingClientTest {

    private static final String TR_ID = "FHPST01710000";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private LeaseSession session;

    private KisDomesticRankingClient client;

    @BeforeEach
    void setUp() {
        client = new KisDomesticRankingClient(guardedKisExecutor, keyLeaseRegistry);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    private void stubGate(KisDomesticRankingResponse response) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        eq(session), any(), eq(TR_ID), eq(KisDomesticRankingResponse.class)))
                .thenReturn(response);
    }

    @Nested
    @DisplayName("fetchRanking — 국내 거래금액 순위 조회 (게이트 경유)")
    class FetchRanking {

        @Test
        @DisplayName("단발 호출 = 1 batch — 세션 1회 open 후 게이트를 throttle-on(4-arg)으로 1회 경유, output 반환")
        void fetchRanking_routesThroughGateOnce_returnsOutput() throws Exception {
            // Arrange
            KisDomesticRankingResponse response =
                    new KisDomesticRankingResponse(
                            "0",
                            "MCA00000",
                            "조회되었습니다.",
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", "1", "삼성전자"),
                                    new KisDomesticRankingResponse.RankedStock(
                                            "000660", "2", "SK하이닉스")));
            stubGate(response);

            // Act
            List<KisDomesticRankingResponse.RankedStock> result = client.fetchRanking();

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result.getFirst().mkscShrnIscd()).isEqualTo("005930");
            assertThat(result.get(1).mkscShrnIscd()).isEqualTo("000660");
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisDomesticRankingResponse.class));
        }

        @Test
        @DisplayName("빈 output 배열 — 빈 목록 반환")
        void fetchRanking_emptyOutput_returnsEmptyList() throws Exception {
            stubGate(new KisDomesticRankingResponse("0", "MCA00000", "조회되었습니다.", List.of()));

            assertThat(client.fetchRanking()).isEmpty();
        }

        @Test
        @DisplayName("null output — 빈 목록 반환")
        void fetchRanking_nullOutput_returnsEmptyList() throws Exception {
            stubGate(new KisDomesticRankingResponse("0", "MCA00000", "조회되었습니다.", null));

            assertThat(client.fetchRanking()).isEmpty();
        }

        @Test
        @DisplayName("dataRank 순서 확인 — 1위 종목이 첫 번째")
        void fetchRanking_ranksAreOrdered() throws Exception {
            stubGate(
                    new KisDomesticRankingResponse(
                            "0",
                            "MCA00000",
                            "조회되었습니다.",
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", "1", "삼성전자"),
                                    new KisDomesticRankingResponse.RankedStock(
                                            "035420", "2", "NAVER"))));

            List<KisDomesticRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result.getFirst().dataRank()).isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("종단 동작 — 패턴 A = 예외 전파 (REQ-KISGATE-022)")
    class Terminal {

        @Test
        @DisplayName("게이트 소진(EGW00201) — 예외가 상위로 전파(호출부 시장별 보류)")
        void fetchRanking_gateExhausted_propagates() throws Exception {
            KisRateLimitException egw = new KisRateLimitException("test", "EGW00201 소진");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDomesticRankingResponse.class)))
                    .thenThrow(egw);

            assertThatThrownBy(client::fetchRanking).isSameAs(egw);
        }

        @Test
        @DisplayName("비즈니스 오류(KisApiBusinessException) — non-retryable 즉시 전파")
        void fetchRanking_businessError_propagates() throws Exception {
            KisApiBusinessException biz = new KisApiBusinessException("1", "EGW00123", "인증 오류");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDomesticRankingResponse.class)))
                    .thenThrow(biz);

            assertThatThrownBy(client::fetchRanking).isSameAs(biz);
        }

        @Test
        @DisplayName("게이트 인터럽트 — 플래그 복원 후 IllegalStateException 전파")
        void fetchRanking_interrupted_restoresFlagAndWraps() throws Exception {
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDomesticRankingResponse.class)))
                    .thenThrow(new InterruptedException());

            assertThatThrownBy(client::fetchRanking)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("인터럽트");
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
