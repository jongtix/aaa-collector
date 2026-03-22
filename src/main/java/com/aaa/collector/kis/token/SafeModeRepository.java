package com.aaa.collector.kis.token;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * KIS 토큰 안전 모드 상태를 Redis에 저장/조회하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>안전 모드: {@code safe_mode:collector:token:{alias}} — {@code "ON"} or {@code "OFF"}, TTL 없음
 * </ul>
 *
 * <p>TODO: WebSocket 모듈 구현 시 이 클래스와 {@link SafeModeManager}를 {@code common.safemode} 패키지로 이동하여 범용
 * 안전 모드 관리자로 확장한다. (TECHSPEC 1.3절 격리 단위 참조)
 */
@Repository
@RequiredArgsConstructor
public class SafeModeRepository {

    private static final String SAFE_MODE_KEY_PREFIX = "safe_mode:collector:token:";
    private static final String SAFE_MODE_ON = "ON";
    private static final String SAFE_MODE_OFF = "OFF";

    private final StringRedisTemplate redisTemplate;

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
     * 안전 모드 상태를 조회한다.
     *
     * @param alias 계정 식별자
     * @return 안전 모드 활성화 여부. 키가 존재하지 않으면 기본값 {@code false} 반환
     */
    public boolean isSafeMode(String alias) {
        return SAFE_MODE_ON.equals(redisTemplate.opsForValue().get(safeModeKey(alias)));
    }

    private String safeModeKey(String alias) {
        return SAFE_MODE_KEY_PREFIX + alias;
    }
}
