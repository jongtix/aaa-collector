package com.aaa.collector.common.safemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SafeModeManagerTest {

    @Mock private SafeModeRepository safeModeRepository;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private SafeModeManager legacyManager() {
        // 정책 없음(WebSocket 컨텍스트) — TTL/백오프 무변경 레거시 동작
        return new SafeModeManager(safeModeRepository, registry, "ws");
    }

    private SafeModeManager tokenManager() {
        // 정책 있음(token 컨텍스트) — TTL/백오프 활성 (D-B)
        SafeModeBackoffPolicy policy =
                new SafeModeBackoffPolicy(Duration.ofHours(1), Duration.ofHours(4));
        return new SafeModeManager(safeModeRepository, registry, "token", policy);
    }

    // ── 정책 없음(WebSocket) — 기존 동작 보존 ────────────────────────────────

    @Nested
    @DisplayName("정책 없음(WebSocket 컨텍스트) — 레거시 동작 보존")
    class LegacyBehavior {

        @Test
        @DisplayName("enter — TTL 없이 setSafeMode(alias, true) 호출, 활성 게이트 없음")
        void enter_withoutPolicy_callsSetSafeModeWithoutTtl() {
            SafeModeManager manager = legacyManager();
            String alias = "ws-alias";
            Throwable cause = new RuntimeException("ws issue");

            manager.enter(alias, cause);

            verify(safeModeRepository).setSafeMode(alias, true);
        }

        @Test
        @DisplayName("exit — setSafeMode(alias, false) 호출, 백오프 삭제 없음")
        void exit_withoutPolicy_callsSetSafeModeFalseWithoutBackoffDelete() {
            SafeModeManager manager = legacyManager();
            String alias = "ws-alias";

            manager.exit(alias);

            verify(safeModeRepository).setSafeMode(alias, false);
            verify(safeModeRepository, never()).deleteBackoffLevel(alias);
        }

        @Test
        @DisplayName("resetBackoff — 정책 없으면 백오프 삭제 미호출(no-op)")
        void resetBackoff_withoutPolicy_doesNothing() {
            SafeModeManager manager = legacyManager();

            manager.resetBackoff("ws-alias");

            verify(safeModeRepository, never()).deleteBackoffLevel("ws-alias");
        }

        @Test
        @DisplayName("isActive — safeModeRepository.isSafeMode(alias) 위임")
        void isActive_delegatesToRepository() {
            SafeModeManager manager = legacyManager();
            when(safeModeRepository.isSafeMode("ws-alias")).thenReturn(true);

            assertThat(manager.isActive("ws-alias")).isTrue();
        }

        @Test
        @DisplayName("enter — enter_total 카운터 1 증가")
        void enter_incrementsEnterCounter() {
            SafeModeManager manager = legacyManager();

            manager.enter("isa", new RuntimeException("test"));

            double count =
                    registry.get("aaa_collector_safe_mode_enter_total")
                            .tags("module", "ws", "alias", "isa")
                            .counter()
                            .count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("exit — exit_total 카운터 1 증가")
        void exit_incrementsExitCounter() {
            SafeModeManager manager = legacyManager();

            manager.exit("isa");

            double count =
                    registry.get("aaa_collector_safe_mode_exit_total")
                            .tags("module", "ws", "alias", "isa")
                            .counter()
                            .count();
            assertThat(count).isEqualTo(1.0);
        }
    }

    // ── 정책 있음(token) — REQ-SAFEMODE-002/003/004/005/008 ───────────────────

    @Nested
    @DisplayName("정책 있음(token 컨텍스트) — TTL·백오프 라이프사이클")
    class TokenPolicyBehavior {

        @Test
        @DisplayName("enter — 최초 진입(백오프 수준 없음, 비활성): 초기 TTL 1시간 저장, 백오프 레벨 0 저장(AC-1)")
        void enter_firstEntry_appliesInitialTtlAndSavesLevelZero() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";
            when(safeModeRepository.isSafeMode(alias)).thenReturn(false);
            when(safeModeRepository.getBackoffLevel(alias)).thenReturn(Optional.empty());

            manager.enter(alias, new RuntimeException("fail"));

            verify(safeModeRepository).setSafeMode(alias, true, Duration.ofHours(1));
            verify(safeModeRepository).saveBackoffLevel(alias, 0);
        }

        @Test
        @DisplayName("enter — 만료 후 최초 재진입(백오프 레벨 0 존재): TTL 2시간으로 확대, 레벨 1 저장(AC-2)")
        void enter_reentryAfterExpiryWithLevelZero_doublesTo2h() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";
            when(safeModeRepository.isSafeMode(alias)).thenReturn(false);
            when(safeModeRepository.getBackoffLevel(alias)).thenReturn(Optional.of(0));

            manager.enter(alias, new RuntimeException("fail"));

            verify(safeModeRepository).setSafeMode(alias, true, Duration.ofHours(2));
            verify(safeModeRepository).saveBackoffLevel(alias, 1);
        }

        @Test
        @DisplayName("enter — 레벨 1 존재: TTL 4시간(상한)으로 확대, 레벨 2 저장(AC-2)")
        void enter_reentryWithLevelOne_expandsTo4hCap() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";
            when(safeModeRepository.isSafeMode(alias)).thenReturn(false);
            when(safeModeRepository.getBackoffLevel(alias)).thenReturn(Optional.of(1));

            manager.enter(alias, new RuntimeException("fail"));

            verify(safeModeRepository).setSafeMode(alias, true, Duration.ofHours(4));
            verify(safeModeRepository).saveBackoffLevel(alias, 2);
        }

        @Test
        @DisplayName("enter — 레벨 2(이미 상한) 존재: TTL이 4시간에서 더 확대되지 않고 고정(AC-2)")
        void enter_reentryWithLevelTwo_staysCappedAt4h() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";
            when(safeModeRepository.isSafeMode(alias)).thenReturn(false);
            when(safeModeRepository.getBackoffLevel(alias)).thenReturn(Optional.of(2));

            manager.enter(alias, new RuntimeException("fail"));

            verify(safeModeRepository).setSafeMode(alias, true, Duration.ofHours(4));
            verify(safeModeRepository).saveBackoffLevel(alias, 3);
        }

        @Test
        @DisplayName(
                "enter — 활성 중(TTL 미만료) 재진입: no-op — setSafeMode/saveBackoffLevel 미호출, enter_total 미증가"
                        + "(REQ-SAFEMODE-008)")
        void enter_whenAlreadyActive_isNoOp() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";
            when(safeModeRepository.isSafeMode(alias)).thenReturn(true);

            manager.enter(alias, new RuntimeException("fail"));

            verify(safeModeRepository, never()).setSafeMode(alias, true);
            verify(safeModeRepository, never())
                    .setSafeMode(
                            org.mockito.ArgumentMatchers.eq(alias),
                            org.mockito.ArgumentMatchers.eq(true),
                            org.mockito.ArgumentMatchers.any());
            verify(safeModeRepository, never())
                    .saveBackoffLevel(
                            org.mockito.ArgumentMatchers.eq(alias),
                            org.mockito.ArgumentMatchers.anyInt());
            assertThat(
                            registry.find("aaa_collector_safe_mode_enter_total")
                                    .tags("module", "token", "alias", alias)
                                    .counter())
                    .isNull();
        }

        @Test
        @DisplayName("enter — 실제 진입(비활성→활성) 시에만 enter_total 카운터 증가")
        void enter_actualEntry_incrementsEnterCounter() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";
            when(safeModeRepository.isSafeMode(alias)).thenReturn(false);
            when(safeModeRepository.getBackoffLevel(alias)).thenReturn(Optional.empty());

            manager.enter(alias, new RuntimeException("fail"));

            double count =
                    registry.get("aaa_collector_safe_mode_enter_total")
                            .tags("module", "token", "alias", alias)
                            .counter()
                            .count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName(
                "exit — setSafeMode(alias, false) 호출, exit_total 증가 (백오프 리셋은 exit()에서 하지 않음, D-F)")
        void exit_callsSetSafeModeFalse() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";

            manager.exit(alias);

            verify(safeModeRepository).setSafeMode(alias, false);
            double count =
                    registry.get("aaa_collector_safe_mode_exit_total")
                            .tags("module", "token", "alias", alias)
                            .counter()
                            .count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("resetBackoff — 정책 있으면 deleteBackoffLevel 호출(REQ-SAFEMODE-005, D-F)")
        void resetBackoff_withPolicy_deletesBackoffLevel() {
            SafeModeManager manager = tokenManager();
            String alias = "isa";

            manager.resetBackoff(alias);

            verify(safeModeRepository).deleteBackoffLevel(alias);
        }

        @Test
        @DisplayName("isActive — safeModeRepository.isSafeMode(alias) 위임(변경 없음)")
        void isActive_delegatesToRepository() {
            SafeModeManager manager = tokenManager();
            when(safeModeRepository.isSafeMode("isa")).thenReturn(true);

            assertThat(manager.isActive("isa")).isTrue();
        }
    }
}
