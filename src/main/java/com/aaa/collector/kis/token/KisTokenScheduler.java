package com.aaa.collector.kis.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KIS API 토큰 스케줄 갱신을 담당하는 컴포넌트.
 *
 * <p>평일(월~금) 08:15 KST에 전 계좌 REST access_token을 사전(eager) 발급하고 WebSocket approval_key를 일괄 재발급한다.
 * REST access_token은 Lazy 발급 경로를 fallback으로 보존한다(SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-103).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenScheduler {

    private final KisTokenService kisTokenService;

    /**
     * 전 계좌 REST access_token 사전발급과 WebSocket approval_key 일괄 재발급을 함께 수행한다.
     *
     * <p>장 시작 전(08:15 KST)에 실행되어, 09:00 장 시작 전에 access_token(②단계 멀티키 분산 소비처)과 approval_key(장중
     * WebSocket 연결)가 모두 준비되도록 한다. 동기화 스케줄(08:20)보다 5분 앞서 발화하여 토큰이 분산 시점에 준비되도록 정렬한다
     * (SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-100,102,110).
     */
    @Scheduled(cron = "0 15 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshTokens() {
        log.info("스케줄 토큰 갱신 시작");
        kisTokenService.issueAllTokens();
        kisTokenService.issueAllApprovalKeys();
        log.info("스케줄 토큰 갱신 완료");
    }
}
