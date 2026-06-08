package com.aaa.collector.kis.websocket;

import java.util.List;

/**
 * 국내 WebSocket 구독 대상 종목 코드를 제공하는 포트 인터페이스.
 *
 * <p>의존성 역전 원칙(DIP)을 적용하여 {@code kis.websocket} 패키지가 {@code stock} 패키지에 직접 의존하지 않도록 한다. 구현체는 {@code
 * stock.grade} 패키지에 위치한다.
 */
public interface DomesticSymbolProvider {

    /**
     * 국내 WebSocket 구독 대상 종목 코드를 반환한다.
     *
     * @return 구독 대상 종목 코드 목록 (최대 100개)
     */
    List<String> getDomesticSymbols();
}
