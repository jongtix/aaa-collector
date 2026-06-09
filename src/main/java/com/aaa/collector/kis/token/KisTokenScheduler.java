package com.aaa.collector.kis.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KIS API WebSocket 승인키 스케줄 갱신을 담당하는 컴포넌트.
 *
 * <p>평일(월~금) 08:30 KST에 전 계좌 WebSocket approval_key를 일괄 재발급한다. REST access_token은 순수 Lazy 발급 방식으로
 * 동작하므로 스케줄 발급 대상이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenScheduler {

    private final KisTokenService kisTokenService;

    /**
     * 전 계좌 WebSocket approval_key를 일괄 재발급한다.
     *
     * <p>장 시작 전(08:30 KST)에 실행되어, 장중 WebSocket 연결 시 유효한 승인키가 준비되도록 한다. REST access_token은 순수 Lazy
     * 발급 방식으로 동작하므로 이 스케줄에서 발급하지 않는다.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshTokens() {
        log.info("스케줄 승인키 갱신 시작");
        kisTokenService.issueAllApprovalKeys();
        log.info("스케줄 승인키 갱신 완료");
    }
}
