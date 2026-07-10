package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.exthours.ExtendedHoursRepository;
import com.aaa.collector.stock.exthours.Session;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExtendedHoursWarmSource — 세션별 last_load seed 값 해석 (REQ-XR-018)")
class ExtendedHoursWarmSourceTest {

    @Mock private ExtendedHoursRepository extendedHoursRepository;
    @InjectMocks private ExtendedHoursWarmSource warmSource;

    @Test
    @DisplayName("preLastLoad는 PRE 세션 최신 거래일을 00:00 LocalDateTime으로 변환한다")
    void preLastLoadConvertsTradeDateToStartOfDay() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        when(extendedHoursRepository.findMaxTradeDateBySession(Session.PRE))
                .thenReturn(Optional.of(tradeDate));

        assertThat(warmSource.preLastLoad()).contains(tradeDate.atStartOfDay());
    }

    @Test
    @DisplayName("afterLastLoad는 AFTER 세션 최신 거래일을 00:00 LocalDateTime으로 변환한다")
    void afterLastLoadConvertsTradeDateToStartOfDay() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        when(extendedHoursRepository.findMaxTradeDateBySession(Session.AFTER))
                .thenReturn(Optional.of(tradeDate));

        assertThat(warmSource.afterLastLoad()).contains(tradeDate.atStartOfDay());
    }

    @Test
    @DisplayName("데이터가 없으면 빈 Optional을 그대로 전달한다 (warm-start skip)")
    void passesThroughEmpty() {
        when(extendedHoursRepository.findMaxTradeDateBySession(Session.PRE))
                .thenReturn(Optional.empty());

        assertThat(warmSource.preLastLoad()).isEmpty();
    }
}
