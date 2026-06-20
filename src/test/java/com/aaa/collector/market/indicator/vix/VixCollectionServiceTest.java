package com.aaa.collector.market.indicator.vix;

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
@DisplayName("VixCollectionService 단위 테스트")
class VixCollectionServiceTest {

    @Mock private MarketIndicatorSourceChain vixChain;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;

    private VixCollectionService service;

    @BeforeEach
    void setUp() {
        service = new VixCollectionService(vixChain, marketIndicatorRepository);
    }

    private MarketIndicatorRow vixRow(LocalDate date) {
        return new MarketIndicatorRow(
                IndicatorCode.VIX,
                date,
                new BigDecimal("18.0000"),
                new BigDecimal("19.0000"),
                new BigDecimal("17.0000"),
                new BigDecimal("18.5000"),
                "CBOE");
    }

    @Nested
    @DisplayName("collectDaily — 일봉 수집")
    class CollectDaily {

        @Test
        @DisplayName("정상 수집 — insertIgnoreDuplicate 호출")
        void normalCollect_insertsRow() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(vixChain.fetchDaily(date)).thenReturn(List.of(vixRow(date)));

            service.collectDaily(date);

            ArgumentCaptor<MarketIndicator> captor = ArgumentCaptor.forClass(MarketIndicator.class);
            verify(marketIndicatorRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getIndicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(captor.getValue().getCloseValue()).isEqualByComparingTo("18.5000");
        }

        @Test
        @DisplayName("빈 결과 — insertIgnoreDuplicate 미호출")
        void emptyResult_noInsert() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(vixChain.fetchDaily(date)).thenReturn(List.of());

            service.collectDaily(date);

            verify(marketIndicatorRepository, never()).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("close null 행 skip (REQ-034)")
        void nullCloseRow_skipped() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            MarketIndicatorRow badRow =
                    new MarketIndicatorRow(IndicatorCode.VIX, date, null, null, null, null, "CBOE");
            when(vixChain.fetchDaily(date)).thenReturn(List.of(badRow));

            service.collectDaily(date);

            verify(marketIndicatorRepository, never()).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("체인 예외 — 전파 없음 (REQ-003)")
        void chainException_notPropagated() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(vixChain.fetchDaily(date)).thenThrow(new RuntimeException("수집 실패"));

            assertThatCode(() -> service.collectDaily(date)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("collectHistory — 전체 이력 수집")
    class CollectHistory {

        @Test
        @DisplayName("다수 행 삽입")
        void multipleRows_allInserted() {
            List<MarketIndicatorRow> rows =
                    List.of(vixRow(LocalDate.of(2026, 1, 2)), vixRow(LocalDate.of(2026, 1, 5)));
            when(vixChain.fetchHistory()).thenReturn(rows);

            int count = service.collectHistory();

            assertThat(count).isEqualTo(2);
        }
    }
}
