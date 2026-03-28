package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KisTokenServiceTest {

    private static final DateTimeFormatter TOKEN_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 테스트 기준 시각: 2026-03-18 09:00:00 KST */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(
                    LocalDateTime.of(2026, 3, 18, 9, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                    ZoneId.of("Asia/Seoul"));

    @Mock private KisTokenClient kisTokenClient;

    @Mock private KisTokenRepository kisTokenRepository;

    @Mock private SafeModeManager safeModeManager;

    private KisAccountCredential credential;
    private KisProperties kisProperties;
    private KisTokenService kisTokenService;

    @BeforeEach
    void setUp() {
        credential =
                new KisAccountCredential("test", "12345678", "test-app-key", "test-app-secret");
        kisProperties = new KisProperties("https://localhost", "testUser", List.of(credential));
        kisTokenService =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {},
                        FIXED_CLOCK,
                        key -> new ReentrantLock());
    }

    // ── issueOne ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issueOne 성공 — KisTokenClient 호출, KisTokenRepository.saveToken 호출, 토큰 반환")
    void issueOne_success_callsClientAndSavesTokenAndReturnsToken() {
        // Arrange
        String expectedToken = "access-token-value";
        KisTokenResponse response =
                tokenResponse(expectedToken, LocalDateTime.of(2026, 3, 19, 9, 0));
        when(kisTokenClient.requestToken(credential)).thenReturn(response);
        when(safeModeManager.isActive("test")).thenReturn(false);

        // Act
        String token = kisTokenService.issueOne(credential);

        // Assert
        assertThat(token).isEqualTo(expectedToken);
        verify(kisTokenClient).requestToken(credential);
        verify(kisTokenRepository).saveToken(eq("test"), eq(expectedToken), any());
    }

    @Test
    @DisplayName("issueOne 1회 실패 후 2회차 성공 — 재시도 동작 확인")
    void issueOne_firstAttemptFails_secondAttemptSucceeds() {
        // Arrange
        String expectedToken = "retry-token-value";
        KisTokenResponse response =
                tokenResponse(expectedToken, LocalDateTime.of(2026, 3, 19, 9, 0));
        when(kisTokenClient.requestToken(credential))
                .thenThrow(new RuntimeException("network error"))
                .thenReturn(response);
        when(safeModeManager.isActive("test")).thenReturn(false);

        // Act
        String token = kisTokenService.issueOne(credential);

        // Assert
        assertThat(token).isEqualTo(expectedToken);
        verify(kisTokenClient, times(2)).requestToken(credential);
        verify(kisTokenRepository).saveToken(eq("test"), eq(expectedToken), any());
    }

    @Test
    @DisplayName(
            "issueOne MAX_ATTEMPTS(3회) 모두 실패 — SafeModeManager.enter 호출, KisTokenIssueException 발생")
    void issueOne_allAttemptsFail_entersSafeModeAndThrowsException() {
        // Arrange
        RuntimeException cause = new RuntimeException("persistent error");
        when(kisTokenClient.requestToken(credential)).thenThrow(cause);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisTokenIssueException.class)
                .hasMessageContaining("test");

        verify(kisTokenClient, times(3)).requestToken(credential);
        verify(safeModeManager).enter(eq("test"), any());
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName(
            "issueOne MAX_ATTEMPTS(3회) 모두 실패 — Sleeper가 정확히 2회 호출됨 (attempt 0, 1 이후만, 마지막 시도 후 미호출)")
    void issueOne_allAttemptsFail_sleeperCalledExactlyTwice() {
        // Arrange
        AtomicInteger sleeperCallCount = new AtomicInteger(0);
        KisTokenService serviceWithCountingSleeper =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> sleeperCallCount.incrementAndGet(),
                        FIXED_CLOCK,
                        key -> new ReentrantLock());

        when(kisTokenClient.requestToken(credential))
                .thenThrow(new RuntimeException("persistent error"));

        // Act
        try {
            serviceWithCountingSleeper.issueOne(credential);
        } catch (KisTokenIssueException ignored) {
        }

        // Assert
        assertThat(sleeperCallCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("issueOne 성공 시 안전 모드가 활성 상태였으면 SafeModeManager.exit 호출")
    void issueOne_successWhenSafeModeActive_callsSafeModeExit() {
        // Arrange
        String expectedToken = "safe-mode-exit-token";
        KisTokenResponse response =
                tokenResponse(expectedToken, LocalDateTime.of(2026, 3, 19, 9, 0));
        when(kisTokenClient.requestToken(credential)).thenReturn(response);
        when(safeModeManager.isActive("test")).thenReturn(true);

        // Act
        kisTokenService.issueOne(credential);

        // Assert
        verify(safeModeManager).exit("test");
    }

    @Test
    @DisplayName("issueOne — 응답 만료시각이 마진(10분) 이내면 기본 TTL 1시간으로 저장된다")
    void issueOne_nearExpiryResponse_savesWithFallbackTtl() {
        // Arrange: 기준시각(09:00) + 5분 = 09:05 → EXPIRY_SAFETY_MARGIN(10분) 이내
        String expectedToken = "near-expiry-token";
        LocalDateTime nearExpiry = LocalDateTime.of(2026, 3, 18, 9, 5);
        KisTokenResponse response = tokenResponse(expectedToken, nearExpiry);
        when(kisTokenClient.requestToken(credential)).thenReturn(response);
        when(safeModeManager.isActive("test")).thenReturn(false);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        // Act
        kisTokenService.issueOne(credential);

        // Assert
        verify(kisTokenRepository).saveToken(eq("test"), eq(expectedToken), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("issueOne 성공 시 정상 만료시각에서 TTL이 올바르게 계산된다 — TTL = expiredAt - now - 10분")
    void issueOne_normalExpiry_savesWithCalculatedTtl() {
        // Arrange: 만료시각 = 기준시각(09:00) + 24h = 2026-03-19 09:00 → TTL = 24h - 10min = 23h 50min
        String expectedToken = "normal-expiry-token";
        LocalDateTime expiredAt = LocalDateTime.of(2026, 3, 19, 9, 0);
        KisTokenResponse response = tokenResponse(expectedToken, expiredAt);
        when(kisTokenClient.requestToken(credential)).thenReturn(response);
        when(safeModeManager.isActive("test")).thenReturn(false);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        // Act
        kisTokenService.issueOne(credential);

        // Assert
        verify(kisTokenRepository).saveToken(eq("test"), eq(expectedToken), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(23).plusMinutes(50));
    }

    @Test
    @DisplayName("issueOne — 락 획득 후 DCL 재확인 캐시 히트 시 API 호출 없이 캐시 토큰 즉시 반환 (CR-01)")
    void issueOne_doubleCheckedLockingCacheHit_returnsCachedTokenWithoutCallingClient() {
        // Arrange
        // DCL 경로: issueOne이 락을 획득한 직후 kisTokenRepository.findToken을 재확인한다.
        // 선행 스레드가 이미 토큰을 저장한 상황을 재현하기 위해, findToken이 바로 토큰을 반환하도록 stubbing한다.
        // issueOne 내부에서 락 획득 후 호출하는 findToken(alias)이 present를 반환하면
        // kisTokenClient.requestToken은 절대 호출되지 않아야 한다.
        String cachedToken = "dck-cached-token";
        when(kisTokenRepository.findToken("test")).thenReturn(Optional.of(cachedToken));

        // Act
        String token = kisTokenService.issueOne(credential);

        // Assert
        assertThat(token).isEqualTo(cachedToken);
        verify(kisTokenClient, never()).requestToken(any());
    }

    @Test
    @DisplayName(
            "issueOne tryLock 타임아웃 — tryLock이 false 반환 시 KisTokenIssueException 발생, kisTokenClient 호출 안 함")
    void issueOne_tryLockTimeout_throwsKisTokenIssueExceptionWithoutCallingClient()
            throws InterruptedException {
        // Arrange
        // tryLock이 즉시 false를 반환하는 mock Lock을 주입한다.
        // 실제 60초 대기 없이 타임아웃 경로를 검증할 수 있다.
        Lock timeoutLock = mock(Lock.class);
        when(timeoutLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        KisTokenService serviceWithTimeoutLock =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {},
                        FIXED_CLOCK,
                        key -> timeoutLock);

        // Act & Assert
        assertThatThrownBy(() -> serviceWithTimeoutLock.issueOne(credential))
                .isInstanceOf(KisTokenIssueException.class)
                .hasMessageContaining("test");

        // Assert
        verify(kisTokenClient, never()).requestToken(any());
    }

    @Test
    @DisplayName("issueOne tryLock 대기 중 인터럽트 수신 — 인터럽트 플래그 복원 후 KisTokenIssueException 발생")
    void issueOne_interruptedWhileWaitingForLock_restoresInterruptFlagAndThrowsException()
            throws InterruptedException {
        // Arrange
        // tryLock 호출 시 InterruptedException을 던지는 mock Lock을 주입한다.
        // CountDownLatch + Thread 조합 없이 인터럽트 경로를 단순하게 검증한다.
        Lock interruptibleLock = mock(Lock.class);
        when(interruptibleLock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("테스트용 인터럽트"));

        KisTokenService serviceWithInterruptibleLock =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {},
                        FIXED_CLOCK,
                        key -> interruptibleLock);

        AtomicBoolean interruptRestored = new AtomicBoolean(false);

        // Act
        Thread testThread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        serviceWithInterruptibleLock.issueOne(credential);
                                    } catch (KisTokenIssueException ignored) {
                                        interruptRestored.set(
                                                Thread.currentThread().isInterrupted());
                                    }
                                });
        testThread.join(5000);

        // Assert
        assertThat(interruptRestored.get()).isTrue();
        verify(kisTokenClient, never()).requestToken(any());
    }

    @Test
    @DisplayName("issueOne — KisApiResponseException 발생 시 재시도 없이 즉시 전파 (CR-02)")
    void issueOne_kisApiResponseException_propagatesImmediatelyWithoutRetry() {
        // Arrange
        KisApiResponseException cause = new KisApiResponseException("test", "accessToken이 null");
        when(kisTokenClient.requestToken(credential)).thenThrow(cause);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(safeModeManager, never()).enter(any(), any());
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("requestAndSaveToken — 응답이 null이면 KisApiResponseException 발생")
    void requestAndSaveToken_nullResponse_throwsKisApiResponseException() {
        // Arrange
        when(kisTokenClient.requestToken(credential)).thenReturn(null);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("requestAndSaveToken — accessToken이 null이면 KisApiResponseException 발생")
    void requestAndSaveToken_nullAccessToken_throwsKisApiResponseException() {
        // Arrange: accessToken=null, accessTokenTokenExpired은 정상
        KisTokenResponse response =
                new KisTokenResponse(
                        null,
                        "Bearer",
                        86_400,
                        LocalDateTime.of(2026, 3, 19, 9, 0).format(TOKEN_TIME_FORMATTER));
        when(kisTokenClient.requestToken(credential)).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("requestAndSaveToken — accessTokenTokenExpired가 null이면 KisApiResponseException 발생")
    void requestAndSaveToken_nullExpiredAt_throwsKisApiResponseException() {
        // Arrange: accessToken=정상, accessTokenTokenExpired=null
        KisTokenResponse response = new KisTokenResponse("valid-token", "Bearer", 86_400, null);
        when(kisTokenClient.requestToken(credential)).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("requestAndSaveToken — accessToken이 빈 문자열이면 KisApiResponseException 발생")
    void requestAndSaveToken_blankAccessToken_throwsKisApiResponseException() {
        // Arrange: accessToken="", accessTokenTokenExpired는 정상
        KisTokenResponse response =
                new KisTokenResponse(
                        "",
                        "Bearer",
                        86_400,
                        LocalDateTime.of(2026, 3, 19, 9, 0).format(TOKEN_TIME_FORMATTER));
        when(kisTokenClient.requestToken(credential)).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("requestAndSaveToken — accessToken이 공백 문자열이면 KisApiResponseException 발생")
    void requestAndSaveToken_whitespaceAccessToken_throwsKisApiResponseException() {
        // Arrange: accessToken="   ", accessTokenTokenExpired는 정상
        KisTokenResponse response =
                new KisTokenResponse(
                        "   ",
                        "Bearer",
                        86_400,
                        LocalDateTime.of(2026, 3, 19, 9, 0).format(TOKEN_TIME_FORMATTER));
        when(kisTokenClient.requestToken(credential)).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName(
            "requestAndSaveToken — accessTokenTokenExpired가 빈 문자열이면 KisApiResponseException 발생")
    void requestAndSaveToken_blankExpiredAt_throwsKisApiResponseException() {
        // Arrange: accessToken=정상, accessTokenTokenExpired=""
        KisTokenResponse response = new KisTokenResponse("valid-token", "Bearer", 86_400, "");
        when(kisTokenClient.requestToken(credential)).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName(
            "requestAndSaveToken — accessTokenTokenExpired가 잘못된 형식이면 KisApiResponseException 발생")
    void requestAndSaveToken_malformedExpiredAt_throwsKisApiResponseException() {
        // Arrange: accessToken=정상, accessTokenTokenExpired="2026/03/19" (yyyy/MM/dd 형식은 파싱 불가)
        KisTokenResponse response =
                new KisTokenResponse("valid-token", "Bearer", 86_400, "2026/03/19");
        when(kisTokenClient.requestToken(credential)).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.issueOne(credential))
                .isInstanceOf(KisApiResponseException.class)
                .cause()
                .isInstanceOf(DateTimeParseException.class);

        // Assert
        verify(kisTokenClient, times(1)).requestToken(credential);
        verify(kisTokenRepository, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("sleepBeforeRetry — sleep 중 인터럽트 수신 시 인터럽트 플래그 복원 후 KisTokenIssueException 발생")
    void sleepBeforeRetry_interruptedDuringSleep_restoresInterruptFlagAndThrowsException() {
        // Arrange
        // sleeper가 InterruptedException을 던지는 KisTokenService 인스턴스 생성
        KisTokenService serviceWithInterruptibleSleeper =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {
                            throw new InterruptedException("테스트용 인터럽트");
                        },
                        FIXED_CLOCK,
                        key -> new ReentrantLock());

        // 첫 번째 시도 실패 → sleepBeforeRetry 호출 → InterruptedException 발생 경로
        when(kisTokenClient.requestToken(credential))
                .thenThrow(new RuntimeException("first attempt fails"));

        AtomicBoolean interruptRestored = new AtomicBoolean(false);

        // Act
        Thread testThread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        serviceWithInterruptibleSleeper.issueOne(credential);
                                    } catch (KisTokenIssueException ex) {
                                        interruptRestored.set(
                                                Thread.currentThread().isInterrupted());
                                    }
                                });

        try {
            testThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert
        assertThat(interruptRestored.get()).isTrue();
    }

    // ── getValidToken ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getValidToken 캐시 히트 — Repository에서 토큰 반환, KisTokenClient 호출 안 함")
    void getValidToken_cacheHit_returnsTokenWithoutCallingClient() {
        // Arrange
        String cachedToken = "cached-token";
        when(kisTokenRepository.findToken("test")).thenReturn(Optional.of(cachedToken));

        // Act
        String token = kisTokenService.getValidToken("test");

        // Assert
        assertThat(token).isEqualTo(cachedToken);
        verify(kisTokenClient, never()).requestToken(any());
    }

    @Test
    @DisplayName("getValidToken 캐시 미스 — issueOne 호출되어 새 토큰 발급")
    void getValidToken_cacheMiss_issuesNewToken() {
        // Arrange
        String newToken = "newly-issued-token";
        KisTokenResponse response = tokenResponse(newToken, LocalDateTime.of(2026, 3, 19, 9, 0));
        when(kisTokenRepository.findToken("test")).thenReturn(Optional.empty());
        when(kisTokenClient.requestToken(credential)).thenReturn(response);
        when(safeModeManager.isActive("test")).thenReturn(false);

        // Act
        String token = kisTokenService.getValidToken("test");

        // Assert
        assertThat(token).isEqualTo(newToken);
        verify(kisTokenClient).requestToken(credential);
        verify(kisTokenRepository).saveToken(eq("test"), eq(newToken), any());
    }

    @Test
    @DisplayName("getValidToken 미등록 alias — IllegalArgumentException 발생")
    void getValidToken_unknownAlias_throwsIllegalArgumentException() {
        // Arrange
        when(kisTokenRepository.findToken("unknown")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> kisTokenService.getValidToken("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");

        verify(kisTokenClient, never()).requestToken(any());
    }

    // ── issueAll ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issueAll — 모든 계좌에 대해 KisTokenClient.requestToken이 계좌 수만큼 호출됨")
    void issueAll_callsRequestTokenForEachAccount() {
        // Arrange
        KisAccountCredential secondCredential =
                new KisAccountCredential("stock", "87654321", "stock-app-key", "stock-app-secret");
        KisProperties multiAccountProperties =
                new KisProperties(
                        "https://localhost", "testUser", List.of(credential, secondCredential));
        KisTokenService multiAccountService =
                new KisTokenService(
                        multiAccountProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {},
                        FIXED_CLOCK,
                        key -> new ReentrantLock());

        KisTokenResponse responseForTest =
                tokenResponse("token-test", LocalDateTime.of(2026, 3, 19, 9, 0));
        KisTokenResponse responseForStock =
                tokenResponse("token-stock", LocalDateTime.of(2026, 3, 19, 9, 0));
        when(kisTokenClient.requestToken(credential)).thenReturn(responseForTest);
        when(kisTokenClient.requestToken(secondCredential)).thenReturn(responseForStock);
        when(safeModeManager.isActive(any())).thenReturn(false);

        // Act
        multiAccountService.issueAll();

        // Assert
        verify(kisTokenClient, timeout(5000).times(1)).requestToken(credential);
        verify(kisTokenClient, timeout(5000).times(1)).requestToken(secondCredential);
    }

    @Test
    @DisplayName("issueAll — 일부 계좌 실패 시 성공한 계좌는 정상 저장되고 전체 호출이 완료됨")
    void issueAll_oneAccountFails_otherAccountSucceeds() {
        // Arrange
        KisAccountCredential accountB =
                new KisAccountCredential("stock", "87654321", "stock-app-key", "stock-app-secret");
        KisProperties multiAccountProperties =
                new KisProperties("https://localhost", "testUser", List.of(credential, accountB));
        KisTokenService multiAccountService =
                new KisTokenService(
                        multiAccountProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {},
                        FIXED_CLOCK,
                        key -> new ReentrantLock());

        // Account A(test) 성공, Account B(stock) 항상 실패
        KisTokenResponse responseForA =
                tokenResponse("token-test", LocalDateTime.of(2026, 3, 19, 9, 0));
        when(kisTokenClient.requestToken(credential)).thenReturn(responseForA);
        when(kisTokenClient.requestToken(accountB)).thenThrow(new RuntimeException("B 발급 실패"));
        when(safeModeManager.isActive("test")).thenReturn(false);

        // Act: issueAll() 자체는 예외 없이 완료되어야 함
        multiAccountService.issueAll();

        // Assert
        // Account A 토큰 저장 확인
        verify(kisTokenRepository, timeout(5000).times(1))
                .saveToken(eq("test"), eq("token-test"), any());

        // Account B는 3회 재시도 후 SafeModeManager.enter 호출
        verify(safeModeManager, timeout(5000).times(1)).enter(eq("stock"), any());
    }

    @Test
    @DisplayName("issueAll — future.get() 대기 중 인터럽트 수신 시 예외 없이 정상 종료")
    void issueAll_interruptedWhileWaitingForFuture_restoresInterruptFlagAndBreaks()
            throws InterruptedException {
        // 흐름:
        //  1. issueOne(Virtual Thread)이 sleeper에 진입 → issueOneStarted 신호
        //  2. 테스트 스레드가 issueOneStarted 대기 해제 → issueAllThread 인터럽트
        //  3. issueAllThread의 future.get()이 InterruptedException 수신 → 플래그 복원 후 break
        //  4. sleeper 내부에서 interruptSent 신호를 대기하다가 신호 수신 후 즉시 반환
        //     → issueOne Virtual Thread도 정상 종료
        //
        // Thread.sleep 하드코딩 대신 CountDownLatch 두 개로 동기화하여 테스트 실행 시간을 1초 이내로 유지한다.

        // Arrange
        CountDownLatch issueOneStarted = new CountDownLatch(1);
        CountDownLatch interruptSent = new CountDownLatch(1);

        KisTokenService interruptibleService =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        kisTokenRepository,
                        safeModeManager,
                        millis -> {
                            issueOneStarted.countDown();
                            // issueAll 스레드가 인터럽트를 받을 때까지 대기 (실제 sleep 없이 latch로 동기화)
                            interruptSent.await();
                        },
                        FIXED_CLOCK,
                        key -> new ReentrantLock());

        when(kisTokenClient.requestToken(credential))
                .thenThrow(new RuntimeException("slow failure"));

        // Act
        Thread issueAllThread = Thread.ofVirtual().start(interruptibleService::issueAll);

        // issueOne이 sleeper에 진입할 때까지 대기 후 issueAll 스레드를 인터럽트
        assertThat(issueOneStarted.await(5, TimeUnit.SECONDS))
                .as("issueOne이 5초 내에 sleeper에 진입해야 함")
                .isTrue();
        issueAllThread.interrupt();
        interruptSent.countDown(); // sleeper 내부 대기 해제 → issueOne Virtual Thread 정상 종료

        issueAllThread.join(5000);

        // Assert: issueAll이 예외 없이 정상 종료됨 (스레드가 살아있지 않아야 함)
        assertThat(issueAllThread.isAlive()).isFalse();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private KisTokenResponse tokenResponse(String token, LocalDateTime expiredAt) {
        return new KisTokenResponse(
                token, "Bearer", 86_400, expiredAt.format(TOKEN_TIME_FORMATTER));
    }
}
