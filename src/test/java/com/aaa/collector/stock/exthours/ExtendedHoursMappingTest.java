package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import java.lang.reflect.Field;
import java.sql.Types;
import org.hibernate.annotations.JdbcTypeCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ExtendedHours 엔티티 매핑 단위 검증")
class ExtendedHoursMappingTest {

    @Nested
    @DisplayName("session 필드 — @Converter 전환 (T2: @Enumerated 제거, SessionConverter autoApply)")
    class SessionFieldConverter {

        @Test
        @DisplayName("@Enumerated 제거 — SessionConverter(autoApply=true)가 매핑을 담당")
        void sessionField_noEnumeratedAnnotation() throws NoSuchFieldException {
            Field sessionField = ExtendedHours.class.getDeclaredField("session");
            assertThat(sessionField.getAnnotation(Enumerated.class)).isNull();
        }

        @Test
        @DisplayName("@JdbcTypeCode(CHAR) 선언 — V29 MySQL ENUM 컬럼 타입과 ddl-auto=validate 정합")
        void sessionField_hasJdbcTypeCodeChar() throws NoSuchFieldException {
            // V29 마이그레이션 session ENUM('PRE','AFTER')은 JDBC Types#CHAR로 보고됨.
            // @Enumerated 제거 후 Hibernate validator가 VARCHAR를 기대하는 것을 방지하기 위해
            // @JdbcTypeCode(Types.CHAR)로 실제 DB 타입을 명시한다.
            Field sessionField = ExtendedHours.class.getDeclaredField("session");
            JdbcTypeCode jdbcTypeCode = sessionField.getAnnotation(JdbcTypeCode.class);

            assertThat(jdbcTypeCode).isNotNull();
            assertThat(jdbcTypeCode.value()).isEqualTo(Types.CHAR);
        }

        @Test
        @DisplayName("@Column(name='session') 유지 — ddl-auto=validate 대상 (REQ-007)")
        void sessionField_columnAnnotationRetained() throws NoSuchFieldException {
            Field sessionField = ExtendedHours.class.getDeclaredField("session");
            Column column = sessionField.getAnnotation(Column.class);

            assertThat(column).isNotNull();
            assertThat(column.name()).isEqualTo("session");
        }
    }

    @Test
    @DisplayName("Session.PRE / Session.AFTER name() — DB ENUM 값과 일치")
    void sessionEnum_nameMatchesDatabaseValues() {
        assertThat(Session.PRE.name()).isEqualTo("PRE");
        assertThat(Session.AFTER.name()).isEqualTo("AFTER");
    }

    @Test
    @DisplayName("Session 값 2개만 존재 — PRE, AFTER")
    void sessionEnum_hasTwoValues() {
        assertThat(Session.values()).containsExactlyInAnyOrder(Session.PRE, Session.AFTER);
    }
}
