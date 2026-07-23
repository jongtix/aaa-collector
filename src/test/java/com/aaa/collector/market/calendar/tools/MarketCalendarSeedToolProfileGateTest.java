package com.aaa.collector.market.calendar.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.market.session.UsMarketProperties;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.StockRepository;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link MarketCalendarSeedTool} 프로파일 게이트 검증 (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-041, TASK-012).
 *
 * <p>{@code tools-market-calendar-seed} 프로파일이 활성화되지 않은 일반 부팅·운영 컨텍스트에서는 이 빈이 생성되지 않아야
 * 한다(REQ-CAL-041 — 일반 부팅·기존 일일 갱신 배치와 완전히 분리된 경로).
 */
@DisplayName("MarketCalendarSeedTool — 전용 프로파일 게이트 (REQ-CAL-041)")
class MarketCalendarSeedToolProfileGateTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MarketCalendarSeedTool.class)
                    .withBean(KisHolidayClient.class, () -> mock(KisHolidayClient.class))
                    .withBean(StockRepository.class, () -> mock(StockRepository.class))
                    .withBean(DailyOhlcvRepository.class, () -> mock(DailyOhlcvRepository.class))
                    .withBean(UsMarketProperties.class, UsMarketProperties::new)
                    .withBean(Clock.class, Clock::systemDefaultZone);

    @Test
    @DisplayName("비-지정 프로파일 컨텍스트에서는 MarketCalendarSeedTool 빈이 부재한다")
    void nonSeedProfile_beanAbsent() {
        contextRunner.run(
                context -> assertThat(context).doesNotHaveBean(MarketCalendarSeedTool.class));
    }

    @Test
    @DisplayName("지정 프로파일(tools-market-calendar-seed)에서만 MarketCalendarSeedTool 빈이 존재한다")
    void seedProfile_beanPresent() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=tools-market-calendar-seed",
                        "calendar.seed.output-file=/tmp/market-calendar-seed-test.sql")
                .run(context -> assertThat(context).hasSingleBean(MarketCalendarSeedTool.class));
    }

    @Test
    @DisplayName("다른 프로파일이 활성화돼도 seed 프로파일이 아니면 빈이 부재한다")
    void otherProfile_beanStillAbsent() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).doesNotHaveBean(MarketCalendarSeedTool.class));
    }
}
