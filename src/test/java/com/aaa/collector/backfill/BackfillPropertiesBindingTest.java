package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * BackfillProperties YAML 바인딩 통합 테스트 (SPEC-COLLECTOR-BACKFILL-002 T3, AC-5.3, REQ-BACKFILL-058b).
 *
 * <p>{@code aaa.backfill.*} 키가 올바른 부모 아래에 배치되어 {@link BackfillProperties}에 바인딩됨을 검증한다. 기존 {@code
 * aaa.fred:} 하위 오배치 교정 결과 확인.
 */
@DisplayName("BackfillProperties YAML 바인딩 통합 테스트")
class BackfillPropertiesBindingTest {

    /**
     * ApplicationContextRunner: BackfillProperties만 활성화해 경량 컨텍스트 로드. 신규 JpaRepository
     * 없으므로 @MockitoBean 추가 불필요.
     */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName(
            "AC-5.3 — aaa.backfill.per-run-completion-cap이 BackfillProperties에 바인딩된다 (REQ-BACKFILL-058b)")
    void perRunCompletionCap_bindsFromAaaBackfillPrefix() {
        contextRunner
                .withPropertyValues("aaa.backfill.per-run-completion-cap=25")
                .run(
                        ctx -> {
                            BackfillProperties props = ctx.getBean(BackfillProperties.class);
                            assertThat(props.getPerRunCompletionCap()).isEqualTo(25);
                        });
    }

    @Test
    @DisplayName(
            "AC-5.3 — aaa.backfill.max-windows-per-target이 BackfillProperties에 바인딩된다 (REQ-BACKFILL-053a)")
    void maxWindowsPerTarget_bindsFromAaaBackfillPrefix() {
        contextRunner
                .withPropertyValues("aaa.backfill.max-windows-per-target=200")
                .run(
                        ctx -> {
                            BackfillProperties props = ctx.getBean(BackfillProperties.class);
                            assertThat(props.getMaxWindowsPerTarget()).isEqualTo(200);
                        });
    }

    @Test
    @DisplayName("AC-5.3 — aaa.backfill.stale-window-threshold이 BackfillProperties에 바인딩된다")
    void staleWindowThreshold_bindsFromAaaBackfillPrefix() {
        contextRunner
                .withPropertyValues("aaa.backfill.stale-window-threshold=5")
                .run(
                        ctx -> {
                            BackfillProperties props = ctx.getBean(BackfillProperties.class);
                            assertThat(props.getStaleWindowThreshold()).isEqualTo(5);
                        });
    }

    @Test
    @DisplayName("AC-5.3 — aaa.backfill 미설정 시 perRunCompletionCap 기본값 10이 유지된다")
    void noProperties_defaultsApply() {
        contextRunner.run(
                ctx -> {
                    BackfillProperties props = ctx.getBean(BackfillProperties.class);
                    assertThat(props.getPerRunCompletionCap()).isEqualTo(10);
                    assertThat(props.getMaxWindowsPerTarget()).isEqualTo(120);
                });
    }

    @EnableConfigurationProperties(BackfillProperties.class)
    static class Config {}
}
