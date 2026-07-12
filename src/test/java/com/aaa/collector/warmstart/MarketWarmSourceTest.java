package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketWarmSource — 시장 필터 warm-start seed 소스 (결합도 관리 추출)")
class MarketWarmSourceTest {

    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private CorporateEventRepository corporateEventRepository;

    private MarketWarmSource source() {
        return new MarketWarmSource(dailyOhlcvRepository, corporateEventRepository);
    }

    @Test
    @DisplayName("domesticDailyLastLoad — 국내 시장(KOSPI/KOSDAQ/KRX) 필터 쿼리 결과를 반환한다")
    void domesticDailyUsesDomesticMarkets() {
        LocalDateTime kstTime = LocalDateTime.of(2026, 7, 12, 16, 0, 0);
        // 정확한 국내 시장 리스트로 stub — 소스가 다른 리스트를 쓰면 매칭 실패로 empty가 되어 단언이 깨진다.
        when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(
                        List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX)))
                .thenReturn(Optional.of(kstTime));

        assertThat(source().domesticDailyLastLoad()).contains(kstTime);
    }

    @Test
    @DisplayName("overseasDailyLastLoad — 해외 시장(NYSE/NASDAQ/AMEX/US) 필터 쿼리 결과를 반환한다")
    void overseasDailyUsesOverseasMarkets() {
        LocalDateTime kstTime = LocalDateTime.of(2026, 7, 12, 6, 0, 0);
        when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(
                        List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US)))
                .thenReturn(Optional.of(kstTime));

        assertThat(source().overseasDailyLastLoad()).contains(kstTime);
    }

    @Test
    @DisplayName("overseasCorporateEventLastLoad — 해외 시장 필터로 corporate_events 최신 시각을 반환한다")
    void overseasCorporateEventUsesOverseasMarkets() {
        LocalDateTime kstTime = LocalDateTime.of(2026, 7, 10, 6, 0, 0);
        when(corporateEventRepository.findMaxCreatedAtByMarketsIn(
                        List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US)))
                .thenReturn(Optional.of(kstTime));

        assertThat(source().overseasCorporateEventLastLoad()).contains(kstTime);
    }

    @Test
    @DisplayName("조회 결과가 없으면 Optional.empty()를 그대로 전달한다")
    void passesThroughEmpty() {
        when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(
                        List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX)))
                .thenReturn(Optional.empty());

        assertThat(source().domesticDailyLastLoad()).isEmpty();
    }
}
