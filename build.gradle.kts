plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    pmd
}

group = "com.aaa"
version = property("version") as String
description = "aaa-collector"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Runtime ---
    implementation(libs.spring.boot.starter.web)          // 내장 Tomcat, Spring MVC, Jackson
    implementation(libs.spring.boot.starter.actuator)     // 헬스체크 엔드포인트
    implementation(libs.spring.boot.starter.data.redis)   // Redis 연동

    // --- Lombok ---
    compileOnly(libs.lombok)                                     // 보일러플레이트 제거 (@Slf4j, @RequiredArgsConstructor 등)
    annotationProcessor(libs.lombok)

    // --- Test ---
    testImplementation(libs.spring.boot.starter.test)     // JUnit 5, Mockito, AssertJ, Spring Test
    testImplementation(libs.archunit)                      // 아키텍처 규칙 검증
    testImplementation(libs.wiremock.spring.boot)          // 외부 API mock 서버
    testImplementation(libs.testcontainers)                // 컨테이너 기반 통합 테스트
    testImplementation(libs.testcontainers.junit)          // Testcontainers JUnit 5 확장
    testImplementation(libs.pmd.java)                      // PMD RuleSetLoader API — ruleset.xml 파싱 검증
    testRuntimeOnly(libs.junit.platform.launcher)          // IDE/Gradle 테스트 실행 엔진

    // --- Static Analysis ---
    spotbugsPlugins(libs.findsecbugs)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("aaa-collector.jar")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // KST 보장: Gradle 테스트 실행 시 JVM 시간대 고정 (main()의 TimeZone.setDefault()는 @SpringBootTest에서 실행되지 않음)
    jvmArgs("-Duser.timezone=Asia/Seoul")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // KST 이중 보장: 로컬 개발 실행 시 JVM 시간대 고정 (프로덕션은 main()의 TimeZone.setDefault()로 보장)
    jvmArgs("-Duser.timezone=Asia/Seoul")
}

// --- Spotless (코드 포맷 자동화) ---
spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get()).aosp()
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// --- SpotBugs (버그 패턴 탐지) ---
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports.create("html") { required.set(true) }
    reports.create("xml") { required.set(false) }
}

// --- PMD (코드 품질 검사) ---
pmd {
    toolVersion = libs.versions.pmd.get()
    isConsoleOutput = true
    ruleSets = emptyList()
    ruleSetFiles = files("config/pmd/ruleset.xml")
}

// --- Git hook 설치 (최초 1회 수동 실행: ./gradlew installGitHooks) ---
tasks.register<Copy>("installGitHooks") {
    from("scripts/pre-commit", "scripts/pre-push")
    into(rootProject.layout.projectDirectory.dir(".git/hooks"))
    filePermissions { unix("rwxr-xr-x") }
    onlyIf { layout.projectDirectory.dir(".git").asFile.exists() }
}
