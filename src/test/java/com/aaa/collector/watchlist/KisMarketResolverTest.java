package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
@DisplayName("KisMarketResolver — 시장 코드 매핑")
class KisMarketResolverTest {

    @Nested
    @DisplayName("국내 시장 (REQ-STOCKMETA-020,022)")
    class Domestic {

        @Test
        @DisplayName("J → KOSPI (coarse 라우팅 값 — mket_id_cd로 확정됨)")
        void resolve_J_returnsKospi() {
            assertThat(KisMarketResolver.resolve("J", "KRX", "005930")).isEqualTo(Market.KOSPI);
        }

        @Test
        @DisplayName("UN → KOSPI (coarse 라우팅 값 — UN→KOSDAQ 매핑 제거, REQ-STOCKMETA-020)")
        void resolve_UN_returnsKospiNotKosdaq() {
            // AC-3: UN→KOSDAQ 오매핑이 제거되어야 한다
            // fid_mrkt_cls_code="UN"은 실제 KOSPI 종목(NAVER·삼성물산 등)이 반환하는 값임(NAS 실측 2026-06-15)
            // 실제 KOSPI/KOSDAQ 확정은 parseDomestic의 mket_id_cd에서 수행됨
            Market result = KisMarketResolver.resolve("UN", "KRX", "035420");
            assertThat(result).isNotEqualTo(Market.KOSDAQ); // UN→KOSDAQ 금지(REQ-STOCKMETA-020)
            assertThat(result).isEqualTo(Market.KOSPI); // coarse 국내 라우팅
        }

        @Test
        @DisplayName("U → KRX (한국 지수)")
        void resolve_U_returnsKrx() {
            assertThat(KisMarketResolver.resolve("U", "KRX", "005930")).isEqualTo(Market.KRX);
        }

        @Test
        @DisplayName("N → US (미국 지수)")
        void resolve_N_returnsUs() {
            assertThat(KisMarketResolver.resolve("N", "KRX", "005930")).isEqualTo(Market.US);
        }
    }

    @Nested
    @DisplayName("해외 시장 (FS)")
    class Overseas {

        @Test
        @DisplayName("FS + NAS → NASDAQ")
        void resolve_FS_NAS_returnsNasdaq() {
            assertThat(KisMarketResolver.resolve("FS", "NAS", "AAPL")).isEqualTo(Market.NASDAQ);
        }

        @Test
        @DisplayName("FS + NYS → NYSE")
        void resolve_FS_NYS_returnsNyse() {
            assertThat(KisMarketResolver.resolve("FS", "NYS", "AAPL")).isEqualTo(Market.NYSE);
        }

        @Test
        @DisplayName("FS + AMS → AMEX")
        void resolve_FS_AMS_returnsAmex() {
            assertThat(KisMarketResolver.resolve("FS", "AMS", "AAPL")).isEqualTo(Market.AMEX);
        }

        @Test
        @DisplayName("FS + 알 수 없는 거래소 → null")
        void resolve_FS_unknownExch_returnsNull() {
            assertThat(KisMarketResolver.resolve("FS", "TSE", "UNKNOWN")).isNull();
        }
    }

    @Nested
    @DisplayName("알 수 없는 코드")
    class Unknown {

        @Test
        @DisplayName("알 수 없는 시장 코드 → null")
        void resolve_unknown_returnsNull() {
            assertThat(KisMarketResolver.resolve("Z", "UNK", "UNKNOWN")).isNull();
        }
    }
}
