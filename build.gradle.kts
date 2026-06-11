plugins {
    java
    jacoco
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

// --- JaCoCo 버전 고정 (libs.versions.toml 단일 소스) ---
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// --- 커버리지 제외 목록 (단일 소스 — jacocoTestReport와 jacocoTestCoverageVerification 양쪽에 동일 적용) ---
// SPEC-COLLECTOR-JACOCO-001 §1.4: 소스 전수 정독 후 손작성 실행문·분기·throw가 0건임을 검증한 17개만 열거
val coverageExclusions = listOf(
    // 메인 클래스 (1): SpringApplication.run 단일 라인
    "com/aaa/collector/AaaCollectorApplication.class",
    // @Configuration 빈 와이어링만 (7): if/for/while/switch/throw/삼항 0건
    "com/aaa/collector/kis/KisConfig.class",
    "com/aaa/collector/kis/token/KisTokenConfig.class",
    "com/aaa/collector/common/retry/RetryConfig.class",
    "com/aaa/collector/common/safemode/SafeModeConfig.class",
    "com/aaa/collector/common/config/ClockConfig.class",
    "com/aaa/collector/common/config/JpaConfig.class",
    "com/aaa/collector/common/health/RedisHealthConfig.class",
    // 순수 DTO/record (9): canonical 생성자·accessor만, 손작성 compact 생성자/메서드 없음
    "com/aaa/collector/watchlist/KisDomesticStockInfoResponse.class",
    "com/aaa/collector/watchlist/KisDomesticStockInfoResponse\$Output.class",
    "com/aaa/collector/watchlist/KisOverseasStockInfoResponse.class",
    "com/aaa/collector/watchlist/KisOverseasStockInfoResponse\$Output.class",
    "com/aaa/collector/kis/token/KisApprovalKeyResponse.class",
    "com/aaa/collector/watchlist/ResolvedStock.class",
    "com/aaa/collector/watchlist/ResolveResult.class",
    "com/aaa/collector/watchlist/ResolveResult\$Success.class",
    "com/aaa/collector/watchlist/ResolveResult\$Skipped.class",
    "com/aaa/collector/kis/websocket/ParsedTick.class",
    "com/aaa/collector/kis/websocket/AesKey.class",
    "com/aaa/collector/stock/grade/GradeInput.class",
    "com/aaa/collector/stock/daily/CollectionResult.class"
)

// --- JaCoCo 커버리지 게이트 (BUNDLE LINE ≥ 85% 즉시 강제 — REQ-JACOCO-010/011/012) ---
// minimum은 Gradle property로 오버라이드 가능 (예: -PjacocoMinimum=0.99, 기본값 영구 0.85)
val jacocoMinimum = (findProperty("jacocoMinimum") as String?)?.toBigDecimal() ?: "0.85".toBigDecimal()

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = jacocoMinimum
            }
        }
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(coverageExclusions) }
        })
    )
}

// check가 jacocoTestCoverageVerification을 의존 — Gradle 기본은 자동 연결하지 않으므로 명시 배선 필수
// (REQ-JACOCO-011: 배선 누락 시 게이트가 조용히 무력화됨)
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// --- JaCoCo 리포트 ---
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(coverageExclusions) }
        })
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Runtime ---
    implementation(libs.spring.boot.starter.web)          // 내장 Tomcat, Spring MVC, Jackson
    implementation(libs.spring.boot.starter.actuator)     // 헬스체크 엔드포인트
    implementation(libs.spring.boot.starter.data.jpa)     // JPA + Hibernate
    implementation(libs.spring.boot.starter.data.redis)   // Redis 연동
    implementation(libs.spring.boot.starter.websocket)    // KIS WebSocket 클라이언트 (StandardWebSocketClient)
    implementation(libs.bucket4j)                          // KIS API TPS 20 rate limiting
    runtimeOnly(libs.mysql.connector)                     // MySQL JDBC 드라이버
    runtimeOnly(libs.flyway.core)                         // Flyway 스키마 마이그레이션
    runtimeOnly(libs.flyway.mysql)                        // Flyway MySQL 방언

    // --- Lombok ---
    compileOnly(libs.lombok)                                     // 보일러플레이트 제거 (@Slf4j, @RequiredArgsConstructor 등)
    annotationProcessor(libs.lombok)

    // --- Test ---
    testImplementation(libs.spring.boot.starter.test)     // JUnit 5, Mockito, AssertJ, Spring Test
    testImplementation(libs.archunit)                      // 아키텍처 규칙 검증
    testImplementation(libs.wiremock.spring.boot)          // 외부 API mock 서버
    testImplementation(libs.testcontainers)                // 컨테이너 기반 통합 테스트
    testImplementation(libs.testcontainers.junit)          // Testcontainers JUnit 5 확장
    testImplementation(libs.testcontainers.mysql)          // Testcontainers MySQL
    testImplementation(libs.spring.boot.testcontainers)    // @ServiceConnection 지원
    testImplementation(libs.flyway.core)                    // 테스트에서 Flyway 빈 직접 참조
    testRuntimeOnly(libs.commons.codec)                    // Testcontainers MySQLContainer가 요구하는 전이 의존성
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
