package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillPendingSlotsWarmStarter — 부팅 시 pending_slots 게이지 초기화 (REQ-WM-029, MI-02)")
class BackfillPendingSlotsWarmStarterTest {

    @Mock private BackfillMetrics backfillMetrics;
    @Mock private BackfillStatusRepository backfillStatusRepository;

    @InjectMocks private BackfillPendingSlotsWarmStarter warmStarter;

    @Nested
    @DisplayName("정상 warm-start")
    class NormalWarmStart {

        @Test
        @DisplayName("리포지토리 조회 결과로 setPendingSlots를 호출한다")
        void warmsPendingSlots() {
            when(backfillStatusRepository.countByStatusInAndTargetType(any(), anyString()))
                    .thenReturn(7L);

            warmStarter.run(null);

            verify(backfillMetrics).setPendingSlots(7L);
        }
    }

    @Nested
    @DisplayName("실패 격리")
    class FailureIsolation {

        @Test
        @DisplayName("조회 실패 시 예외 없이 완료된다 (0 유지)")
        void continuesOnFailure() {
            when(backfillStatusRepository.countByStatusInAndTargetType(any(), anyString()))
                    .thenThrow(new QueryTimeoutException("DB 오류"));

            assertThatNoException().isThrownBy(() -> warmStarter.run(null));
        }
    }
}
