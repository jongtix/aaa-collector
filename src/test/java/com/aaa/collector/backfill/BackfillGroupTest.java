package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BackfillGroup} data_table 분류 단위 테스트.
 *
 * <p>[SPEC-COLLECTOR-BACKFILL-009 REQ-BACKFILL-145] {@code corporate_events_dividend}(DIVIDEND 과거
 * 백필)의 GROUP_A 분류를 고정한다 — SPLIT(`corporate_events`)과 동일하게 "100건 미만=완료" 종료 판정 패턴에 부합한다.
 */
@DisplayName("BackfillGroup — data_table 종료 규칙 분류")
class BackfillGroupTest {

    @Test
    @DisplayName("REQ-BACKFILL-145: corporate_events_dividend → GROUP_A (SPLIT과 동일 종료 규칙)")
    void dividendBackfillTable_isGroupA() {
        assertThat(BackfillGroup.ofDataTable("corporate_events_dividend"))
                .isEqualTo(BackfillGroup.GROUP_A);
    }

    @Test
    @DisplayName("daily_ohlcv → GROUP_A (기존 분류 불변)")
    void existingGroupATables_unchanged() {
        assertThat(BackfillGroup.ofDataTable("daily_ohlcv")).isEqualTo(BackfillGroup.GROUP_A);
    }

    @Test
    @DisplayName("REQ-GC-002: corporate_events(SPLIT) → GROUP_C (GROUP_A에서 이관, #82 접두어 노이즈 대응)")
    void corporateEventsSplitTable_isGroupC() {
        assertThat(BackfillGroup.ofDataTable("corporate_events")).isEqualTo(BackfillGroup.GROUP_C);
    }

    @Test
    @DisplayName("수급 3종은 GROUP_B (100건 미만 종료 규칙 미적용, 분류 불변)")
    void supplyTables_areGroupB() {
        assertThat(BackfillGroup.ofDataTable("short_sale_domestic"))
                .isEqualTo(BackfillGroup.GROUP_B);
        assertThat(BackfillGroup.ofDataTable("investor_trend")).isEqualTo(BackfillGroup.GROUP_B);
        assertThat(BackfillGroup.ofDataTable("credit_balance")).isEqualTo(BackfillGroup.GROUP_B);
    }

    @Test
    @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
    @DisplayName("AC-1 (REQ-GC-001/002/005): 6종 data_table 분류 고정 매트릭스")
    void ac1_sixTableClassificationMatrix() {
        assertThat(BackfillGroup.ofDataTable("corporate_events")).isEqualTo(BackfillGroup.GROUP_C);
        assertThat(BackfillGroup.ofDataTable("corporate_events_dividend"))
                .isEqualTo(BackfillGroup.GROUP_A);
        assertThat(BackfillGroup.ofDataTable("daily_ohlcv")).isEqualTo(BackfillGroup.GROUP_A);
        assertThat(BackfillGroup.ofDataTable("short_sale_domestic"))
                .isEqualTo(BackfillGroup.GROUP_B);
        assertThat(BackfillGroup.ofDataTable("investor_trend")).isEqualTo(BackfillGroup.GROUP_B);
        assertThat(BackfillGroup.ofDataTable("credit_balance")).isEqualTo(BackfillGroup.GROUP_B);
    }
}
