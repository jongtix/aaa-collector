package com.aaa.collector.schedule.catchup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CatchUpRunner 설정 바인딩 (SPEC-COLLECTOR-CATCHUP-001 T4/T5).
 *
 * <p>프로퍼티 접두사: {@code aaa.catchup}. 테스트 프로파일에서 {@code enabled=false}로 비활성화한다(CR-01).
 *
 * @param enabled CatchUpRunner 활성화 여부 (기본값 true)
 * @param graceSeconds expectedLastFire 이후 경과해야 재실행 조건이 성립하는 초 단위 유예 시간 (기본값 300)
 * @param unitTimeoutSeconds 단위당 배치 재실행 최대 대기 초 (기본값 600)
 */
@ConfigurationProperties(prefix = "aaa.catchup")
public record CatchUpProperties(boolean enabled, long graceSeconds, long unitTimeoutSeconds) {

    /** 기본 생성자 — Spring이 바인딩 시 사용. */
    public CatchUpProperties {
        // Spring @ConfigurationProperties는 기본값을 지원하지 않으므로
        // application.yml에서 명시적으로 제공해야 한다.
    }
}
