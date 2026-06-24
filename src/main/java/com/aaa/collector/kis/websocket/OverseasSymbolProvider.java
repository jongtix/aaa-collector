package com.aaa.collector.kis.websocket;

import java.util.List;

/**
 * 해외 WebSocket 구독 대상 tr_key 목록을 제공하는 포트 인터페이스.
 *
 * <p>의존성 역전으로 kis.websocket ↔ stock.grade 순환의존 방지.
 */
@FunctionalInterface
public interface OverseasSymbolProvider {
    /** 해외 WebSocket 구독에 사용할 tr_key 목록을 반환한다 (예: {@code "DNASAAPL"}). */
    List<String> getOverseasSubscriptionKeys();
}
