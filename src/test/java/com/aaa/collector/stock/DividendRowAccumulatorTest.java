package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * DividendRowAccumulator 단위 테스트 (SPEC-COLLECTOR-DIVIDEND-FIX-001 T3).
 *
 * <p>REQ-DIVFIX-020~022(미확정 defer), REQ-DIVFIX-030~032(skip 판정·rate-only 저장), RD-6(날짜 무관 무조건
 * defer), RD-7(rate-only 원본 저장·역산 금지)을 검증한다.
 */
@DisplayName("DividendRowAccumulator 단위 테스트")
class DividendRowAccumulatorTest {

    private DividendRowAccumulator accumulator;
    private Stock stock;
    private List<CorporateEvent> batch;

    @BeforeEach
    void setUp() {
        accumulator = new DividendRowAccumulator();
        stock =
                Stock.builder()
                        .symbol("005930")
                        .nameKo("삼성전자")
                        .market(Market.KRX)
                        .assetType(AssetType.STOCK)
                        .active(true)
                        .build();
        batch = new ArrayList<>();
    }

    private KisDividendScheduleResponse.DividendRow row(
            String recordDate, String perStoDiviAmt, String diviRate, String faceVal) {
        return new KisDividendScheduleResponse.DividendRow(
                "005930",
                recordDate,
                "결산배당",
                perStoDiviAmt,
                diviRate,
                "0.00",
                "20260814",
                "",
                "",
                faceVal,
                "보통주",
                "N");
    }

    @Nested
    @DisplayName("확정 행 — 정상 매핑·배치 추가")
    class ConfirmedRow {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // 매핑 필드 전체 검증을 한 테스트에서 수행
        @DisplayName("cash_amount·cash_rate 모두 0이 아니면 배치에 추가되고 카운터는 증가하지 않는다")
        void confirmedRow_addedToBatch() {
            // Arrange
            KisDividendScheduleResponse.DividendRow confirmedRow =
                    row("20260612", "361", "0.50", "100");

            // Act
            accumulator.buildRow(confirmedRow, stock, batch);

            // Assert
            assertThat(batch).hasSize(1);
            CorporateEvent saved = batch.getFirst();
            assertThat(saved.getEventType()).isEqualTo(EventType.DIVIDEND);
            assertThat(saved.getCashAmount()).isEqualByComparingTo("361");
            assertThat(saved.getCashRate()).isEqualByComparingTo("0.5000");
            assertThat(accumulator.skippedUnconfirmed()).isZero();
            assertThat(accumulator.skippedValidation()).isZero();
        }
    }

    @Nested
    @DisplayName("0/0 무조건 defer (REQ-DIVFIX-020/030, RD-6 — 날짜 경계 없음)")
    class UnconditionalDefer {

        @Test
        @DisplayName("과거 record_date + amt=0 + rate=0.00 — defer (미생성), skippedUnconfirmed 증가")
        void pastRecordDate_zeroAmountZeroRate_defers() {
            // Arrange — 2020년(과거) 기준일
            KisDividendScheduleResponse.DividendRow pastZeroRow =
                    row("20200101", "0", "0.00", "5000");

            // Act
            accumulator.buildRow(pastZeroRow, stock, batch);

            // Assert
            assertThat(batch).isEmpty();
            assertThat(accumulator.skippedUnconfirmed()).isEqualTo(1);
        }

        @Test
        @DisplayName(
                "미래 record_date + amt=0 + rate=0.00 — defer (미생성), skippedUnconfirmed 증가 — 과거와 동일 동작(날짜 무관)")
        void futureRecordDate_zeroAmountZeroRate_defers() {
            // Arrange — 2099년(미래) 기준일
            KisDividendScheduleResponse.DividendRow futureZeroRow =
                    row("20990101", "0", "0.00", "5000");

            // Act
            accumulator.buildRow(futureZeroRow, stock, batch);

            // Assert — 과거 기준일 케이스와 완전히 동일하게 defer됨(날짜 경계 없음 증명)
            assertThat(batch).isEmpty();
            assertThat(accumulator.skippedUnconfirmed()).isEqualTo(1);
        }

        @Test
        @DisplayName("복수 0/0 행 — skippedUnconfirmed가 누적된다")
        void multipleZeroRows_counterAccumulates() {
            accumulator.buildRow(row("20200101", "0", "0.00", "5000"), stock, batch);
            accumulator.buildRow(row("20990101", "0", "0.00", "5000"), stock, batch);

            assertThat(batch).isEmpty();
            assertThat(accumulator.skippedUnconfirmed()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("rate-only 행 원본 저장 (REQ-DIVFIX-032, RD-7 — 역산 금지)")
    class RateOnlyRow {

        @Test
        @DisplayName("amt=0, rate!=0 — defer 대상 아님. cash_amount=0 그대로, face_val×rate/100 역산 없음")
        void amountZeroRateNonZero_storedAsIs_noBackComputation() {
            // Arrange — amt=0, rate=2.50%, face_val=5000 → 역산하면 125가 되어야 하나 역산 금지(RD-7)
            KisDividendScheduleResponse.DividendRow rateOnlyRow =
                    row("20260612", "0", "2.50", "5000");

            // Act
            accumulator.buildRow(rateOnlyRow, stock, batch);

            // Assert — defer 아님, 원본 그대로 저장
            assertThat(accumulator.skippedUnconfirmed()).isZero();
            assertThat(batch).hasSize(1);
            CorporateEvent saved = batch.getFirst();
            assertThat(saved.getCashAmount()).isEqualByComparingTo("0");
            assertThat(saved.getCashRate()).isEqualByComparingTo("2.5000");
            assertThat(saved.getFaceValue()).isEqualTo(5000L);
        }
    }

    @Nested
    @DisplayName("검증 실패 skip (REQ-BATCH3-070)")
    class ValidationSkip {

        @Test
        @DisplayName("record_date null — skippedValidation 증가, 배치 미추가")
        void nullRecordDate_skipsValidation() {
            KisDividendScheduleResponse.DividendRow nullDateRow = row(null, "500", "2.50", "5000");

            accumulator.buildRow(nullDateRow, stock, batch);

            assertThat(batch).isEmpty();
            assertThat(accumulator.skippedValidation()).isEqualTo(1);
            assertThat(accumulator.skippedUnconfirmed()).isZero();
        }
    }
}
