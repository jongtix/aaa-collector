package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BackfillWindowOutcome} GROUP_C 팩토리 단위 테스트 (SPEC-COLLECTOR-BACKFILL-GROUPC-001 T-001,
 * REQ-GC-001/004, AC-1 부분).
 */
@DisplayName("BackfillWindowOutcome — GROUP_C 팩토리")
class BackfillWindowOutcomeTest {

    @Test
    @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
    @DisplayName("groupC() — group=GROUP_C, 나머지 필드는 무해값(0/null)")
    void groupC_returnsMinimalHarmlessOutcome() {
        BackfillWindowOutcome outcome = BackfillWindowOutcome.groupC();

        assertThat(outcome.group()).isEqualTo(BackfillGroup.GROUP_C);
        assertThat(outcome.rowCount()).isZero();
        assertThat(outcome.rawRowCount()).isZero();
        assertThat(outcome.oldestTradeDate()).isNull();
        assertThat(outcome.previousOldest()).isNull();
        assertThat(outcome.previousRowCount()).isNull();
        assertThat(outcome.currentStaleCount()).isZero();
    }
}
