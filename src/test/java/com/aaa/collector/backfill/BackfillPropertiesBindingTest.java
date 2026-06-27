package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * BackfillProperties YAML 바인딩 통합 테스트 (SPEC-COLLECTOR-BACKFILL-002 T3, SPEC-COLLECTOR-BACKFILL-004
 * T3, SPEC-COLLECTOR-BACKFILL-005 T5).
 *
 * <p>{@code aaa.backfill.*} 키가 올바른 부모 아래에 배치되어 {@link BackfillProperties}에 바인딩됨을 검증한다. {@code
 * per-run-completion-cap} → {@code per-table-completion-cap} 리네임(REQ-BACKFILL-064a) 바인딩 정합 확인.
 * {@code floor-date} ISO-8601 문자열 → {@link LocalDate} 바인딩 확인 (AC-8).
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
            "AC-5.3 — aaa.backfill.per-table-completion-cap이 BackfillProperties에 바인딩된다"
                    + " (REQ-BACKFILL-064a)")
    void perTableCompletionCap_bindsFromAaaBackfillPrefix() {
        contextRunner
                .withPropertyValues("aaa.backfill.per-table-completion-cap=25")
                .run(
                        ctx -> {
                            BackfillProperties props = ctx.getBean(BackfillProperties.class);
                            assertThat(props.getPerTableCompletionCap()).isEqualTo(25);
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
    @DisplayName("AC-5.3 — aaa.backfill 미설정 시 perTableCompletionCap 기본값 10이 유지된다")
    void noProperties_defaultsApply() {
        contextRunner.run(
                ctx -> {
                    BackfillProperties props = ctx.getBean(BackfillProperties.class);
                    assertThat(props.getPerTableCompletionCap()).isEqualTo(10);
                    assertThat(props.getMaxWindowsPerTarget()).isEqualTo(120);
                });
    }

    @Test
    @DisplayName("AC-8 — aaa.backfill.floor-date ISO-8601 문자열이 LocalDate로 바인딩된다")
    void floorDate_bindsFromIso8601String() {
        contextRunner
                .withPropertyValues("aaa.backfill.floor-date=2000-01-01")
                .run(
                        ctx -> {
                            BackfillProperties props = ctx.getBean(BackfillProperties.class);
                            assertThat(props.getFloorDate()).isEqualTo(LocalDate.of(2000, 1, 1));
                        });
    }

    @Test
    @DisplayName("AC-1 — aaa.backfill 미설정 시 floorDate 기본값 1950-01-01이 유지된다")
    void noFloorDateProperty_defaultIs1950() {
        contextRunner.run(
                ctx -> {
                    BackfillProperties props = ctx.getBean(BackfillProperties.class);
                    assertThat(props.getFloorDate()).isEqualTo(LocalDate.of(1950, 1, 1));
                });
    }

    @EnableConfigurationProperties(BackfillProperties.class)
    static class Config {}
}
