package com.aaa.collector.common.safemode;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 안전 모드 상태를 Redis에 저장/조회하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>안전 모드: {@code {keyPrefix}{alias}} — {@code "ON"} or {@code "OFF"}, TTL 없음
 * </ul>
 *
 * <p>키 프리픽스는 생성자 주입으로 결정되어, 토큰(safe_mode:collector:token:)과 WebSocket(safe_mode:collector:ws:) 등 서로
 * 다른 컨텍스트에서 동일한 클래스를 재사용할 수 있다.
 */
@RequiredArgsConstructor
public class SafeModeRepository {

    private static final String SAFE_MODE_ON = "ON";
    private static final String SAFE_MODE_OFF = "OFF";

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
     * 안전 모드 상태를 조회한다.
     *
     * @param alias 계정 식별자
     * @return 안전 모드 활성화 여부. 키가 존재하지 않으면 기본값 {@code false} 반환
     */
    public boolean isSafeMode(String alias) {
        return SAFE_MODE_ON.equals(redisTemplate.opsForValue().get(safeModeKey(alias)));
    }

    private String safeModeKey(String alias) {
        return keyPrefix + alias;
    }
}
