package com.aaa.collector.kis.token;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * KIS API 토큰을 Redis에 저장/조회하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>토큰: {@code cache:kis:token:{alias}} — access_token 문자열, TTL 있음
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class KisTokenRepository {

    private static final String TOKEN_KEY_PREFIX = "cache:kis:token:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 토큰을 Redis에 저장한다.
     *
     * @param alias 계정 식별자
     * @param token KIS API access_token 문자열
     * @param ttl 토큰 만료 기간
     */
    public void saveToken(String alias, String token, Duration ttl) {
        redisTemplate.opsForValue().set(tokenKey(alias), token, ttl);
    }

    /**
     * Redis에서 토큰을 조회한다.
     *
     * @param alias 계정 식별자
     * @return 저장된 토큰. 존재하지 않으면 {@code Optional.empty()}
     */
    public Optional<String> findToken(String alias) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(tokenKey(alias)));
    }

    /**
     * Redis에서 토큰을 삭제한다.
     *
     * @param alias 계정 식별자
     */
    public void deleteToken(String alias) {
        redisTemplate.delete(tokenKey(alias));
    }

    private String tokenKey(String alias) {
        return TOKEN_KEY_PREFIX + alias;
    }
}
