package com.aaa.collector.kis.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 장 중 재배포 시 국내 WebSocket 구독 복구 설정 (SPEC-COLLECTOR-WS-RECOVERY-001).
 *
 * <p>기본값: {@code enabled=true} — 키 누락 시 default-active. {@code record} 기반(누락 시 {@code false}로 바인딩되어
 * 침묵 비활성)을 사용하지 않는다(MA-01, REQ-WSREC-052).
 */
@ConfigurationProperties(prefix = "aaa.ws-recovery")
public class WsRecoveryProperties {

    /**
     * 장 중 재배포 시 국내 WebSocket 구독 복구 활성화 여부.
     *
     * <p>기본값 {@code true}(default-active) — 프로덕션에서 키 누락 시 복구가 침묵 비활성되지 않도록 보장한다. 비프로덕션 프로파일에서는
     * {@code application-test.yml}에서 {@code false}로 명시적으로 비활성화한다(REQ-WSREC-051).
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
