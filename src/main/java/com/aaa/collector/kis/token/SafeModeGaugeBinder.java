package com.aaa.collector.kis.token;

import com.aaa.collector.common.safemode.SafeModeManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 안전 모드 상태형 게이지 {@code aaa_collector_safe_mode_active{module, alias}}(0/1)를 노출한다
 * (SPEC-COLLECTOR-SAFEMODEGAUGE-001 REQ-SMG-001~003/007, aaa-infra#85).
 *
 * <p><b>왜 상태형 게이지인가</b>: 기존 {@code aaa_collector_safe_mode_exit_total} 카운터는 오직 {@code
 * SafeModeManager.exit()}(token 컨텍스트, 발급 성공 시)만 증가시켰다. 그러나 지배적 해제 경로인 <b>TTL 자연 만료</b>와 Redis 키 수동
 * 삭제는 애플리케이션 코드를 거치지 않아 카운터를 올리지 못했고, ws 컨텍스트는 {@code exit()} 호출 지점이 아예 없어 해제 이벤트가 원천적으로 발생할 수 없었다
 * — 결과적으로 해제 알림이 발화하지 않는 관측 사각이 있었다. 이 게이지는 관측 대상을 <b>이벤트에서 상태로</b> 바꾼다: 매 스크레이프 시점에 상태 저장소를 직접 조회해
 * 값을 반환하므로, 해제가 어느 경로(TTL 만료·수동 삭제·명시적 {@code exit()})로 일어났든 값이 {@code 1}에서 {@code 0}으로 떨어지는 것을
 * vmalert의 firing→resolved 전이로 포착할 수 있다.
 *
 * <p><b>메터 이름에 {@code _total} 접미사를 쓰지 않는 이유</b>: Micrometer의 {@code PrometheusNamingConvention}은
 * {@code Counter}가 아닌 미터(예: {@code Gauge})의 이름이 {@code _total}로 끝나면 그 접미사를 자동으로 제거해 노출한다({@code
 * _total}은 Prometheus 규약상 단조증가 카운터 전용 접미사이기 때문). 이 게이지는 상태형(0/1)이지 카운터가 아니므로 이름은 {@code
 * aaa_collector_safe_mode_active}로 고정하고 {@code _total}을 절대 붙이지 않는다 — 붙이면 {@code
 * /actuator/prometheus} 노출명이 달라져 aaa-infra vmalert 룰이 존재하지 않는 시계열을 평가하게 되는 실사고(선례: {@code
 * ExpectedRunGaugeBinder}의 {@code enrolled} 게이지)로 이어진다. 이 불일치는 {@code PrometheusMeterRegistry}를 실제로
 * 거쳐야만 드러나므로 {@code SimpleMeterRegistry} 기반 단위 테스트로는 잡히지 않는다.
 *
 * <p><b>등록 방식</b>: {@code token}/{@code ws} 두 {@link SafeModeManager} Bean과 {@link KisProperties}를
 * 주입받아, 부팅 시 {@link #registerGauges()}({@code @PostConstruct})에서 각 (컨텍스트, alias) 조합마다
 * 값-함수(value-function) 게이지를 전수 등록한다. 값 함수는 매 스크레이프 시 {@code manager.isActive(alias)}를 호출해 상태
 * 저장소(Redis)를 직접 조회하므로 별도 저장·이벤트 소싱이 없다. alias 순회는 두 컨텍스트 공통으로 {@link KisProperties#accounts()}를
 * 사용한다({@code KisWebSocketSessionManager} 선례가 ws 세션 alias도 동일 계정 목록 기반임을 확증). {@code module} 태그 값은
 * 하드코딩 대신 {@link SafeModeManager#getModule()}을 재사용해 {@code enter_total} 카운터의 {@code module} 태그와
 * 일관성을 보장한다.
 *
 * <p><b>패키지 배치 근거</b>: 이 바인더는 alias 목록을 {@link KisProperties#accounts()}에서 얻어야 하는데, {@code
 * KisProperties}는 {@code kis.token} 피처 패키지 소유다. ArchUnit 규칙 {@code
 * MdcArchitectureTest.commonPackageDoesNotDependOnFeaturePackages}가 {@code common..} → 피처 패키지 의존을
 * 금지하므로 바인더를 {@code common.safemode}에 둘 수 없다(SPEC plan.md가 바인더 위치를 "Run에서 확정"으로 위임). {@code
 * kis.token}은 {@code KisProperties}를 소유하고 {@link SafeModeManager}(common)에 대한 의존이 이미 존재하는({@code
 * KisTokenService}·{@code HealthyKeySelector}) 합법·무순환 경로라 최소 결합 배치처다.
 */
// @MX:NOTE: [AUTO] safe_mode_active 상태형 게이지(0/1)를 매 스크레이프 시 Redis 직접 조회로 노출 — vmalert
// collector-safemode 룰의 firing/resolved 입력 소스(exit_total 카운터를 대체, aaa-infra#85)
@Component
public class SafeModeGaugeBinder {

    static final String SAFE_MODE_ACTIVE_NAME = "aaa_collector_safe_mode_active";

    private final SafeModeManager tokenSafeModeManager;
    private final SafeModeManager webSocketSafeModeManager;
    private final KisProperties kisProperties;
    private final MeterRegistry registry;

    /**
     * token·ws 두 컨텍스트의 {@link SafeModeManager}와 계정 목록·레지스트리를 주입받는다.
     *
     * <p>{@link SafeModeManager} 타입 Bean이 둘({@code tokenSafeModeManager}·{@code
     * webSocketSafeModeManager})이므로 {@link Qualifier}로 명시 구분한다({@code KisTokenService}·{@code
     * HealthyKeySelector}와 동일 패턴).
     *
     * @param tokenSafeModeManager token 컨텍스트 안전 모드 관리자
     * @param webSocketSafeModeManager ws 컨텍스트 안전 모드 관리자
     * @param kisProperties alias(계정 식별자) 목록 원천
     * @param registry 게이지 등록 대상 레지스트리
     */
    public SafeModeGaugeBinder(
            @Qualifier("tokenSafeModeManager") SafeModeManager tokenSafeModeManager,
            @Qualifier("webSocketSafeModeManager") SafeModeManager webSocketSafeModeManager,
            KisProperties kisProperties,
            MeterRegistry registry) {
        this.tokenSafeModeManager = tokenSafeModeManager;
        this.webSocketSafeModeManager = webSocketSafeModeManager;
        this.kisProperties = kisProperties;
        this.registry = registry;
    }

    /**
     * 부팅 시 token·ws 두 컨텍스트 × 전 계정 alias 조합의 상태형 게이지를 전수 등록한다 (REQ-SMG-001).
     *
     * <p>metrics 엔드포인트가 ready 되기 전에 모든 시계열이 존재하도록 {@code @PostConstruct}에서 eager 등록한다.
     */
    @PostConstruct
    void registerGauges() {
        registerGaugesFor(tokenSafeModeManager);
        registerGaugesFor(webSocketSafeModeManager);
    }

    /**
     * 주어진 컨텍스트의 매니저에 대해 전 계정 alias의 {@code aaa_collector_safe_mode_active} 게이지를 등록한다.
     *
     * <p>값 함수는 상태 객체로 매니저를 잡고({@code strongReference(true)}로 GC 회수 방지), 매 스크레이프 시 {@code
     * isActive(alias)}가 {@code true}면 {@code 1.0}, {@code false}면 {@code 0.0}을
     * 반환한다(REQ-SMG-002/003).
     */
    private void registerGaugesFor(SafeModeManager manager) {
        for (KisAccountCredential credential : kisProperties.accounts()) {
            String alias = credential.alias();
            Gauge.builder(SAFE_MODE_ACTIVE_NAME, manager, m -> m.isActive(alias) ? 1.0 : 0.0)
                    .tag("module", manager.getModule())
                    .tag("alias", alias)
                    .strongReference(true)
                    .register(registry);
        }
    }
}
