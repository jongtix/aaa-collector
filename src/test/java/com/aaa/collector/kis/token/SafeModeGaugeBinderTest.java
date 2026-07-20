package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.safemode.SafeModeManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SafeModeGaugeBinder 단위 테스트 — 상태형 게이지 {@code aaa_collector_safe_mode_active}의 노출·값 전이 검증.
 *
 * <p>{@link SimpleMeterRegistry}와 상태를 제어 가능한 mock {@link SafeModeManager}로 게이지가 스크레이프(게이지 read) 시점의
 * {@code isActive(alias)}를 그대로 추종함을 검증한다(REQ-SMG-001/002/003/007 · AC-1/2/3/7).
 */
class SafeModeGaugeBinderTest {

    private static final String GAUGE = "aaa_collector_safe_mode_active";

    private SimpleMeterRegistry registry;
    private SafeModeManager tokenManager;
    private SafeModeManager webSocketManager;
    private KisProperties kisProperties;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        tokenManager = mock(SafeModeManager.class);
        webSocketManager = mock(SafeModeManager.class);
        when(tokenManager.getModule()).thenReturn("token");
        when(webSocketManager.getModule()).thenReturn("ws");
        kisProperties =
                new KisProperties(
                        "https://openapi.koreainvestment.com",
                        "testuser",
                        List.of(
                                new KisAccountCredential(
                                        "isa", "12345678", "appkey-isa", "appsecret-isa"),
                                new KisAccountCredential(
                                        "gold", "87654321", "appkey-gold", "appsecret-gold")),
                        new KisProperties.RateLimit(20, 20, 5));
    }

    private void registerBinder() {
        SafeModeGaugeBinder binder =
                new SafeModeGaugeBinder(tokenManager, webSocketManager, kisProperties, registry);
        binder.registerGauges();
    }

    private double gaugeValue(String module, String alias) {
        return registry.get(GAUGE).tags("module", module, "alias", alias).gauge().value();
    }

    @Test
    @DisplayName("REQ-SMG-001/AC-1: (module, alias) 각 조합마다 게이지가 활성=1·비활성=0으로 노출된다")
    void registersGaugePerModuleAliasCombination() {
        // Arrange
        when(tokenManager.isActive("isa")).thenReturn(true);
        when(tokenManager.isActive("gold")).thenReturn(false);
        when(webSocketManager.isActive("isa")).thenReturn(false);
        when(webSocketManager.isActive("gold")).thenReturn(true);

        // Act
        registerBinder();

        // Assert: 2 컨텍스트 × 2 alias = 4 시계열, 각 조합 값이 isActive를 따른다
        assertThat(registry.find(GAUGE).gauges()).hasSize(4);
        assertThat(gaugeValue("token", "isa")).isEqualTo(1.0);
        assertThat(gaugeValue("token", "gold")).isEqualTo(0.0);
        assertThat(gaugeValue("ws", "isa")).isEqualTo(0.0);
        assertThat(gaugeValue("ws", "gold")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("REQ-SMG-002/AC-2: 게이지는 사전 캐시가 아니라 read 시점의 실제 상태를 조회한다")
    void readsStateAtScrapeTimeInsteadOfCache() {
        // Arrange: 등록 시점에는 활성, 이후 외부에서 비활성으로 전환(애플리케이션 코드 개입 없음)
        AtomicBoolean active = new AtomicBoolean(true);
        when(tokenManager.isActive("isa")).thenAnswer(invocation -> active.get());
        registerBinder();
        assertThat(gaugeValue("token", "isa")).isEqualTo(1.0);

        // Act
        active.set(false);

        // Assert: 재조회 시 캐시가 아닌 현재 상태(0)를 반영
        assertThat(gaugeValue("token", "isa")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("REQ-SMG-003/AC-3: 상태 저장소가 비활성으로 보고하면 게이지 값은 0이다")
    void returnsZeroWhenInactive() {
        when(tokenManager.isActive("isa")).thenReturn(false);

        registerBinder();

        assertThat(gaugeValue("token", "isa")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("REQ-SMG-007/AC-7: 활성↔비활성 반복 전이에 따라 게이지가 1→0→1로 왕복 전이한다")
    void gaugeFollowsActiveInactiveActiveTransition() {
        // Arrange
        AtomicBoolean active = new AtomicBoolean(true);
        when(tokenManager.isActive("isa")).thenAnswer(invocation -> active.get());
        registerBinder();

        // Act & Assert: 1 → 0 → 1
        assertThat(gaugeValue("token", "isa")).isEqualTo(1.0);
        active.set(false);
        assertThat(gaugeValue("token", "isa")).isEqualTo(0.0);
        active.set(true);
        assertThat(gaugeValue("token", "isa")).isEqualTo(1.0);
    }
}
