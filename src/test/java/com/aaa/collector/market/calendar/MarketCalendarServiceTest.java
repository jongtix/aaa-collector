package com.aaa.collector.market.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName(
        "MarketCalendarService — 우선순위 인지 upsert (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-003/-004/-005/-022)")
class MarketCalendarServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 1, 5);

    @Mock private MarketCalendarRepository marketCalendarRepository;

    private MarketCalendarService service;

    @BeforeEach
    void setUp() {
        service = new MarketCalendarService(marketCalendarRepository);
    }

    @Nested
    @DisplayName("기존 행 없음 — INSERT")
    class Insert {

        @Test
        @DisplayName("기존 행이 없으면 새 MarketCalendar를 저장한다")
        void noExistingRow_savesNewEntity() {
            // Arrange
            when(marketCalendarRepository.findByCalendarCodeAndCalDate(CalendarCode.KRX, DATE))
                    .thenReturn(Optional.empty());

            // Act
            service.upsert(CalendarCode.KRX, DATE, true, CalendarSource.KIS_API);

            // Assert
            ArgumentCaptor<MarketCalendar> captor = ArgumentCaptor.forClass(MarketCalendar.class);
            verify(marketCalendarRepository).save(captor.capture());
            MarketCalendar saved = captor.getValue();
            assertThat(saved.getCalendarCode()).isEqualTo(CalendarCode.KRX);
            assertThat(saved.getCalDate()).isEqualTo(DATE);
            assertThat(saved.isOpen()).isTrue();
            assertThat(saved.getSource()).isEqualTo(CalendarSource.KIS_API);
        }
    }

    @Nested
    @DisplayName("기존 행 있음 — 우선순위 판정 (REQ-CAL-003, -004)")
    class PriorityAwareUpdate {

        @Test
        @DisplayName("낮은 우선순위(source) upsert는 높은 우선순위 기존 행을 변경하지 않는다 (AC-2)")
        void lowerPriority_doesNotOverwrite() {
            // Arrange — 기존 MANUAL(최우선), false로 저장된 상태
            MarketCalendar existing =
                    MarketCalendar.builder()
                            .calendarCode(CalendarCode.KRX)
                            .calDate(DATE)
                            .open(false)
                            .source(CalendarSource.MANUAL)
                            .build();
            when(marketCalendarRepository.findByCalendarCodeAndCalDate(CalendarCode.KRX, DATE))
                    .thenReturn(Optional.of(existing));

            // Act — 자동 갱신 경로가 KIS_API(낮은 우선순위)로 개장(true) upsert 시도
            service.upsert(CalendarCode.KRX, DATE, true, CalendarSource.KIS_API);

            // Assert — 기존 값 불변, save() 미호출(신규 저장 아님)
            assertThat(existing.isOpen()).isFalse();
            assertThat(existing.getSource()).isEqualTo(CalendarSource.MANUAL);
            verify(marketCalendarRepository, never()).save(any());
        }

        @Test
        @DisplayName("같은 우선순위 upsert는 정상 반영된다 (AC-2 대조 케이스)")
        void samePriority_updatesEntity() {
            // Arrange — 기존 ALGORITHM, false
            MarketCalendar existing =
                    MarketCalendar.builder()
                            .calendarCode(CalendarCode.NYSE)
                            .calDate(DATE)
                            .open(false)
                            .source(CalendarSource.ALGORITHM)
                            .build();
            when(marketCalendarRepository.findByCalendarCodeAndCalDate(CalendarCode.NYSE, DATE))
                    .thenReturn(Optional.of(existing));

            // Act — 같은 우선순위(ALGORITHM)로 값 정정
            service.upsert(CalendarCode.NYSE, DATE, true, CalendarSource.ALGORITHM);

            // Assert
            assertThat(existing.isOpen()).isTrue();
            assertThat(existing.getSource()).isEqualTo(CalendarSource.ALGORITHM);
        }

        @Test
        @DisplayName("높은 우선순위(MANUAL) upsert가 기존 낮은 우선순위(KIS_API) 값을 덮어쓴다 (AC-2 대조 케이스)")
        void higherPriority_overwritesLower() {
            // Arrange
            MarketCalendar existing =
                    MarketCalendar.builder()
                            .calendarCode(CalendarCode.KRX)
                            .calDate(DATE)
                            .open(true)
                            .source(CalendarSource.KIS_API)
                            .build();
            when(marketCalendarRepository.findByCalendarCodeAndCalDate(CalendarCode.KRX, DATE))
                    .thenReturn(Optional.of(existing));

            // Act
            service.upsert(CalendarCode.KRX, DATE, false, CalendarSource.MANUAL);

            // Assert
            assertThat(existing.isOpen()).isFalse();
            assertThat(existing.getSource()).isEqualTo(CalendarSource.MANUAL);
        }
    }

    @Nested
    @DisplayName("DELETE 미사용 (REQ-CAL-005, AC-3)")
    class NoDelete {

        @Test
        @DisplayName("MarketCalendarService에 delete 관련 공개 메서드가 선언되어 있지 않다")
        void serviceHasNoDeleteMethod() {
            List<String> deleteMethods =
                    declaredMethodNamesContaining(MarketCalendarService.class, "delete");
            assertThat(deleteMethods).isEmpty();
        }

        @Test
        @DisplayName("MarketCalendarRepository에 커스텀 delete 관련 메서드가 선언되어 있지 않다")
        void repositoryHasNoCustomDeleteMethod() {
            List<String> deleteMethods =
                    declaredMethodNamesContaining(MarketCalendarRepository.class, "delete");
            assertThat(deleteMethods).isEmpty();
        }

        private List<String> declaredMethodNamesContaining(Class<?> type, String needle) {
            return Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
    }
}
