package com.aaa.collector.kis.gate;

import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 건강 키 스냅샷 기반 least-busy 동적 lease 레지스트리 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-005/006/024/031).
 *
 * <p>정적 {@code HealthyKeyRoundRobinDistributor}(i%N 사전 분배)를 대체하는 동적 lease 메커니즘이다. 건강 키 판정은 기존
 * {@link HealthyKeySelector}에 위임하며(REQ-KISGATE-006 — 신규 헬스 로직 부재), 본 레지스트리는 in-use 카운터 기반
 * least-busy 선택만 담당한다.
 *
 * <p><strong>per-batch 스냅샷(DP3, REQ-KISGATE-006a):</strong> {@link #openSession()}이 {@link
 * HealthyKeySelector#selectHealthy()}를 <b>정확히 1회</b> 호출해 {@link LeaseSession}을 만든다. 그 세션의 모든 lease는
 * 고정 스냅샷 내에서만 선택한다 — lease 직전마다 라이브 토큰 프로브(콜드 ~60s)를 반복하지 않고 단위당 1회로 bound한다.
 *
 * <p><strong>lock-free best-effort(DP5, REQ-KISGATE-005b):</strong> "최소 키 선택 + 카운트 증가"를 원자적으로 묶지
 * 않는다(락·CAS 재시도 루프 없음). 개별 카운터 증감만 {@link AtomicInteger}로 thread-safe. 일시 쏠림은 키별 {@code
 * KisRateLimiter} backpressure로 자기교정된다.
 *
 * @see KeyLease
 * @see HealthyKeySelector
 */
@Component
// @MX:ANCHOR: [AUTO] 동적 키 lease 진입점 — 게이트 모든 멀티키 호출이 per-batch 스냅샷에서 키를 lease (정적 distributor 대체)
// @MX:REASON: SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-005/006a — least-busy 동적 lease, per-batch 1회
// 스냅샷
// @MX:SPEC: SPEC-COLLECTOR-KISGATE-001
public class KeyLeaseRegistry {

    private final HealthyKeySelector healthyKeySelector;

    /**
     * @param healthyKeySelector 건강 키 스냅샷 산출 위임 대상(REQ-KISGATE-006)
     */
    public KeyLeaseRegistry(HealthyKeySelector healthyKeySelector) {
        this.healthyKeySelector = healthyKeySelector;
    }

    /**
     * 수집 작업 단위(배치 전 종목 순회 / 패턴 A 단발 collect 1회) 시작 시 호출하여 건강 키 스냅샷을 1회 고정한 {@link LeaseSession}을
     * 연다.
     *
     * <p>{@link HealthyKeySelector#selectHealthy()}를 정확히 1회 호출한다(REQ-KISGATE-006a). 반환된 세션의 lease는
     * selectHealthy를 재호출하지 않는다.
     *
     * @return 고정 스냅샷 기반 lease 세션
     */
    public LeaseSession openSession() {
        List<KisAccountCredential> snapshot = healthyKeySelector.selectHealthy();
        return new LeaseSession(snapshot);
    }

    /**
     * per-batch 건강 키 스냅샷을 고정 보유하고 그 안에서 least-busy lease를 발급하는 세션.
     *
     * <p>alias별 in-use {@link AtomicInteger} 카운터를 배치 수명 동안 유지한다(REQ-KISGATE-005/031). 스냅샷이 비어 있으면
     * 모든 lease는 {@link Optional#empty()}를 반환하여 전 키 사망을 신호한다(REQ-KISGATE-024).
     */
    public static final class LeaseSession {

        /** 스냅샷 키 순서(동률 시 stable-first tie-break의 안정 기준). */
        private final List<KisAccountCredential> snapshot;

        /** alias → in-use 카운터 (배치 수명 동안 유지, 병렬 증감). */
        private final Map<String, AtomicInteger> inUseByAlias;

        private LeaseSession(List<KisAccountCredential> snapshot) {
            this.snapshot = List.copyOf(snapshot);
            this.inUseByAlias =
                    this.snapshot.stream()
                            .collect(
                                    Collectors.toMap(
                                            KisAccountCredential::alias,
                                            credential -> new AtomicInteger(0),
                                            (a, b) -> a,
                                            ConcurrentHashMap::new));
        }

        /**
         * 스냅샷이 비어 있는지(전 키 사망) 여부.
         *
         * @return 건강 키가 0개이면 {@code true}
         */
        public boolean isEmpty() {
            return snapshot.isEmpty();
        }

        /**
         * @return 스냅샷의 건강 키 수
         */
        public int healthyKeyCount() {
            return snapshot.size();
        }

        /**
         * 지정 alias의 현재 in-use 카운터 값(관측용).
         *
         * @param alias 조회할 키 alias
         * @return 현재 in-use 수 (미등록 alias는 0)
         */
        public int inUseCount(String alias) {
            AtomicInteger counter = inUseByAlias.get(alias);
            return counter == null ? 0 : counter.get();
        }

        /**
         * 스냅샷 내 in-use 최소 키를 lease한다(무대기, REQ-KISGATE-005a).
         *
         * @return lease, 스냅샷이 비어 있으면 {@link Optional#empty()}(전 키 사망 신호)
         */
        public Optional<KeyLease> lease() {
            return lease(null);
        }

        /**
         * 스냅샷 내 in-use 최소 키를 lease하되, {@code avoidAlias}는 가능하면 회피한다(재시도 시 막힌 키 자동 전환,
         * REQ-KISGATE-021).
         *
         * <p>회피 대상을 제외한 키가 하나라도 있으면 그중 least-busy를 선택하고, 회피 대상이 유일한 키이면 그 키로 폴백한다(키 1개 엣지). 동률은 스냅샷
         * 순서 stable-first로 결정한다.
         *
         * <p><strong>lock-free:</strong> 최소 선택과 카운터 증가를 원자적으로 묶지 않는다 — best-effort(DP5).
         *
         * @param avoidAlias 가능하면 피할 키 alias (없으면 {@code null})
         * @return lease, 스냅샷이 비어 있으면 {@link Optional#empty()}
         */
        public Optional<KeyLease> lease(String avoidAlias) {
            KisAccountCredential chosen = selectLeastBusy(avoidAlias);
            if (chosen == null) {
                return Optional.empty();
            }

            AtomicInteger counter = inUseByAlias.get(chosen.alias());
            // lock-free best-effort: 선택과 증가 사이에 락 없음 — 미세 쏠림 허용, 개별 증감만 thread-safe
            counter.incrementAndGet();
            return Optional.of(new InMemoryKeyLease(chosen, counter::decrementAndGet));
        }

        /**
         * 회피 대상을 우선 배제한 뒤 in-use 최소(동률 stable-first) credential을 선택한다. 막힌 키가 유일하면 그 키로 폴백.
         *
         * @return 선택된 credential, 스냅샷이 비어 있으면 {@code null}
         */
        private KisAccountCredential selectLeastBusy(String avoidAlias) {
            KisAccountCredential best = null;
            int bestInUse = Integer.MAX_VALUE;
            KisAccountCredential fallback = null;
            int fallbackInUse = Integer.MAX_VALUE;

            // 스냅샷 순서로 순회 — 동률 시 먼저 나온 키(stable-first)를 유지
            for (KisAccountCredential credential : snapshot) {
                int inUse = inUseByAlias.get(credential.alias()).get();

                // 폴백 후보(회피 대상 포함 전체) 중 최소도 추적 — 회피 제외 후 남는 키가 없을 때 사용
                if (inUse < fallbackInUse) {
                    fallbackInUse = inUse;
                    fallback = credential;
                }

                if (credential.alias().equals(avoidAlias)) {
                    continue;
                }
                if (inUse < bestInUse) {
                    bestInUse = inUse;
                    best = credential;
                }
            }

            return best != null ? best : fallback;
        }
    }
}
