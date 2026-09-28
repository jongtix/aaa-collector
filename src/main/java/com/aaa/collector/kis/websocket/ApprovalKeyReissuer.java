package com.aaa.collector.kis.websocket;

import com.aaa.collector.kis.token.KisTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * approval_key 인증 실패 식별 시 무효화+재발급을 수행하고, 발급된 새 키를 살아있는 세션에 즉시 주입한다(REQ-WSRES2-007,
 * SPEC-COLLECTOR-WS-RESILIENCE-002).
 *
 * <p>{@link KisWebSocketSessionManager}에서 분리한 이유는 (1) 단일 책임 분리(재발급 오케스트레이션 vs 세션 라이프사이클/구독 분배)와 (2)
 * WMC 감소(GodClass 완화)다. {@code KisWebSocketSessionManager.wireCallbacks}가 세션 생성 직후 이 클래스를 콜백으로
 * 연결한다.
 */
@Slf4j
@RequiredArgsConstructor
class ApprovalKeyReissuer {

    private final KisTokenService kisTokenService;

    /**
     * approval_key를 무효화하고 재발급하여 살아있는 세션에 즉시 주입한다.
     *
     * <p>WebSocket I/O 스레드({@code WebSocketClient-AsyncIO-*})를 블로킹하지 않도록 별도 Virtual Thread에서
     * 실행한다({@link KisWebSocketScheduler#onApplicationReady}의 기존 패턴과 동형). 재발급이 실패해도 예외를 삼키고 로그만 남긴다
     * — 다음 재연결 시도에서도 여전히 무효화된 캐시 키를 재사용하지 않도록 {@link KisTokenService#getValidApprovalKey}의 기존 Lazy
     * 갱신 경로가 후속 시도를 흡수한다.
     *
     * @param alias 계정 식별자
     * @param session 승인키를 갱신할 대상 세션
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    void reissueAsync(String alias, KisWebSocketSession session) {
        Thread.ofVirtual()
                .name("ws-approval-reissue-" + alias)
                .start(
                        () -> {
                            try {
                                kisTokenService.invalidateApprovalKey(alias); // REQ-WSRES2-007 (i)
                                String freshKey = kisTokenService.reissueApprovalKey(alias); // (ii)
                                session.updateApprovalKey(freshKey);
                                log.info(
                                        "[{}] approval_key 무효화+재발급 완료 — 세션 즉시 갱신 (REQ-WSRES2-007)",
                                        alias);
                            } catch (Exception e) {
                                log.error("[{}] approval_key 재발급 실패 — 이후 재연결 시도에서 재시도됨", alias, e);
                            }
                        });
    }
}
