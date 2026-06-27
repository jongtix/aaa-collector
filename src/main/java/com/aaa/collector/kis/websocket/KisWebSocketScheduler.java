package com.aaa.collector.kis.websocket;

import com.aaa.collector.common.gate.MarketOpenGate;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KIS WebSocket 장 개시/종료 cron 트리거.
 *
 * <p>국내(KST)·해외(ET) 장 개시 및 종료를 스케줄링하며, 재진입 방지 플래그로 중복 실행을 막는다.
 *
 * <p>부팅 완료 시 국내 장 중이면 WebSocket 구독을 1회 복구한다(SPEC-COLLECTOR-WS-RECOVERY-001, REQ-WSREC-001/002).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(WsRecoveryProperties.class)
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class KisWebSocketScheduler {

    private final KisWebSocketSessionManager sessionManager;
    private final SubscriptionTargetResolver subscriptionTargetResolver;
    // @MX:NOTE: [AUTO] MarketOpenGate 인터페이스 타입 주입 — kis→common.gate 단방향 (ArchUnit CR-01 순환 방지)
    // @MX:SPEC: SPEC-COLLECTOR-WS-RECOVERY-001
    private final MarketOpenGate marketOpenGate;
    // @MX:NOTE: [AUTO] UsMarketOpenGate 인터페이스 타입 주입 — kis→common.gate 단방향 (ArchUnit CR-01 순환 방지)
    // @MX:SPEC: SPEC-COLLECTOR-USMKT-001
    private final UsMarketOpenGate usMarketOpenGate;
    private final WsRecoveryProperties wsRecoveryProperties;

    // 재진입 방지 플래그 (EtfRepresentativeScheduler 패턴 참고)
    // package-private: 테스트에서 직접 접근 가능
    final AtomicBoolean domesticRunning = new AtomicBoolean(false);
    final AtomicBoolean overseasRunning = new AtomicBoolean(false);

    // @MX:NOTE: [AUTO] 부팅 완료 시 국내·해외 WS 복구 트리거 — 재배포 시 구독 공백 해소 (REQ-WSREC-001, REQ-USMKT-015)
    // @MX:SPEC: SPEC-COLLECTOR-WS-RECOVERY-001, SPEC-COLLECTOR-USMKT-001
    /** 부팅 완료 이벤트 수신 후 별도 Virtual Thread에서 복구를 실행한다 (REQ-WSREC-001, REQ-USMKT-015). */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("ws-recovery").start(this::recoverDomesticSessionOnStartup);
        Thread.ofVirtual()
                .name("ws-overseas-recovery")
                .start(this::recoverOverseasSessionOnStartup);
    }

    /**
     * 미장 중 재배포 시 해외 WebSocket 구독 복구 본체 (REQ-USMKT-015/016).
     *
     * <p>3-가드 순서: (1) enabled — (2) isRunning 미기동 — (3) UsMarketSessionGate.isMarketOpenNow 장중. 미장외
     * 부팅 → 복구 생략+로그(REQ-USMKT-016).
     *
     * <p>package-private: 단위 테스트에서 직접 호출 가능.
     */
    void recoverOverseasSessionOnStartup() {
        if (!wsRecoveryProperties.isEnabled()) {
            return;
        }
        if (sessionManager.isRunning()) {
            log.info("해외 WebSocket 세션 이미 기동됨 → 복구 생략");
            return;
        }
        if (!usMarketOpenGate.isMarketOpenNow()) {
            log.info("미장외 → 해외 WebSocket 복구 생략");
            return;
        }
        log.info("미장 중 → 해외 WebSocket 구독 복구");
        openOverseasSession();
    }

    /**
     * 장 중 재배포 시 국내 WebSocket 구독 복구 본체.
     *
     * <p>3-가드 순서: (1) enabled — (2) isRunning 미기동 — (3) isMarketOpenNow 장중. 모두 통과하면 {@link
     * #openDomesticSession()}을 재사용한다(REQ-WSREC-012).
     *
     * <p>package-private: 단위 테스트에서 직접 호출 가능(REQ-WSREC-060).
     */
    void recoverDomesticSessionOnStartup() {
        if (!wsRecoveryProperties.isEnabled()) {
            return;
        }
        if (sessionManager.isRunning()) {
            log.info("국내 WebSocket 세션 이미 기동됨 → 복구 생략");
            return;
        }
        if (!marketOpenGate.isMarketOpenNow()) {
            log.info("장외 → 국내 WebSocket 복구 생략");
            return;
        }
        log.info("장 중 → 국내 WebSocket 구독 복구");
        openDomesticSession();
    }

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
