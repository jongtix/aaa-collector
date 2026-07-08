package com.aaa.collector.common.safemode;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 안전 모드 상태를 Redis에 저장/조회하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>안전 모드: {@code {keyPrefix}{alias}} — {@code "ON"} or {@code "OFF"}. TTL은 {@link
 *       #setSafeMode(String, boolean, Duration)} 호출 여부에 따라 부여되거나 부여되지 않는다(호출자가 결정)
 *   <li>백오프 수준(SPEC-COLLECTOR-SAFEMODE-001 D-A): {@code {keyPrefix}backoff:{alias}} — 정수 레벨 문자열,
 *       TTL 없이 영구 저장. 안전 모드 "ON" 값의 TTL 만료와 독립적으로 유지되어야 하므로(REQ-SAFEMODE-004) 별도 키에 보관한다. 발급 성공 시에만
 *       {@link #deleteBackoffLevel(String)}로 리셋된다
 * </ul>
 *
 * <p>키 프리픽스는 생성자 주입으로 결정되어, 토큰(safe_mode:collector:token:)과 WebSocket(safe_mode:collector:ws:) 등 서로
 * 다른 컨텍스트에서 동일한 클래스를 재사용할 수 있다. TTL·백오프 부여 여부는 {@link SafeModeManager}가 컨텍스트별 정책({@link
 * SafeModeBackoffPolicy})의 유무로 결정하며, 본 레포지토리는 호출된 대로 저장만 수행한다(REQ-SAFEMODE-016 스코프 격리는
 * SafeModeManager/SafeModeConfig 계층에서 보장).
 */
@RequiredArgsConstructor
public class SafeModeRepository {

    private static final String SAFE_MODE_ON = "ON";
    private static final String SAFE_MODE_OFF = "OFF";
    private static final String BACKOFF_KEY_SEGMENT = "backoff:";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    /**
     * 안전 모드 상태를 Redis에 설정한다. TTL 없이 영구 저장된다.
     *
     * @param alias 계정 식별자
     * @param on {@code true}이면 {@code "ON"}, {@code false}이면 {@code "OFF"} 저장
     */
    public void setSafeMode(String alias, boolean on) {
        redisTemplate.opsForValue().set(safeModeKey(alias), on ? SAFE_MODE_ON : SAFE_MODE_OFF);
    }

    /**
     * 안전 모드 상태를 Redis에 설정한다. {@code on=true}이면 지정된 TTL과 함께 저장하여, TTL 경과 시 Redis가 상태를 자동 삭제하도록
     * 한다(REQ-SAFEMODE-001). {@code on=false}(해제)는 TTL을 부여할 대상이 없으므로 {@code ttl} 인자를 무시하고 {@link
     * #setSafeMode(String, boolean)}와 동일하게 동작한다.
     *
     * @param alias 계정 식별자
     * @param on {@code true}이면 {@code "ON"}(TTL 부여), {@code false}이면 {@code "OFF"}(TTL 없음)
     * @param ttl {@code on=true}일 때 적용할 만료 기간
     */
    public void setSafeMode(String alias, boolean on, Duration ttl) {
        if (on && ttl != null) {
            redisTemplate.opsForValue().set(safeModeKey(alias), SAFE_MODE_ON, ttl);
            return;
        }
        setSafeMode(alias, on);
    }

    /**
     * 안전 모드 상태를 조회한다.
     *
     * @param alias 계정 식별자
     * @return 안전 모드 활성화 여부. 키가 존재하지 않으면(TTL 만료로 자연 삭제된 경우 포함) 기본값 {@code false} 반환
     */
    public boolean isSafeMode(String alias) {
        return SAFE_MODE_ON.equals(redisTemplate.opsForValue().get(safeModeKey(alias)));
    }

    /**
     * 재진입 백오프 수준을 별도 키에 TTL 없이 저장한다. 안전 모드 "ON" 값이 TTL 만료로 소멸해도 이 값은 유지된다(REQ-SAFEMODE-004).
     *
     * @param alias 계정 식별자
     * @param level 저장할 백오프 레벨(0부터 시작)
     */
    public void saveBackoffLevel(String alias, int level) {
        redisTemplate.opsForValue().set(backoffKey(alias), String.valueOf(level));
    }

    /**
     * 지속 저장된 백오프 수준을 조회한다.
     *
     * @param alias 계정 식별자
     * @return 저장된 백오프 레벨. 키가 없으면(유지 중인 백오프 수준이 없는 상태, REQ-SAFEMODE-002) {@code Optional.empty()}
     */
    public Optional<Integer> getBackoffLevel(String alias) {
        String raw = redisTemplate.opsForValue().get(backoffKey(alias));
        return raw == null ? Optional.empty() : Optional.of(Integer.parseInt(raw));
    }

    /**
     * 백오프 수준을 리셋(삭제)한다. 발급 성공 시 호출된다(REQ-SAFEMODE-005).
     *
     * @param alias 계정 식별자
     */
    public void deleteBackoffLevel(String alias) {
        redisTemplate.delete(backoffKey(alias));
    }

    private String safeModeKey(String alias) {
        return keyPrefix + alias;
    }

    private String backoffKey(String alias) {
        return keyPrefix + BACKOFF_KEY_SEGMENT + alias;
    }
}
