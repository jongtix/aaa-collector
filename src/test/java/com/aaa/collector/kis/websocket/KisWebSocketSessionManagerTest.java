package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KisWebSocketSessionManager")
class KisWebSocketSessionManagerTest {

    private static final int SESSION_COUNT = 5;
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 40;
    private static final int SAFE_MODE_SESSION_INDEX = 2;

    @Mock private KisProperties kisProperties;
    @Mock private KisTokenService kisTokenService;
    @Mock private KisTickPublisher tickPublisher;
    @Mock private SafeModeManager webSocketSafeModeManager;
    @Mock private KisMarketSchedule marketSchedule;
    @Mock private Sleeper sleeper;
    @Mock private Clock clock;

    /** 테스트용 세션 팩토리 — alias별 mock 세션을 반환 */
    private Map<String, KisWebSocketSession> mockSessionMap;

    private List<KisWebSocketSession> mockSessions;

    private KisWebSocketSessionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // 5개 계좌 설정
        List<KisAccountCredential> accounts = buildAccounts(SESSION_COUNT);
        when(kisProperties.accounts()).thenReturn(accounts);
        when(kisTokenService.getValidApprovalKey(anyString())).thenReturn("approval-key");

        // mock 세션 생성
        mockSessions = new ArrayList<>();
        mockSessionMap = new ConcurrentHashMap<>();
        for (KisAccountCredential account : accounts) {
            KisWebSocketSession mockSession = mock(KisWebSocketSession.class);
            when(mockSession.getAlias()).thenReturn(account.alias());
            when(mockSession.isInSafeMode()).thenReturn(false);
            when(mockSession.getSubscriptionCount()).thenReturn(0);
            mockSessions.add(mockSession);
            mockSessionMap.put(account.alias(), mockSession);
        }

        // 팩토리 함수: alias → mock 세션
        KisWebSocketSessionFactory factory = (alias, approvalKey) -> mockSessionMap.get(alias);

        manager =
                new KisWebSocketSessionManager(
                        kisProperties,
                        kisTokenService,
                        tickPublisher,
                        webSocketSafeModeManager,
                        marketSchedule,
                        sleeper,
                        clock,
                        factory,
                        new SimpleMeterRegistry());

        manager.openAll();
    }

    // ──────────────────────────────────────────────────────────────────
    // 균등 분배 (REQ-WS-004, AC-3, AC-4)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("200개 구독 균등 분배")
    class EvenDistribution {

        @Test
        @DisplayName("100 심볼 × 2 trId = 200 구독 → 세션당 40개 균등 분배 (REQ-WS-004)")
        @SuppressWarnings({
            "PMD.AvoidCatchingGenericException",
            "PMD.AvoidThrowingRawExceptionTypes"
        })
        void shouldDistribute200SubscriptionsEvenly() {
            // Arrange — 각 세션이 실제 카운터처럼 동작하도록 설정
            int[] counts = new int[SESSION_COUNT];
            for (int i = 0; i < SESSION_COUNT; i++) {
                final int idx = i;
                KisWebSocketSession s = mockSessions.get(i);
                // getSubscriptionCount()가 동적으로 counts[idx] 반환
                when(s.getSubscriptionCount()).thenAnswer(inv -> counts[idx]);
                when(s.isInSafeMode()).thenReturn(false);
                // subscribe 호출 시 counts 증가
                try {
                    doAnswer(
                                    inv -> {
                                        counts[idx]++;
                                        return null;
                                    })
                            .when(s)
                            .subscribe(anyString(), anyString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            // Act — 100 심볼 × 2 trId
            for (int i = 0; i < 100; i++) {
                String symbol = String.format("SYM%03d", i);
                manager.assignSubscription("H0STCNT0", symbol);
                manager.assignSubscription("H0STASP0", symbol);
            }

            // Assert — 각 세션 40개, 분산 ≤ 1
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int count : counts) {
                min = Math.min(min, count);
                max = Math.max(max, count);
            }
            assertThat(max - min).isLessThanOrEqualTo(1);
            // 총합 200
            int total = 0;
            for (int count : counts) {
                total += count;
            }
            assertThat(total).isEqualTo(200);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 포화 세션 스킵 (REQ-WS-007, AC-4)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("포화 세션 스킵")
    class SkipSaturatedSession {

        @Test
        @DisplayName("session-0이 40개로 포화 → session-1부터 할당 (REQ-WS-007)")
        void shouldSkipSaturatedSession() throws Exception {
            // Arrange — session-0 포화
            when(mockSessions.getFirst().getSubscriptionCount())
                    .thenReturn(MAX_SUBSCRIPTIONS_PER_SESSION);
            when(mockSessions.getFirst().isInSafeMode()).thenReturn(false);
            // session-1..4 사용 가능
            for (int i = 1; i < SESSION_COUNT; i++) {
                when(mockSessions.get(i).getSubscriptionCount()).thenReturn(0);
                when(mockSessions.get(i).isInSafeMode()).thenReturn(false);
            }

            // Act
            boolean result = manager.assignSubscription("H0STCNT0", "005930");

            // Assert — 성공, session-0에는 subscribe 호출 없음
            assertThat(result).isTrue();
            verify(mockSessions.getFirst(), never()).subscribe(anyString(), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 안전 모드 세션 스킵 (REQ-WS-008, AC-21)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("안전 모드 세션 스킵")
    class SkipSafeModeSession {

        @Test
        @DisplayName("session-2가 안전 모드 → session-2에는 구독 할당 없음 (REQ-WS-008)")
        void shouldNotRouteToSafeModeSession() throws Exception {
            // Arrange — session-2만 안전 모드
            when(mockSessions.get(SAFE_MODE_SESSION_INDEX).isInSafeMode()).thenReturn(true);

            // 나머지 세션 사용 가능
            for (int i = 0; i < SESSION_COUNT; i++) {
                if (i != SAFE_MODE_SESSION_INDEX) {
                    when(mockSessions.get(i).isInSafeMode()).thenReturn(false);
                    when(mockSessions.get(i).getSubscriptionCount()).thenReturn(0);
                }
            }

            // Act — 여러 번 할당
            for (int i = 0; i < 10; i++) {
                manager.assignSubscription("H0STCNT0", "SYM" + i);
            }

            // Assert — session-2에는 구독 없음
            verify(mockSessions.get(SAFE_MODE_SESSION_INDEX), never())
                    .subscribe(anyString(), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 전체 포화 → 거부 (REQ-WS-006, AC-3)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("전체 포화 시 거부")
    class RejectWhenAllFull {

        @Test
        @DisplayName("5개 세션 모두 40개 포화 → assignSubscription false 반환 (REQ-WS-006)")
        void shouldReturnFalseWhenAllSessionsFull() {
            // Arrange — 전체 포화
            for (KisWebSocketSession s : mockSessions) {
                when(s.getSubscriptionCount()).thenReturn(MAX_SUBSCRIPTIONS_PER_SESSION);
                when(s.isInSafeMode()).thenReturn(false);
            }

            // Act & Assert
            boolean result = manager.assignSubscription("H0STCNT0", "005930");
            assertThat(result).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 부분 실패 독립성 (REQ-WS-023, AC-10)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("부분 실패 독립성")
    class PartialFailureIndependence {

        @Test
        @DisplayName("session-0 안전 모드여도 나머지 세션은 정상 구독 (REQ-WS-023)")
        void shouldContinueWithRemainingSessionsWhenOneInSafeMode() throws Exception {
            // Arrange
            when(mockSessions.getFirst().isInSafeMode()).thenReturn(true);
            for (int i = 1; i < SESSION_COUNT; i++) {
                when(mockSessions.get(i).isInSafeMode()).thenReturn(false);
                when(mockSessions.get(i).getSubscriptionCount()).thenReturn(0);
            }

            // Act
            boolean result = manager.assignSubscription("H0STCNT0", "005930");

            // Assert
            assertThat(result).isTrue();
            verify(mockSessions.getFirst(), never()).subscribe(anyString(), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 401/Auth 실패 → 승인키 재발급 (REQ-WS-042, AC-15)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("승인키 재발급 (REQ-WS-042)")
    class ApprovalKeyReissue {

        @Test
        @DisplayName("connect() 실패 → reissueApprovalKey 호출 후 재연결 성공 (REQ-WS-042)")
        void shouldReissueApprovalKeyOnConnectFailure() throws Exception {
            // Arrange
            KisProperties singleAccountProps = mock(KisProperties.class);
            List<KisAccountCredential> singleAccount =
                    List.of(
                            new KisAccountCredential(
                                    "single",
                                    "1234560-01",
                                    "APPKEY0ABCDEF1234567890",
                                    "SECRET0ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890"));
            when(singleAccountProps.accounts()).thenReturn(singleAccount);

            KisTokenService reissueTokenService = mock(KisTokenService.class);
            when(reissueTokenService.getValidApprovalKey("single")).thenReturn("old-key");
            when(reissueTokenService.reissueApprovalKey("single")).thenReturn("new-key");

            // 첫 번째 세션: connect() 실패
            KisWebSocketSession failSession = mock(KisWebSocketSession.class);
            when(failSession.getAlias()).thenReturn("single");
            doThrow(new RuntimeException("WebSocket 연결 실패")).when(failSession).connect(anyString());

            // 두 번째 세션: connect() 성공
            KisWebSocketSession successSession = mock(KisWebSocketSession.class);
            when(successSession.getAlias()).thenReturn("single");

            // 팩토리: "old-key"이면 failSession, "new-key"이면 successSession 반환
            KisWebSocketSessionFactory reissueFactory = mock(KisWebSocketSessionFactory.class);
            when(reissueFactory.create(eq("single"), eq("old-key"))).thenReturn(failSession);
            when(reissueFactory.create(eq("single"), eq("new-key"))).thenReturn(successSession);

            KisWebSocketSessionManager reissueManager =
                    new KisWebSocketSessionManager(
                            singleAccountProps,
                            reissueTokenService,
                            tickPublisher,
                            webSocketSafeModeManager,
                            marketSchedule,
                            sleeper,
                            clock,
                            reissueFactory,
                            new SimpleMeterRegistry());

            // Act
            reissueManager.openAll();

            // Assert — reissueApprovalKey 호출 후 successSession으로 재연결
            verify(reissueTokenService).reissueApprovalKey("single");
            verify(failSession).connect(anyString());
            verify(successSession).connect(anyString());
        }

        @Test
        @DisplayName("getValidApprovalKey 1회 실패 후 2회에 성공 → openAll() 성공")
        void shouldRetryApprovalKeyOnAuthFailure() throws Exception {
            // Arrange — account-0만 1회 실패, 이후 성공
            KisProperties singleAccountProps = mock(KisProperties.class);
            List<KisAccountCredential> singleAccount =
                    List.of(
                            new KisAccountCredential(
                                    "single",
                                    "1234560-01",
                                    "APPKEY0ABCDEF1234567890",
                                    "SECRET0ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890"));
            when(singleAccountProps.accounts()).thenReturn(singleAccount);

            KisTokenService retryTokenService = mock(KisTokenService.class);
            when(retryTokenService.getValidApprovalKey("single"))
                    .thenThrow(new RuntimeException("401 Unauthorized"))
                    .thenReturn("new-approval-key");

            KisWebSocketSession retrySession = mock(KisWebSocketSession.class);
            when(retrySession.getAlias()).thenReturn("single");
            KisWebSocketSessionFactory retryFactory = (alias, approvalKey) -> retrySession;

            KisWebSocketSessionManager retryManager =
                    new KisWebSocketSessionManager(
                            singleAccountProps,
                            retryTokenService,
                            tickPublisher,
                            webSocketSafeModeManager,
                            marketSchedule,
                            sleeper,
                            clock,
                            retryFactory,
                            new SimpleMeterRegistry());

            // Act — 1회 실패 → 자동 재시도 → 성공
            retryManager.openAll();

            // Assert — 정확히 2회 호출 (1회 실패 + 1회 재시도)
            verify(retryTokenService, times(2)).getValidApprovalKey("single");
            verify(retrySession, times(1)).connect(anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 구독 해제 라우팅 (REQ-WS-062)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("구독 해제 라우팅 (REQ-WS-062)")
    class UnassignSubscription {

        @Test
        @DisplayName("symbol을 session-1에 구독 → unassign 시 session-1의 unsubscribe 호출")
        void shouldRouteUnassignToCorrectSession() throws Exception {
            // Arrange — session-0 포화, session-1 사용 가능
            when(mockSessions.getFirst().getSubscriptionCount())
                    .thenReturn(MAX_SUBSCRIPTIONS_PER_SESSION);
            when(mockSessions.getFirst().isInSafeMode()).thenReturn(false);
            when(mockSessions.get(1).getSubscriptionCount()).thenReturn(0);
            when(mockSessions.get(1).isInSafeMode()).thenReturn(false);
            // 나머지 사용 가능
            for (int i = 2; i < SESSION_COUNT; i++) {
                when(mockSessions.get(i).isInSafeMode()).thenReturn(false);
                when(mockSessions.get(i).getSubscriptionCount()).thenReturn(0);
            }

            manager.assignSubscription("H0STCNT0", "005930");

            // Act
            manager.unassignSubscription("H0STCNT0", "005930");

            // Assert — 005930이 할당된 세션(session-1)에만 unsubscribe 호출
            verify(mockSessions.getFirst(), never()).unsubscribe(anyString(), anyString());
            verify(mockSessions.get(1), times(1)).unsubscribe("H0STCNT0", "005930");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 해외 구독 (subscribeOverseasSymbols, REQ-WSOV-031)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("subscribeOverseasSymbols — 해외 tr_key 구독")
    class SubscribeOverseasSymbols {

        @Test
        @DisplayName("AC-SUB-1: 2개 tr_key 입력 → HDFSCNT0 + HDFSASP0 각 2회 = assignSubscription 4회")
        void shouldCallAssignSubscriptionTwicePerTrKey() throws Exception {
            // Arrange — 모든 세션 사용 가능
            for (KisWebSocketSession s : mockSessions) {
                when(s.getSubscriptionCount()).thenReturn(0);
                when(s.isInSafeMode()).thenReturn(false);
            }
            List<String> trKeys = List.of("DNASAAPL", "DNYSSPY");

            // Act
            manager.subscribeOverseasSymbols(trKeys);

            // Assert — assignSubscription은 내부에서 session.subscribe를 호출
            // 2 trKey × (HDFSCNT0 + HDFSASP0) = 4번 subscribe 호출 (어느 세션에든)
            int totalSubscribeCalls = 0;
            for (KisWebSocketSession s : mockSessions) {
                // subscribe 총 호출수 집계
                org.mockito.invocation.InvocationOnMock[] invocations =
                        org.mockito.Mockito.mockingDetails(s).getInvocations().stream()
                                .filter(inv -> "subscribe".equals(inv.getMethod().getName()))
                                .toArray(org.mockito.invocation.InvocationOnMock[]::new);
                totalSubscribeCalls += invocations.length;
            }
            assertThat(totalSubscribeCalls).isEqualTo(4);
        }

        @Test
        @DisplayName("AC-SUB-2: 세션 포화(assignSubscription false) 시 break — 이후 tr_key 구독 없음")
        void shouldBreakWhenSessionFull() throws Exception {
            // Arrange — 모든 세션 포화
            for (KisWebSocketSession s : mockSessions) {
                when(s.getSubscriptionCount()).thenReturn(MAX_SUBSCRIPTIONS_PER_SESSION);
                when(s.isInSafeMode()).thenReturn(false);
            }
            List<String> trKeys = List.of("DNASAAPL", "DNYSSPY", "DAMSXYZ");

            // Act
            manager.subscribeOverseasSymbols(trKeys);

            // Assert — 어느 세션에도 subscribe 호출 없음 (전체 포화)
            for (KisWebSocketSession s : mockSessions) {
                verify(s, never()).subscribe(anyString(), anyString());
            }
        }

        @Test
        @DisplayName("AC-SCOPE-1: subscribeOverseasSymbols가 사용하는 trId는 HDFSCNT0+HDFSASP0 2개만")
        void shouldOnlyUseOverseasTrIds() throws Exception {
            // Arrange
            int[] counts = new int[SESSION_COUNT];
            List<String> usedTrIds = new ArrayList<>();
            for (int i = 0; i < SESSION_COUNT; i++) {
                final int idx = i;
                KisWebSocketSession s = mockSessions.get(i);
                when(s.getSubscriptionCount()).thenAnswer(inv -> counts[idx]);
                when(s.isInSafeMode()).thenReturn(false);
                doAnswer(
                                inv -> {
                                    usedTrIds.add(inv.getArgument(0));
                                    counts[idx]++;
                                    return null;
                                })
                        .when(s)
                        .subscribe(anyString(), anyString());
            }

            // Act — tr_key 1개 구독
            manager.subscribeOverseasSymbols(List.of("DNASAAPL"));

            // Assert — 사용된 trId가 HDFSCNT0 + HDFSASP0 2개만
            assertThat(usedTrIds).containsExactlyInAnyOrder("HDFSCNT0", "HDFSASP0");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────

    private List<KisAccountCredential> buildAccounts(int count) {
        List<KisAccountCredential> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            accounts.add(
                    new KisAccountCredential(
                            "account-" + i,
                            "123456" + i + "-01",
                            "APPKEY" + i + "ABCDEF1234567890",
                            "SECRET"
                                    + i
                                    + "ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890"));
        }
        return accounts;
    }
}
