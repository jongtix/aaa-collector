package com.aaa.collector.kis.token;

import com.aaa.collector.common.safemode.SafeModeManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * ②단계 멀티키 분산 직전 키별 헬스 사전점검을 수행하여 건강한 키 집합을 산출하는 컴포넌트 (SPEC-COLLECTOR-WLSYNC-006
 * REQ-WLSYNC-130~134).
 *
 * <p>헬스 판정: 토큰 컨텍스트 SafeMode가 비활성(`!isActive(alias)`)이고 키당 정확히 1회 {@link
 * KisTokenService#getValidToken(String)} 시도로 유효한 access_token을 확보한 키를 "건강"으로 분류한다. SafeMode active
 * 키는 토큰 시도 없이 isActive 게이트로 즉시 제외된다.
 *
 * <p>[HARD] 키별 <b>병렬</b>({@link Executors#newVirtualThreadPerTaskExecutor()})로 수행한다. 직렬 점검은 주말 콜드
 * 경로에서 키당 비용(~60s)을 누적시켜 09:00 장 시작을 재침범하므로, 병렬 수행으로 전체 점검 소요를 단일 키 최악 비용으로 bound한다(D2 A안). 키당 정확히
 * 1회 시도하여 종목당 Lazy 재시도 폭주를 원천 차단한다(REQ-WLSYNC-133).
 */
@Slf4j
@Component
// @MX:ANCHOR: [AUTO] ②단계 헬스 키 필터 진입점 — 키별 병렬 1회 토큰 확보로 건강 키 집합 산출
// @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-130~134 — 죽은 키 제외로 종목당 Lazy 재시도 폭주 차단, 병렬 비용
// bound
// @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006
public class HealthyKeySelector {

    private final KisProperties kisProperties;
    private final KisTokenService kisTokenService;
    private final SafeModeManager tokenSafeModeManager;

    public HealthyKeySelector(
            KisProperties kisProperties,
            KisTokenService kisTokenService,
            @Qualifier("tokenSafeModeManager") SafeModeManager tokenSafeModeManager) {
        this.kisProperties = kisProperties;
        this.kisTokenService = kisTokenService;
        this.tokenSafeModeManager = tokenSafeModeManager;
    }

    /**
     * 5개 키 각각에 대해 키별 병렬로 키당 1회 헬스 점검을 수행하여 건강한 키 집합을 반환한다.
     *
     * @return 건강한 키의 자격증명 목록 (모두 죽은 경우 빈 목록)
     */
    public List<KisAccountCredential> selectHealthy() {
        List<KisAccountCredential> accounts = kisProperties.accounts();
        log.info("②단계 헬스 키 사전점검 시작 — 키 수: {}", accounts.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<KisAccountCredential>> futures = new ArrayList<>();

            for (KisAccountCredential credential : accounts) {
                futures.add(executor.submit(() -> evaluate(credential)));
            }

            List<KisAccountCredential> healthy = new ArrayList<>();
            for (Future<KisAccountCredential> future : futures) {
                try {
                    KisAccountCredential result = future.get();
                    if (result != null) {
                        healthy.add(result);
                    }
                } catch (ExecutionException e) {
                    log.warn("[헬스 점검] 예상치 못한 예외", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("헬스 점검 중 인터럽트 수신 — 남은 키 결과 수집 중단");
                    break;
                }
            }

            log.info("②단계 헬스 키 사전점검 완료 — 건강 키 수: {}/{}", healthy.size(), accounts.size());
            return List.copyOf(healthy);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 토큰 확보 실패는 죽은 키 신호로 흡수
    private KisAccountCredential evaluate(KisAccountCredential credential) {
        String alias = credential.alias();
        if (tokenSafeModeManager.isActive(alias)) {
            log.warn("[{}] SafeMode active — 죽은 키로 제외 (토큰 시도 생략)", alias);
            return null;
        }
        try {
            kisTokenService.getValidToken(alias);
            return credential;
        } catch (Exception e) {
            log.warn("[{}] 토큰 확보 실패 — 죽은 키로 제외", alias, e);
            return null;
        }
    }
}
