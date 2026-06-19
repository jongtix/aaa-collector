package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/**
 * SPEC-COLLECTOR-KISGATE-001 M5(T13) — {@link KisWatchlistClient} 게이트 경유 단위 테스트.
 *
 * <p>패턴 B는 Behavior:Changed(DP2)이므로 <b>회귀</b>(정상 응답·null output·예외 전파)와 <b>신규 동작</b>(게이트 throttle
 * on/off 경로 차이)을 함께 검증한다. 게이트의 매 시도 재경유·키 재선택은 {@code GuardedKisExecutorTest}(AC-3)가 담당하므로, 본 테스트는
 * 클라이언트가 게이트를 올바른 throttle 플래그·세션·TR로 경유하는지에 집중한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KisWatchlistClient — 게이트 경유(throttle on/off)")
class KisWatchlistClientTest {

    private static final String GROUP_TR_ID = "HHKCM113004C7";
    private static final String STOCK_TR_ID = "HHKCM113004C6";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private KisProperties kisProperties;
    @Mock private LeaseSession session;

    private KisWatchlistClient client;

    @BeforeEach
    void setUp() {
        client = new KisWatchlistClient(guardedKisExecutor, keyLeaseRegistry, kisProperties);
        Mockito.lenient().when(kisProperties.userId()).thenReturn("testUser");
    }

    @Nested
    @DisplayName("fetchGroups — throttle-off 1-shot (REQ-KISGATE-040 / AC-8)")
    class FetchGroups {

        @Test
        @DisplayName("정상 응답 — 자체 세션 open 후 게이트를 throttle=false로 경유하여 그룹 목록 반환")
        void fetchGroups_normalResponse_routesThroughGateThrottleOff() throws Exception {
            // Arrange
            when(keyLeaseRegistry.openSession()).thenReturn(session);
            KisGroupListResponse ok =
                    new KisGroupListResponse(
                            "0",
                            "MCA00000",
                            "정상",
                            List.of(new KisGroupListResponse.Group("001", "관심그룹1", "3", "1")));
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(GROUP_TR_ID),
                            eq(KisGroupListResponse.class),
                            eq(false)))
                    .thenReturn(ok);

            // Act
            List<KisGroupListResponse.Group> groups = client.fetchGroups();

            // Assert: 1-shot 보존 — throttle=false 오버로드로 정확히 1회 경유, 자체 세션 1회 open
            assertThat(groups).hasSize(1);
            assertThat(groups.getFirst().interGrpCode()).isEqualTo("001");
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(1))
                    .execute(
                            eq(session),
                            any(),
                            eq(GROUP_TR_ID),
                            eq(KisGroupListResponse.class),
                            eq(false));
        }

        @Test
        @DisplayName("output2 null 응답 — 빈 리스트 반환")
        void fetchGroups_nullOutput2_returnsEmptyList() throws Exception {
            when(keyLeaseRegistry.openSession()).thenReturn(session);
            KisGroupListResponse nullOutput = new KisGroupListResponse("0", "MCA00000", "정상", null);
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(GROUP_TR_ID),
                            eq(KisGroupListResponse.class),
                            eq(false)))
                    .thenReturn(nullOutput);

            assertThat(client.fetchGroups()).isEmpty();
        }

        @Test
        @DisplayName("게이트 소진 전파(EGW00201) — 예외가 상위로 전파(호출부 그룹 skip)")
        void fetchGroups_gateExhausted_propagates() throws Exception {
            when(keyLeaseRegistry.openSession()).thenReturn(session);
            KisRateLimitException egw = new KisRateLimitException("test", "EGW00201 소진");
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(GROUP_TR_ID),
                            eq(KisGroupListResponse.class),
                            eq(false)))
                    .thenThrow(egw);

            assertThatThrownBy(client::fetchGroups).isSameAs(egw);
        }

        @Test
        @DisplayName("게이트 인터럽트 — 플래그 복원 후 IllegalStateException 전파")
        void fetchGroups_interrupted_restoresFlagAndWraps() throws Exception {
            when(keyLeaseRegistry.openSession()).thenReturn(session);
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(GROUP_TR_ID),
                            eq(KisGroupListResponse.class),
                            eq(false)))
                    .thenThrow(new InterruptedException());

            assertThatThrownBy(client::fetchGroups)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("인터럽트");
            assertThat(Thread.interrupted()).isTrue(); // 플래그 복원 확인 + 정리
        }
    }

    @Nested
    @DisplayName("fetchStocksByGroup — throttle-on, 주입 세션 사용 (REQ-KISGATE-030a)")
    class FetchStocksByGroup {

        @Test
        @DisplayName("정상 응답 — 주입 세션으로 게이트를 throttle-on(기본)으로 경유하여 종목 목록 반환")
        void fetchStocksByGroup_normalResponse_routesThroughGateThrottleOn() throws Exception {
            // Arrange
            KisStockListByGroupResponse ok =
                    new KisStockListByGroupResponse(
                            "0",
                            "MCA00000",
                            "정상",
                            List.of(
                                    new KisStockListByGroupResponse.Stock(
                                            "J", "005930", "KRX", "삼성전자")));
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(STOCK_TR_ID),
                            eq(KisStockListByGroupResponse.class)))
                    .thenReturn(ok);

            // Act
            List<KisStockListByGroupResponse.Stock> stocks =
                    client.fetchStocksByGroup(session, "001");

            // Assert: throttle-on(3-arg 오버로드 = 기본 throttle=true)으로 경유, 세션은 주입받은 것 사용(자체 open 없음)
            assertThat(stocks).hasSize(1);
            assertThat(stocks.getFirst().jongCode()).isEqualTo("005930");
            verify(guardedKisExecutor, times(1))
                    .execute(
                            eq(session),
                            any(),
                            eq(STOCK_TR_ID),
                            eq(KisStockListByGroupResponse.class));
            verify(keyLeaseRegistry, never()).openSession();
        }

        @Test
        @DisplayName("output2 null 응답 — 빈 리스트 반환")
        void fetchStocksByGroup_nullOutput2_returnsEmptyList() throws Exception {
            KisStockListByGroupResponse nullOutput =
                    new KisStockListByGroupResponse("0", "MCA00000", "정상", null);
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(STOCK_TR_ID),
                            eq(KisStockListByGroupResponse.class)))
                    .thenReturn(nullOutput);

            assertThat(client.fetchStocksByGroup(session, "001")).isEmpty();
        }

        @Test
        @DisplayName("게이트 소진 전파(RestClientException) — 예외가 상위로 전파(호출부 그룹 skip)")
        void fetchStocksByGroup_gateExhausted_propagates() throws Exception {
            RestClientException netError = new RestClientException("네트워크 오류");
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(STOCK_TR_ID),
                            eq(KisStockListByGroupResponse.class)))
                    .thenThrow(netError);

            assertThatThrownBy(() -> client.fetchStocksByGroup(session, "001")).isSameAs(netError);
        }

        @Test
        @DisplayName("비즈니스 오류(KisApiBusinessException) — 게이트 non-retryable 즉시 전파")
        void fetchStocksByGroup_businessError_propagates() throws Exception {
            KisApiBusinessException biz = new KisApiBusinessException("1", "EGW00456", "그룹 조회 실패");
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(STOCK_TR_ID),
                            eq(KisStockListByGroupResponse.class)))
                    .thenThrow(biz);

            assertThatThrownBy(() -> client.fetchStocksByGroup(session, "001")).isSameAs(biz);
        }

        @Test
        @DisplayName("게이트 인터럽트 — 플래그 복원 후 IllegalStateException 전파")
        void fetchStocksByGroup_interrupted_restoresFlagAndWraps() throws Exception {
            when(guardedKisExecutor.execute(
                            eq(session),
                            any(),
                            eq(STOCK_TR_ID),
                            eq(KisStockListByGroupResponse.class)))
                    .thenThrow(new InterruptedException());

            assertThatThrownBy(() -> client.fetchStocksByGroup(session, "001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("인터럽트");
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Nested
    @DisplayName("구조적 baseline — 게이트 경유로 전환됨 (DP2 신규 구조)")
    class StructuralBaseline {

        @Test
        @DisplayName(
                "클라이언트는 GuardedKisExecutor/KeyLeaseRegistry를 협력자로 가진다 (RetryExecutor/Sleeper 직접 구성 제거)")
        void watchlistClient_collaboratesWithGate() {
            List<Class<?>> ctorParamTypes =
                    java.util.Arrays.asList(
                            client.getClass().getDeclaredConstructors()[0].getParameterTypes());

            // 게이트로 retry+throttle 흡수 — 클라이언트는 더 이상 Sleeper/RetryExecutor를 직접 구성하지 않는다.
            assertThat(ctorParamTypes)
                    .contains(
                            GuardedKisExecutor.class, KeyLeaseRegistry.class, KisProperties.class);
        }

        @Test
        @DisplayName("mock 게이트로 단독 동작 — 외부 의존 없이 게이트만으로 응답 매핑")
        void watchlistClient_worksWithMockGate() throws Exception {
            GuardedKisExecutor gate = mock(GuardedKisExecutor.class);
            KeyLeaseRegistry registry = mock(KeyLeaseRegistry.class);
            KisProperties props = mock(KisProperties.class);
            KisWatchlistClient isolated = new KisWatchlistClient(gate, registry, props);

            when(gate.execute(any(), any(), eq(STOCK_TR_ID), eq(KisStockListByGroupResponse.class)))
                    .thenReturn(new KisStockListByGroupResponse("0", "00000", "OK", null));

            assertThat(isolated.fetchStocksByGroup(session, "001")).isEmpty();
        }
    }
}
