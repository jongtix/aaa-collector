package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.etf.EtfMetaInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EtfMetaInfo 단위 테스트")
class EtfMetaInfoTest {

    @Nested
    @DisplayName("buildGroupKey — group_key 생성")
    class BuildGroupKey {

        @Test
        @DisplayName("일반 ETF — KOSPI:069500:1:NORMAL:false")
        void buildGroupKey_normalEtf_correctFormat() {
            EtfMetaInfo meta = new EtfMetaInfo("069500", 1, false, false, false);

            assertThat(meta.buildGroupKey("KOSPI")).isEqualTo("KOSPI:069500:1:NORMAL:false");
        }

        @Test
        @DisplayName("인버스 ETF — 방향 INVERSE")
        void buildGroupKey_inverseEtf_inverseDirection() {
            EtfMetaInfo meta = new EtfMetaInfo("069500", 1, true, false, false);

            assertThat(meta.buildGroupKey("KOSPI")).isEqualTo("KOSPI:069500:1:INVERSE:false");
        }

        @Test
        @DisplayName("레버리지 2배 ETF")
        void buildGroupKey_leveragedEtf_leverageInKey() {
            EtfMetaInfo meta = new EtfMetaInfo("069500", 2, false, false, false);

            assertThat(meta.buildGroupKey("KOSPI")).isEqualTo("KOSPI:069500:2:NORMAL:false");
        }

        @Test
        @DisplayName("환헤지 ETF — hedged=true")
        void buildGroupKey_hedgedEtf_hedgedTrue() {
            EtfMetaInfo meta = new EtfMetaInfo("069500", 1, false, true, false);

            assertThat(meta.buildGroupKey("KOSPI")).isEqualTo("KOSPI:069500:1:NORMAL:true");
        }

        @Test
        @DisplayName("underlyingIndexCode null → UNKNOWN 사용")
        void buildGroupKey_nullIndexCode_usesUnknown() {
            EtfMetaInfo meta = new EtfMetaInfo(null, 1, false, false, false);

            assertThat(meta.buildGroupKey("KOSPI")).isEqualTo("KOSPI:UNKNOWN:1:NORMAL:false");
        }

        @Test
        @DisplayName("해외 ETF — NASDAQ 시장명 사용")
        void buildGroupKey_overseasEtf_marketNameInKey() {
            EtfMetaInfo meta = new EtfMetaInfo("SPY500", 1, false, false, false);

            assertThat(meta.buildGroupKey("NASDAQ")).isEqualTo("NASDAQ:SPY500:1:NORMAL:false");
        }
    }

    @Nested
    @DisplayName("trStop 필드")
    class TrStopField {

        @Test
        @DisplayName("trStop=true 설정")
        void trStop_true_reflected() {
            EtfMetaInfo meta = new EtfMetaInfo("069500", 1, false, false, true);

            assertThat(meta.trStop()).isTrue();
        }

        @Test
        @DisplayName("trStop=false 기본값")
        void trStop_false_default() {
            EtfMetaInfo meta = new EtfMetaInfo("069500", 1, false, false, false);

            assertThat(meta.trStop()).isFalse();
        }
    }
}
