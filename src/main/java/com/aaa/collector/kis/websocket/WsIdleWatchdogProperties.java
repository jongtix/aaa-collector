package com.aaa.collector.kis.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 유휴(idle) 단절 워치독 설정 (SPEC-COLLECTOR-WS-RESILIENCE-002, REQ-WSRES2-004).
 *
 * <p>2026-09-28 인시던트(국내 틱 수신이 오류 신호 없이 18분+ 침묵 중단)의 재발 방지 — 마지막 수신 이후 경과 시간이 {@link
 * #idleThresholdSeconds}를 초과하면 강제 재연결을 트리거한다.
 *
 * <p>기본값 근거(OQ-1): KIS PINGPONG 발신 주기의 정확한 초 단위 값은 {@code api-specs/kis/} 명세·collector 소스 어디에도
 * 문서화되어 있지 않다(2026-09-28 Run 단계 실측 조사 — SPEC-COLLECTOR-WS-RESILIENCE-002 progress.md §E.2). spec.md
 * §8 OQ-1이 명시한 폴백 정책("PINGPONG 주기가 확인되지 않을 경우 보수적으로 60초")을 채택해 기본값을 60초로 설정하고, 운영 관측 후 조정 가능하도록 외부
 * 설정값으로 노출한다.
 */
@ConfigurationProperties(prefix = "aaa.ws-idle-watchdog")
public class WsIdleWatchdogProperties {

    /**
     * 유휴 워치독 활성화 여부.
     *
     * <p>기본값 {@code true}(default-active) — 프로덕션에서 키 누락 시 워치독이 침묵 비활성되지 않도록 보장한다({@link
     * WsRecoveryProperties}와 동형 default-active 정책, REQ-WSREC-051 패턴 재사용).
     */
    private boolean enabled = true;

    /**
     * 유휴 판정 임계값(초). 기본값 60초(OQ-1 보수적 폴백).
     *
     * <p>마지막 수신(Type A 틱, Type B 제어 메시지, PINGPONG 포함) 이후 경과 시간이 이 값을 초과하면 워치독이 강제 재연결을 트리거한다
     * (REQ-WSRES2-002).
     */
    private long idleThresholdSeconds = 60L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIdleThresholdSeconds() {
        return idleThresholdSeconds;
    }

    public void setIdleThresholdSeconds(long idleThresholdSeconds) {
        this.idleThresholdSeconds = idleThresholdSeconds;
    }
}
