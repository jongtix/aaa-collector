package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
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
    @DisplayName("refreshTokens 호출 시 issueAllTokens()와 issueAllApprovalKeys()가 함께 실행된다 (AC-1)")
    void refreshTokens_callsBothIssueAllTokensAndIssueAllApprovalKeys() {
        kisTokenScheduler.refreshTokens();

        verify(kisTokenService).issueAllTokens();
        verify(kisTokenService).issueAllApprovalKeys();
    }

    @Test
    @DisplayName(
            "refreshTokens의 @Scheduled cron은 '0 15 8 * * MON-FRI'이고 zone은 'Asia/Seoul'이다 (AC-4)")
    void refreshTokens_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
        Method method = KisTokenScheduler.class.getMethod("refreshTokens");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 15 8 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    // ── T-004: 주말 전용 무조건 발급 트리거 (REQ-SAFEMODE-009/010/012) ────────

    @Test
    @DisplayName(
            "refreshWeekendTokens 호출 시 issueAllTokens()만 호출되고 issueAllApprovalKeys()는 호출되지 않는다(AC-6)")
    void refreshWeekendTokens_callsOnlyIssueAllTokens() {
        kisTokenScheduler.refreshWeekendTokens();

        verify(kisTokenService).issueAllTokens();
        verify(kisTokenService, never()).issueAllApprovalKeys();
    }

    @Test
    @DisplayName(
            "refreshWeekendTokens의 @Scheduled cron은 '0 15 8 * * SAT,SUN'이고 zone은 'Asia/Seoul'이다(D-3)")
    void refreshWeekendTokens_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
        Method method = KisTokenScheduler.class.getMethod("refreshWeekendTokens");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 15 8 * * SAT,SUN");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
