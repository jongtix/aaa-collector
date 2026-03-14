package com.aaa.collector.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("TraceIdManager")
class TraceIdManagerTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("generate()")
    class GenerateTest {

        @Test
        @DisplayName("UUID 형식의 traceId를 생성한다")
        void generatesUuidFormat() {
            String traceId = TraceIdManager.generate();
            assertThat(traceId)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("생성된 traceId가 MDC에 설정된다")
        void setsTraceIdInMdc() {
            String traceId = TraceIdManager.generate();
            assertThat(MDC.get(TraceIdManager.MDC_KEY_TRACE_ID)).isEqualTo(traceId);
        }

        @Test
        @DisplayName("호출마다 다른 traceId를 생성한다")
        void generatesUniqueTraceIds() {
            String first = TraceIdManager.generate();
            String second = TraceIdManager.generate();
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("set()")
    class SetTest {

        @Test
        @DisplayName("null이면 신규 UUID v4 traceId를 생성한다")
        void nullGeneratesNewUuidV4TraceId() {
            TraceIdManager.set(null);
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("blank이면 신규 UUID v4 traceId를 생성한다")
        void blankGeneratesNewUuidV4TraceId() {
            TraceIdManager.set("   ");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("유효한 UUID v4 traceId는 MDC에 그대로 설정된다")
        void validTraceIdSetInMdc() {
            String traceId = "550e8400-e29b-41d4-a716-446655440000";
            TraceIdManager.set(traceId);
            assertThat(MDC.get(TraceIdManager.MDC_KEY_TRACE_ID)).isEqualTo(traceId);
        }

        @Test
        @DisplayName("UUID v4가 아닌 형식이면 신규 traceId를 생성한다")
        void nonUuidFormatGeneratesNewTraceId() {
            TraceIdManager.set("custom-trace-id-001");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("개행 문자 포함 시 신규 traceId를 생성한다")
        void newlineInTraceIdGeneratesNewTraceId() {
            TraceIdManager.set("550e8400-e29b-41d4\n-a716-446655440000");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("초장문 문자열이면 신규 traceId를 생성한다")
        void tooLongTraceIdGeneratesNewTraceId() {
            TraceIdManager.set("a".repeat(10_000));
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("UUID v1이면 신규 UUID v4 traceId를 생성한다")
        void uuidV1GeneratesNewUuidV4TraceId() {
            TraceIdManager.set("550e8400-e29b-11d4-a716-446655440000");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("UUID v3이면 신규 UUID v4 traceId를 생성한다")
        void uuidV3GeneratesNewUuidV4TraceId() {
            TraceIdManager.set("550e8400-e29b-31d4-a716-446655440000");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("UUID v5이면 신규 UUID v4 traceId를 생성한다")
        void uuidV5GeneratesNewUuidV4TraceId() {
            TraceIdManager.set("550e8400-e29b-51d4-a716-446655440000");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("대문자 UUID v4이면 신규 traceId를 생성한다")
        void uppercaseUuidV4GeneratesNewTraceId() {
            TraceIdManager.set("550E8400-E29B-41D4-A716-446655440000");
            assertThat(TraceIdManager.current())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTest {

        @Test
        @DisplayName("traceId를 MDC에서 제거한다")
        void removesTraceId() {
            TraceIdManager.generate();
            TraceIdManager.clear();
            assertThat(TraceIdManager.current()).isNull();
        }

        @Test
        @DisplayName("다른 MDC 키에 영향을 주지 않는다")
        void preservesOtherMdcKeys() {
            MDC.put("requestId", "req-123");
            TraceIdManager.generate();
            TraceIdManager.clear();
            assertThat(MDC.get("requestId")).isEqualTo("req-123");
        }
    }

    @Nested
    @DisplayName("Virtual Threads MDC 격리")
    class VirtualThreadMdcIsolationTest {

        @Test
        @DisplayName("Virtual Thread는 부모 MDC를 상속하지 않는다")
        void virtualThreadDoesNotInheritParentMdc() throws InterruptedException {
            TraceIdManager.generate();
            AtomicReference<String> childTraceId = new AtomicReference<>();
            Thread virtualThread =
                    Thread.ofVirtual().start(() -> childTraceId.set(TraceIdManager.current()));
            virtualThread.join();
            assertThat(childTraceId.get()).isNull();
        }

        @Test
        @DisplayName("Virtual Thread 내에서 set() 후 current()가 동일한 값을 반환한다")
        void virtualThreadSetAndCurrentReturnsSameValue() throws InterruptedException {
            String traceId = "550e8400-e29b-41d4-a716-446655440000";
            AtomicReference<String> result = new AtomicReference<>();
            Thread virtualThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        TraceIdManager.set(traceId);
                                        result.set(TraceIdManager.current());
                                    });
            virtualThread.join();
            assertThat(result.get()).isEqualTo(traceId);
        }

        @Test
        @DisplayName("Virtual Thread 내에서 generate()가 UUID v4 형식의 traceId를 생성한다")
        void virtualThreadGenerateProducesUuidFormat() throws InterruptedException {
            AtomicReference<String> result = new AtomicReference<>();
            Thread virtualThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        TraceIdManager.generate();
                                        result.set(TraceIdManager.current());
                                    });
            virtualThread.join();
            assertThat(result.get())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("Virtual Thread에서 set()해도 부모 Thread의 MDC는 변경되지 않는다")
        void virtualThreadSetDoesNotAffectParentMdc() throws InterruptedException {
            String parentTraceId = TraceIdManager.generate();
            Thread virtualThread =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            TraceIdManager.set(
                                                    "550e8400-e29b-41d4-a716-446655440000"));
            virtualThread.join();
            assertThat(TraceIdManager.current()).isEqualTo(parentTraceId);
        }
    }
}
