package com.aaa.collector.kis.websocket;

/**
 * {@link KisWebSocketSession} 생성 팩토리 인터페이스.
 *
 * <p>프로덕션 환경에서는 실제 세션을 생성하고, 테스트 환경에서는 mock 세션을 주입하기 위해 사용한다.
 */
@FunctionalInterface
public interface KisWebSocketSessionFactory {

    /**
     * 새 KIS WebSocket 세션을 생성한다.
     *
     * @param alias 계좌 식별자
     * @param approvalKey WebSocket 승인키
     * @return 새로 생성된 세션 인스턴스
     */
    KisWebSocketSession create(String alias, String approvalKey);
}
