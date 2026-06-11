package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.safemode.SafeModeManager;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HealthyKeySelectorTest {

    @Mock private KisTokenService kisTokenService;
    @Mock private SafeModeManager tokenSafeModeManager;

    private HealthyKeySelector healthyKeySelector;

    @BeforeEach
    void setUp() {
        KisProperties fiveKeyProperties =
                new KisProperties(
                        "https://localhost",
                        "testUser",
                        List.of(
                                credential("isa"),
                                credential("gold"),
                                credential("pension"),
                                credential("stock"),
                                credential("dc")),
                        new KisProperties.RateLimit(20, 20, 10));
        healthyKeySelector =
                new HealthyKeySelector(fiveKeyProperties, kisTokenService, tokenSafeModeManager);
    }

    private static KisAccountCredential credential(String alias) {
        return new KisAccountCredential(alias, "12345678", alias + "-key", alias + "-secret");
    }

    @Nested
    @DisplayName("selectHealthy — 건강 키 산출")
    class SelectHealthy {

        @Test
        @DisplayName("전 키 건강(SafeMode 비활성 + 토큰 확보) — 5키 모두 건강 집합에 포함")
        void allHealthy_returnsAllFiveKeys() {
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            when(kisTokenService.getValidToken(anyString())).thenReturn("token");

            List<KisAccountCredential> healthy = healthyKeySelector.selectHealthy();

            assertThat(healthy).hasSize(5);
            assertThat(healthy)
                    .extracting(KisAccountCredential::alias)
                    .containsExactlyInAnyOrder("isa", "gold", "pension", "stock", "dc");
        }

        @Test
        @DisplayName("SafeMode active 키 — 토큰 시도 없이 죽은 키로 제외 (isActive 게이트 우선)")
        void safeModeActiveKey_excludedWithoutTokenAttempt() {
            // Arrange
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            when(tokenSafeModeManager.isActive("gold")).thenReturn(true);
            when(tokenSafeModeManager.isActive("dc")).thenReturn(true);
            lenient().when(kisTokenService.getValidToken(anyString())).thenReturn("token");

            // Act
            List<KisAccountCredential> healthy = healthyKeySelector.selectHealthy();

            // Assert: gold/dc 제외, isa/pension/stock 건강. SafeMode active 키는 getValidToken 미호출
            assertThat(healthy)
                    .extracting(KisAccountCredential::alias)
                    .containsExactlyInAnyOrder("isa", "pension", "stock");
            verify(kisTokenService, times(0)).getValidToken("gold");
            verify(kisTokenService, times(0)).getValidToken("dc");
        }

        @Test
        @DisplayName("토큰 확보 실패 키 — 죽은 키로 제외 (getValidToken 예외)")
        void tokenAcquisitionFailureKey_excluded() {
            // Arrange
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            when(kisTokenService.getValidToken(anyString())).thenReturn("token");
            when(kisTokenService.getValidToken("stock"))
                    .thenThrow(new KisTokenIssueException("stock", new RuntimeException("dead")));

            // Act
            List<KisAccountCredential> healthy = healthyKeySelector.selectHealthy();

            // Assert
            assertThat(healthy)
                    .extracting(KisAccountCredential::alias)
                    .containsExactlyInAnyOrder("isa", "gold", "pension", "dc");
        }

        @Test
        @DisplayName("전 키 죽음 — 빈 건강 집합 반환 (예외 미전파)")
        void allDead_returnsEmptyList() {
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(true);

            List<KisAccountCredential> healthy = healthyKeySelector.selectHealthy();

            assertThat(healthy).isEmpty();
        }

        @Test
        @DisplayName("헬스 점검 작업 자체가 예외(isActive 실패) — 해당 키만 제외하고 나머지 정상, 예외 미전파")
        void taskThrows_keyExcludedWithoutPropagation() {
            // Arrange: stock 키의 isActive가 예외를 던져 작업 자체가 실패 → ExecutionException 경로
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            when(tokenSafeModeManager.isActive("stock"))
                    .thenThrow(new IllegalStateException("redis down"));
            lenient().when(kisTokenService.getValidToken(anyString())).thenReturn("token");

            // Act
            List<KisAccountCredential> healthy = healthyKeySelector.selectHealthy();

            // Assert: stock 제외, 나머지 4키 건강. 예외 전파되지 않음.
            assertThat(healthy)
                    .extracting(KisAccountCredential::alias)
                    .containsExactlyInAnyOrder("isa", "gold", "pension", "dc");
        }
    }

    @Nested
    @DisplayName("selectHealthy — 비용 모델 (AC-6b)")
    class CostModel {

        @Test
        @DisplayName("키당 getValidToken 시도는 정확히 1회 (종목당 아님)")
        void exactlyOneTokenAttemptPerKey() {
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            when(kisTokenService.getValidToken(anyString())).thenReturn("token");

            healthyKeySelector.selectHealthy();

            verify(kisTokenService, times(1)).getValidToken("isa");
            verify(kisTokenService, times(1)).getValidToken("gold");
            verify(kisTokenService, times(1)).getValidToken("pension");
            verify(kisTokenService, times(1)).getValidToken("stock");
            verify(kisTokenService, times(1)).getValidToken("dc");
        }

        @Test
        @DisplayName("전 키 캐시 히트 — 추가 토큰 API 발급 0회 (getValidToken만, 발급 없음)")
        void allCacheHit_zeroAdditionalIssuance() {
            // getValidToken 캐시 히트는 issueOne(=requestToken)을 트리거하지 않는다.
            // HealthyKeySelector는 getValidToken만 호출하고 직접 requestToken을 호출하지 않는다.
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            when(kisTokenService.getValidToken(anyString())).thenReturn("token");

            List<KisAccountCredential> healthy = healthyKeySelector.selectHealthy();

            assertThat(healthy).hasSize(5);
            verify(kisTokenService, times(5)).getValidToken(anyString());
        }

        @Test
        @DisplayName("키별 병렬 수행 — 직렬 누적되지 않는다 (5키 동시 진입 확인)")
        void parallelExecution_notSerialAccumulation() throws InterruptedException {
            // Arrange: 5키가 동시에 getValidToken에 진입하는지 CountDownLatch로 검증.
            // 직렬이면 5번째 키가 진입하기 전 앞 4키가 순차 완료되어야 하므로 동시 진입 카운트가 5에 도달 못 함.
            when(tokenSafeModeManager.isActive(anyString())).thenReturn(false);
            CountDownLatch allEntered = new CountDownLatch(5);
            CountDownLatch release = new CountDownLatch(1);
            Queue<String> entered = new ConcurrentLinkedQueue<>();
            when(kisTokenService.getValidToken(anyString()))
                    .thenAnswer(
                            inv -> {
                                entered.add(inv.getArgument(0));
                                allEntered.countDown();
                                // 모든 키가 진입할 때까지 블록 — 직렬이면 데드락(타임아웃)
                                boolean released = release.await(2, TimeUnit.SECONDS);
                                assertThat(released).isTrue();
                                return "token";
                            });

            // Act: 별도 스레드로 실행하고 5키 동시 진입을 기다린다
            Thread runner = Thread.ofVirtual().start(healthyKeySelector::selectHealthy);
            boolean allConcurrent = allEntered.await(2, TimeUnit.SECONDS);
            release.countDown();
            runner.join(3000);

            // Assert: 5키가 모두 동시에 진입했다 = 병렬 수행
            assertThat(allConcurrent).isTrue();
            assertThat(entered).hasSize(5);
        }
    }
}
