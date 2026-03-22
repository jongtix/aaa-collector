package com.aaa.collector.kis.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KIS API 토큰 스케줄 갱신을 담당하는 컴포넌트.
 *
 * <p>평일(월~금) 08:30 KST에 전 계좌 토큰을 일괄 재발급한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenScheduler {

    private final KisTokenService kisTokenService;

    /**
     * 전 계좌 토큰을 일괄 재발급한다.
     *
     * <p>장 시작 전(08:30 KST)에 실행되어, 장중 API 호출 시 유효한 토큰이 준비되도록 한다.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshTokens() {
        log.info("스케줄 토큰 갱신 시작");
        kisTokenService.issueAll();
        log.info("스케줄 토큰 갱신 완료");
    }
}
