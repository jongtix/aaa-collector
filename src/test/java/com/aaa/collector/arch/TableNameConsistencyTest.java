package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier-1 인서터의 {@code INSERT IGNORE} SQL 테이블명과 {@code SilentDropWarningCounter.countDropsPerRow} /
 * {@code countDropsPerRowIsolated} 호출부에 전달된 {@code tableName} 인자 리터럴이 서로 일치하는지 검증하는 회귀 가드 테스트
 * (SPEC-COLLECTOR-OBSV-002 코드 리뷰 후속 WA-01).
 *
 * <p>구현 방식 선택: 대상 14개 인서터는 {@code countDropsPerRow}를 람다·메서드 참조와 함께 인자로 호출하므로 ArchUnit의 바이트코드 기반
 * 애노테이션 검사({@link Tier1InsertIgnoreGuardTest} 참조)로는 지역 변수/리터럴 인자를 신뢰성 있게 추출할 수 없다. 대신 {@code
 * BatchCronsTest}가 이미 사용 중인 소스 텍스트 스캔 패턴({@link Files#readString})을 재사용해 정규식으로 (a) {@code INSERT
 * IGNORE INTO <table>} SQL 상수의 테이블명, (b) 호출부의 첫 문자열 리터럴 인자({@code tableName})를 추출·비교한다. 순수 텍스트
 * 스캔이므로 Spring 컨텍스트·컴파일이 불필요하다.
 *
 * <p>대상 14개 인서터는 전부 {@code (ps, rows, binder, "tableName", describer)} 또는 {@code (ps, rows, binder,
 * onFailure, "tableName", describer)} 순서로 호출하며, binder/onFailure 인자에는 문자열 리터럴이 없으므로 호출 시작 지점 이후 첫
 * 번째로 등장하는 {@code "식별자",} 형태가 곧 {@code tableName} 인자다(전수 확인, 2026-07-20).
 */
@DisplayName("Tier-1 인서터 테이블명 리터럴 대조 가드 (SPEC-COLLECTOR-OBSV-002 WA-01)")
class TableNameConsistencyTest {

    private static final String BASE = "src/main/java/com/aaa/collector/";

    /** 검사 대상 14개 Tier-1 인서터(com/aaa/collector/ 기준 상대경로). REQ-OBSV-023/024 구현체 전수. */
    private static final List<String> INSERTER_PATHS =
            List.of(
                    "stock/daily/WarningCountingOhlcvInserter.java",
                    "stock/supply/ShortSaleInserter.java",
                    "stock/supply/InvestorTrendInserter.java",
                    "stock/supply/CreditBalanceInserter.java",
                    "macro/MacroIndicatorInserter.java",
                    "market/MarketIndicatorInserter.java",
                    "news/DomesticNewsHeadlineInserter.java",
                    "news/overseas/OverseasNewsHeadlineInserter.java",
                    "stock/fundamental/FinancialInserter.java",
                    "stock/fundamental/AnalystEstimateInserter.java",
                    "stock/CorporateEventInserter.java",
                    "stock/exthours/ExtendedHoursInserter.java",
                    "dart/disclosure/DisclosureInserter.java",
                    "dart/corpcode/CorpCodeMappingInserter.java");

    /** {@code INSERT IGNORE INTO <table>} SQL 상수에서 테이블명을 추출하는 패턴. */
    private static final Pattern SQL_TABLE_PATTERN =
            Pattern.compile(
                    "INSERT\\s+IGNORE\\s+INTO\\s+(\\w+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * {@code countDropsPerRow}/{@code countDropsPerRowIsolated} 호출 시작 지점 이후 최대 600자 이내에서 처음 등장하는
     * {@code "식별자",} 형태를 {@code tableName} 인자로 추출하는 패턴(전수 확인된 인자 순서 기반, 클래스 Javadoc 참조).
     */
    private static final Pattern CALL_TABLE_PATTERN =
            Pattern.compile(
                    "countDropsPerRow(?:Isolated)?\\([\\s\\S]{0,600}?\"(\\w+)\"\\s*,",
                    Pattern.MULTILINE);

    @Test
    @DisplayName(
            "14개 인서터의 INSERT IGNORE SQL 테이블명과 countDropsPerRow(Isolated) 호출 tableName 인자가 전부 일치한다")
    void insertIgnoreTableName_matchesCountDropsPerRowArgument() {
        // Arrange
        List<String> violations = new ArrayList<>();
        int checkedFileCount = 0;
        int checkedCallCount = 0;

        // Act
        for (String relativePath : INSERTER_PATHS) {
            String content = readSource(relativePath);
            checkedFileCount++;

            String sqlTable = extractSqlTableName(relativePath, content);
            List<String> callTables = extractCallTableNames(content);

            if (callTables.isEmpty()) {
                violations.add(
                        "[VIOLATION] "
                                + relativePath
                                + " — countDropsPerRow(Isolated) 호출부에서 tableName 리터럴을 추출하지 못함"
                                + " (정규식 패턴이 실제 코드와 어긋났을 수 있음, 가드 점검 필요)");
                continue;
            }

            checkedCallCount += callTables.size();
            for (String callTable : callTables) {
                if (!callTable.equals(sqlTable)) {
                    violations.add(
                            String.format(
                                    "[VIOLATION] %s — SQL 테이블명=%s, countDropsPerRow(Isolated) 인자=%s (불일치)",
                                    relativePath, sqlTable, callTable));
                }
            }
        }

        // Assert
        assertThat(checkedFileCount)
                .as("검사 대상 인서터 목록이 비어 있으면 가드가 동작하지 않는다")
                .isEqualTo(INSERTER_PATHS.size());
        assertThat(checkedCallCount)
                .as("countDropsPerRow(Isolated) 호출을 하나도 못 찾으면 가드가 동작하지 않는다")
                .isGreaterThan(0);

        if (!violations.isEmpty()) {
            fail(
                    "Tier-1 인서터 테이블명 리터럴 불일치 발견 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] countDropsPerRow(Isolated) 호출의 tableName 인자를 INSERT IGNORE SQL"
                            + " 상수의 대상 테이블명과 동일하게 맞출 것 (REQ-OBSV-024)."
                            + "\n[참조] SPEC-COLLECTOR-OBSV-002");
        }
    }

    private String extractSqlTableName(String relativePath, String content) {
        Matcher matcher = SQL_TABLE_PATTERN.matcher(content);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    relativePath + " — INSERT IGNORE INTO <table> SQL 상수를 찾지 못함 (가드 전제 위반)");
        }
        return matcher.group(1);
    }

    private List<String> extractCallTableNames(String content) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = CALL_TABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    private String readSource(String relativePath) {
        try {
            return Files.readString(Path.of(BASE + relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
