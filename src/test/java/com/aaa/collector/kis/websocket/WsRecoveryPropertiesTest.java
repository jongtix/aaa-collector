package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WsRecoveryProperties — enabled 기본값 검증 (AC-14)")
class WsRecoveryPropertiesTest {

    @Test
    @DisplayName("enabled 키 누락 시 기본값 true 반환 (default-active, MA-01 silent-disable 방지)")
    // 대비: record 기반(boolean enabled)이었다면 Java 기본값 false로 바인딩되어 키 누락 시 침묵 비활성됨.
    // 기본값 true 필드를 갖는 @ConfigurationProperties 클래스를 사용해 이를 방지한다(REQ-WSREC-052).
    void enabledDefaultsToTrue() {
        WsRecoveryProperties properties = new WsRecoveryProperties();

        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("setEnabled(false) 이후 isEnabled()는 false를 반환한다")
    void setEnabledFalseReturnsFalse() {
        WsRecoveryProperties properties = new WsRecoveryProperties();
        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
    }
}
