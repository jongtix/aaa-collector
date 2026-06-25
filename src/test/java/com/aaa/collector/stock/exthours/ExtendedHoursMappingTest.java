package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExtendedHours 엔티티 매핑 단위 검증")
class ExtendedHoursMappingTest {

    @Test
    @DisplayName("session 필드에 @Enumerated(STRING) 선언 — DB에 'PRE'/'AFTER' 문자열로 저장")
    void sessionField_hasEnumeratedStringAnnotation() throws NoSuchFieldException {
        Field sessionField = ExtendedHours.class.getDeclaredField("session");
        Enumerated enumerated = sessionField.getAnnotation(Enumerated.class);

        assertThat(enumerated).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
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
