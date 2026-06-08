package com.aaa.collector.kis.websocket;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
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
@DisplayName("KisWebSocketSessionManager — 증분 구독 (addSymbol/removeSymbol)")
class KisWebSocketSessionManagerIncrementalTest {

    private static final int SESSION_COUNT = 5;
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 40;

    @Mock private KisProperties kisProperties;
    @Mock private KisTokenService kisTokenService;
    @Mock private KisTickPublisher tickPublisher;
    @Mock private SafeModeManager webSocketSafeModeManager;
    @Mock private KisMarketSchedule marketSchedule;
    @Mock private Sleeper sleeper;
    @Mock private Clock clock;

    private Map<String, KisWebSocketSession> mockSessionMap;
    private List<KisWebSocketSession> mockSessions;
    private KisWebSocketSessionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        List<KisAccountCredential> accounts = buildAccounts(SESSION_COUNT);
        when(kisProperties.accounts()).thenReturn(accounts);
        when(kisTokenService.getValidApprovalKey(anyString())).thenReturn("approval-key");

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
                        factory);

        manager.openAll();
    }

    // ──────────────────────────────────────────────────────────────────
    // addSymbol
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addSymbol — 단일 종목 증분 추가")
    class AddSymbol {

        @Test
        @DisplayName("addSymbol 호출 시 H0STCNT0과 H0STASP0 구독이 모두 할당된다")
        void shouldAssignBothTrIdSubscriptions() throws Exception {
            // Act
            manager.addSymbol("005930");

            // Assert — 체결(H0STCNT0)과 호가(H0STASP0) 두 구독 모두 세션에 전달됨
            // 라운드로빈으로 세션에 분산되므로 전체 subscribe 호출 수를 검증
            int totalSubscribeCalls = 0;
            for (KisWebSocketSession session : mockSessions) {
                totalSubscribeCalls +=
                        org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                                .filter(inv -> "subscribe".equals(inv.getMethod().getName()))
                                .mapToInt(inv -> 1)
                                .sum();
            }
            org.assertj.core.api.Assertions.assertThat(totalSubscribeCalls).isEqualTo(2);
        }

        @Test
        @DisplayName("세션 포화 상태에서 addSymbol 호출 시 예외 없이 경고만 기록된다")
        void shouldNotThrowWhenSessionsAreSaturated() {
            // Arrange — 모든 세션 포화
            for (KisWebSocketSession session : mockSessions) {
                when(session.getSubscriptionCount()).thenReturn(MAX_SUBSCRIPTIONS_PER_SESSION);
            }

            // Act & Assert — 예외 없이 처리됨
            org.assertj.core.api.Assertions.assertThatCode(() -> manager.addSymbol("005930"))
                    .doesNotThrowAnyException();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // removeSymbol
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeSymbol — 단일 종목 증분 제거")
    class RemoveSymbol {

        @Test
        @DisplayName("addSymbol 후 removeSymbol 호출 시 H0STCNT0·H0STASP0 구독 해제가 각각 전달된다")
        void shouldUnsubscribeBothTrIdsFromOwnerSession() throws Exception {
            // Arrange — 먼저 구독 등록 (H0STCNT0:005930, H0STASP0:005930 복합 키로 각각 추적)
            manager.addSymbol("005930");

            // Act
            manager.removeSymbol("005930");

            // Assert — H0STCNT0·H0STASP0 각각에 대해 unsubscribe 총 2회 호출
            int totalUnsubscribeCalls = 0;
            for (KisWebSocketSession session : mockSessions) {
                totalUnsubscribeCalls +=
                        org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                                .filter(inv -> "unsubscribe".equals(inv.getMethod().getName()))
                                .mapToInt(inv -> 1)
                                .sum();
            }
            org.assertj.core.api.Assertions.assertThat(totalUnsubscribeCalls).isEqualTo(2);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // subscribeSymbols — 100종목 × 2 = 200건
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("subscribeSymbols — 100종목 일괄 구독")
    class SubscribeSymbols {

        @Test
        @DisplayName("100종목 subscribeSymbols 호출 시 subscribe가 총 200회 호출된다")
        void shouldCallSubscribe200TimesFor100Symbols() throws Exception {
            // Arrange — 각 세션이 실제 카운터처럼 동작하도록 설정
            int[] counts = new int[SESSION_COUNT];
            for (int i = 0; i < SESSION_COUNT; i++) {
                final int idx = i;
                KisWebSocketSession s = mockSessions.get(i);
                when(s.getSubscriptionCount()).thenAnswer(inv -> counts[idx]);
                when(s.isInSafeMode()).thenReturn(false);
                org.mockito.Mockito.doAnswer(
                                inv -> {
                                    counts[idx]++;
                                    return null;
                                })
                        .when(s)
                        .subscribe(anyString(), anyString());
            }

            // Act
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                symbols.add(String.format("SYM%03d", i));
            }
            manager.subscribeSymbols(symbols);

            // Assert — 100종목 × 2 trId = 200회 subscribe 호출
            int total = 0;
            for (int count : counts) {
                total += count;
            }
            org.assertj.core.api.Assertions.assertThat(total).isEqualTo(200);
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
