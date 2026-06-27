package com.aaa.collector.common.safemode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 안전 모드의 진입·해제·조회를 담당하는 컴포넌트.
 *
 * <p>안전 모드는 토큰 발급 실패·WebSocket 연결 오류 등 비정상 상황에서 추가 API 호출을 차단하기 위해 사용된다. 상태는 {@link
 * SafeModeRepository}를 통해 Redis에 영구 저장된다.
 *
 * <p>토큰 컨텍스트(safe_mode:collector:token:)와 WebSocket 컨텍스트(safe_mode:collector:ws:)가 각각 별도 Bean으로
 * 등록되어 서로 다른 네임스페이스를 사용한다. {@link SafeModeConfig} 참조.
 *
 * <p>진입/해제 시 {@code aaa_collector_safe_mode_enter_total}/{@code aaa_collector_safe_mode_exit_total}
 * 메트릭을 계측한다.
 */
// @MX:NOTE: [AUTO] 안전 모드 진입/해제 시 aaa_collector_safe_mode_enter/exit_total 메트릭 계측 — vmalert
// CollectorSafeMode 룰의 입력 소스
@Slf4j
@RequiredArgsConstructor
public class SafeModeManager {

    private final SafeModeRepository safeModeRepository;
    private final MeterRegistry registry;
    private final String module;

    /**
     * 안전 모드에 진입한다.
     *
     * @param alias 계정/연결 식별자
     * @param cause 안전 모드 진입의 원인이 된 예외
     */
    public void enter(String alias, Throwable cause) {
        safeModeRepository.setSafeMode(alias, true);
        log.error("[{}] 안전 모드 진입", alias, cause);
        counter("aaa_collector_safe_mode_enter_total", alias).increment();
    }

    /**
     * 안전 모드를 해제한다.
     *
     * @param alias 계정/연결 식별자
     */
    public void exit(String alias) {
        safeModeRepository.setSafeMode(alias, false);
        log.info("[{}] 안전 모드 해제", alias);
        counter("aaa_collector_safe_mode_exit_total", alias).increment();
    }

    /**
     * 안전 모드 활성화 여부를 반환한다.
     *
     * @param alias 계정/연결 식별자
     * @return 안전 모드 활성화 시 {@code true}
     */
    public boolean isActive(String alias) {
        return safeModeRepository.isSafeMode(alias);
    }

    private Counter counter(String name, String alias) {
        // alias = KIS 계좌/세션 식별자 (isa, key1~key4, ws 세션). 최대 ~10종 고정값 — cardinality 안전.
        // Micrometer.register()는 멱등 — 동일 name+tags는 캐시된 counter를 반환한다.
        return Counter.builder(name).tag("module", module).tag("alias", alias).register(registry);
    }
}
