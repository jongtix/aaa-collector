package com.aaa.collector.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * InserterProperties YAML 바인딩 통합 테스트 (SPEC-COLLECTOR-INSERT-001 T-004, REQ-INSERT-010).
 *
 * <p>{@code aaa.inserter.chunk-size}가 최상위 {@code aaa:} 블록에 정확히 배치되어 {@link InserterProperties}에
 * 바인딩됨을 검증한다 (aaa.fred 하위 오배치 선례 재발 방지).
 */
@DisplayName("InserterProperties YAML 바인딩 통합 테스트 (REQ-INSERT-010)")
class InserterPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("AC-5 — aaa.inserter.chunk-size=1000이 InserterProperties.chunkSize에 바인딩된다")
    void chunkSize_bindsFromAaaInserterPrefix() {
        contextRunner
                .withPropertyValues("aaa.inserter.chunk-size=1000")
                .run(
                        ctx -> {
                            InserterProperties props = ctx.getBean(InserterProperties.class);
                            assertThat(props.getChunkSize()).isEqualTo(1000);
                        });
    }

    @Test
    @DisplayName("AC-5 — aaa.inserter 미설정 시 기본값 1000 유지")
    void noProperties_defaultChunkSize1000() {
        contextRunner.run(
                ctx -> {
                    InserterProperties props = ctx.getBean(InserterProperties.class);
                    assertThat(props.getChunkSize()).isEqualTo(1000);
                });
    }

    @Test
    @DisplayName("AC-5 — 커스텀 청크 크기 2000 바인딩")
    void customChunkSize_binds() {
        contextRunner
                .withPropertyValues("aaa.inserter.chunk-size=2000")
                .run(
                        ctx -> {
                            InserterProperties props = ctx.getBean(InserterProperties.class);
                            assertThat(props.getChunkSize()).isEqualTo(2000);
                        });
    }

    @EnableConfigurationProperties(InserterProperties.class)
    static class Config {}
}
