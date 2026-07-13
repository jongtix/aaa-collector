package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KisWebSocketSessionManager — 구독/세션 계측 (REQ-OBSV-011)")
class KisWebSocketSessionManagerMetricsTest {

    private static final int SESSION_COUNT = 5;
    private static final String ACTIVE_SUBS = "aaa_collector_tick_active_subscriptions";
    private static final String SESSION_CONNECTED = "aaa_collector_websocket_session_connected";

    @Mock private KisProperties kisProperties;
    @Mock private KisTokenService kisTokenService;
    @Mock private KisTickPublisher tickPublisher;
    @Mock private SafeModeManager webSocketSafeModeManager;
    @Mock private KisMarketSchedule marketSchedule;
    @Mock private Sleeper sleeper;
    @Mock private Clock clock;

    private SimpleMeterRegistry meterRegistry;
    private Map<String, KisWebSocketSession> mockSessionMap;
    private KisWebSocketSessionManager manager;

    @BeforeEach
    void setUp() {
        List<KisAccountCredential> accounts = buildAccounts(SESSION_COUNT);
        when(kisProperties.accounts()).thenReturn(accounts);
        when(kisTokenService.getValidApprovalKey(anyString())).thenReturn("approval-key");

        mockSessionMap = new ConcurrentHashMap<>();
        for (KisAccountCredential account : accounts) {
            KisWebSocketSession session = mock(KisWebSocketSession.class);
            when(session.getAlias()).thenReturn(account.alias());
            when(session.isInSafeMode()).thenReturn(false);
            when(session.getSubscriptionCount()).thenReturn(0);
            when(session.isConnected()).thenReturn(true);
            // 기본값: 전송 성공(true) — Task1(REQ-WSRES-015) boolean 전파 이후 필요한 기본 스텁
            when(session.subscribe(anyString(), anyString())).thenReturn(true);
            mockSessionMap.put(account.alias(), session);
        }

        KisWebSocketSessionFactory factory = (alias, approvalKey) -> mockSessionMap.get(alias);
        meterRegistry = new SimpleMeterRegistry();

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
                        meterRegistry);
    }

    @Test
    @DisplayName("활성 구독 종목 수 gauge가 distinct symbol을 센다 (trId:trKey 복합키 중복 제외)")
    void activeSubscriptionGaugeCountsDistinctSymbols() {
        // Arrange
        manager.openAll();
        // 동일 종목 005930의 체결+호가 = 2개 복합키이나 distinct symbol은 1
        manager.assignSubscription("H0STCNT0", "005930");
        manager.assignSubscription("H0STASP0", "005930");
        manager.assignSubscription("H0STCNT0", "000660");

        // Act
        double activeSymbols = meterRegistry.get(ACTIVE_SUBS).gauge().value();

        // Assert — 005930, 000660 → 2 distinct symbols
        assertThat(activeSymbols).isEqualTo(2.0);
    }

    @Test
    @DisplayName("세션 connected gauge가 연결 세션 1, 끊긴 세션 0을 노출한다 (label: alias)")
    void sessionConnectedGaugeReflectsConnectionState() {
        // Arrange
        manager.openAll();
        // 한 세션을 끊김 상태로 전환
        when(mockSessionMap.get("acct-0").isConnected()).thenReturn(false);

        // Act
        double connected0 =
                meterRegistry.get(SESSION_CONNECTED).tags("alias", "acct-0").gauge().value();
        double connected1 =
                meterRegistry.get(SESSION_CONNECTED).tags("alias", "acct-1").gauge().value();

        // Assert
        assertThat(connected0).isEqualTo(0.0);
        assertThat(connected1).isEqualTo(1.0);
    }

    private List<KisAccountCredential> buildAccounts(int count) {
        List<KisAccountCredential> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            accounts.add(
                    new KisAccountCredential(
                            "acct-" + i, "account-" + i, "app-key-" + i, "app-secret-" + i));
        }
        return accounts;
    }
}
