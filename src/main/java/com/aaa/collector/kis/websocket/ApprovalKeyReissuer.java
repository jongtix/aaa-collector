package com.aaa.collector.kis.websocket;

import com.aaa.collector.kis.token.KisTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * approval_key 인증 실패 식별 시 무효화+재발급을 수행하고, 발급된 새 키를 살아있는 세션에 즉시 주입한다(REQ-WSRES2-007,
 * SPEC-COLLECTOR-WS-RESILIENCE-002).
 *
 * <p>{@link KisWebSocketSessionManager}에서 분리한 이유는 (1) 단일 책임 분리(재발급 오케스트레이션 vs 세션 라이프사이클/구독 분배)와 (2)
 * WMC 감소(GodClass 완화)다. {@code KisWebSocketSessionManager.wireCallbacks}가 세션 생성 직후 이 클래스를 콜백으로
 * 연결한다.
 *
 * <p>인증 실패 콜백은 거절된 SUBSCRIBE 응답마다(세션당 최대 40건) 호출되므로, alias별 <b>단일 비행(single-flight)</b>과 <b>최소 재발급
 * 간격</b>으로 한 번의 거절 폭주가 KIS 발급 호출 1회로만 이어지게 한다.
 */
@Slf4j
@RequiredArgsConstructor
class ApprovalKeyReissuer {

    // @MX:NOTE: [AUTO] 60초 = 인증 실패 회로차단기(창 30초 / 재연결 지연 하한 30초)의 2배 — 회로차단기가 늦추는 재연결 주기마다 발급이
    // 재시작되지 않게 하고 KIS 발급 엔드포인트 호출을 분당 1회 이하로 묶는다. api-specs/kis에 승인키 발급 빈도 제한 명세가 없어(미실측)
    // 보수적으로 잡은 값이며, 실패한 시도도 시작으로 계산한다.
    /** alias별 재발급 시작 사이의 최소 간격. */
    static final Duration MIN_REISSUE_INTERVAL = Duration.ofSeconds(60);

    private final KisTokenService kisTokenService;
    private final Clock clock;

    /** alias별 재발급 상태 — 마지막 시작 시각과 진행 중 여부를 {@code compute}로 원자적으로 갱신한다. */
    private final Map<String, ReissueState> stateByAlias = new ConcurrentHashMap<>();

    /**
     * approval_key를 무효화하고 재발급하여 살아있는 세션에 즉시 주입한다.
     *
     * <p>같은 alias에서 재발급이 진행 중이거나 직전 재발급 시작 후 {@link #MIN_REISSUE_INTERVAL}이 지나지 않았으면 새로 시작하지 않고 즉시
     * 완료된 future를 돌려준다(합류). 간격이 지난 뒤의 진짜 거절은 다시 재발급을 시작한다.
     *
     * <p>WebSocket I/O 스레드({@code WebSocketClient-AsyncIO-*})를 블로킹하지 않도록 별도 Virtual Thread에서
     * 실행한다({@link KisWebSocketScheduler#onApplicationReady}의 기존 패턴과 동형). 재발급이 실패해도 예외를 삼키고 로그만 남긴다
     * — 다음 재연결 시도에서도 여전히 무효화된 캐시 키를 재사용하지 않도록 {@link KisTokenService#getValidApprovalKey}의 기존 Lazy
     * 갱신 경로가 후속 시도를 흡수한다.
     *
     * @param alias 계정 식별자
     * @param session 승인키를 갱신할 대상 세션
     * @return 재발급 작업 완료 시점을 나타내는 future(테스트 동기화용, 호출자는 무시해도 된다). 합류·억제된 호출은 이미 완료된 상태다
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    CompletableFuture<Void> reissueAsync(String alias, KisWebSocketSession session) {
        if (!tryStart(alias)) {
            log.debug("[{}] approval_key 재발급 합류/억제 — 진행 중이거나 최소 간격 미경과", alias);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        // @MX:WARN: [AUTO] 재발급을 별도 Virtual Thread에서 비동기 실행 — 세션 승인키(volatile)를 다른 스레드가 덮어쓴다
        // @MX:REASON: alias별 단일 비행+최소 간격(stateByAlias)이 유일한 방어선 — 게이트를 우회하면 거절 응답마다 KIS 발급이
        // 팬아웃되고 마지막 완료 스레드의 키(=이미 무효화됐을 수 있는 키)가 세션에 남는 경합이 생긴다
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
                            } finally {
                                finish(alias);
                                done.complete(null);
                            }
                        });
        return done;
    }

    /** 진행 중이 아니고 최소 간격이 지났을 때만 시작 권한을 얻는다(원자적). */
    private boolean tryStart(String alias) {
        Instant now = clock.instant();
        AtomicBoolean granted = new AtomicBoolean(false);
        stateByAlias.compute(
                alias,
                (key, state) -> {
                    if (state != null
                            && (state.inFlight()
                                    || now.isBefore(
                                            state.lastStartedAt().plus(MIN_REISSUE_INTERVAL)))) {
                        return state;
                    }
                    granted.set(true);
                    return new ReissueState(now, true);
                });
        return granted.get();
    }

    /** 진행 중 표시만 해제한다 — 마지막 시작 시각은 유지되어 최소 간격이 계속 적용된다. */
    private void finish(String alias) {
        stateByAlias.computeIfPresent(
                alias, (key, state) -> new ReissueState(state.lastStartedAt(), false));
    }

    /** alias별 재발급 상태. */
    private record ReissueState(Instant lastStartedAt, boolean inFlight) {}
}
