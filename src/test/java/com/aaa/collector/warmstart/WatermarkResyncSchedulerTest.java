package com.aaa.collector.warmstart;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WatermarkResyncScheduler — 일 1회 워터마크 재동기화 (REQ-WM-004)")
class WatermarkResyncSchedulerTest {

    @Mock private WatermarkWarmStarter watermarkWarmStarter;

    @InjectMocks private WatermarkResyncScheduler scheduler;

    @Test
    @DisplayName("resync() 호출 시 WatermarkWarmStarter.resyncAll()에 위임한다 (부팅 warm-start와 쿼리 집합 공유)")
    void resync_delegatesToWarmStarterResyncAll() {
        scheduler.resync();

        verify(watermarkWarmStarter).resyncAll();
    }
}
