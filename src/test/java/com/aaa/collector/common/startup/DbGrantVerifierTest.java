package com.aaa.collector.common.startup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DbGrantVerifier — DB 권한 self-check 로직")
class DbGrantVerifierTest {

    private final DbGrantVerifier verifier = new DbGrantVerifier();

    @Nested
    @DisplayName("스키마 레벨 권한 검증")
    class SchemaPrivileges {

        @Test
        @DisplayName("SELECT, INSERT 모두 존재하면 예외 없음")
        void passes_when_select_and_insert_present() {
            assertThatCode(
                            () ->
                                    verifier.verify(
                                            Set.of("SELECT", "INSERT"),
                                            Set.of(
                                                    "stocks",
                                                    "stock_grades",
                                                    "short_sale_overseas",
                                                    "etf_metadata")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SELECT 누락 시 예외 발생 + 누락 권한 메시지 포함")
        void fails_when_select_missing() {
            // Arrange
            Set<String> schemaPrivs = Set.of("INSERT");
            Set<String> tier2Tables =
                    Set.of("stocks", "stock_grades", "short_sale_overseas", "etf_metadata");

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContaining("SELECT");
        }

        @Test
        @DisplayName("INSERT 누락 시 예외 발생 + 누락 권한 메시지 포함")
        void fails_when_insert_missing() {
            // Arrange
            Set<String> schemaPrivs = Set.of("SELECT");
            Set<String> tier2Tables =
                    Set.of("stocks", "stock_grades", "short_sale_overseas", "etf_metadata");

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContaining("INSERT");
        }

        @Test
        @DisplayName("SELECT, INSERT 모두 누락 시 예외 메시지에 양쪽 모두 포함")
        void fails_with_both_missing_privileges_named() {
            // Arrange
            Set<String> schemaPrivs = Set.of();
            Set<String> tier2Tables =
                    Set.of("stocks", "stock_grades", "short_sale_overseas", "etf_metadata");

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContainingAll("SELECT", "INSERT");
        }
    }

    @Nested
    @DisplayName("Tier-2 테이블 UPDATE 권한 검증")
    class Tier2TablePrivileges {

        @Test
        @DisplayName("4개 Tier-2 테이블 모두 존재하면 예외 없음")
        void passes_when_all_tier2_tables_present() {
            assertThatCode(
                            () ->
                                    verifier.verify(
                                            Set.of("SELECT", "INSERT"),
                                            Set.of(
                                                    "stocks",
                                                    "stock_grades",
                                                    "short_sale_overseas",
                                                    "etf_metadata")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("stocks UPDATE 누락 시 예외 발생 + 테이블명 포함")
        void fails_when_stocks_update_missing() {
            // Arrange
            Set<String> schemaPrivs = Set.of("SELECT", "INSERT");
            Set<String> tier2Tables = Set.of("stock_grades", "short_sale_overseas", "etf_metadata");

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContaining("stocks");
        }

        @Test
        @DisplayName("여러 Tier-2 테이블 누락 시 예외 메시지에 누락된 테이블 모두 포함")
        void fails_naming_all_missing_tier2_tables() {
            // Arrange
            Set<String> schemaPrivs = Set.of("SELECT", "INSERT");
            Set<String> tier2Tables = Set.of("stocks"); // 3개 누락

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContainingAll("stock_grades", "short_sale_overseas", "etf_metadata");
        }

        @Test
        @DisplayName("Tier-2 테이블 전체 누락 시 예외 발생")
        void fails_when_all_tier2_tables_missing() {
            // Arrange
            Set<String> schemaPrivs = Set.of("SELECT", "INSERT");
            Set<String> tier2Tables = Set.of();

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContainingAll(
                            "stocks", "stock_grades", "short_sale_overseas", "etf_metadata");
        }
    }

    @Nested
    @DisplayName("스키마 + Tier-2 복합 누락")
    class CombinedMissing {

        @Test
        @DisplayName("스키마 권한과 Tier-2 테이블 모두 누락 시 한 번에 모두 보고")
        void fails_reporting_all_missing_at_once() {
            // Arrange
            Set<String> schemaPrivs = Set.of(); // SELECT, INSERT 모두 누락
            Set<String> tier2Tables = Set.of(); // 4개 테이블 모두 누락

            // Act & Assert
            assertThatThrownBy(() -> verifier.verify(schemaPrivs, tier2Tables))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContainingAll(
                            "SELECT",
                            "INSERT",
                            "stocks",
                            "stock_grades",
                            "short_sale_overseas",
                            "etf_metadata");
        }
    }
}
