package com.aaa.collector.kis.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisDomesticRankingResponse;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 M1(PRESERVE) — 패턴 A/C 현 동작 특성화(characterization) 테스트.
 *
 * <p>게이트({@code GuardedKisExecutor}/{@code KeyLeaseRegistry}) 도입 <b>전</b>의 KIS REST 호출 경로의 현 동작을
 * baseline으로 고정한다. 본 테스트는 마이그레이션 이전의 <b>현재 프로덕션 코드</b>에 대해 통과하며(특성화의 본질), M4/M6 마이그레이션의 회귀 가드가 된다.
 * 본 파일은 어떤 동작도 "있어야 할 모습"이 아니라 "현재 그러한 모습"을 포착한다(REQ-KISGATE-009 종단 동작 보존 검증 기준선).
 *
 * <p>경로별 baseline:
 *
 * <ul>
 *   <li>패턴 A(맨몸): {@code KisApiExecutor.executeGet} 3-arg 직접 호출. throttle❌·재시도❌ — EGW00201 즉시 전파.
 *   <li>패턴 B(watchlist): DP2(M5)로 {@code GuardedKisExecutor} 게이트 통합 완료. 신규 동작은 {@code
 *       KisWatchlistClientTest}·{@code WatchlistSyncServiceTest}에서 검증.
 *   <li>패턴 C(배치): {@code BatchRestExecutor} MAX_RETRIES=2(총 3회)·매 시도 limiter 재경유·소진 시 skip·인터럽트 →
 *       플래그 복원+skip. {@code HealthyKeyRoundRobinDistributor} 정적 i%N 결정성.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KISGATE M1 특성화 — 패턴 A/C 현 동작 baseline")
class PatternCharacterizationTest {

    @Nested
    @DisplayName("패턴 A (맨몸) — KisDomesticRankingClient: throttle❌ 재시도❌, EGW00201 즉시 전파")
    @ExtendWith(MockitoExtension.class)
    class PatternA {

        private static final String RANKING_TR_ID = "FHPST01710000";

        @Mock private KisApiExecutor kisApiExecutor;

        private KisDomesticRankingClient client;

        @BeforeEach
        void setUp() {
            // 패턴 A 대표: 협력자가 KisApiExecutor 단 하나 — rate limiter도 RetryExecutor도 주입되지 않음.
            // 이 생성자 의존성 부재 자체가 "throttle❌ 재시도❌" 맨몸 경로의 구조적 baseline이다.
            client = new KisDomesticRankingClient(kisApiExecutor);
        }

        @Test
        @DisplayName("정상 응답 — 3-arg executeGet 1회 호출 후 output 반환")
        void fetchRanking_success_callsThreeArgExecuteGetOnce() {
            KisDomesticRankingResponse response =
                    new KisDomesticRankingResponse(
                            "0",
                            "MCA00000",
                            "정상",
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", "1", "삼성전자")));
            when(kisApiExecutor.executeGet(
                            any(), anyString(), eq(KisDomesticRankingResponse.class)))
                    .thenReturn(response);

            List<KisDomesticRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().mkscShrnIscd()).isEqualTo("005930");
            // 3-arg(맨몸) 오버로드만 호출 — 4-arg(멀티키) 경로 미사용
            verify(kisApiExecutor, times(1))
                    .executeGet(any(), eq(RANKING_TR_ID), eq(KisDomesticRankingResponse.class));
        }

        @Test
        @DisplayName("EGW00201(KisRateLimitException) — 재시도 없이 즉시 전파, executeGet 정확히 1회 (재시도❌)")
        void fetchRanking_egw00201_propagatesImmediatelyWithoutRetry() {
            // Arrange: 맨몸 경로는 KisRateLimitException을 흡수/재시도하지 않고 그대로 전파한다.
            KisRateLimitException egw =
                    new KisRateLimitException(
                            "isa", "EGW00201 rate-limit 오류 — tr_id=" + RANKING_TR_ID);
            when(kisApiExecutor.executeGet(
                            any(), anyString(), eq(KisDomesticRankingResponse.class)))
                    .thenThrow(egw);

            // Act & Assert: 동일 예외 인스턴스가 그대로 전파됨(흡수/변환 없음)
            assertThatThrownBy(client::fetchRanking).isSameAs(egw);

            // 재시도 부재 — executeGet은 단 1회만 호출됨(throttle 재경유·백오프 없음)
            verify(kisApiExecutor, times(1))
                    .executeGet(any(), eq(RANKING_TR_ID), eq(KisDomesticRankingResponse.class));
        }

        @Test
        @DisplayName("비즈니스 오류(KisApiBusinessException) — 재시도 없이 즉시 전파, executeGet 1회")
        void fetchRanking_businessError_propagatesImmediately() {
            KisApiBusinessException businessError =
                    new KisApiBusinessException("1", "EGW00123", "인증 오류");
            when(kisApiExecutor.executeGet(
                            any(), anyString(), eq(KisDomesticRankingResponse.class)))
                    .thenThrow(businessError);

            assertThatThrownBy(client::fetchRanking).isSameAs(businessError);

            verify(kisApiExecutor, times(1))
                    .executeGet(any(), eq(RANKING_TR_ID), eq(KisDomesticRankingResponse.class));
        }
    }

    @Nested
    @DisplayName(
            "패턴 C (배치) — BatchRestExecutor: MAX_RETRIES=2(총 3회) 매 시도 재경유 / 소진 skip / 인터럽트 skip")
    @ExtendWith(MockitoExtension.class)
    class PatternCBatch {

        private static final KisAccountCredential GOLD =
                new KisAccountCredential("gold", "87654321", "appkey-gold", "appsecret-gold");

        /**
         * 패턴 C 재시도 상한 baseline. {@code BatchRestExecutor.MAX_RETRIES}(=2)는 package-private이라 게이트
         * 패키지에서 참조 불가 — M1 PRESERVE 제약상 가시성을 넓히지 않고 행위(총 3회 시도)로 고정한다. 초기 시도 + 2회 재시도 = 3회.
         */
        private static final int EXPECTED_TOTAL_ATTEMPTS = 3;

        @Mock private KisApiExecutor kisApiExecutor;
        @Mock private KisRateLimiterRegistry kisRateLimiterRegistry;
        @Mock private Sleeper sleeper;
        @Mock private KisRateLimiter limiter;

        private BatchRestExecutor executor;

        @BeforeEach
        void setUp() {
            executor = new BatchRestExecutor(kisApiExecutor, kisRateLimiterRegistry, sleeper);
            when(kisRateLimiterRegistry.forAlias("gold")).thenReturn(limiter);
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

        @Test
        @DisplayName(
                "EGW00201 소진(3회) — BatchResult.skip(종목), 배치 미실패. consume/release 각 3회(매 시도 재경유)")
        void execute_egw00201Exhausted_returnsSkipAndReRoutesLimiterPerAttempt() throws Exception {
            // Arrange: 초기 + 2회 재시도 모두 EGW00201
            when(kisApiExecutor.executeGet(eq(GOLD), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"));

            // Act
            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD,
                            (Function<UriBuilder, URI>) b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "005930");

            // Assert: 배치 전체 실패가 아닌 graceful skip
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getSkippedSymbol()).isPresent().contains("005930");
            // 매 시도 limiter 재경유(retry storm 방지) — consume/release가 시도 횟수만큼(총 3회)
            verify(limiter, times(EXPECTED_TOTAL_ATTEMPTS)).consume();
            verify(limiter, times(EXPECTED_TOTAL_ATTEMPTS)).release();
        }

        @Test
        @DisplayName("EGW00201 1회 후 성공 — consume/release 각 2회, 백오프 1회(매 시도 재경유)")
        void execute_egw00201OnceThenSuccess_consumePerAttempt() throws Exception {
            StubResponse stub = new StubResponse();
            when(kisApiExecutor.executeGet(eq(GOLD), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"))
                    .thenReturn(stub);

            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD,
                            (Function<UriBuilder, URI>) b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "005930");

            assertThat(result.isSuccess()).isTrue();
            verify(limiter, times(2)).consume();
            verify(limiter, times(2)).release();
            verify(sleeper, times(1)).sleep(any(Long.class));
        }

        @Test
        @DisplayName("영구 오류(비-EGW00201) — 재시도 없이 전파, consume/release 1쌍, 백오프 없음")
        void execute_permanentError_propagatesWithoutRetry() throws Exception {
            RuntimeException permanent = new RuntimeException("영구 비즈니스 오류");
            when(kisApiExecutor.executeGet(eq(GOLD), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(permanent);

            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            GOLD,
                                            (Function<UriBuilder, URI>) b -> URI.create("/test"),
                                            "TR001",
                                            StubResponse.class,
                                            "005930"))
                    .isSameAs(permanent);

            verify(limiter, times(1)).consume();
            verify(limiter, times(1)).release();
        }

        @Test
        @DisplayName("인터럽트 수신 — BatchResult.skip 변환(전파 아님) + 인터럽트 플래그 복원")
        void execute_interrupted_returnsSkipAndRestoresFlag() throws Exception {
            // Arrange: 첫 시도에서 InterruptedException 발생
            when(kisApiExecutor.executeGet(eq(GOLD), any(), anyString(), eq(StubResponse.class)))
                    .thenAnswer(
                            inv -> {
                                Thread.currentThread().interrupt();
                                throw new InterruptedException("테스트용 인터럽트");
                            });

            BatchResult<?>[] captured = new BatchResult[1];
            boolean[] flagRestored = new boolean[1];

            // 인터럽트 플래그 검증을 위해 별도 가상 스레드에서 실행
            Thread worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        captured[0] =
                                                executor.execute(
                                                        GOLD,
                                                        (Function<UriBuilder, URI>)
                                                                b -> URI.create("/test"),
                                                        "TR001",
                                                        StubResponse.class,
                                                        "005930");
                                        flagRestored[0] = Thread.currentThread().isInterrupted();
                                    });
            worker.join(5000);

            // Assert: 전파가 아닌 skip 변환, 인터럽트 플래그 복원
            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].isSuccess()).isFalse();
            assertThat(captured[0].getSkippedSymbol()).contains("005930");
            assertThat(flagRestored[0]).isTrue();
        }
    }

    @Nested
    @DisplayName("패턴 C (배치) — HealthyKeyRoundRobinDistributor: 정적 i%N 결정성 / 전 키 사망 시 빈 할당")
    @ExtendWith(MockitoExtension.class)
    class PatternCDistributor {

        private static final KisAccountCredential K1 =
                new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
        private static final KisAccountCredential K2 =
                new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");
        private static final KisAccountCredential K3 =
                new KisAccountCredential(
                        "pension", "33333333", "appkey-pension", "appsecret-pension");

        @Mock private HealthyKeySelector healthyKeySelector;

        private HealthyKeyRoundRobinDistributor distributor;

        @BeforeEach
        void setUp() {
            distributor = new HealthyKeyRoundRobinDistributor(healthyKeySelector);
        }

        @Test
        @DisplayName("정적 i%N — item[i] → healthyKeys.get(i%N) 결정적 할당")
        void distribute_staticModulo_assignsByModulo() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));

            // item[0]→K1, item[1]→K2, item[2]→K3, item[3]→K1
            Map<KisAccountCredential, List<String>> allocation =
                    distributor.distribute(List.of("a", "b", "c", "d"));

            assertThat(allocation.get(K1)).containsExactly("a", "d");
            assertThat(allocation.get(K2)).containsExactly("b");
            assertThat(allocation.get(K3)).containsExactly("c");
        }

        @Test
        @DisplayName("정적 i%N — 동일 입력 → 동일 할당(결정성)")
        void distribute_staticModulo_isDeterministicForSameInput() {
            List<String> items = List.of("a", "b", "c", "d");
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));
            Map<KisAccountCredential, List<String>> first = distributor.distribute(items);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));

            Map<KisAccountCredential, List<String>> second = distributor.distribute(items);

            // i%N 결정성: 동일 입력은 항상 동일 할당
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("전 키 사망(빈 건강 키) — 빈 할당 반환, full-list fallback 없음 (REQ-KEYDIST-020 baseline)")
        void distribute_allKeysDead_returnsEmptyNoFallback() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            Map<KisAccountCredential, List<String>> allocation =
                    distributor.distribute(List.of("a", "b", "c"));

            // 빈 건강 키 → 빈 맵(죽은 키 재배정 없음). skip-all·ERROR 로그는 호출부 책임.
            assertThat(allocation).isEmpty();
        }
    }
}
