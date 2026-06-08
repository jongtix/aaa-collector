package com.aaa.collector.kis.websocket;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 1 구독 종목 선정: A·B 등급 국내 종목 최대 100개(200건) 절삭.
 *
 * <p>종목 선정 로직은 {@link DomesticSymbolProvider} 구현체에 위임하여 순환 의존성을 방지한다.
 */
// @MX:ANCHOR: [AUTO] 구독 대상 종목 선정의 단일 진입점
// @MX:REASON: KisWebSocketScheduler에서 호출, Phase 2 이후 해외 종목 확장 예정으로 fan_in >= 3 예상
@Component
@RequiredArgsConstructor
public class SubscriptionTargetResolver {

    private final DomesticSymbolProvider domesticSymbolProvider;

    /**
     * Phase 1 구독 대상 국내 종목 코드를 선정한다.
     *
     * <p>A·B 등급 종목을 stock_grades 테이블에서 조회하여 최대 100종목을 반환한다. 101종목 이상이면 A등급 우선으로 절삭한다 (REQ-WS-053).
     *
     * @return 구독 대상 종목 코드 목록 (최대 100개)
     */
    public List<String> resolveDomesticSymbols() {
        return domesticSymbolProvider.getDomesticSymbols();
    }

    /**
     * Phase 1 해외 구독 대상 종목을 반환한다.
     *
     * <p>Phase 1에서는 해외 구독을 지원하지 않으므로 빈 리스트를 반환한다.
     *
     * @return 빈 목록
     */
    public List<String> resolveOverseasSymbols() {
        return List.of();
    }
}
