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
    "com/aaa/collector/stock/daily/CollectionResult.class",
    // M3 순수 nested record DTO: accessor만, 손작성 로직 0건 (KisHolidayResponse 외부 클래스는 compact 생성자 있어 제외 불가)
    "com/aaa/collector/kis/holiday/KisHolidayResponse\$HolidayRow.class"
)

// --- 테스트 계층 분리 (SPEC-COLLECTOR-TESTLAYER-001) ---
// 기본 test = 단위 테스트 전용 (컨테이너 없음, pre-push가 호출). 통합 태그 제외.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// integrationTest = Testcontainers 기반 통합 테스트 전용 (37 MySQL + 3 Redis). CI check에서만 실행.
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests tagged @Tag(\"integration\") (Testcontainers)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // tasks.withType<Test> {}(파일 하단)이 useJUnitPlatform 기본 설정 + KST jvmArgs를 자동 적용한다.
}

// --- JaCoCo 커버리지 게이트 (BUNDLE LINE ≥ 85% 즉시 강제 — REQ-JACOCO-010/011/012) ---
// minimum은 Gradle property로 오버라이드 가능 (예: -PjacocoMinimum=0.99, 기본값 영구 0.85)
val jacocoMinimum = (findProperty("jacocoMinimum") as String?)?.toBigDecimal() ?: "0.85".toBigDecimal()

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    // jacocoTestReport와 동일하게 단위+통합 실행 데이터를 명시 합산한다.
    // 미지정 시 Jacoco 플러그인 기본 컨벤션이 verification의 executionData를 `test` 태스크에만 바인딩하여
    // 통합 테스트로만 커버되는 라인이 게이트 계산에서 누락되고 85% 라인 게이트가 거짓 실패한다
    // (SPEC-COLLECTOR-TESTLAYER-001 REQ-TESTLAYER-011/012 — 실측: 미지정 시 0.84로 거짓 실패, 지정 시 정상 통과).
    executionData(tasks.test.get(), tasks.named<Test>("integrationTest").get())
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

// --- JaCoCo 리포트 (단위 + 통합 실행 데이터 합산 — SPEC-COLLECTOR-TESTLAYER-001 §6 D3 안 A) ---
tasks.jacocoTestReport {
    dependsOn(tasks.test, tasks.named("integrationTest"))
    executionData(tasks.test.get(), tasks.named<Test>("integrationTest").get())
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

// [HARD] tasks.test { finalizedBy(jacocoTestReport) }는 의도적으로 두지 않는다.
// finalizer를 남긴 채 jacocoTestReport.dependsOn(test, integrationTest)를 추가하면
// Gradle의 finalizer 의존성 스케줄링 규칙에 따라 `./gradlew test`(pre-push가 호출) 실행 시
// jacocoTestReport가 스케줄되고 그 dependsOn 체인이 integrationTest를 태스크 그래프로
// 끌어들여 pre-push가 통합 테스트를 실행하게 된다(8분+ 병목 재발, REQ-TESTLAYER-007/013 무력화).
// jacocoTestReport(및 그것이 끌어들이는 integrationTest)는 check 체인으로만 도달 가능해야 하므로
// finalizer를 제거한다 — 85% 게이트는 jacocoTestCoverageVerification(check 경유)에 있으므로
// 게이트 동작에는 영향이 없다(spec.md §6 D3).

repositories {
    mavenCentral()
}

dependencies {
    // --- Runtime ---
    implementation(libs.spring.boot.starter.web)          // 내장 Tomcat, Spring MVC, Jackson
    implementation(libs.spring.boot.starter.actuator)     // 헬스체크 + Prometheus 노출 엔드포인트
    implementation(libs.micrometer.registry.prometheus)   // /actuator/prometheus (VictoriaMetrics 호환) 노출
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

// SpotBugs 6.5.6(core 4.10.2)은 commons-lang3:3.20.0(org.apache.commons.lang3.Strings)을 요구하지만
// io.spring.dependency-management(Spring Boot BOM)이 commons-lang3를 3.17.0으로 관리해 spotbugs 분석이
// NoClassDefFoundError: org/apache/commons/lang3/Strings 로 크래시한다(exit 4). 이 플러그인은 resolutionStrategy.force
// 보다 우선하므로, 관리 버전 자체를 spotbugs가 요구하는 3.20.0으로 override한다. commons-lang3는 runtimeClasspath에
// 없어(빌드/정적분석 전용) 런타임 아티팩트에는 영향이 없다.
dependencyManagement {
    dependencies {
        dependency("org.apache.commons:commons-lang3:3.20.0")
    }
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
