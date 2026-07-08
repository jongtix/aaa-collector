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
 *
 * <p><b>주말 무조건 발급 일일 플로어(SPEC-COLLECTOR-SAFEMODE-001, D-3)</b>: 주말(토·일) 08:15 KST에는 별도 트리거({@link
 * #refreshWeekendTokens()})가 access_token 사전발급만 수행한다. approval_key는 평일 전용으로 유지한다
 * (SPEC-COLLECTOR-TOKEN-001 REQ-TOKEN-003 보존, REQ-SAFEMODE-009~012). 이 무조건 발급은 SafeMode 활성 여부와 무관하게
 * 시도되므로(REQ-SAFEMODE-011), 짧은 KIS 장애 이후에도 하루 최소 1회 자동 복구 시도를 보장하는 안전망 역할을 한다.
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

    /**
     * 주말(토·일) 08:15 KST에 전 계좌 REST access_token 사전발급만 수행한다. approval_key는 재발급하지 않는다
     * (REQ-SAFEMODE-010/012, TOKEN-001 REQ-TOKEN-003 "approval_key 평일 전용" 보존).
     *
     * <p>{@link #refreshTokens()}와 별개 트리거로 분리하여, 요일 필드만 확장(MON-FRI → *)하는 방식으로는 발생할 주말 approval_key
     * 재발급 부수효과를 원천 차단한다(D-3).
     */
    // @MX:NOTE: [AUTO] 평일 refreshTokens()와 독립된 트리거 — 요일 필드만 확장(MON-FRI→*)하지 않고 별도 메서드로 분리해
    // 주말 approval_key 재발급 부수효과를 차단(D-3, TOKEN-001 REQ-TOKEN-003 보존)
    @Scheduled(cron = "0 15 8 * * SAT,SUN", zone = "Asia/Seoul")
    public void refreshWeekendTokens() {
        log.info("주말 스케줄 access_token 무조건 발급 시작");
        kisTokenService.issueAllTokens();
        log.info("주말 스케줄 access_token 무조건 발급 완료");
    }
}
