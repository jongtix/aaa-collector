package com.aaa.collector.kis.token;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * KIS API 토큰 및 WebSocket 승인키를 Redis에 저장/조회하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>토큰: {@code cache:kis:token:{alias}} — access_token 문자열, TTL 있음
 *   <li>승인키: {@code cache:kis:approval_key:{alias}} — approval_key 문자열, TTL 24시간 고정
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class KisTokenRepository {

    private static final String TOKEN_KEY_PREFIX = "cache:kis:token:";
    private static final String APPROVAL_KEY_PREFIX = "cache:kis:approval_key:";
    private static final Duration APPROVAL_KEY_TTL = Duration.ofDays(1);

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

    /**
     * WebSocket 접속용 승인키를 Redis에 저장한다. TTL은 24시간 고정.
     *
     * @param alias 계정 식별자
     * @param approvalKey KIS WebSocket 승인키
     */
    public void saveApprovalKey(String alias, String approvalKey) {
        redisTemplate.opsForValue().set(approvalKeyKey(alias), approvalKey, APPROVAL_KEY_TTL);
    }

    /**
     * Redis에서 WebSocket 승인키를 조회한다.
     *
     * @param alias 계정 식별자
     * @return 저장된 승인키. 존재하지 않으면 {@code Optional.empty()}
     */
    public Optional<String> findApprovalKey(String alias) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(approvalKeyKey(alias)));
    }

    private String tokenKey(String alias) {
        return TOKEN_KEY_PREFIX + alias;
    }

    private String approvalKeyKey(String alias) {
        return APPROVAL_KEY_PREFIX + alias;
    }
}
