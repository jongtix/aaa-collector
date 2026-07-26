package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @ConditionalOnBean}/{@code @ConditionalOnMissingBean} 오용 재발 방지 가드 (aaa-infra#120).
 *
 * <p><b>배경</b>: {@code DbGrantCheckConfig}(일반 {@code @Configuration})가
 * {@code @ConditionalOnBean(JdbcTemplate.class)}를 썼던 시절, Spring Boot는 사용자 {@code @Configuration}의
 * 조건을 {@code JdbcTemplateAutoConfiguration}이 {@code JdbcTemplate} 빈을 등록하기 <b>이전</b>에 평가한다 — 사용자 구성
 * 클래스가 자동 구성보다 먼저 처리되는 Spring Boot 컨텍스트 초기화 순서 때문이다. 그 결과 실제 DataSource가 있는 프로덕션 환경에서도 조건이 항상 미충족으로
 * 평가되어, DB 권한 self-check(fail-fast, ADR-026 결정 4, SPEC-INFRA-DBGRANT-001) 관련 빈 3개가 90일간 단 한 번도 등록되지
 * 않았다(실측: 프로덕션 로그에 {@code DbGrantCheckRunner} 출력 0건). "{@code @ConditionalOnBean}은
 * auto-configuration 클래스에서만 신뢰할 수 있다"는 잘 알려진 Spring Boot 함정이다.
 *
 * <p><b>허용 예외 — {@code @AutoConfiguration} 클래스</b>: {@code RedisHealthConfig}(aaa-infra#89)는
 * {@code @AutoConfiguration(after = RedisAutoConfiguration.class)}로 선언되어 {@code
 * AutoConfiguration.imports}에 등록된 진짜 auto-configuration 클래스다 — 이 경우 Spring Boot가 지정된 순서({@code
 * after}) 이후에 조건을 평가함을 보장하므로 {@code @ConditionalOnBean}이 안전하고 정상 동작한다(그 클래스 Javadoc에 실측 근거 문서화됨).
 * 따라서 이 가드는 {@code @ConditionalOnBean}/{@code @ConditionalOnMissingBean} 자체를 전면 금지하지 않고, <b>일반
 * {@code @Configuration} 클래스(비-auto-configuration)에서의 사용만</b> 위반으로 판정한다 — 실제 함정은 평가 순서 보장이 없는
 * 컨텍스트에서의 사용이지, 애너테이션 자체가 아니다.
 *
 * <p>대안: 일반 {@code @Configuration} 클래스는 {@code @ConditionalOnProperty}(환경 프로퍼티만으로 즉시 평가, 컨텍스트 초기화
 * 순서 무관 — {@code DbGrantCheckConfig} 참고) 또는 {@code @Profile}을 사용할 것. 순서 보장이 반드시 필요하다면 {@code
 * RedisHealthConfig}처럼 진짜 {@code @AutoConfiguration}으로 전환하고 {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}에 등록할 것.
 *
 * <p>Spring 컨텍스트 불필요 — 순수 클래스파일 스캔이므로 {@code @SpringBootTest} 없이 실행된다({@link
 * Tier1InsertIgnoreGuardTest}와 동일 원칙).
 *
 * @see <a href="https://github.com/jongtix/aaa-infra/issues/120">aaa-infra#120</a>
 */
@DisplayName("@ConditionalOnBean/@ConditionalOnMissingBean 오용 금지 가드 (aaa-infra#120)")
class ConditionalOnBeanGuardTest {

    private static final String CONDITIONAL_ON_BEAN =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnBean";
    private static final String CONDITIONAL_ON_MISSING_BEAN =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean";
    private static final String AUTO_CONFIGURATION =
            "org.springframework.boot.autoconfigure.AutoConfiguration";

    @Test
    @DisplayName("일반 @Configuration 클래스는 @ConditionalOnBean/@ConditionalOnMissingBean을 쓰면 안 된다")
    void nonAutoConfigurationClasses_mustNotUseConditionalOnBean() {
        // Arrange
        Iterable<JavaClass> scannedClasses =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.aaa.collector");

        List<String> violations = new ArrayList<>();
        int classCount = 0;

        // Act
        for (JavaClass javaClass : scannedClasses) {
            classCount++;
            if (javaClass.isAnnotatedWith(AUTO_CONFIGURATION)) {
                continue; // 진짜 auto-configuration — 평가 순서 보장, 안전(aaa-infra#89 RedisHealthConfig)
            }
            collectViolations(javaClass, violations);
        }

        // Assert
        assertThat(classCount)
                .as("com.aaa.collector 클래스 스캔 결과가 비어 있으면 가드가 동작하지 않는다")
                .isGreaterThan(0);

        if (!violations.isEmpty()) {
            fail(
                    "일반 @Configuration 클래스의 @ConditionalOnBean/@ConditionalOnMissingBean 오용 발견 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] 컨텍스트 초기화 순서와 무관하게 즉시 평가되는 @ConditionalOnProperty 또는"
                            + " @Profile로 교체할 것(DbGrantCheckConfig 참고). 순서 보장이 꼭 필요하면"
                            + " @AutoConfiguration(after = ...)으로 전환하고"
                            + " AutoConfiguration.imports에 등록할 것(RedisHealthConfig 참고)."
                            + "\n[근거] aaa-infra#120 — 일반 @Configuration의 @ConditionalOnBean은"
                            + " 대상 auto-configuration보다 먼저 평가되어 실제 DataSource/빈이 있는 환경에서도"
                            + " 항상 미충족으로 판정된다(DbGrantCheckConfig 90일 무출력 실측 사례).");
        }
    }

    /** 클래스 자신과 모든 메서드에서 금지 애너테이션 사용을 검사하여 위반 항목을 {@code violations}에 추가한다. */
    private void collectViolations(JavaClass javaClass, List<String> violations) {
        if (javaClass.isAnnotatedWith(CONDITIONAL_ON_BEAN)) {
            violations.add(
                    "[VIOLATION] "
                            + javaClass.getFullName()
                            + " — 클래스 레벨 @ConditionalOnBean (비-auto-configuration)");
        }
        if (javaClass.isAnnotatedWith(CONDITIONAL_ON_MISSING_BEAN)) {
            violations.add(
                    "[VIOLATION] "
                            + javaClass.getFullName()
                            + " — 클래스 레벨 @ConditionalOnMissingBean (비-auto-configuration)");
        }
        for (JavaMethod method : javaClass.getMethods()) {
            if (method.isAnnotatedWith(CONDITIONAL_ON_BEAN)) {
                violations.add(
                        "[VIOLATION] "
                                + javaClass.getSimpleName()
                                + "#"
                                + method.getName()
                                + " — @ConditionalOnBean (비-auto-configuration 클래스)");
            }
            if (method.isAnnotatedWith(CONDITIONAL_ON_MISSING_BEAN)) {
                violations.add(
                        "[VIOLATION] "
                                + javaClass.getSimpleName()
                                + "#"
                                + method.getName()
                                + " — @ConditionalOnMissingBean (비-auto-configuration 클래스)");
            }
        }
    }
}
