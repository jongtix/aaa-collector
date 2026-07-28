package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.DelistingReason;
import com.aaa.collector.stock.enums.ListingStatus;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
@DisplayName("Stock.correctMetadata — 시장 교정·상장일 채우기 (REQ-STOCKMETA-004,011,012)")
class StockTest {

    private static Stock kospiStockNullDate() {
        return Stock.builder()
                .symbol("005930")
                .nameKo("삼성전자")
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .build();
    }

    private static Stock kosdaqStockWithDate(LocalDate listedDate) {
        return Stock.builder()
                .symbol("247540")
                .nameKo("에코프로비엠")
                .market(Market.KOSDAQ)
                .assetType(AssetType.STOCK)
                .listedDate(listedDate)
                .build();
    }

    @Nested
    @DisplayName("시장 교정 (AC-4, REQ-STOCKMETA-011)")
    class MarketCorrection {

        @Test
        @DisplayName("저장 시장이 권위 시장과 다르면 교정되고 true 반환")
        void correctMetadata_differentMarket_corrected() {
            // Arrange — KOSPI로 저장된 종목이 실제로는 KOSDAQ
            Stock stock =
                    Stock.builder()
                            .symbol("247540")
                            .nameKo("에코프로비엠")
                            .market(Market.KOSPI) // 잘못 저장된 시장
                            .assetType(AssetType.STOCK)
                            .build();

            // Act
            boolean changed = stock.correctMetadata(Market.KOSDAQ, null);

            // Assert
            assertThat(changed).isTrue();
            assertThat(stock.getMarket()).isEqualTo(Market.KOSDAQ);
        }

        @Test
        @DisplayName("저장 시장이 권위 시장과 같으면 변경 없이 false 반환")
        void correctMetadata_sameMarket_noChange() {
            Stock stock = kosdaqStockWithDate(LocalDate.of(2019, 3, 15));

            boolean changed = stock.correctMetadata(Market.KOSDAQ, null);

            assertThat(changed).isFalse();
            assertThat(stock.getMarket()).isEqualTo(Market.KOSDAQ);
        }

        @Test
        @DisplayName("권위 시장이 null이면 시장 교정을 수행하지 않음")
        void correctMetadata_nullAuthoritativeMarket_noMarketChange() {
            Stock stock = kosdaqStockWithDate(LocalDate.of(2019, 3, 15));

            boolean changed = stock.correctMetadata(null, null);

            assertThat(changed).isFalse();
            assertThat(stock.getMarket()).isEqualTo(Market.KOSDAQ);
        }
    }

    @Nested
    @DisplayName("상장일 채우기 (AC-5, REQ-STOCKMETA-012)")
    class ListedDateFill {

        @Test
        @DisplayName("저장 상장일이 null이고 권위 상장일이 non-null이면 채워지고 true 반환")
        void correctMetadata_nullListedDateFilledByAuthoritative() {
            // Arrange — 상장일 없이 저장된 종목
            Stock stock = kospiStockNullDate();
            LocalDate authoritativeDate = LocalDate.of(2000, 1, 1);

            // Act
            boolean changed = stock.correctMetadata(null, authoritativeDate);

            // Assert
            assertThat(changed).isTrue();
            assertThat(stock.getListedDate()).isEqualTo(authoritativeDate);
        }

        @Test
        @DisplayName("저장 상장일이 이미 non-null이면 덮어쓰지 않음")
        void correctMetadata_existingListedDate_notOverwritten() {
            LocalDate existing = LocalDate.of(2019, 3, 15);
            Stock stock = kosdaqStockWithDate(existing);
            LocalDate different = LocalDate.of(2020, 1, 1);

            boolean changed = stock.correctMetadata(null, different);

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(existing);
        }

        @Test
        @DisplayName("저장 상장일이 null이고 권위 상장일도 null이면 변경 없음")
        void correctMetadata_bothNullListedDate_noChange() {
            Stock stock = kospiStockNullDate();

            boolean changed = stock.correctMetadata(null, null);

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isNull();
        }
    }

    @Nested
    @DisplayName("시장 교정 + 상장일 채우기 동시 수행")
    class CombinedCorrection {

        @Test
        @DisplayName("시장과 상장일 모두 교정 대상이면 둘 다 교정되고 true 반환")
        void correctMetadata_bothMarketAndDateCorrected() {
            // Arrange — 잘못된 시장 + null 상장일
            Stock stock =
                    Stock.builder()
                            .symbol("247540")
                            .nameKo("에코프로비엠")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .build();
            LocalDate authoritativeDate = LocalDate.of(2019, 3, 15);

            // Act
            boolean changed = stock.correctMetadata(Market.KOSDAQ, authoritativeDate);

            // Assert
            assertThat(changed).isTrue();
            assertThat(stock.getMarket()).isEqualTo(Market.KOSDAQ);
            assertThat(stock.getListedDate()).isEqualTo(authoritativeDate);
        }

        @Test
        @DisplayName("시장과 상장일 모두 교정 불필요이면 false 반환")
        void correctMetadata_nothingToCorrect_false() {
            LocalDate date = LocalDate.of(2019, 3, 15);
            Stock stock = kosdaqStockWithDate(date);

            boolean changed = stock.correctMetadata(Market.KOSDAQ, LocalDate.of(2020, 1, 1));

            assertThat(changed).isFalse();
            assertThat(stock.getMarket()).isEqualTo(Market.KOSDAQ);
            assertThat(stock.getListedDate()).isEqualTo(date);
        }
    }

    @Nested
    @DisplayName(
            "correctListedDateDownTo — listed_date 하향 전용 보정 (SPEC-COLLECTOR-BACKFILL-010 REQ-159, AC-16)")
    class CorrectListedDateDownTo {

        @Test
        @DisplayName("MIN(trade_date) < listed_date → 하향 보정되고 true 반환")
        void minBeforeListedDate_correctsDown() {
            LocalDate overestimated = LocalDate.of(2024, 11, 26); // 거래소 이전일(과대평가)
            Stock stock = kosdaqStockWithDate(overestimated);
            LocalDate trueMin = LocalDate.of(2020, 9, 30); // 진짜 IPO

            boolean changed = stock.correctListedDateDownTo(trueMin);

            assertThat(changed).isTrue();
            assertThat(stock.getListedDate()).isEqualTo(trueMin);
        }

        @Test
        @DisplayName("MIN(trade_date) == listed_date(정합) → 변경 없음, false 반환")
        void minEqualsListedDate_noChange() {
            LocalDate date = LocalDate.of(2020, 9, 30);
            Stock stock = kosdaqStockWithDate(date);

            boolean changed = stock.correctListedDateDownTo(date);

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(date);
        }

        @Test
        @DisplayName("MIN(trade_date) > listed_date(정상, 상장 후 시작) → 상향 보정 금지, false 반환")
        void minAfterListedDate_neverCorrectsUp() {
            LocalDate date = LocalDate.of(2015, 1, 1);
            Stock stock = kosdaqStockWithDate(date);
            LocalDate laterMin = LocalDate.of(2015, 6, 1);

            boolean changed = stock.correctListedDateDownTo(laterMin);

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(date); // 불변 — 상향 보정 없음
        }

        @Test
        @DisplayName("listed_date가 NULL이면 보정 대상 아님 — false 반환, null 유지")
        void listedDateNull_noCorrection() {
            Stock stock = kospiStockNullDate();

            boolean changed = stock.correctListedDateDownTo(LocalDate.of(2020, 1, 1));

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isNull();
        }

        @Test
        @DisplayName("min이 NULL(daily_ohlcv 행 없음)이면 보정 대상 아님 — false 반환")
        void minNull_noCorrection() {
            LocalDate date = LocalDate.of(2020, 1, 1);
            Stock stock = kosdaqStockWithDate(date);

            boolean changed = stock.correctListedDateDownTo(null);

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(date);
        }
    }

    // @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002
    @Nested
    @DisplayName("운영자 오버라이드 표시 가드 — 방향 무관 자동 경로 차단 (REQ-SSOG-023,028(g),029,030,031)")
    class ListedDateOverrideGuard {

        private static Stock overriddenOverseasStock(LocalDate listedDate) {
            return Stock.builder()
                    .symbol("RKLB")
                    .nameEn("Rocket Lab")
                    .market(Market.NASDAQ)
                    .assetType(AssetType.STOCK)
                    .active(true)
                    .listedDate(listedDate)
                    .listedDateOverridden(true)
                    .build();
        }

        @Test
        @DisplayName("표시 TRUE + 취득값이 더 이름 → correctListedDateDownTo no-op(하향 차단, AC-23)")
        void overridden_acquiredEarlier_downCorrectionBlocked() {
            LocalDate overridden = LocalDate.of(2021, 8, 25);
            Stock stock = overriddenOverseasStock(overridden);

            boolean changed = stock.correctListedDateDownTo(LocalDate.of(2020, 11, 24));

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(overridden);
        }

        @Test
        @DisplayName("표시 TRUE + 취득값이 더 늦음 → 종전대로 no-op(상향 불가 회귀 고정)")
        void overridden_acquiredLater_neverCorrectsUp() {
            LocalDate overridden = LocalDate.of(1920, 1, 1);
            Stock stock = overriddenOverseasStock(overridden);

            boolean changed = stock.correctListedDateDownTo(LocalDate.of(1980, 3, 17));

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(overridden);
        }

        @Test
        @DisplayName(
                "표시 TRUE + 저장 상장일 NULL + 취득값 non-null → correctMetadata가 상장일을 채우지 않음(방어 가드, AC-29)")
        void overridden_nullListedDate_metadataFillBlocked() {
            Stock stock =
                    Stock.builder()
                            .symbol("RKLB")
                            .nameEn("Rocket Lab")
                            .market(Market.NASDAQ)
                            .assetType(AssetType.STOCK)
                            .active(true)
                            .listedDateOverridden(true)
                            .build();

            boolean changed = stock.correctMetadata(null, LocalDate.of(2020, 11, 24));

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isNull();
        }

        @Test
        @DisplayName("표시 TRUE에서도 correctMetadata의 market 교정은 정상 동작(범위 밖 회귀 가드)")
        void overridden_marketCorrectionStillApplies() {
            LocalDate overridden = LocalDate.of(2021, 8, 25);
            Stock stock =
                    Stock.builder()
                            .symbol("RKLB")
                            .nameEn("Rocket Lab")
                            .market(Market.NYSE) // 잘못 저장된 시장
                            .assetType(AssetType.STOCK)
                            .active(true)
                            .listedDate(overridden)
                            .listedDateOverridden(true)
                            .build();

            boolean changed = stock.correctMetadata(Market.NASDAQ, LocalDate.of(2020, 11, 24));

            assertThat(changed).isTrue();
            assertThat(stock.getMarket()).isEqualTo(Market.NASDAQ);
            assertThat(stock.getListedDate()).isEqualTo(overridden); // 상장일은 불변
        }

        @Test
        @DisplayName("표시 FALSE → 기존 하향 정정 동작과 완전히 동일(회귀 없음)")
        void notOverridden_downCorrectionBehavesAsBefore() {
            LocalDate overestimated = LocalDate.of(2024, 4, 18);
            Stock stock = kosdaqStockWithDate(overestimated);

            boolean changed = stock.correctListedDateDownTo(LocalDate.of(2024, 3, 8));

            assertThat(changed).isTrue();
            assertThat(stock.getListedDate()).isEqualTo(LocalDate.of(2024, 3, 8));
        }

        @Test
        @DisplayName("밀도 경로 관통 확인 — CoverageRefresher가 쓰는 동일 메서드도 호출자 무관하게 항상 no-op")
        void overridden_densityPathGuardHoldsRegardlessOfCaller() {
            // CoverageRefresher.correctListedDateAndReset()도 동일한 Stock.correctListedDateDownTo를
            // 호출한다(별도 배선 없음) — 이 테스트는 그 호출자와 무관하게 도메인 가드 자체가 항상 no-op임을
            // 고정한다(SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002 결정 6-2).
            LocalDate overridden = LocalDate.of(2021, 8, 25);
            Stock stock = overriddenOverseasStock(overridden);

            boolean changed = stock.correctListedDateDownTo(LocalDate.of(2007, 8, 20));

            assertThat(changed).isFalse();
            assertThat(stock.getListedDate()).isEqualTo(overridden);
        }
    }

    @Nested
    @DisplayName(
            "reflectListingStatus — 상폐/거래정지 상태 전이 (SPEC-COLLECTOR-WLSYNC-008"
                    + " REQ-WLSYNC-144~147,152)")
    class ReflectListingStatus {

        private static Stock activeStock() {
            return Stock.builder()
                    .symbol("010620")
                    .nameKo("HD현대미포")
                    .market(Market.KOSPI)
                    .assetType(AssetType.STOCK)
                    .active(true)
                    .build();
        }

        @Test
        @DisplayName("시나리오 6 — 상폐 최초 감지: active=false, delisted_at 설정, reason=UNKNOWN")
        void delisted_firstDetection_setsActiveFalseAndDelistedAt() {
            Stock stock = activeStock();
            LocalDate delistedDate = LocalDate.of(2025, 12, 15);

            boolean changed = stock.reflectListingStatus(ListingStatus.DELISTED, delistedDate);

            assertThat(changed).isTrue();
            assertThat(stock.isActive()).isFalse();
            assertThat(stock.getDelistedAt()).isEqualTo(delistedDate);
            assertThat(stock.getDelistingReason()).isEqualTo(DelistingReason.UNKNOWN);
        }

        @Test
        @DisplayName("시나리오 6 — 상폐 재감지(set-only 비가역): delisted_at 최초값 유지, active 그대로")
        void delisted_reDetection_isNoOpSetOnly() {
            Stock stock = activeStock();
            LocalDate firstDate = LocalDate.of(2025, 12, 15);
            stock.reflectListingStatus(ListingStatus.DELISTED, firstDate);

            boolean changed =
                    stock.reflectListingStatus(ListingStatus.DELISTED, LocalDate.of(2026, 1, 1));

            assertThat(changed).isFalse();
            assertThat(stock.getDelistedAt()).isEqualTo(firstDate);
            assertThat(stock.isActive()).isFalse();
        }

        @Test
        @DisplayName("시나리오 7 — 거래정지: active=false, delisted_at은 NULL 유지(가역)")
        void halted_setsActiveFalseWithoutDelistedAt() {
            Stock stock = activeStock();

            boolean changed = stock.reflectListingStatus(ListingStatus.HALTED, null);

            assertThat(changed).isTrue();
            assertThat(stock.isActive()).isFalse();
            assertThat(stock.getDelistedAt()).isNull();
        }

        @Test
        @DisplayName("시나리오 7 — 거래정지 해제 후 정상 재감지: active=true로 복구, delisted_at NULL 유지")
        void halted_thenNormal_recoversActive() {
            Stock stock = activeStock();
            stock.reflectListingStatus(ListingStatus.HALTED, null);

            boolean changed = stock.reflectListingStatus(ListingStatus.NORMAL, null);

            assertThat(changed).isTrue();
            assertThat(stock.isActive()).isTrue();
            assertThat(stock.getDelistedAt()).isNull();
        }

        @Test
        @DisplayName("REQ-WLSYNC-146 — 이미 active=true인 종목의 정상 재감지는 변경 없음")
        void normal_alreadyActive_noChange() {
            Stock stock = activeStock();

            boolean changed = stock.reflectListingStatus(ListingStatus.NORMAL, null);

            assertThat(changed).isFalse();
            assertThat(stock.isActive()).isTrue();
        }

        @Test
        @DisplayName("REQ-WLSYNC-152 — 상폐 확정 종목에 정상 재감지가 와도 active 복구 금지(비가역)")
        void delisted_thenNormalDetected_neverRecovers() {
            Stock stock = activeStock();
            stock.reflectListingStatus(ListingStatus.DELISTED, LocalDate.of(2025, 12, 15));

            boolean changed = stock.reflectListingStatus(ListingStatus.NORMAL, null);

            assertThat(changed).isFalse();
            assertThat(stock.isActive()).isFalse();
            assertThat(stock.getDelistedAt()).isEqualTo(LocalDate.of(2025, 12, 15));
        }

        @Test
        @DisplayName(
                "Edge case — 상폐 확정 종목에 거래정지 응답 유입 — delisted_at·active 그대로(REQ-WLSYNC-145"
                        + " 전제조건 미충족)")
        void delisted_thenHaltedDetected_staysDelisted() {
            Stock stock = activeStock();
            stock.reflectListingStatus(ListingStatus.DELISTED, LocalDate.of(2025, 12, 15));

            boolean changed = stock.reflectListingStatus(ListingStatus.HALTED, null);

            assertThat(changed).isFalse();
            assertThat(stock.isActive()).isFalse();
            assertThat(stock.getDelistedAt()).isEqualTo(LocalDate.of(2025, 12, 15));
        }

        @Test
        @DisplayName("REQ-WLSYNC-147 불변식 — 임의 입력 조합 후 delisted_at != null이면 항상 active == false")
        void invariant_delistedAtNotNullImpliesActiveFalse() {
            Stock stock = activeStock();

            stock.reflectListingStatus(ListingStatus.DELISTED, LocalDate.of(2025, 12, 15));

            if (stock.getDelistedAt() != null) {
                assertThat(stock.isActive()).isFalse();
            }
        }

        @Test
        @DisplayName("해외 가설 케이스 — 상폐 감지됐으나 상폐일자 미확보(null): delisted_at 미설정, active=false(가역)")
        void delistedWithoutDate_treatsLikeHaltedWithoutSettingDelistedAt() {
            Stock stock = activeStock();

            boolean changed = stock.reflectListingStatus(ListingStatus.DELISTED, null);

            assertThat(changed).isTrue();
            assertThat(stock.isActive()).isFalse();
            assertThat(stock.getDelistedAt()).isNull();
        }
    }
}
