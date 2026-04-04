package com.aaa.collector.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.aaa.collector.common.logging.SafeMdc;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.slf4j.MDC;

/**
 * MDC 및 패키지 아키텍처 규칙을 검증하는 ArchUnit 테스트.
 *
 * <p>스캔 대상: main 소스만 (테스트 클래스 제외).
 */
@SuppressWarnings({
    "PMD.TestClassWithoutTestCases", // ArchUnit은 @ArchTest로 규칙을 선언하며 @Test 메서드를 사용하지 않음
    "PMD.FieldNamingConventions" // ArchUnit @ArchTest 필드는 규칙 가독성을 위해 camelCase 관례 사용
})
@AnalyzeClasses(
        packages = "com.aaa.collector",
        importOptions = ImportOption.DoNotIncludeTests.class)
class MdcArchitectureTest {

    @ArchTest
    static final ArchRule noDirectMdcPutExceptSafeMdc =
            noClasses()
                    .that()
                    .resideInAPackage("com.aaa.collector..")
                    .and()
                    .areNotAssignableTo(SafeMdc.class)
                    .should()
                    .callMethod(MDC.class, "put", String.class, String.class)
                    .because(
                            "MDC.put()은 SafeMdc.put()을 통해서만 호출해야 한다."
                                    + " SafeMdc는 MaskingLevel 등록 키를 자동 마스킹하여 민감 데이터 누출을 방지한다.");

    @ArchTest
    static final ArchRule noDirectMdcRemoveExceptSafeMdc =
            noClasses()
                    .that()
                    .resideInAPackage("com.aaa.collector..")
                    .and()
                    .areNotAssignableTo(SafeMdc.class)
                    .should()
                    .callMethod(MDC.class, "remove", String.class)
                    .because(
                            "MDC.remove()는 SafeMdc.remove()를 통해서만 호출해야 한다."
                                    + " 직접 호출 시 SafeMdc의 키 관리 정책을 우회할 수 있다.");

    @ArchTest
    static final ArchRule noDirectMdcClearExceptSafeMdc =
            noClasses()
                    .that()
                    .resideInAPackage("com.aaa.collector..")
                    .and()
                    .areNotAssignableTo(SafeMdc.class)
                    .should()
                    .callMethod(MDC.class, "clear")
                    .because(
                            "MDC.clear()는 SafeMdc.clear()를 통해서만 호출해야 한다."
                                    + " 직접 호출 시 SafeMdc의 키 관리 정책을 우회할 수 있다.");

    @ArchTest
    static final ArchRule noCircularDependenciesBetweenFeaturePackages =
            slices().matching("com.aaa.collector.(*)..")
                    .should()
                    .beFreeOfCycles()
                    .because("피처 패키지 간 순환 의존성은 모듈 독립성을 해친다.");

    @ArchTest
    static final ArchRule commonPackageDoesNotDependOnFeaturePackages =
            noClasses()
                    .that()
                    .resideInAPackage("com.aaa.collector.common..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages(
                            "com.aaa.collector.common..",
                            "java..",
                            "jakarta..",
                            "lombok..",
                            "org.slf4j..",
                            "org.springframework..",
                            "com.fasterxml..")
                    .because("common 패키지는 공용 유틸리티로서 특정 피처 패키지에 의존해서는 안 된다.");
}
