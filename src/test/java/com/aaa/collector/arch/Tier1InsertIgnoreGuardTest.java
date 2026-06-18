package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier-1 INSERT IGNORE 회귀 가드 테스트 (REQ-DBGRANT2-012, -013, -023, -031).
 *
 * <p>구현 방식 선택: ArchUnit {@link ClassFileImporter}로 클래스 정보를 읽고, {@code @Query} 애너테이션의 {@code value}
 * / {@code nativeQuery} 속성을 ArchUnit의 {@code JavaAnnotation} API로 직접 접근한다. ArchUnit 1.4.2는 이미
 * {@code testImplementation} 의존성으로 존재하므로 신규 의존성 0(REQ-023).
 *
 * <p>ArchUnit 표준 fluent DSL({@code @ArchTest} + {@code ArchRule})은 {@code @Query} 문자열 내용을 직접 매칭하는
 * 빌트인 술어가 없어 커스텀 {@code ArchCondition}이 필요하다. 동일 커스텀 로직이지만 {@code @AnalyzeClasses} +
 * {@code @ArchTest} 형식 대신 일반 {@code @Test} 메서드로 작성하여 실패 메시지에 위반 상세(리포지토리명, 메서드, 테이블명)를 포함시킨다.
 *
 * <p>스캔 대상: {@code com.aaa.collector} 패키지 하위 클래스(테스트 클래스 제외). 특정 리포지토리를 하드코딩하지 않으며, 향후 추가되는 Tier-1
 * 리포지토리도 자동으로 탐지한다(REQ-013).
 *
 * <p>Tier-2 허용목록(ADR-026 결정 2): {@code stocks}, {@code stock_grades}, {@code short_sale_overseas},
 * {@code etf_metadata} — 이 테이블에 기록하는 리포지토리만 UPDATE 경로 SQL이 허용된다(REQ-031).
 *
 * <p><b>RED 입증</b>: 전환 전(news/macro 등에 {@code ON DUPLICATE KEY UPDATE} 잔존) 이 테스트는 실패한다. 전환 후 통과한다.
 *
 * <p>Spring 컨텍스트 불필요 — 순수 클래스파일 스캔이므로 {@code @SpringBootTest} 없이 실행된다. 신규 {@link
 * org.springframework.data.jpa.repository.JpaRepository} 추가 시 smoke 테스트에 {@code @MockitoBean}을 추가해야
 * 한다는 프로젝트 규칙(aaa auto-memory: project-smoke-test-repo-mock)과 무관하다.
 */
@DisplayName("Tier-1 리포지토리 ON DUPLICATE KEY UPDATE 금지 가드 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)")
class Tier1InsertIgnoreGuardTest {

    /**
     * Tier-2 허용목록 — ADR-026 결정 2의 단일 소스. 이 테이블에 기록하는 리포지토리만 UPDATE 경로 SQL이 허용된다. 목록 변경 시 반드시
     * ADR-026을 함께 수정한다.
     */
    private static final Set<String> TIER2_TABLE_ALLOWLIST =
            Set.of("stocks", "stock_grades", "short_sale_overseas", "etf_metadata");

    /** 금지 패턴: {@code ON DUPLICATE KEY UPDATE} (대소문자·공백/줄바꿈 무시). text block SQL에서도 정확히 탐지한다. */
    private static final Pattern FORBIDDEN_PATTERN =
            Pattern.compile(
                    "ON\\s+DUPLICATE\\s+KEY\\s+UPDATE", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * INSERT INTO 뒤의 테이블명 추출 패턴. {@code INSERT IGNORE INTO table_name} 또는 {@code INSERT INTO
     * table_name} 형태를 모두 지원한다.
     */
    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile(
                    "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+(\\w+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    @DisplayName("Tier-1 리포지토리 네이티브 @Query에 ON DUPLICATE KEY UPDATE 사용 금지")
    void tier1Repositories_mustNotUseOnDuplicateKeyUpdate() {
        // Arrange
        Iterable<JavaClass> scannedClasses =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.aaa.collector");

        List<String> violations = new ArrayList<>();
        int classCount = 0;

        // Act — 각 클래스의 모든 메서드에서 nativeQuery=true @Query 검사
        for (JavaClass javaClass : scannedClasses) {
            classCount++;
            collectViolations(javaClass, violations);
        }

        // Assert
        assertThat(classCount)
                .as("com.aaa.collector 클래스 스캔 결과가 비어 있으면 가드가 동작하지 않는다")
                .isGreaterThan(0);

        if (!violations.isEmpty()) {
            fail(
                    "Tier-1 리포지토리 ON DUPLICATE KEY UPDATE 위반 발견 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] INSERT IGNORE INTO ... VALUES (...) 로 교체하고"
                            + " @MX:WARN/@MX:REASON 가드 주석 추가"
                            + " (DailyOhlcvRepository 참조, ADR-025)."
                            + "\n[근거] ADR-026 Tier-1 테이블은 UPDATE 권한 없음 →"
                            + " ON DUPLICATE KEY UPDATE는 SQL 1142 유발."
                            + "\n[참조] SPEC-COLLECTOR-DBGRANT-002,"
                            + " .claude/rules/moai/development/collector-db-grant-tiers.md");
        }
    }

    /**
     * 클래스의 모든 메서드를 검사하여 위반 항목을 {@code violations}에 추가한다.
     *
     * <p>nativeQuery=true인 {@code @Query} 메서드 중 {@code ON DUPLICATE KEY UPDATE} 패턴이 있고, 대상 테이블이
     * Tier-2 허용목록에 없는 경우를 위반으로 판정한다.
     */
    private void collectViolations(JavaClass javaClass, List<String> violations) {
        for (JavaMethod method : javaClass.getMethods()) {
            Optional<com.tngtech.archunit.core.domain.JavaAnnotation<JavaMethod>> queryOpt =
                    method.tryGetAnnotationOfType("org.springframework.data.jpa.repository.Query");
            if (queryOpt.isEmpty()) {
                continue;
            }
            checkNativeQuery(javaClass, method, queryOpt.get(), violations);
        }
    }

    /** 단일 네이티브 {@code @Query} 메서드를 검사하여 위반이면 {@code violations}에 추가한다. */
    private void checkNativeQuery(
            JavaClass javaClass,
            JavaMethod method,
            com.tngtech.archunit.core.domain.JavaAnnotation<JavaMethod> query,
            List<String> violations) {
        Optional<Object> nativeQueryAttr = query.get("nativeQuery");
        boolean isNative =
                nativeQueryAttr.isPresent() && Boolean.TRUE.equals(nativeQueryAttr.get());
        if (!isNative) {
            return;
        }

        Optional<Object> valueAttr = query.get("value");
        if (valueAttr.isEmpty()) {
            return;
        }
        String sql = valueAttr.get().toString();

        if (!FORBIDDEN_PATTERN.matcher(sql).find()) {
            return;
        }

        String targetTable = extractTableName(sql);
        if (targetTable != null && TIER2_TABLE_ALLOWLIST.contains(targetTable)) {
            return; // Tier-2 허용목록 테이블 — UPDATE 경로 SQL 허용 (ADR-026 결정 2)
        }

        violations.add(
                String.format(
                        "[VIOLATION] %s#%s — 테이블: %s,"
                                + " SQL에 ON DUPLICATE KEY UPDATE 사용"
                                + " (ADR-026 Tier-1 위반, INSERT IGNORE로 교체할 것)",
                        javaClass.getSimpleName(),
                        method.getName(),
                        targetTable != null ? targetTable : "(테이블명 추출 실패)"));
    }

    /**
     * SQL 문자열에서 INSERT 대상 테이블명을 추출한다.
     *
     * @return 테이블명(소문자), 추출 실패 시 {@code null}
     */
    private String extractTableName(String sql) {
        Matcher matcher = TABLE_NAME_PATTERN.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
