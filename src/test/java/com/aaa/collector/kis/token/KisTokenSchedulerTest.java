package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class KisTokenSchedulerTest {

    @Mock private KisTokenService kisTokenService;

    @InjectMocks private KisTokenScheduler kisTokenScheduler;

    @Test
    @DisplayName("refreshTokens 호출 시 kisTokenService.issueAll()이 실행된다")
    void refreshTokens_callsIssueAll() {
        kisTokenScheduler.refreshTokens();

        verify(kisTokenService).issueAll();
    }

    @Test
    @DisplayName("refreshTokens의 @Scheduled cron은 '0 30 8 * * MON-FRI'이고 zone은 'Asia/Seoul'이다")
    void refreshTokens_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
        Method method = KisTokenScheduler.class.getMethod("refreshTokens");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 30 8 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
