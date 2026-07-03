package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

/**
 * 통합 태그 누락 회귀 가드 테스트 (SPEC-COLLECTOR-TESTLAYER-001 REQ-TESTLAYER-015, AC-7).
 *
 * <p>규칙: {@code org.testcontainers.junit.jupiter.Container}로 애노테이트된 필드를 하나라도 가진 테스트 클래스는 클래스 레벨에
 * {@code @Tag("integration")}를 반드시 가져야 한다. 이 조합("컨테이너 필드 보유 + 통합 태그 부재")을 정확히 판별하는 기존 ArchUnit 규칙은
 * 없으므로({@link Tier1InsertIgnoreGuardTest}·{@link KisApiExecutorGateGuardTest}는 각각 다른 판별 대상) 신규 커스텀
 * 로직으로 작성한다(REQ-TESTLAYER-015 — "참고해 신규 작성").
 *
 * <p><b>{@code @Tag} {@code @Repeatable} 엣지케이스</b>: JUnit 5 {@code @Tag}는 {@code @Repeatable}이므로 한
 * 클래스에 태그가 2개 이상이면 바이트코드상 {@code @Tags} 컨테이너 애노테이션으로 감싸진다. 본 가드는 {@code @Tag} 단독과 {@code @Tags} 래핑
 * 두 경우를 모두 인식한다(각 케이스는 아래 fixture로 RED/GREEN 재현).
 *
 * <p>이 가드 자체는 순수 클래스파일 스캔(Testcontainers 컨테이너를 인스턴스화하지 않음)이므로 pre-push의 단위 테스트 집합에 포함되어도 성능에 영향을 주지
 * 않는다.
 */
@DisplayName("Testcontainers @Container 필드 보유 클래스의 통합 태그 누락 가드 (REQ-TESTLAYER-015, AC-7)")
class IntegrationTagGuardTest {

    private static final String CONTAINER_ANNOTATION = "org.testcontainers.junit.jupiter.Container";
    private static final String INTEGRATION_TAG = "integration";

    @Test
    @DisplayName("GREEN — 프로덕션/테스트 코드: @Container 필드 보유 클래스는 전부 @Tag(\"integration\")를 갖는다")
    void containerFieldClasses_allHaveIntegrationTag_passes() {
        // Arrange
        Iterable<JavaClass> scannedClasses =
                new ClassFileImporter().importPackages("com.aaa.collector");

        List<String> violations = new ArrayList<>();
        int classCount = 0;

        // Act — fixture 패키지(arch.IntegrationTagGuardTest 자기 자신)는 RED 재현용이므로 GREEN 스캔에서 제외한다.
        for (JavaClass javaClass : scannedClasses) {
            if (javaClass.getName().startsWith(IntegrationTagGuardTest.class.getName())) {
                continue;
            }
            classCount++;
            if (hasContainerField(javaClass) && !hasIntegrationTag(javaClass)) {
                violations.add(javaClass.getFullName());
            }
        }

        // Assert
        assertThat(classCount)
                .as("com.aaa.collector 클래스 스캔 결과가 비어 있으면 가드가 동작하지 않는다")
                .isGreaterThan(0);

        if (!violations.isEmpty()) {
            fail(
                    "@Container 필드를 보유했지만 @Tag(\"integration\")가 없는 클래스 발견 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] 클래스 레벨에 @Tag(\"integration\")를 추가할 것"
                            + " (SPEC-COLLECTOR-TESTLAYER-001 REQ-TESTLAYER-001/015).");
        }
    }

    @Test
    @DisplayName("RED 입증 — @Container 필드 보유 + 태그 부재 fixture를 규칙이 탐지한다")
    void rule_detectsMissingTag_onContainerFieldWithoutTag() {
        // Arrange
        JavaClass fixture = new ClassFileImporter().importClass(Fixtures.ContainerWithoutTag.class);

        // Act & Assert
        assertThat(hasContainerField(fixture)).isTrue();
        assertThat(hasIntegrationTag(fixture))
                .as("태그 미부여 fixture는 통합 태그를 갖지 않아야 한다 (RED 입증)")
                .isFalse();
    }

    @Test
    @DisplayName("GREEN 입증 — @Container 필드 + 단독 @Tag(\"integration\") fixture는 위반이 아니다")
    void rule_passesForSingleTag() {
        // Arrange
        JavaClass fixture =
                new ClassFileImporter().importClass(Fixtures.ContainerWithSingleTag.class);

        // Act & Assert
        assertThat(hasContainerField(fixture)).isTrue();
        assertThat(hasIntegrationTag(fixture))
                .as("@Tag(\"integration\") 단독 부여 fixture는 통합 태그를 가져야 한다")
                .isTrue();
    }

    @Test
    @DisplayName(
            "GREEN 입증 — @Container 필드 + 다중 @Tag(@Tags 래핑) fixture도 통합 태그를 인식한다 (@Repeatable 엣지케이스)")
    void rule_passesForRepeatedTagsWrapper() {
        // Arrange
        JavaClass fixture =
                new ClassFileImporter().importClass(Fixtures.ContainerWithMultipleTags.class);

        // Act & Assert
        assertThat(hasContainerField(fixture)).isTrue();
        assertThat(hasIntegrationTag(fixture))
                .as("@Tags로 래핑된 다중 태그 중 \"integration\"이 있으면 통합 태그 보유로 인식해야 한다 (@Repeatable 엣지케이스)")
                .isTrue();
    }

    /** 클래스가 {@code @Container} 애노테이트 필드를 하나라도 갖는지 판별한다. */
    private boolean hasContainerField(JavaClass javaClass) {
        for (JavaField field : javaClass.getFields()) {
            if (field.isAnnotatedWith(CONTAINER_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 클래스가 통합 태그({@code @Tag("integration")})를 보유하는지 판별한다. {@code @Tag} 단독 형태와 {@code @Repeatable}에
     * 의해 컴파일러가 생성하는 {@code @Tags} 래핑 형태를 모두 인식한다.
     */
    private boolean hasIntegrationTag(JavaClass javaClass) {
        Optional<JavaAnnotation<JavaClass>> singleTag =
                javaClass.tryGetAnnotationOfType(Tag.class.getName());
        if (singleTag.isPresent() && isIntegrationValue(singleTag.get())) {
            return true;
        }

        Optional<JavaAnnotation<JavaClass>> tagsWrapper =
                javaClass.tryGetAnnotationOfType(Tags.class.getName());
        if (tagsWrapper.isEmpty()) {
            return false;
        }

        Optional<Object> valueAttr = tagsWrapper.get().get("value");
        if (valueAttr.isEmpty() || !(valueAttr.get() instanceof JavaAnnotation<?>[] nestedTags)) {
            return false;
        }

        for (JavaAnnotation<?> nestedTag : nestedTags) {
            if (isIntegrationValue(nestedTag)) {
                return true;
            }
        }
        return false;
    }

    /** 단일 {@code @Tag} 애노테이션의 {@code value} 속성이 {@code "integration"}인지 확인한다. */
    private boolean isIntegrationValue(JavaAnnotation<?> tagAnnotation) {
        Optional<Object> valueAttr = tagAnnotation.get("value");
        return valueAttr.isPresent() && INTEGRATION_TAG.equals(valueAttr.get());
    }

    /**
     * RED/GREEN 입증용 fixture — 실제로 실행되지 않으며(클래스파일 스캔 대상일 뿐) {@code @Container} 필드 + 태그 조합 3가지 케이스를
     * 재현한다. 필드 타입은 임의 타입({@code String})으로 두어 Docker/Testcontainers 런타임 의존 없이 순수 애노테이션 존재 여부만
     * 검사한다(실제 컨테이너 인스턴스화 불필요).
     */
    @SuppressWarnings("unused")
    static final class Fixtures {

        private Fixtures() {}

        /** 태그 미부여 — RED. */
        static final class ContainerWithoutTag {
            @org.testcontainers.junit.jupiter.Container
            private static final String CONTAINER_FIELD = "dummy";
        }

        /** 단독 {@code @Tag("integration")} — GREEN. */
        @Tag(INTEGRATION_TAG)
        static final class ContainerWithSingleTag {
            @org.testcontainers.junit.jupiter.Container
            private static final String CONTAINER_FIELD = "dummy";
        }

        /** 다중 태그({@code @Tags} 래핑, {@code integration} 포함) — GREEN, {@code @Repeatable} 엣지케이스. */
        @Tag(INTEGRATION_TAG)
        @Tag("slow")
        static final class ContainerWithMultipleTags {
            @org.testcontainers.junit.jupiter.Container
            private static final String CONTAINER_FIELD = "dummy";
        }
    }
}
