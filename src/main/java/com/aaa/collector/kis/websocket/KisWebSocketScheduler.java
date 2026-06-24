package com.aaa.collector.kis.websocket;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KIS WebSocket 장 개시/종료 cron 트리거.
 *
 * <p>국내(KST)·해외(ET) 장 개시 및 종료를 스케줄링하며, 재진입 방지 플래그로 중복 실행을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class KisWebSocketScheduler {

    private final KisWebSocketSessionManager sessionManager;
    private final SubscriptionTargetResolver subscriptionTargetResolver;

    // 재진입 방지 플래그 (EtfRepresentativeScheduler 패턴 참고)
    // package-private: 테스트에서 직접 접근 가능
    final AtomicBoolean domesticRunning = new AtomicBoolean(false);
    final AtomicBoolean overseasRunning = new AtomicBoolean(false);

    /** 국내 장 개시 — 08:55 KST (REQ-WS-050). */
    @Scheduled(cron = "0 55 8 * * MON-FRI", zone = "Asia/Seoul")
    public void openDomesticSession() {
        if (!domesticRunning.compareAndSet(false, true)) {
            log.warn("국내 WebSocket 장 개시 작업 중복 실행 방지");
            return;
        }
        try {
            log.info("국내 WebSocket 장 개시 시작");
            sessionManager.openAll();
            List<String> symbols = subscriptionTargetResolver.resolveDomesticSymbols();
            sessionManager.subscribeSymbols(symbols);
            log.info("국내 WebSocket 장 개시 완료 — 구독 종목: {}개", symbols.size());
        } catch (Exception e) {
            log.error("국내 WebSocket 장 개시 중 오류", e);
        } finally {
            domesticRunning.set(false);
        }
    }

    /** 국내 장 종료 — 15:35 KST (REQ-WS-051). */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void closeDomesticSession() {
        try {
            log.info("국내 WebSocket 장 종료 시작");
            sessionManager.closeAll();
            log.info("국내 WebSocket 장 종료 완료");
        } catch (Exception e) {
            log.error("국내 WebSocket 장 종료 중 오류", e);
        }
    }

    /** 해외 장 개시 — 09:25 America/New_York (EDT/EST 자동 반영, REQ-WSOV-010). */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "America/New_York")
    public void openOverseasSession() {
        if (!overseasRunning.compareAndSet(false, true)) {
            log.warn("해외 WebSocket 장 개시 작업 중복 실행 방지");
            return;
        }
        try {
            log.info("해외 WebSocket 장 개시 시작");
            sessionManager.openAll();
            List<String> trKeys = subscriptionTargetResolver.resolveOverseasSymbols();
            sessionManager.subscribeOverseasSymbols(trKeys);
            log.info("해외 WebSocket 장 개시 완료 — 구독 대상: {}개", trKeys.size());
        } catch (Exception e) {
            log.error("해외 WebSocket 장 개시 중 오류", e);
        } finally {
            overseasRunning.set(false);
        }
    }

    /** 해외 장 종료 — 16:05 America/New_York (REQ-WSOV-011). */
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "America/New_York")
    public void closeOverseasSession() {
        try {
            log.info("해외 WebSocket 장 종료 시작");
            sessionManager.closeAll();
            log.info("해외 WebSocket 장 종료 완료");
        } catch (Exception e) {
            log.error("해외 WebSocket 장 종료 중 오류", e);
        }
    }
}
