package com.aaa.collector.market.indicator.usdkrw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorInserter;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsdkrwCollectionService 단위 테스트")
class UsdkrwCollectionServiceTest {

    @Mock private MarketIndicatorSourceChain usdkrwChain;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private MarketIndicatorInserter marketIndicatorInserter;

    @Captor private ArgumentCaptor<List<MarketIndicator>> inserterCaptor;

    private UsdkrwCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new UsdkrwCollectionService(
                        usdkrwChain, marketIndicatorRepository, marketIndicatorInserter);
    }

    private MarketIndicatorRow usdkrwRow(LocalDate date) {
        return new MarketIndicatorRow(
                IndicatorCode.USDKRW,
                date,
                null,
                null,
                null,
                new BigDecimal("1380.0000"),
                "KOREAEXIM");
    }

    @Nested
    @DisplayName("collectDaily — 일봉 수집")
    class CollectDaily {

        @Test
        @DisplayName("정상 수집 — insertBatch 호출, USDKRW 엔티티")
        void normalCollect_insertsUsdkrwRow() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(date)).thenReturn(List.of(usdkrwRow(date)));

            service.collectDaily(date);

            verify(marketIndicatorInserter).insertBatch(inserterCaptor.capture());
            List<MarketIndicator> inserted =
                    inserterCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertThat(inserted).hasSize(1);
            assertThat(inserted.getFirst().getIndicatorCode()).isEqualTo(IndicatorCode.USDKRW);
            assertThat(inserted.getFirst().getCloseValue()).isEqualByComparingTo("1380.0000");
        }

        @Test
        @DisplayName("빈 결과 — insertBatch 미호출")
        void emptyResult_noInsert() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(date)).thenReturn(List.of());

            service.collectDaily(date);

            verify(marketIndicatorInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("체인 예외 — 전파 없음 (REQ-003)")
        void chainException_notPropagated() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(date)).thenThrow(new RuntimeException("수집 실패"));

            assertThatCode(() -> service.collectDaily(date)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName(
                "AC-A3: target 날짜 이중 게이트 — 불일치 행은 저장하지 않는다 (SPEC-COLLECTOR-MARKETIND-005 TASK-A)")
        void targetDateGate_dropsMismatchedRow() {
            LocalDate target = LocalDate.of(2026, 6, 20);
            LocalDate mismatched = LocalDate.of(2026, 6, 19);
            when(usdkrwChain.fetchDaily(target))
                    .thenReturn(List.of(usdkrwRow(target), usdkrwRow(mismatched)));

            service.collectDaily(target);

            verify(marketIndicatorInserter).insertBatch(inserterCaptor.capture());
            List<MarketIndicator> inserted =
                    inserterCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertThat(inserted).hasSize(1);
            assertThat(inserted.getFirst().getTradeDate()).isEqualTo(target);
        }
    }

    @Nested
    @DisplayName("collectHistory — 전체 이력 수집")
    class CollectHistory {

        @Test
        @DisplayName("다수 행 저장")
        void multipleRows_allInserted() {
            List<MarketIndicatorRow> rows =
                    List.of(
                            usdkrwRow(LocalDate.of(2026, 1, 2)),
                            usdkrwRow(LocalDate.of(2026, 1, 5)));
            when(usdkrwChain.fetchHistory()).thenReturn(rows);

            int count = service.collectHistory();

            assertThat(count).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName(
            "collectDailyForBackfillWithRaw — target 게이트 + raw 의미 (SPEC-COLLECTOR-MARKETIND-005"
                    + " TASK-A)")
    class CollectDailyForBackfillWithRaw {

        @Test
        @DisplayName("AC-A4: 정상 동작 — 게이트 통과 후 raw == kept == 계약 준수 응답 행수")
        void normalOperation_rawEqualsKept() {
            LocalDate target = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(target)).thenReturn(List.of(usdkrwRow(target)));

            UsdkrwCollectionService.SaveOutcome outcome =
                    service.collectDailyForBackfillWithRaw(target);

            assertThat(outcome.kept()).isEqualTo(1);
            assertThat(outcome.raw()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A4: target 불일치 행은 게이트에서 탈락 — raw/kept 모두 게이트 통과 후 행수로 카운트")
        void mismatchedRow_droppedByGate_rawExcludesIt() {
            LocalDate target = LocalDate.of(2026, 6, 20);
            LocalDate mismatched = LocalDate.of(2026, 6, 19);
            when(usdkrwChain.fetchDaily(target))
                    .thenReturn(List.of(usdkrwRow(target), usdkrwRow(mismatched)));

            UsdkrwCollectionService.SaveOutcome outcome =
                    service.collectDailyForBackfillWithRaw(target);

            assertThat(outcome.kept()).isEqualTo(1);
            assertThat(outcome.raw()).isEqualTo(1);
        }

        @Test
        @DisplayName("빈 결과 — raw == kept == 0")
        void emptyResult_rawAndKeptZero() {
            LocalDate target = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(target)).thenReturn(List.of());

            UsdkrwCollectionService.SaveOutcome outcome =
                    service.collectDailyForBackfillWithRaw(target);

            assertThat(outcome.kept()).isEqualTo(0);
            assertThat(outcome.raw()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName(
            "saveBackfillRows — 백필 backward walk 전용 저장 진입점 (SPEC-COLLECTOR-MARKETIND-004 REQ-020)")
    class SaveBackfillRows {

        @Test
        @DisplayName("조회 없이 전달받은 행만 저장 — usdkrwChain 미호출")
        void savesGivenRows_withoutFetchingFromChain() {
            LocalDate date = LocalDate.of(2015, 6, 10);

            int kept = service.saveBackfillRows(List.of(usdkrwRow(date)));

            assertThat(kept).isEqualTo(1);
            verify(marketIndicatorInserter).insertBatch(inserterCaptor.capture());
            List<MarketIndicator> inserted =
                    inserterCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertThat(inserted).hasSize(1);
            assertThat(inserted.getFirst().getIndicatorCode()).isEqualTo(IndicatorCode.USDKRW);
            verify(usdkrwChain, never()).fetchDaily(any());
        }

        @Test
        @DisplayName("빈 목록 — insertBatch 미호출, kept=0")
        void emptyRows_noInsert() {
            int kept = service.saveBackfillRows(List.of());

            assertThat(kept).isEqualTo(0);
            verify(marketIndicatorInserter, never()).insertBatch(any());
        }
    }
}
