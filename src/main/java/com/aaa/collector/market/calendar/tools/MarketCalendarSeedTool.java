package com.aaa.collector.market.calendar.tools;

import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.market.session.UsMarketProperties;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.StockRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code market_calendar} 초기 시딩 도구 (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-040~049, TASK-012).
 *
 * <p>{@code @Profile("tools-market-calendar-seed")}로 게이트되어 일반 부팅·기존 일일 갱신 배치와 완전히 분리된 경로에서만
 * 실행된다(REQ-CAL-041) — 프로파일이 활성화되지 않으면 이 빈 자체가 생성되지 않으므로 REQ-CAL-041의 불변식을 스프링 프로파일 메커니즘으로 구조적으로
 * 보장한다.
 *
 * <p>실제 시딩 절차(KRX/NYSE 대사, 군집 판정, SQL 출력)는 {@link MarketCalendarSeedService}(Spring 빈 아님, 이 클래스가
 * 주입받은 의존성을 그대로 전달해 수동 생성)에 위임한다 — 단일 책임 분리로 이 빈 자체의 결합도를 낮게 유지한다.
 *
 * <p>실행: {@code ./gradlew bootRun --args='--spring.profiles.active=tools-market-calendar-seed'
 * --calendar.seed.output-file=/absolute/path/seed.sql}.
 */
@Component
@Profile("tools-market-calendar-seed")
@RequiredArgsConstructor
public class MarketCalendarSeedTool implements CommandLineRunner {

    private final KisHolidayClient kisHolidayClient;
    private final StockRepository stockRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final UsMarketProperties usMarketProperties;
    private final Clock clock;

    @Value("${calendar.seed.output-file}")
    private String outputFilePath;

    @Override
    public void run(String... args) throws IOException {
        Path outputPath = resolveOutputPath();
        MarketCalendarSeedService service =
                new MarketCalendarSeedService(
                        kisHolidayClient,
                        stockRepository,
                        dailyOhlcvRepository,
                        usMarketProperties,
                        clock);
        service.seed(outputPath);
    }

    private Path resolveOutputPath() {
        if (outputFilePath == null || outputFilePath.isBlank()) {
            throw new IllegalStateException(
                    "calendar.seed.output-file 프로퍼티가 필요합니다(절대경로) — REQ-CAL-048");
        }
        Path path = Path.of(outputFilePath);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                    "calendar.seed.output-file은 절대경로여야 합니다 — 값=" + outputFilePath);
        }
        return path;
    }
}
