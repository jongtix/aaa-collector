package com.aaa.collector.kis.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.kis.token.KisAccountCredential;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC-COLLECTOR-KISGATE-001 M2(T02) — {@link InMemoryKeyLease} 단위 테스트.
 *
 * <p>{@link KeyLease} 추상화(REQ-KISGATE-007)의 in-memory 구현 검증: alias/credential 노출, release()의 멱등성 및
 * 누수 없음(REQ-KISGATE-005c). release 콜백은 발급 세션이 주입하는 in-use 카운터 감소 동작을 대표한다.
 */
@DisplayName("InMemoryKeyLease — 멱등 release + 누수 없음")
class InMemoryKeyLeaseTest {

    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Nested
    @DisplayName("노출 계약 (alias / credential)")
    class Exposure {

        @Test
        @DisplayName("alias() — credential.alias()를 그대로 노출한다")
        void alias_returnsCredentialAlias() {
            KeyLease lease = new InMemoryKeyLease(GOLD, () -> {});

            assertThat(lease.alias()).isEqualTo("gold");
        }

        @Test
        @DisplayName("credential() — 주입된 자격증명을 그대로 노출한다")
        void credential_returnsInjectedCredential() {
            KeyLease lease = new InMemoryKeyLease(GOLD, () -> {});

            assertThat(lease.credential()).isSameAs(GOLD);
        }
    }

    @Nested
    @DisplayName("release() 멱등성 + 누수 방지")
    class Release {

        @Test
        @DisplayName("release() 1회 — release 콜백을 정확히 1회 실행한다")
        void release_once_runsCallbackOnce() {
            AtomicInteger callbackRuns = new AtomicInteger(0);
            KeyLease lease = new InMemoryKeyLease(GOLD, callbackRuns::incrementAndGet);

            lease.release();

            assertThat(callbackRuns.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("release() 중복 호출 — 멱등하여 콜백을 한 번만 실행(누수 없음)")
        void release_twice_isIdempotent() {
            // Arrange: 콜백이 카운터를 감소시킨다고 가정 — 이중 실행 시 음수가 되는 경계
            AtomicInteger counter = new AtomicInteger(1);
            KeyLease lease = new InMemoryKeyLease(GOLD, counter::decrementAndGet);

            // Act: 이중 release (finally 중복 호출 시뮬레이션)
            lease.release();
            lease.release();

            // Assert: 멱등 — 단 1회만 반영되어 0에서 멈춤(음수 진입 없음)
            assertThat(counter.get()).isZero();
        }

        @Test
        @DisplayName("동시 release 다수 호출 — 멱등하여 콜백은 정확히 1회만 실행")
        void release_concurrent_runsCallbackExactlyOnce() throws InterruptedException {
            AtomicInteger callbackRuns = new AtomicInteger(0);
            KeyLease lease = new InMemoryKeyLease(GOLD, callbackRuns::incrementAndGet);

            // Act: 여러 가상 스레드가 같은 lease를 동시에 release
            int threads = 64;
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = Thread.ofVirtual().start(lease::release);
            }
            for (Thread worker : workers) {
                worker.join();
            }

            // Assert: 멱등 — 동시 다수 호출에도 콜백은 단 1회만 실행
            assertThat(callbackRuns.get()).isEqualTo(1);
        }
    }
}
