package com.aaa.collector.schedule.catchup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CatchUpRunner 설정 바인딩 (SPEC-COLLECTOR-CATCHUP-001 T4/T5).
 *
 * <p>프로퍼티 접두사: {@code aaa.catchup}. 테스트 프로파일에서 {@code enabled=false}로 비활성화한다(CR-01).
 *
 * <p><b>배포 요구사항</b>: Java record는 기본값을 지원하지 않으므로 {@code application.yml}에 아래 절이 반드시 존재해야 한다. 누락 시
 * 애플리케이션 시작 실패.
 *
 * <pre>{@code
 * aaa:
 *   catchup:
 *     enabled: true
 *     grace-seconds: 300
 *     unit-timeout-seconds: 600
 * }</pre>
 *
 * @param enabled CatchUpRunner 활성화 여부 (프로덕션: true, 테스트 프로파일: false)
 * @param graceSeconds expectedLastFire 이후 경과해야 재실행 조건이 성립하는 초 단위 유예 시간 (권장: 300)
 * @param unitTimeoutSeconds 단위당 배치 재실행 최대 대기 초 (권장: 600)
 */
@ConfigurationProperties(prefix = "aaa.catchup")
public record CatchUpProperties(boolean enabled, long graceSeconds, long unitTimeoutSeconds) {

    /** compact constructor — 유효성 검증 (CR-01). */
    public CatchUpProperties {
        if (graceSeconds < 0) {
            throw new IllegalArgumentException("graceSeconds는 0 이상이어야 합니다: " + graceSeconds);
        }
        if (unitTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "unitTimeoutSeconds는 양수여야 합니다: " + unitTimeoutSeconds);
        }
    }
}
