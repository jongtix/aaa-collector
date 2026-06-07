package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Grade enum 단위 테스트")
class GradeTest {

    @Nested
    @DisplayName("values — 4개 등급 존재")
    class Values {

        @Test
        @DisplayName("A, B, C, F 4개 값이 존재한다")
        void values_hasFourGrades() {
            assertThat(Grade.values()).hasSize(4);
        }

        @Test
        @DisplayName("name() 메서드가 'A', 'B', 'C', 'F' 문자열을 반환한다")
        void values_nameReturnsCorrectString() {
            assertThat(Grade.A.name()).isEqualTo("A");
            assertThat(Grade.B.name()).isEqualTo("B");
            assertThat(Grade.C.name()).isEqualTo("C");
            assertThat(Grade.F.name()).isEqualTo("F");
        }

        @Test
        @DisplayName("valueOf() 로 각 등급을 역참조할 수 있다")
        void values_valueOfReturnsEnum() {
            assertThat(Grade.valueOf("A")).isEqualTo(Grade.A);
            assertThat(Grade.valueOf("B")).isEqualTo(Grade.B);
            assertThat(Grade.valueOf("C")).isEqualTo(Grade.C);
            assertThat(Grade.valueOf("F")).isEqualTo(Grade.F);
        }
    }
}
