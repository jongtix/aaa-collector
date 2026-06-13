package com.aaa.collector.common.startup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DbGrantCheckRunner — ApplicationRunner 기동 시 권한 검증")
class DbGrantCheckRunnerTest {

    @Mock private DbGrantLoader loader;

    @Nested
    @DisplayName("전체 권한 충족 시")
    class AllGrantsPresent {

        @Test
        @DisplayName("run() 호출 시 예외 없이 정상 완료")
        void run_succeeds_when_all_grants_present() throws Exception {
            // Arrange
            when(loader.loadSchemaPrivileges()).thenReturn(Set.of("SELECT", "INSERT"));
            when(loader.loadTier2UpdateTables())
                    .thenReturn(
                            Set.of(
                                    "stocks",
                                    "stock_grades",
                                    "short_sale_overseas",
                                    "etf_metadata"));
            DbGrantCheckRunner runner = new DbGrantCheckRunner(loader, new DbGrantVerifier());

            // Act & Assert
            assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("권한 누락 시")
    class GrantsMissing {

        @Test
        @DisplayName("스키마 SELECT 누락 — run() 이 DbGrantMissingException 던짐")
        void run_throws_when_schema_select_missing() throws Exception {
            // Arrange
            when(loader.loadSchemaPrivileges()).thenReturn(Set.of("INSERT"));
            when(loader.loadTier2UpdateTables())
                    .thenReturn(
                            Set.of(
                                    "stocks",
                                    "stock_grades",
                                    "short_sale_overseas",
                                    "etf_metadata"));
            DbGrantCheckRunner runner = new DbGrantCheckRunner(loader, new DbGrantVerifier());

            // Act & Assert
            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContaining("SELECT");
        }

        @Test
        @DisplayName("Tier-2 테이블 누락 — run() 이 DbGrantMissingException 던짐 + 테이블명 포함")
        void run_throws_when_tier2_table_missing() throws Exception {
            // Arrange
            when(loader.loadSchemaPrivileges()).thenReturn(Set.of("SELECT", "INSERT"));
            when(loader.loadTier2UpdateTables()).thenReturn(Set.of("stocks")); // 3개 누락

            DbGrantCheckRunner runner = new DbGrantCheckRunner(loader, new DbGrantVerifier());

            // Act & Assert
            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(DbGrantMissingException.class)
                    .hasMessageContainingAll("stock_grades", "short_sale_overseas", "etf_metadata");
        }
    }
}
