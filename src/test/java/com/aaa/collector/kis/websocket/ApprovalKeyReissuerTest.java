package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.token.KisTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ApprovalKeyReissuer} 단일 비행(single-flight) + 최소 간격 검증 (SPEC-COLLECTOR-WS-RESILIENCE-002
 * F1).
 *
 * <p>모든 시간 의존은 주입한 {@link MutableTestClock}으로만 제어하며 실제 sleep·경과 시간 단언을 하지 않는다. 비동기 완료 동기화는 {@link
 * ApprovalKeyReissuer#reissueAsync}가 돌려주는 future로만 수행하므로 느린 CI 머신에서도 결정적이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalKeyReissuer — 별칭별 단일 비행 + 최소 재발급 간격")
class ApprovalKeyReissuerTest {

    private static final String ALIAS = "reissue-alias";
    private static final int BURST_SIZE = 40;
    private static final long AWAIT_SECONDS = 10L;
    private static final Instant T0 = Instant.parse("2026-09-28T02:00:00Z");
    private static final Duration INTERVAL = ApprovalKeyReissuer.MIN_REISSUE_INTERVAL;

    @Mock private KisTokenService kisTokenService;
    @Mock private KisWebSocketSession session;

    private MutableTestClock clock;
    private ApprovalKeyReissuer reissuer;

    @BeforeEach
    void setUp() {
        clock = new MutableTestClock(T0);
        reissuer = new ApprovalKeyReissuer(kisTokenService, clock);
    }

    /** 첫 재발급을 {@code release}가 열릴 때까지 정지시켜 "진행 중" 상태를 결정적으로 만든다. */
    private void blockFirstReissue(CountDownLatch entered, CountDownLatch release) {
        when(kisTokenService.reissueApprovalKey(ALIAS))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertThat(release.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                            return "fresh-key";
                        });
    }

    @Test
    @DisplayName("F1 재현-우선: 한 alias에 인증 실패 콜백 40건이 몰려도 KIS 발급 호출·세션 갱신은 각 정확히 1회")
    void burstOfTriggers_forOneAlias_producesExactlyOneIssuance() throws Exception {
        // Arrange
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstReissue(entered, release);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Act — 선두 재발급이 진행 중인 상태에서 나머지 콜백이 연속 발화
        futures.add(reissuer.reissueAsync(ALIAS, session));
        assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (int i = 1; i < BURST_SIZE; i++) {
            futures.add(reissuer.reissueAsync(ALIAS, session));
        }
        release.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Assert
        verify(kisTokenService, times(1)).invalidateApprovalKey(ALIAS);
        verify(kisTokenService, times(1)).reissueApprovalKey(ALIAS);
        verify(session, times(1)).updateApprovalKey("fresh-key");
    }

    @Test
    @DisplayName("진행 중 재발급은 최소 간격이 이미 경과했더라도 후속 트리거를 합류시킨다(single-flight)")
    void triggerWhileInFlight_evenAfterIntervalElapsed_isCoalesced() throws Exception {
        // Arrange
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstReissue(entered, release);

        // Act
        CompletableFuture<Void> leader = reissuer.reissueAsync(ALIAS, session);
        assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        clock.advance(INTERVAL.plusSeconds(1));
        CompletableFuture<Void> follower = reissuer.reissueAsync(ALIAS, session);
        boolean followerCompletedImmediately = follower.isDone();
        release.countDown();
        leader.get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Assert
        assertThat(followerCompletedImmediately).isTrue();
        verify(kisTokenService, times(1)).reissueApprovalKey(ALIAS);
        verify(session, times(1)).updateApprovalKey("fresh-key");
    }

    @Test
    @DisplayName("재발급 완료 직후라도 최소 간격 이내의 후속 트리거는 억제된다")
    void triggerWithinInterval_afterCompletion_isSuppressed() throws Exception {
        // Arrange
        when(kisTokenService.reissueApprovalKey(ALIAS)).thenReturn("key-1");
        reissuer.reissueAsync(ALIAS, session).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        clock.advance(INTERVAL.minusMillis(1));

        // Act
        CompletableFuture<Void> suppressed = reissuer.reissueAsync(ALIAS, session);

        // Assert
        assertThat(suppressed.isDone()).isTrue();
        verify(kisTokenService, times(1)).reissueApprovalKey(ALIAS);
        verify(session, times(1)).updateApprovalKey("key-1");
    }

    @Test
    @DisplayName("최소 간격 경과 후의 진짜 거절은 새 재발급을 다시 시작한다(1회성 아님)")
    void triggerAfterInterval_startsNewReissue() throws Exception {
        // Arrange
        when(kisTokenService.reissueApprovalKey(ALIAS)).thenReturn("key-1", "key-2");
        reissuer.reissueAsync(ALIAS, session).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        clock.advance(INTERVAL);

        // Act
        reissuer.reissueAsync(ALIAS, session).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Assert
        verify(kisTokenService, times(2)).reissueApprovalKey(ALIAS);
        verify(session, times(1)).updateApprovalKey("key-1");
        verify(session, times(1)).updateApprovalKey("key-2");
    }

    @Test
    @DisplayName("재발급이 실패해도 진행 중 표시가 해제되고 간격은 유지되며, 간격 경과 후 재시도된다")
    void failedReissue_releasesInFlight_andStillHonorsInterval() throws Exception {
        // Arrange
        when(kisTokenService.reissueApprovalKey(ALIAS))
                .thenThrow(new IllegalStateException("KIS 발급 실패"))
                .thenReturn("key-2");
        reissuer.reissueAsync(ALIAS, session).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        verify(session, never()).updateApprovalKey(anyString());

        // Act 1 — 간격 이내: 실패한 시도도 KIS 호출 1회로 계산되므로 억제
        clock.advance(INTERVAL.minusMillis(1));
        CompletableFuture<Void> suppressed = reissuer.reissueAsync(ALIAS, session);

        // Assert 1
        assertThat(suppressed.isDone()).isTrue();
        verify(kisTokenService, times(1)).reissueApprovalKey(ALIAS);

        // Act 2 — 간격 경과: 진행 중 표시가 해제되어 있으므로 새 시도가 시작
        clock.advance(Duration.ofMillis(1));
        reissuer.reissueAsync(ALIAS, session).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Assert 2
        verify(kisTokenService, times(2)).reissueApprovalKey(ALIAS);
        verify(session, times(1)).updateApprovalKey("key-2");
    }

    @Test
    @DisplayName("단일 비행·최소 간격은 alias별로 독립이다 — 다른 alias의 재발급은 서로를 막지 않는다")
    void differentAliases_areThrottledIndependently() throws Exception {
        // Arrange
        KisWebSocketSession otherSession = mock(KisWebSocketSession.class);
        when(kisTokenService.reissueApprovalKey(ALIAS)).thenReturn("key-a");
        when(kisTokenService.reissueApprovalKey("other-alias")).thenReturn("key-b");

        // Act
        reissuer.reissueAsync(ALIAS, session).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        reissuer.reissueAsync("other-alias", otherSession).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Assert
        verify(session, times(1)).updateApprovalKey("key-a");
        verify(otherSession, times(1)).updateApprovalKey("key-b");
    }
}
