package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;

import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.properties.PropertyDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PMD ruleset.xml 파싱 유효성을 검증하는 테스트.
 *
 * <p>Gradle PMD 플러그인은 Ant PMDTask를 경유하므로 ruleset 파싱 에러(프로퍼티명 변경 등)를 삼키고 exit code 0을 반환할 수 있다. 이
 * 테스트는 PMD RuleSetLoader API를 직접 호출하여 해당 누락을 보완한다.
 */
@DisplayName("PMD RuleSetLoader")
class PmdRuleSetLoaderTest {

    private static final String RULESET_PATH = "config/pmd/ruleset.xml";

    @Nested
    @DisplayName("config/pmd/ruleset.xml 로드")
    class RuleSetLoad {

        @Test
        @DisplayName("파싱 에러 없이 로드된다")
        void loadsWithoutParsingError() {
            // Arrange
            RuleSetLoader loader = new RuleSetLoader();

            // Act
            RuleSet ruleSet = loader.loadFromResource(RULESET_PATH);

            // Assert
            assertThat(ruleSet).isNotNull();
        }

        @Test
        @DisplayName("로드된 룰셋에 룰이 존재한다")
        void containsRules() {
            // Arrange
            RuleSetLoader loader = new RuleSetLoader();

            // Act
            RuleSet ruleSet = loader.loadFromResource(RULESET_PATH);

            // Assert
            assertThat(ruleSet.getRules()).isNotEmpty();
        }

        @Test
        @DisplayName("FieldNamingConventions의 constantPattern이 커스텀 값으로 적용된다")
        void customPropertyIsApplied() {
            // Arrange
            RuleSetLoader loader = new RuleSetLoader();
            RuleSet ruleSet = loader.loadFromResource(RULESET_PATH);

            // Act
            Rule rule = ruleSet.getRuleByName("FieldNamingConventions");

            // Assert
            assertThat(rule)
                    .as("FieldNamingConventions 룰이 존재해야 한다")
                    .isNotNull()
                    .satisfies(
                            r -> {
                                PropertyDescriptor<?> descriptor =
                                        r.getPropertyDescriptor("constantPattern");
                                assertThat(descriptor)
                                        .as("constantPattern 프로퍼티가 존재해야 한다")
                                        .isNotNull();
                                assertThat(r.getProperty(descriptor))
                                        .hasToString("[A-Z][A-Z_0-9]*|log");
                            });
        }
    }
}
