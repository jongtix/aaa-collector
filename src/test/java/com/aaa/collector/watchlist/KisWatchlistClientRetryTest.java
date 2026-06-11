package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * KisWatchlistClient 재시도 정책 검증.
 *
 * <p>AOP 기반 Spring Retry에서 RetryExecutor + Sleeper 주입 기반으로 재작성. 실제 sleep 없이 동작 검증.
 */
@ExtendWith(MockitoExtension.class)
class KisWatchlistClientRetryTest {

    @Mock private KisApiExecutor kisApiExecutor;
    @Mock private KisProperties kisProperties;

    /** no-op Sleeper: 실제 sleep 없이 재시도 로직 검증 (AC-8.4). */
    private KisWatchlistClient client;

    private final List<Long> capturedSleepDelays = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        // lenient: 일부 테스트에서 userId() 미호출 시 UnnecessaryStubbingException 방지
        lenient().when(kisProperties.userId()).thenReturn("testUser");
        client = new KisWatchlistClient(kisApiExecutor, kisProperties, capturedSleepDelays::add);
    }

    // ── AC-4.1 / AC-4.2: fetchGroups 재시도 정책 ──────────────────────────────

    @Nested
    @DisplayName("fetchGroups — RestClientException 재시도 (AC-4.1)")
    class FetchGroupsRestClientRetry {

        @Test
        @DisplayName("항상 RestClientException — maxAttempts=3 재시도 후 전파")
        void fetchGroups_restClientException_retriesMaxAttempts() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new RestClientException("서버 오류"));

            // Act & Assert
            assertThatThrownBy(client::fetchGroups).isInstanceOf(RestClientException.class);

            verify(kisApiExecutor, times(3)).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("RestClientResponseException(하위타입) — retryable, 3회 호출")
        void fetchGroups_restClientResponseException_retriedAsSubtype() {
            // Arrange
            RestClientResponseException ex =
                    new RestClientResponseException(
                            "5xx",
                            org.springframework.http.HttpStatusCode.valueOf(500),
                            "ISE",
                            null,
                            null,
                            null);
            when(kisApiExecutor.executeGet(any(), anyString(), any())).thenThrow(ex);

            // Act & Assert
            assertThatThrownBy(client::fetchGroups).isInstanceOf(RestClientResponseException.class);

            verify(kisApiExecutor, times(3)).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("KisApiBusinessException — 재시도 없이 즉시 전파 (API 1회 호출) (AC-4.2)")
        void fetchGroups_businessError_noRetry() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00123", "인증 오류"));

            // Act & Assert
            assertThatThrownBy(client::fetchGroups).isInstanceOf(KisApiBusinessException.class);

            verify(kisApiExecutor, times(1)).executeGet(any(), anyString(), any());
        }
    }

    // ── AC-4.3: fetchStocksByGroup 재시도 정책 ────────────────────────────────

    @Nested
    @DisplayName("fetchStocksByGroup — RestClientException 재시도 (AC-4.3)")
    class FetchStocksByGroupRestClientRetry {

        @Test
        @DisplayName("항상 RestClientException — maxAttempts=3 재시도 후 전파")
        void fetchStocksByGroup_restClientException_retriesMaxAttempts() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new RestClientException("서버 오류"));

            // Act & Assert
            assertThatThrownBy(() -> client.fetchStocksByGroup("001"))
                    .isInstanceOf(RestClientException.class);

            verify(kisApiExecutor, times(3)).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("KisApiBusinessException — 재시도 없이 즉시 전파 (API 1회 호출)")
        void fetchStocksByGroup_businessError_noRetry() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00456", "그룹코드 오류"));

            // Act & Assert
            assertThatThrownBy(() -> client.fetchStocksByGroup("001"))
                    .isInstanceOf(KisApiBusinessException.class);

            verify(kisApiExecutor, times(1)).executeGet(any(), anyString(), any());
        }
    }

    // ── AC-5: EGW00201 신규 동작 (D2) ────────────────────────────────────────

    @Nested
    @DisplayName("AC-5: EGW00201(KisRateLimitException) 신규 재시도 동작 (D2)")
    class EgwRateLimitNewBehavior {

        @Test
        @DisplayName("AC-5.1: EGW00201 2회 후 성공 — 총 3회 호출, 정상 결과 반환")
        void fetchGroups_rateLimitTwiceThenSuccess_totalThreeCalls() {
            // Arrange
            KisGroupListResponse successResponse = mock(KisGroupListResponse.class);
            when(successResponse.output2()).thenReturn(List.of());
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"))
                    .thenReturn(successResponse);

            // Act
            List<KisGroupListResponse.Group> result = client.fetchGroups();

            // Assert: 성공, 총 3회 호출
            assertThat(result).isEmpty();
            verify(kisApiExecutor, times(3)).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("AC-5.2: EGW00201 3회 소진 — 3회 호출 후 KisRateLimitException 전파")
        void fetchGroups_rateLimitExhausted_propagatesException() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"));

            // Act & Assert
            assertThatThrownBy(client::fetchGroups).isInstanceOf(KisRateLimitException.class);

            verify(kisApiExecutor, times(3)).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("AC-5.3: 회귀 가드 — RestClientException과 BusinessException은 EGW00201과 독립적으로 동작")
        void fetchGroups_egw00201VsOtherExceptions_differentBehavior() {
            // EGW00201: 3회 호출
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"));
            assertThatThrownBy(client::fetchGroups).isInstanceOf(KisRateLimitException.class);
            verify(kisApiExecutor, times(3)).executeGet(any(), anyString(), any());

            // 재설정 (kisProperties 스텁은 @BeforeEach lenient으로 유지되므로 재설정 불필요)
            org.mockito.Mockito.reset(kisApiExecutor);
            lenient().when(kisProperties.userId()).thenReturn("testUser");

            // BusinessException: 1회 호출 (변경 없음)
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00456", "비즈니스 오류"));
            assertThatThrownBy(client::fetchGroups).isInstanceOf(KisApiBusinessException.class);
            verify(kisApiExecutor, times(1)).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("AC-5.1 fetchStocksByGroup: EGW00201 1회 후 성공 — 총 2회 호출")
        void fetchStocksByGroup_rateLimitOnceThenSuccess() {
            // Arrange
            KisStockListByGroupResponse successResponse = mock(KisStockListByGroupResponse.class);
            when(successResponse.output2()).thenReturn(List.of());
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"))
                    .thenReturn(successResponse);

            // Act
            List<KisStockListByGroupResponse.Stock> result = client.fetchStocksByGroup("001");

            // Assert
            assertThat(result).isEmpty();
            verify(kisApiExecutor, times(2)).executeGet(any(), anyString(), any());
        }
    }

    // ── AC-9.1: baseDelay=500ms 보존 ──────────────────────────────────────────

    @Nested
    @DisplayName("AC-9.1: 백오프 baseDelay=500ms 보존")
    class BackoffDelay {

        @Test
        @DisplayName("EGW00201 2회 후 성공: delay(0)=500ms, delay(1)=1000ms Sleeper에 전달됨")
        void fetchGroups_rateLimitTwice_backoffDelays500msAnd1000ms() {
            // Arrange
            KisGroupListResponse successResponse = mock(KisGroupListResponse.class);
            when(successResponse.output2()).thenReturn(List.of());
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201"))
                    .thenReturn(successResponse);

            // Act
            client.fetchGroups();

            // Assert: delay(0,500)=500ms, delay(1,500)=1000ms
            assertThat(capturedSleepDelays).hasSize(2);
            assertThat(capturedSleepDelays.getFirst()).isEqualTo(500L);
            assertThat(capturedSleepDelays.get(1)).isEqualTo(1000L);
        }

        @Test
        @DisplayName("RestClientException 1회 후 성공: delay(0)=500ms Sleeper에 전달됨")
        void fetchGroups_restClientOnce_backoffDelay500ms() {
            // Arrange
            KisGroupListResponse successResponse = mock(KisGroupListResponse.class);
            when(successResponse.output2()).thenReturn(List.of());
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new RestClientException("일시 오류"))
                    .thenReturn(successResponse);

            // Act
            client.fetchGroups();

            // Assert
            assertThat(capturedSleepDelays).hasSize(1);
            assertThat(capturedSleepDelays.getFirst()).isEqualTo(500L);
        }
    }

    // ── AC-4.4: caller 소진 동작 보존 (WatchlistSyncService 그룹 skip) ────────

    @Nested
    @DisplayName("AC-4.4: caller 소진 동작 보존 — 예외 전파하여 WatchlistSyncService가 흡수")
    class CallerExhaustionBehavior {

        @Test
        @DisplayName("소진 후 예외 전파 — caller(.exceptionally)가 그룹 skip 처리 가능")
        void fetchGroups_exhausted_propagatesForCallerToHandle() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), anyString(), any()))
                    .thenThrow(new RestClientException("서버 오류"));

            // Act: WatchlistSyncService의 CompletableFuture.exceptionally가 포착 가능한 예외 전파
            AtomicInteger failedGroupCount = new AtomicInteger(0);
            List<KisStockListByGroupResponse.Stock> result;
            try {
                client.fetchStocksByGroup("001");
                result = List.of();
            } catch (RestClientException ex) {
                // exceptionally 핸들러 역할 — RestClientException 소진 후 전파
                failedGroupCount.incrementAndGet();
                result = List.of();
            }

            // Assert
            assertThat(failedGroupCount.get()).isEqualTo(1);
            assertThat(result).isEmpty();
        }
    }
}
