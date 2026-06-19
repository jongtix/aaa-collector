package com.aaa.collector.kis.gate;

import com.aaa.collector.kis.token.KisAccountCredential;

/**
 * 게이트 한 시도 동안 점유하는 KIS 앱키 lease(임대) 추상화 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-007).
 *
 * <p>{@code KeyLeaseRegistry}가 건강 키 스냅샷 중 in-use 최소 키를 선택해 발급한다. {@link #alias()}/{@link
 * #credential()}로 호출 대상 키를 노출하고, 사용 종료 시 {@link #release()}로 in-use 카운터를 반환한다.
 *
 * <p><strong>release 규약:</strong> 호출부는 반드시 {@code finally} 블록에서 {@link #release()}를 호출하여 카운터 누수(영구
 * 증가)를 막아야 한다(REQ-KISGATE-005c). 구현체는 release를 멱등하게 보장한다(이중 호출 시 카운터가 음수로 떨어지지 않음).
 *
 * <p>본 SPEC은 {@link InMemoryKeyLease} 단일 인스턴스 구현까지만 제공한다. 향후 멀티 인스턴스 전환 시 Redis 기반 구현으로 교체 가능하다.
 */
public interface KeyLease {

    /**
     * @return lease된 키의 alias (예: isa, gold, pension, stock, dc)
     */
    String alias();

    /**
     * @return lease된 키의 자격증명 ({@code KisApiExecutor.executeGet} 4-arg 경로에 전달)
     */
    KisAccountCredential credential();

    /**
     * lease를 반환하여 해당 키의 in-use 카운터를 1 감소시킨다.
     *
     * <p>멱등하다 — 동일 lease에 대한 중복 release는 카운터를 한 번만 감소시킨다.
     */
    void release();
}
