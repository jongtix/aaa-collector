package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.stock.Stock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FinraCdnCoveredGapWalkRunner} 단위 테스트 (SPEC-COLLECTOR-BACKFILL-011 AC-16/-17, DP-4).
 *
 * <p>오케스트레이터에서 {@code CoveredRangeService}/필러 구성 책임을 이관받은 협력자다(코드리뷰 — PMD CouplingBetweenObjects 완화
 * 리팩터). 필러가 기존 CDN 경로({@code client.fetch})만 재사용함과 예외 격리(REQ-045 정신)를 이 클래스 수준에서 검증한다 — 이전엔 {@code
 * FinraCdnShortSaleBackfillOrchestratorTest}가 담당하던 시나리오다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinraCdnCoveredGapWalkRunner — 전역 앵커 갭 walk 실행 (SPEC-COLLECTOR-BACKFILL-011)")
class FinraCdnCoveredGapWalkRunnerTest {

    @Mock private CoveredRangeService coveredRangeService;
    @Mock private FinraCdnDailyFileClient client;
    @Mock private FinraCdnDailyLoader loader;

    private FinraCdnCoveredGapWalkRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FinraCdnCoveredGapWalkRunner(coveredRangeService, client);
    }

    private static BackfillStatus anchor() {
        return BackfillStatus.builder()
                .targetType("OVERSEAS_SHORTSALE")
                .targetCode("__GLOBAL__")
                .dataTable("short_sale_overseas")
                .status(BackfillStatusType.IN_PROGRESS)
                .build();
    }

    @Nested
    @DisplayName("runFor — 필러 구성·위임")
    class RunFor {

        @Test
        @DisplayName("① CoveredRangeService.walkGapForward가 정확히 1회, 주어진 앵커·오늘 날짜로 호출된다")
        void delegatesToWalkGapForward() {
            BackfillStatus anchor = anchor();
            LocalDate today = LocalDate.of(2026, 7, 15);
            Map<String, Stock> symbolMap = Map.of();

            runner.runFor(anchor, today, loader, symbolMap);

            verify(coveredRangeService).walkGapForward(eq(anchor), any(), eq(today));
        }

        @Test
        @DisplayName("④ 구성된 필러는 client.fetch(CDN)만 재사용 — 신규 fetch 메서드 없음")
        void constructedFillerWrapsExistingCdnPath() {
            BackfillStatus anchor = anchor();
            LocalDate today = LocalDate.of(2026, 7, 15);
            Map<String, Stock> symbolMap = Map.of();
            ArgumentCaptor<FinraCdnCoveredGapFiller> fillerCaptor =
                    ArgumentCaptor.forClass(FinraCdnCoveredGapFiller.class);

            runner.runFor(anchor, today, loader, symbolMap);

            verify(coveredRangeService)
                    .walkGapForward(eq(anchor), fillerCaptor.capture(), eq(today));
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            when(client.fetch(cursor))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));
            fillerCaptor.getValue().persistStep(cursor);
            verify(client).fetch(cursor); // 필러가 결국 기존 CDN 경로(client.fetch)만 호출함을 확인
        }

        @Test
        @DisplayName("갭 walk 예외 — runFor 밖으로 전파되지 않는다(REQ-045 예외 격리 정신 재사용)")
        void gapWalkException_doesNotPropagate() {
            BackfillStatus anchor = anchor();
            LocalDate today = LocalDate.of(2026, 7, 15);
            doThrow(new RuntimeException("gap walk 실패"))
                    .when(coveredRangeService)
                    .walkGapForward(any(), any(), any());

            assertThatCode(() -> runner.runFor(anchor, today, loader, Map.of()))
                    .doesNotThrowAnyException();
        }
    }
}
