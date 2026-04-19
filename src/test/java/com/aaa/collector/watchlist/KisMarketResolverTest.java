package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KisMarketResolver — 시장 코드 매핑")
class KisMarketResolverTest {

    @Nested
    @DisplayName("국내 시장")
    class Domestic {

        @Test
        @DisplayName("J → KOSPI")
        void resolve_J_returnsKospi() {
            assertThat(KisMarketResolver.resolve("J", "KRX")).isEqualTo(Market.KOSPI);
        }

        @Test
        @DisplayName("UN → KOSDAQ")
        void resolve_UN_returnsKosdaq() {
            assertThat(KisMarketResolver.resolve("UN", "KRX")).isEqualTo(Market.KOSDAQ);
        }

        @Test
        @DisplayName("U → KRX (한국 지수)")
        void resolve_U_returnsKrx() {
            assertThat(KisMarketResolver.resolve("U", "KRX")).isEqualTo(Market.KRX);
        }

        @Test
        @DisplayName("N → US (미국 지수)")
        void resolve_N_returnsUs() {
            assertThat(KisMarketResolver.resolve("N", "KRX")).isEqualTo(Market.US);
        }
    }

    @Nested
    @DisplayName("해외 시장 (FS)")
    class Overseas {

        @Test
        @DisplayName("FS + NAS → NASDAQ")
        void resolve_FS_NAS_returnsNasdaq() {
            assertThat(KisMarketResolver.resolve("FS", "NAS")).isEqualTo(Market.NASDAQ);
        }

        @Test
        @DisplayName("FS + NYS → NYSE")
        void resolve_FS_NYS_returnsNyse() {
            assertThat(KisMarketResolver.resolve("FS", "NYS")).isEqualTo(Market.NYSE);
        }

        @Test
        @DisplayName("FS + AMS → AMEX")
        void resolve_FS_AMS_returnsAmex() {
            assertThat(KisMarketResolver.resolve("FS", "AMS")).isEqualTo(Market.AMEX);
        }

        @Test
        @DisplayName("FS + 알 수 없는 거래소 → null")
        void resolve_FS_unknownExch_returnsNull() {
            assertThat(KisMarketResolver.resolve("FS", "TSE")).isNull();
        }
    }

    @Nested
    @DisplayName("알 수 없는 코드")
    class Unknown {

        @Test
        @DisplayName("알 수 없는 시장 코드 → null")
        void resolve_unknown_returnsNull() {
            assertThat(KisMarketResolver.resolve("Z", "UNK")).isNull();
        }
    }
}
