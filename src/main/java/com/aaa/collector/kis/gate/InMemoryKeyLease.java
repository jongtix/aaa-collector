package com.aaa.collector.kis.gate;

import com.aaa.collector.kis.token.KisAccountCredential;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 단일 인스턴스용 {@link KeyLease} in-memory 구현 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-007).
 *
 * <p>발급한 레지스트리 세션이 제공한 release 콜백을 보유한다 — 카운터 등 내부 표현은 lease가 알지 못하고, {@link #release()} 시 콜백을 1회
 * 실행할 뿐이다(캡슐화 + 내부 표현 비노출).
 *
 * <p><strong>멱등 release:</strong> {@link AtomicBoolean} 가드로 최초 1회만 콜백을 실행한다 — finally 중복 호출이나 동시
 * release에도 카운터가 음수로 떨어지지 않는다(REQ-KISGATE-005c 누수 방지).
 */
public final class InMemoryKeyLease implements KeyLease {

    private final KisAccountCredential accountCredential;
    private final Runnable releaseAction;
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * @param accountCredential lease된 키의 자격증명
     * @param releaseAction release 시 1회 실행할 콜백(발급 세션의 in-use 카운터 감소)
     */
    public InMemoryKeyLease(KisAccountCredential accountCredential, Runnable releaseAction) {
        this.accountCredential = accountCredential;
        this.releaseAction = releaseAction;
    }

    @Override
    public String alias() {
        return accountCredential.alias();
    }

    @Override
    public KisAccountCredential credential() {
        return accountCredential;
    }

    @Override
    public void release() {
        // 멱등: compareAndSet으로 최초 1회만 콜백 실행 — 이중/동시 release 시 카운터 음수 진입 방지
        if (released.compareAndSet(false, true)) {
            releaseAction.run();
        }
    }
}
