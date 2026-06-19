package com.aaa.collector.kis.gate;

/**
 * 건강 키가 0개인 스냅샷에서 게이트 실행이 시도될 때 던지는 미배정 신호 예외 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-024).
 *
 * <p>정적 {@code HealthyKeyRoundRobinDistributor}의 "빈 할당" 신호의 lease 등가물이다. 가짜 키를 lease하지 않고 명시적 예외로 전
 * 키 사망을 알려, 호출부가 기존 전 키 사망 정책(per-call 0회 수행 + skip-all + ERROR 로그, REQ-KEYDIST-020)을 적용하도록 한다.
 *
 * <p>재시도해도 같은 per-batch 스냅샷에서는 키가 생기지 않으므로 게이트의 retryable 분류에서 제외된다(즉시 전파). 정상 운용에서는 호출부가 {@code
 * LeaseSession.isEmpty()}로 사전 단락하므로, 본 예외는 방어적 신호 경로다.
 */
public class NoHealthyKeyException extends RuntimeException {

    public NoHealthyKeyException() {
        super("건강 키가 0개입니다 — per-batch 스냅샷에 lease 가능한 키 없음(전 키 사망)");
    }
}
