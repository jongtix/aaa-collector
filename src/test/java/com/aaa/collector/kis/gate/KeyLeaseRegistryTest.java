package com.aaa.collector.kis.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-KISGATE-001 M2(T03) — {@link KeyLeaseRegistry} 단위 테스트.
 *
 * <p>검증 범위: per-batch 헬스 스냅샷(DP3 — {@code selectHealthy()} 단위당 정확히 1회), least-busy 선택(AC-4),
 * 무대기(REQ-005a), lock-free best-effort 병렬 정합성(REQ-005b/031), release finally 누수 없음, 전 키 사망
 * 신호(AC-5/REQ-024).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeyLeaseRegistry — per-batch 스냅샷 least-busy lease")
class KeyLeaseRegistryTest {

    private static final KisAccountCredential K1 =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential K2 =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");
    private static final KisAccountCredential K3 =
            new KisAccountCredential("pension", "33333333", "appkey-pension", "appsecret-pension");

    @Mock private HealthyKeySelector healthyKeySelector;

    private KeyLeaseRegistry registry;

    @Nested
    @DisplayName("per-batch 헬스 스냅샷 (DP3, REQ-KISGATE-006a)")
    class Snapshot {

        @Test
        @DisplayName("openSession() 1회 — selectHealthy()를 정확히 1회만 호출한다")
        void openSession_callsSelectHealthyExactlyOnce() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            registry = new KeyLeaseRegistry(healthyKeySelector);

            registry.openSession();

            verify(healthyKeySelector, times(1)).selectHealthy();
        }

        @Test
        @DisplayName("세션 내 다수 lease — selectHealthy()는 추가로 호출되지 않는다(스냅샷 고정)")
        void lease_doesNotReinvokeSelectHealthy() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));
            registry = new KeyLeaseRegistry(healthyKeySelector);

            LeaseSession session = registry.openSession();
            session.lease();
            session.lease();
            session.lease();

            // lease 직전마다 라이브 프로브 금지 — selectHealthy는 openSession의 1회뿐
            verify(healthyKeySelector, times(1)).selectHealthy();
            verifyNoMoreInteractions(healthyKeySelector);
        }
    }

    @Nested
    @DisplayName("least-busy 선택 (AC-4, REQ-KISGATE-005)")
    class LeastBusy {

        @Test
        @DisplayName("모든 키 in-use 0 — 동률이면 스냅샷 순서 stable-first(K1) 선택")
        void lease_allZero_picksStableFirst() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            KeyLease first = session.lease().orElseThrow();

            // 모두 0(동률)이면 스냅샷 첫 키를 안정적으로 선택
            assertThat(first.alias()).isEqualTo("isa");
            assertThat(session.inUseCount("isa")).isEqualTo(1);
        }

        @Test
        @DisplayName("명시적 분포 K1=2,K2=0,K3=1 — 다음 lease는 최소(K2)를 선택한다")
        void lease_withExplicitDistribution_picksMinimum() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            // Arrange: avoidAlias 미사용 lease는 동률 시 stable-first를 고른다.
            // K1을 2회 점유하려면 release 없이 같은 키를 두 번 골라야 한다 — avoidAlias로 강제 분포 구성.
            KeyLease a = session.lease().orElseThrow(); // K1 (0,0,0 stable-first) → K1=1
            KeyLease b = session.lease().orElseThrow(); // K2 (K1=1,K2=0,K3=0) → K2=1
            KeyLease c = session.lease().orElseThrow(); // K3 (K1=1,K2=1,K3=0) → K3=1
            // 현재 K1=1,K2=1,K3=1. b(K2) release → K2=0. a 유지(K1=1), 한 번 더 K1 점유 위해 c release & K3
            // 재lease 회피.
            b.release(); // K1=1,K2=0,K3=1
            // K1을 한 번 더 점유: 현재 min은 K2(0)이므로 avoidAlias로 K2를 피해 K1과 K3 중 stable-first K1 선택
            KeyLease d =
                    session.lease("gold")
                            .orElseThrow(); // avoid K2 → min(K1=1,K3=1) stable-first K1 → K1=2
            assertThat(d.alias()).isEqualTo("isa");

            // 분포 K1=2,K2=0,K3=1 — 다음 lease(avoid 없음)는 최소 K2 선택
            KeyLease next = session.lease().orElseThrow();
            assertThat(next.alias()).isEqualTo("gold");
            assertThat(session.inUseCount("gold")).isEqualTo(1);

            // cleanup
            a.release();
            c.release();
            d.release();
            next.release();
        }

        @Test
        @DisplayName("release() — 해당 키의 in-use 카운터를 1 감소시킨다")
        void release_decrementsCounter() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            KeyLease lease = session.lease().orElseThrow();
            assertThat(session.inUseCount(lease.alias())).isEqualTo(1);

            lease.release();

            assertThat(session.inUseCount(lease.alias())).isZero();
        }
    }

    @Nested
    @DisplayName("무대기 + re-lease 키 회피 (REQ-KISGATE-005a/021)")
    class NoWaitAndAvoid {

        @Test
        @DisplayName("모든 키 in-use여도 즉시 최소 키 반환(블로킹 없음)")
        void lease_neverBlocks_evenWhenAllInUse() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            // Arrange: 두 키 모두 점유 상태로 만든다
            session.lease().orElseThrow(); // K1=1
            session.lease().orElseThrow(); // K2=1

            // Act & Assert: 모두 in-use여도 대기 없이 최소 키 즉시 반환(backpressure는 rate limiter 담당)
            Optional<KeyLease> third = session.lease();
            assertThat(third).isPresent();
        }

        @Test
        @DisplayName("avoidAlias — 다른 키가 있으면 막힌 키를 피해 재선택(AC-3 re-lease 토대)")
        void lease_withAvoid_picksDifferentKeyWhenAvailable() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            KeyLease first = session.lease().orElseThrow(); // K1
            first.release(); // 카운터 0,0으로 복귀 — avoidAlias 없으면 다시 K1을 고를 것

            KeyLease released = session.lease(first.alias()).orElseThrow();
            assertThat(released.alias()).isNotEqualTo(first.alias());
        }

        @Test
        @DisplayName("avoidAlias — 키가 1개뿐이면 회피 대상이라도 그 키로 폴백한다")
        void lease_withAvoid_fallsBackWhenOnlyOneKey() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            KeyLease only = session.lease("isa").orElseThrow();
            assertThat(only.alias()).isEqualTo("isa");
        }
    }

    @Nested
    @DisplayName("전 키 사망 신호 (AC-5, REQ-KISGATE-024)")
    class AllKeysDead {

        @Test
        @DisplayName("빈 스냅샷 — isEmpty()=true, lease()는 Optional.empty()")
        void emptySnapshot_signalsNoAssignment() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            // 빈 건강 키 → 가짜 키 lease 없이 미배정 신호. 호출부가 skip-all 정책 적용.
            assertThat(session.isEmpty()).isTrue();
            assertThat(session.healthyKeyCount()).isZero();
            assertThat(session.lease()).isEmpty();
            assertThat(session.lease("isa")).isEmpty();
        }

        @Test
        @DisplayName("비어있지 않은 스냅샷 — isEmpty()=false, healthyKeyCount=스냅샷 크기")
        void nonEmptySnapshot_reportsCount() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            assertThat(session.isEmpty()).isFalse();
            assertThat(session.healthyKeyCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("lock-free 병렬 정합성 (REQ-KISGATE-005b/031)")
    class Concurrency {

        @Test
        @DisplayName("다수 가상 스레드 lease+finally release — 카운터 누수 없음(전부 0 복귀, 음수 없음)")
        void concurrentLeaseRelease_noLeakNoNegative() throws InterruptedException {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(K1, K2, K3));
            registry = new KeyLeaseRegistry(healthyKeySelector);
            LeaseSession session = registry.openSession();

            // Act: 300개 가상 스레드가 각각 lease → finally release
            int tasks = 300;
            Thread[] workers = new Thread[tasks];
            for (int i = 0; i < tasks; i++) {
                workers[i] =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            KeyLease lease = session.lease().orElseThrow();
                                            try {
                                                Thread.onSpinWait();
                                            } finally {
                                                lease.release();
                                            }
                                        });
            }
            for (Thread worker : workers) {
                worker.join();
            }

            // Assert: lock-free best-effort라도 release를 finally로 보장했으므로 모든 카운터가 시작값(0)으로 복귀.
            // 미세 쏠림(동시 동일 키 선택)은 허용되나 개별 증감은 AtomicInteger라 유실/음수 없음.
            assertThat(session.inUseCount("isa")).isZero();
            assertThat(session.inUseCount("gold")).isZero();
            assertThat(session.inUseCount("pension")).isZero();
        }
    }
}
