package com.aaa.collector.common.safemode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 안전 모드의 진입·해제·조회를 담당하는 컴포넌트.
 *
 * <p>안전 모드는 토큰 발급 실패·WebSocket 연결 오류 등 비정상 상황에서 추가 API 호출을 차단하기 위해 사용된다. 상태는 {@link
 * SafeModeRepository}를 통해 Redis에 저장된다.
 *
 * <p>토큰 컨텍스트(safe_mode:collector:token:)와 WebSocket 컨텍스트(safe_mode:collector:ws:)가 각각 별도 Bean으로
 * 등록되어 서로 다른 네임스페이스를 사용한다. {@link SafeModeConfig} 참조.
 *
 * <p><b>TTL·백오프 라이프사이클(SPEC-COLLECTOR-SAFEMODE-001, D-B 옵션 A)</b>: {@link SafeModeBackoffPolicy} 정책
 * 협력자가 주입된 인스턴스(token 컨텍스트)만 SafeMode "ON"에 유한 TTL을 부여하고 재진입 백오프(1h→2h→4h 상한)를 적용한다. 정책이 {@code
 * null}인 인스턴스(WebSocket 컨텍스트)는 TTL 없이 영구 저장하는 현행 동작을 그대로 유지한다(REQ-SAFEMODE-016).
 *
 * <p>진입/해제 시 {@code aaa_collector_safe_mode_enter_total}/{@code aaa_collector_safe_mode_exit_total}
 * 메트릭을 계측한다. {@code enter_total}은 실제 진입(비활성→활성 전이)에서만 증가하며, 활성 중 재진입 no-op(REQ-SAFEMODE-008)은 카운트하지
 * 않는다.
 */
// @MX:NOTE: [AUTO] 안전 모드 진입/해제 시 aaa_collector_safe_mode_enter/exit_total 메트릭 계측 — vmalert
// CollectorSafeMode 룰의 입력 소스
// @MX:NOTE: [AUTO] TTL·백오프 라이프사이클은 backoffPolicy != null인 인스턴스(token 컨텍스트)에만 적용된다(D-B)
@Slf4j
@AllArgsConstructor
public class SafeModeManager {

    private final SafeModeRepository safeModeRepository;
    private final MeterRegistry registry;
    private final String module;
    private final SafeModeBackoffPolicy backoffPolicy;

    /**
     * 정책 없는 레거시 생성자. WebSocket 컨텍스트 등 TTL·백오프를 적용하지 않는 인스턴스에 사용한다.
     *
     * <p>필드 대입은 Lombok이 생성하는 4-args 생성자에 전부 위임한다(EI_EXPOSE_REP2 회피 — 다른 패키지의 Spring DI 생성자 주입 표준
     * 패턴과 동일하게, Lombok 생성 생성자는 정적 분석 도구의 생성 코드 예외 처리 대상이 된다).
     *
     * @param safeModeRepository 안전 모드 상태 저장소
     * @param registry 메트릭 레지스트리
     * @param module 모듈 태그(예: {@code "ws"})
     */
    public SafeModeManager(
            SafeModeRepository safeModeRepository, MeterRegistry registry, String module) {
        this(safeModeRepository, registry, module, null);
    }

    /**
     * 안전 모드에 진입한다.
     *
     * <p>정책이 없으면(레거시) TTL 없이 즉시 "ON"을 저장한다.
     *
     * <p>정책이 있으면(token 컨텍스트) 다음 순서로 처리한다(D-C, 단일 활성 게이트로 REQ-SAFEMODE-008/014 통합 처리):
     *
     * <ol>
     *   <li>이미 활성(TTL 미만료)이면 no-op — 기존 TTL 잔여시간·백오프 수준을 그대로 두고 즉시 반환(REQ-SAFEMODE-008)
     *   <li>비활성(최초 진입 또는 만료 후 최초 재진입)이면 지속 저장된 백오프 레벨을 읽어 다음 레벨의 TTL을 산정하고, "ON"을 그 TTL과 함께 저장한 뒤
     *       백오프 레벨을 갱신 저장한다(REQ-SAFEMODE-002/003/004)
     * </ol>
     *
     * @param alias 계정/연결 식별자
     * @param cause 안전 모드 진입의 원인이 된 예외
     */
    public void enter(String alias, Throwable cause) {
        if (backoffPolicy == null) {
            safeModeRepository.setSafeMode(alias, true);
            log.error("[{}] 안전 모드 진입", alias, cause);
            counter("aaa_collector_safe_mode_enter_total", alias).increment();
            return;
        }

        if (safeModeRepository.isSafeMode(alias)) {
            // 활성 중 재진입 — no-op(REQ-SAFEMODE-008, REQ-SAFEMODE-014)
            log.warn("[{}] 안전 모드 이미 활성 — 재진입 no-op(TTL/백오프 변경 없음)", alias, cause);
            return;
        }

        int nextLevel = safeModeRepository.getBackoffLevel(alias).orElse(-1) + 1;
        Duration ttl = backoffPolicy.ttlForLevel(nextLevel);
        safeModeRepository.setSafeMode(alias, true, ttl);
        safeModeRepository.saveBackoffLevel(alias, nextLevel);
        log.error("[{}] 안전 모드 진입 (TTL={}, 백오프 레벨={})", alias, ttl, nextLevel, cause);
        counter("aaa_collector_safe_mode_enter_total", alias).increment();
    }

    /**
     * 안전 모드를 해제한다. 백오프 수준 리셋은 {@link #resetBackoff(String)}가 별도로 담당한다(D-F, 발급 성공 이벤트에 직접 결부되어
     * {@code exit()} 호출 여부와 무관하게 동작해야 하므로).
     *
     * @param alias 계정/연결 식별자
     */
    public void exit(String alias) {
        safeModeRepository.setSafeMode(alias, false);
        log.info("[{}] 안전 모드 해제", alias);
        counter("aaa_collector_safe_mode_exit_total", alias).increment();
    }

    /**
     * 백오프 수준을 리셋한다. 발급 성공 시 SafeMode 활성 여부와 무관하게 호출되어야 한다(REQ-SAFEMODE-005, D-F) — TTL 자연만료로 이미
     * 비활성이던 키가 발급에 성공하는 가장 흔한 회복 경로에서도 백오프를 리셋하기 위함이다.
     *
     * <p>정책이 없으면(레거시, WebSocket 컨텍스트) no-op이다.
     *
     * @param alias 계정/연결 식별자
     */
    // @MX:NOTE: [AUTO] KisTokenService.requestAndSaveToken()이 발급 성공마다 무조건 호출 — exit()의 isActive
    // 게이트와 분리된 별도 진입점(D-F). TTL 자연만료 후 성공 경로의 stale 백오프 리셋 누락을 방지한다
    public void resetBackoff(String alias) {
        if (backoffPolicy == null) {
            return;
        }
        safeModeRepository.deleteBackoffLevel(alias);
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

    /**
     * 이 매니저의 module 태그 값({@code "token"} 또는 {@code "ws"})을 반환한다.
     *
     * <p>{@code enter_total} 카운터의 {@code module} 태그에 쓰이는 값과 동일한 값을 신규 상태형 게이지({@code
     * aaa_collector_safe_mode_active})의 {@code module} 태그에도 재사용하기 위해 노출한다. 컨텍스트별 문자열을 게이지 바인더에서 별도로
     * 하드코딩하지 않고 이 접근자를 재사용함으로써 두 메트릭의 {@code module} 태그 일관성을 코드로 보장한다.
     *
     * @return module 태그 값
     */
    public String getModule() {
        return module;
    }

    private Counter counter(String name, String alias) {
        // alias = KIS 계좌/세션 식별자 (isa, key1~key4, ws 세션). 최대 ~10종 고정값 — cardinality 안전.
        // Micrometer.register()는 멱등 — 동일 name+tags는 캐시된 counter를 반환한다.
        return Counter.builder(name).tag("module", module).tag("alias", alias).register(registry);
    }
}
