package com.aaa.collector.kis.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.KisRateLimiterRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 M3(T04) — {@link GuardedKisExecutor} 단위 테스트.
 *
 * <p>검증 범위: 성공 경로 lease+consume+execute 1회(REQ-020), EGW00201 재시도 시 키 재선택(AC-3, 첫 release가 재시도 전)·매
 * 시도 consume/release, non-retryable 즉시 전파(AC-9), throttle-off가 consume 생략(AC-8), 소진 시 전파(REQ-022),
 * Sleeper 주입으로 실제 sleep 없이 동작(REQ-032).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardedKisExecutor — lease+throttle+retry 단일 진입점")
class GuardedKisExecutorTest {

    private static final String TR_ID = "TR001";

    private static final KisAccountCredential K1 =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential K2 =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private KisApiExecutor kisApiExecutor;
    @Mock private KisRateLimiterRegistry kisRateLimiterRegistry;
    @Mock private Sleeper sleeper;

    private GuardedKisExecutor gate;
    private KeyLeaseRegistry keyLeaseRegistry;

    private static final Function<UriBuilder, URI> URI_CUSTOMIZER = b -> URI.create("/test");

    @BeforeEach
    void setUp() {
        keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        gate = new GuardedKisExecutor(kisRateLimiterRegistry, kisApiExecutor, sleeper);
    }

    /** 최소 KisApiResponse 스텁. */
    static class StubResponse implements KisApiResponse {
        @Override
        public String rtCd() {
            return "0";
        }

        @Override
        public String msgCd() {
            return "00000";
        }

        @Override
        public String msg1() {
            return "OK";
        }
    }

    @Nested
    @DisplayName("성공 경로 (REQ-KISGATE-020)")
    class Success {

        @Test
        @DisplayName("정상 — lease 1회 + consume/release 1쌍 + executeGet 1회(leased credential 전달)")
        void execute_success_leasesConsumesExecutesOnce() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            KisRateLimiter limiter = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias("isa")).thenReturn(limiter);
            StubResponse stub = new StubResponse();
            when(kisApiExecutor.executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenReturn(stub);

            LeaseSession session = keyLeaseRegistry.openSession();
            StubResponse result = gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class);

            assertThat(result).isSameAs(stub);
            verify(limiter, times(1)).consume();
            verify(limiter, times(1)).release();
            verify(kisApiExecutor, times(1))
                    .executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class));
            // 스냅샷 1회 — lease 직전 라이브 프로브 없음
            verify(healthyKeySelector, times(1)).selectHealthy();
        }
    }

    @Nested
    @DisplayName("재시도 시 키 재선택 (AC-3, REQ-KISGATE-021)")
    class ReLease {

        @Test
        @DisplayName("첫 시도 K1 EGW00201 → 재시도는 K2로 전환 + 매 시도 consume/release 재경유")
        void execute_egw00201_reLeasesToK2() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            KisRateLimiter limiterK1 = mock(KisRateLimiter.class);
            KisRateLimiter limiterK2 = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias("isa")).thenReturn(limiterK1);
            when(kisRateLimiterRegistry.forAlias("gold")).thenReturn(limiterK2);

            StubResponse stub = new StubResponse();
            // K1(첫 시도)은 EGW00201, K2(재시도)는 성공
            when(kisApiExecutor.executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"));
            when(kisApiExecutor.executeGet(eq(K2), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenReturn(stub);

            LeaseSession session = keyLeaseRegistry.openSession();
            StubResponse result = gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class);

            // 재시도가 K2로 전환됨(막힌 키 K1 회피) + 매 시도 limiter 재경유(K1 1쌍, K2 1쌍)
            assertThat(result).isSameAs(stub);
            verify(kisApiExecutor).executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class));
            verify(kisApiExecutor).executeGet(eq(K2), any(), eq(TR_ID), eq(StubResponse.class));
            verify(limiterK1).release();
            verify(limiterK2).consume();
        }

        @Test
        @DisplayName("첫 시도의 limiter release가 백오프 sleep(재시도 직전)보다 먼저 일어난다(AC-3)")
        void execute_egw00201_releasesBeforeRetryBackoff() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            KisRateLimiter limiterK1 = mock(KisRateLimiter.class);
            KisRateLimiter limiterK2 = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias("isa")).thenReturn(limiterK1);
            when(kisRateLimiterRegistry.forAlias("gold")).thenReturn(limiterK2);
            when(kisApiExecutor.executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"));
            when(kisApiExecutor.executeGet(eq(K2), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenReturn(new StubResponse());

            LeaseSession session = keyLeaseRegistry.openSession();
            gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class);

            // 첫 시도의 limiter release가 백오프 sleep보다 먼저 — 슬롯을 재시도 전에 반환(retry storm 방지)
            // Sleeper 주입으로 실제 sleep 없이 1회 백오프
            InOrder order = inOrder(limiterK1, sleeper);
            order.verify(limiterK1).release();
            order.verify(sleeper).sleep(anyLong());
        }

        @Test
        @DisplayName("건강 키 1개 — 재시도도 동일 키 재경유(전환 대상 없음)")
        void execute_singleKey_reusesSameKeyOnRetry() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1));
            KisRateLimiter limiter = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias("isa")).thenReturn(limiter);

            StubResponse stub = new StubResponse();
            when(kisApiExecutor.executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"))
                    .thenReturn(stub);

            LeaseSession session = keyLeaseRegistry.openSession();
            StubResponse result = gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class);

            assertThat(result).isSameAs(stub);
            // 동일 키 2회 시도(전환 대상 없음) — 매 시도 재경유
            verify(kisApiExecutor, times(2))
                    .executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class));
            verify(limiter, times(2)).consume();
            verify(limiter, times(2)).release();
        }
    }

    @Nested
    @DisplayName("retryable 분류 (AC-9, REQ-KISGATE-003a/023)")
    class Classification {

        @Test
        @DisplayName("non-retryable(KisApiBusinessException) — 재시도 없이 즉시 전파, executeGet 1회")
        void execute_businessException_propagatesImmediately() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            KisRateLimiter limiter = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias("isa")).thenReturn(limiter);
            KisApiBusinessException businessError =
                    new KisApiBusinessException("1", "EGW00123", "인증 오류");
            when(kisApiExecutor.executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenThrow(businessError);

            LeaseSession session = keyLeaseRegistry.openSession();

            assertThatThrownBy(
                            () -> gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class))
                    .isSameAs(businessError);

            verify(kisApiExecutor, times(1))
                    .executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class));
            verify(limiter, times(1)).consume();
            verify(limiter, times(1)).release();
            verify(sleeper, never()).sleep(anyLong());
        }

        @Test
        @DisplayName("retryable(RestClientException) — 소진까지 재시도 후 전파(RETRY-001 D2 보존)")
        void execute_restClientException_retriesThenPropagates() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            KisRateLimiter limiter = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias(anyString())).thenReturn(limiter);
            RestClientException netError = new RestClientException("네트워크 오류");
            when(kisApiExecutor.executeGet(any(), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenThrow(netError);

            LeaseSession session = keyLeaseRegistry.openSession();

            assertThatThrownBy(
                            () -> gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class))
                    .isSameAs(netError);

            // 초기 + 2회 재시도 = 3회 (MAX_ATTEMPTS=3, 패턴 C와 동일)
            verify(kisApiExecutor, times(3))
                    .executeGet(any(), any(), eq(TR_ID), eq(StubResponse.class));
        }
    }

    @Nested
    @DisplayName("throttle-off 파라미터 (AC-8, REQ-KISGATE-040)")
    class ThrottleOff {

        @Test
        @DisplayName("throttle=false — consume 생략(rate limiter 미경유) + 키는 정상 lease, executeGet 1회")
        void execute_throttleOff_skipsConsume() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            StubResponse stub = new StubResponse();
            when(kisApiExecutor.executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenReturn(stub);

            LeaseSession session = keyLeaseRegistry.openSession();
            StubResponse result =
                    gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class, false);

            assertThat(result).isSameAs(stub);
            // throttle-off: rate limiter 레지스트리 자체를 경유하지 않음(consume/release 생략)
            verify(kisRateLimiterRegistry, never()).forAlias(anyString());
            // 키는 여전히 lease되어 executeGet에 전달됨
            verify(kisApiExecutor, times(1))
                    .executeGet(eq(K1), any(), eq(TR_ID), eq(StubResponse.class));
        }
    }

    @Nested
    @DisplayName("consume() 인터럽트 시 lease 누수 방지 (REQ-KISGATE-005c)")
    class ConsumeInterruptLeaseLeak {

        @Test
        @DisplayName("limiter.consume()가 InterruptedException — lease 카운터 0 복귀(누수 없음) + 인터럽트 전파")
        void execute_consumeInterrupted_releasesLeaseNoLeak() throws Exception {
            // Arrange: consume()이 슬롯 대기 중 인터럽트되는 상황. lease는 이미 획득된 상태여야 한다.
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1));
            KisRateLimiter limiter = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias("isa")).thenReturn(limiter);
            InterruptedException interrupt = new InterruptedException("throttle 대기 중 인터럽트");
            doThrow(interrupt).when(limiter).consume();

            LeaseSession session = keyLeaseRegistry.openSession();

            // Act & Assert: 인터럽트는 RetryExecutor를 통해 그대로 전파된다(재시도 대상 아님).
            assertThatThrownBy(
                            () -> gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class))
                    .isSameAs(interrupt);

            // lease 누수 없음: consume()이 던졌어도 lease.release()가 finally로 보장되어 카운터가 0으로 복귀.
            assertThat(session.inUseCount("isa")).isZero();
            // consume() 실패 → executeGet 미도달 → limiter.release()는 호출되지 않음(acquire/release 페어링 보존).
            verify(kisApiExecutor, never()).executeGet(any(), any(), anyString(), any());
            verify(limiter, never()).release();
        }
    }

    @Nested
    @DisplayName("소진 + 전 키 사망 (REQ-KISGATE-022/024)")
    class ExhaustionAndDeadKeys {

        @Test
        @DisplayName("EGW00201 소진 — 마지막 예외를 전파(종단 변환은 호출부 책임)")
        void execute_egw00201Exhausted_propagatesLastException() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            KisRateLimiter limiter = mock(KisRateLimiter.class);
            when(kisRateLimiterRegistry.forAlias(anyString())).thenReturn(limiter);
            KisRateLimitException egw = new KisRateLimitException("isa", "EGW00201");
            when(kisApiExecutor.executeGet(any(), any(), eq(TR_ID), eq(StubResponse.class)))
                    .thenThrow(egw);

            LeaseSession session = keyLeaseRegistry.openSession();

            assertThatThrownBy(
                            () -> gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class))
                    .isInstanceOf(KisRateLimitException.class);

            // 총 3회 시도, 매 시도 재경유
            verify(kisApiExecutor, times(3))
                    .executeGet(any(), any(), eq(TR_ID), eq(StubResponse.class));
            verify(limiter, times(3)).consume();
            verify(limiter, times(3)).release();
        }

        @Test
        @DisplayName("전 키 사망(빈 스냅샷) — NoHealthyKeyException 즉시 전파(재시도 없음), executeGet 미호출")
        void execute_emptySnapshot_throwsNoHealthyKey() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            LeaseSession session = keyLeaseRegistry.openSession();

            assertThatThrownBy(
                            () -> gate.execute(session, URI_CUSTOMIZER, TR_ID, StubResponse.class))
                    .isInstanceOf(NoHealthyKeyException.class);

            verify(kisApiExecutor, never()).executeGet(any(), any(), anyString(), any());
            verify(sleeper, never()).sleep(anyLong());
        }
    }
}
