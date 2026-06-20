package com.aaa.collector.market.indicator.usdkrw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.MarketIndicator;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsdkrwCollectionService 단위 테스트")
class UsdkrwCollectionServiceTest {

    @Mock private MarketIndicatorSourceChain usdkrwChain;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;

    private UsdkrwCollectionService service;

    @BeforeEach
    void setUp() {
        service = new UsdkrwCollectionService(usdkrwChain, marketIndicatorRepository);
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
        @DisplayName("정상 수집 — insertIgnoreDuplicate 호출, USDKRW 엔티티")
        void normalCollect_insertsUsdkrwRow() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(date)).thenReturn(List.of(usdkrwRow(date)));

            service.collectDaily(date);

            ArgumentCaptor<MarketIndicator> captor = ArgumentCaptor.forClass(MarketIndicator.class);
            verify(marketIndicatorRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getIndicatorCode()).isEqualTo(IndicatorCode.USDKRW);
            assertThat(captor.getValue().getCloseValue()).isEqualByComparingTo("1380.0000");
        }

        @Test
        @DisplayName("빈 결과 — insertIgnoreDuplicate 미호출")
        void emptyResult_noInsert() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(date)).thenReturn(List.of());

            service.collectDaily(date);

            verify(marketIndicatorRepository, never()).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("체인 예외 — 전파 없음 (REQ-003)")
        void chainException_notPropagated() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(usdkrwChain.fetchDaily(date)).thenThrow(new RuntimeException("수집 실패"));

            assertThatCode(() -> service.collectDaily(date)).doesNotThrowAnyException();
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
}
