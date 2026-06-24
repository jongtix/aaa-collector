package com.aaa.collector.kis.websocket;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 구독 대상 종목 선정의 단일 진입점.
 *
 * <p>종목 선정 로직은 {@link DomesticSymbolProvider} / {@link OverseasSymbolProvider} 구현체에 위임하여 순환 의존성을
 * 방지한다.
 */
// @MX:ANCHOR: [AUTO] 구독 대상 종목 선정의 단일 진입점
// @MX:REASON: KisWebSocketScheduler에서 국내·해외 모두 호출 — fan_in >= 3
@Component
@RequiredArgsConstructor
public class SubscriptionTargetResolver {

    private final DomesticSymbolProvider domesticSymbolProvider;
    private final OverseasSymbolProvider overseasSymbolProvider;

    /**
     * 구독 대상 국내 종목 코드를 선정한다.
     *
     * <p>A·B 등급 종목을 stock_grades 테이블에서 조회하여 최대 100종목을 반환한다. 101종목 이상이면 A등급 우선으로 절삭한다 (REQ-WS-053).
     *
     * @return 구독 대상 종목 코드 목록 (최대 100개)
     */
    public List<String> resolveDomesticSymbols() {
        return domesticSymbolProvider.getDomesticSymbols();
    }

    /**
     * 해외 WebSocket 구독 대상 tr_key 목록을 반환한다 (REQ-WSOV-001).
     *
     * @return tr_key 목록 (예: {@code "DNASAAPL"})
     */
    public List<String> resolveOverseasSymbols() {
        return overseasSymbolProvider.getOverseasSubscriptionKeys();
    }
}
