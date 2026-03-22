package com.aaa.collector.kis.token;

import com.aaa.collector.common.retry.ExponentialBackoff;
import com.aaa.collector.common.retry.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * KIS API 액세스 토큰의 발급·갱신·조회를 담당하는 서비스.
 *
 * <p>계좌별 {@link Lock}으로 동시 발급을 방지하고, 지수 백오프 재시도를 수행한다. 최종 실패 시 {@link SafeModeManager}에 안전 모드 진입을
 * 위임한다.
 *
 * <p>토큰은 Redis에 저장되며, {@link #getValidToken(String)}은 캐시 히트 시 API를 호출하지 않는 Lazy 갱신 방식으로 동작한다.
 */
@Slf4j
@Service
public class KisTokenService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_DELAY_MS = 1_000L;
    private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofMinutes(10);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter TOKEN_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KisProperties kisProperties;
    private final KisTokenClient kisTokenClient;
    private final KisTokenRepository kisTokenRepository;
    private final SafeModeManager safeModeManager;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Map<String, Lock> accountLocks;

    public KisTokenService(
            KisProperties kisProperties,
            KisTokenClient kisTokenClient,
            KisTokenRepository kisTokenRepository,
            SafeModeManager safeModeManager,
            Sleeper sleeper,
            Clock clock,
            LockFactory lockFactory) {
        this.kisProperties = kisProperties;
        this.kisTokenClient = kisTokenClient;
        this.kisTokenRepository = kisTokenRepository;
        this.safeModeManager = safeModeManager;
        this.sleeper = sleeper;
        this.clock = clock;
        this.accountLocks = buildAccountLocks(kisProperties, lockFactory);
    }

    private static Map<String, Lock> buildAccountLocks(
            KisProperties properties, LockFactory lockFactory) {
        Map<String, Lock> locks = new HashMap<>();
        for (KisAccountCredential account : properties.accounts()) {
            locks.put(account.alias(), lockFactory.create(account.alias()));
        }
        return Map.copyOf(locks);
    }

    /**
     * 전체 계좌에 대해 병렬로 토큰을 발급한다.
     *
     * <p>Virtual Thread executor를 사용하며, {@link Future#get()}으로 각 계좌의 결과를 순차적으로 수집한다. 개별 계좌 발급
     * 실패({@link ExecutionException})는 경고 로그 후 다음 계좌로 진행하며, 다른 계좌에 영향을 주지 않는다.
     *
     * <p>인터럽트({@link InterruptedException}) 수신 시 인터럽트 플래그를 복원하고 남은 결과 수집을 즉시 중단한다.
     */
    public void issueAll() {
        log.info("전 계좌 토큰 발급 시작 — 계좌 수: {}", kisProperties.accounts().size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>();

            for (KisAccountCredential credential : kisProperties.accounts()) {
                futures.add(
                        executor.submit(
                                () -> {
                                    issueOne(credential);
                                    return null;
                                }));
            }

            int failureCount = 0;
            boolean interrupted = false;

            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    failureCount++;
                    switch (e.getCause()) {
                        case KisApiResponseException ex ->
                                log.error("[토큰 발급 실패] KIS API 응답 검증 오류", ex);
                        case KisTokenIssueException ex -> log.warn("[토큰 발급 실패] 재시도 소진 후 최종 실패", ex);
                        default ->
                                log.error(
                                        "[토큰 발급 실패] 예상치 못한 예외: {}",
                                        e.getCause().getClass().getName(),
                                        e.getCause());
                    }
                } catch (InterruptedException e) {
                    // 인터럽트 플래그 복원 → try-with-resources의 close() 진입 시
                    // ThreadPerTaskExecutor.close()가 플래그를 감지하여 자동으로
                    // shutdownNow() (= 모든 Virtual Thread에 interrupt() 전파) 수행.
                    // 명시적 shutdownNow() 호출 불필요. (JDK 21 ThreadPerTaskExecutor 소스 참조)
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                }
            }

            if (interrupted) {
                log.warn("토큰 발급 중 인터럽트 수신 — 남은 계좌 결과 수집 중단");
            } else if (failureCount > 0) {
                log.warn("전 계좌 토큰 발급 부분 실패 — 실패 {}건", failureCount);
            } else {
                log.info("전 계좌 토큰 발급 완료");
            }
        }
    }

    /**
     * 단일 계좌에 대해 토큰을 발급한다.
     *
     * <p>계좌별 {@link Lock}으로 동시 발급을 방지하며, 실패 시 {@link ExponentialBackoff} 지연 후 최대 {@value
     * MAX_ATTEMPTS}회 재시도한다. 모든 시도가 실패하면 안전 모드에 진입하고 {@link KisTokenIssueException}을 던진다.
     *
     * <p><b>Double-checked locking:</b> 다수 스레드가 동시에 캐시 미스를 감지하고 이 메서드에 진입할 수 있다. 락 획득 후 Redis를
     * 재확인하여, 선행 스레드가 이미 토큰을 발급·저장했다면 KIS API를 호출하지 않고 캐시된 토큰을 즉시 반환한다.
     *
     * <p><b>tryLock 타임아웃:</b> 60초({@link #LOCK_TIMEOUT}) 내에 락을 획득하지 못하면 {@link
     * KisTokenIssueException}을 던진다. 타임아웃은 최악의 시나리오(연결 5초 + 읽기 10초) × 3회 + 백오프 3초 ≈ 48초를 고려하여 설정한다.
     *
     * @param credential 토큰을 발급받을 계좌 자격증명
     * @return 발급된 액세스 토큰 문자열 (캐시 히트 시 캐시된 토큰, 캐시 미스 시 새로 발급된 토큰)
     * @throws KisTokenIssueException 락 획득 타임아웃, 인터럽트, 또는 최대 재시도 횟수 소진 후에도 발급 실패 시
     * @throws KisApiResponseException KIS API 응답 검증 실패 시 (재시도 없이 즉시 전파)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 모든 예외를 재시도 대상으로 포착
    public String issueOne(KisAccountCredential credential) {
        String alias = credential.alias();
        Lock lock = accountLocks.get(alias);

        try {
            if (!lock.tryLock(LOCK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new KisTokenIssueException(
                        alias, new TimeoutException("락 획득 타임아웃: " + alias));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KisTokenIssueException(alias, e);
        }

        try {
            // Double-checked locking: 다른 스레드가 이미 토큰을 발급했을 수 있음
            Optional<String> existing = kisTokenRepository.findToken(alias);
            if (existing.isPresent()) {
                return existing.get();
            }
            return issueWithRetry(credential, alias);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private String issueWithRetry(KisAccountCredential credential, String alias) {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return requestAndSaveToken(credential, alias);
            } catch (KisApiResponseException ex) {
                throw ex; // 응답 검증 실패는 재시도 불필요 — 즉시 전파
            } catch (Exception ex) {
                lastException = ex;
                log.warn("[{}] 토큰 발급 실패 — attempt={}/{}", alias, attempt + 1, MAX_ATTEMPTS, ex);
                if (attempt < MAX_ATTEMPTS - 1) {
                    sleepBeforeRetry(alias, attempt);
                }
            }
        }

        safeModeManager.enter(alias, lastException);
        throw new KisTokenIssueException(alias, lastException);
    }

    private String requestAndSaveToken(KisAccountCredential credential, String alias) {
        KisTokenResponse response = kisTokenClient.requestToken(credential);

        validateResponse(alias, response);

        LocalDateTime expiredAt;
        try {
            expiredAt =
                    LocalDateTime.parse(response.accessTokenTokenExpired(), TOKEN_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new KisApiResponseException(
                    alias, "accessTokenTokenExpired 파싱 실패: " + e.getMessage());
        }
        Duration ttl = calculateTtl(alias, expiredAt);

        String token = response.accessToken();
        kisTokenRepository.saveToken(alias, token, ttl);

        // 토큰 발급 성공 시 안전 모드가 켜져 있으면 해제한다.
        // 정상 경로(안전 모드 OFF)에서도 isActive()로 Redis GET이 1회 발생하지만,
        // 일 1회·5개 계좌 규모에서 무시할 수 있는 비용이므로 현행 유지.
        if (safeModeManager.isActive(alias)) {
            safeModeManager.exit(alias);
        }

        log.info("[{}] 토큰 발급 성공 (만료시각={})", alias, expiredAt);
        return token;
    }

    private Duration calculateTtl(String alias, LocalDateTime expiredAt) {
        Duration ttl =
                Duration.between(LocalDateTime.now(clock), expiredAt).minus(EXPIRY_SAFETY_MARGIN);
        if (ttl.isNegative() || ttl.isZero()) {
            log.warn("[{}] 계산된 TTL이 0 이하 — 기본 TTL 1시간 사용 (만료시각={})", alias, expiredAt);
            return Duration.ofHours(1);
        }
        return ttl;
    }

    private void sleepBeforeRetry(String alias, int attempt) {
        try {
            sleeper.sleep(ExponentialBackoff.delay(attempt, BASE_DELAY_MS).toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KisTokenIssueException(alias, ie);
        }
    }

    /**
     * 유효한 토큰을 반환하는 Lazy 갱신 진입점.
     *
     * <p>Redis에 토큰이 존재하면 그대로 반환한다. 토큰이 만료되었거나 존재하지 않으면 {@link #issueOne(KisAccountCredential)}을
     * 호출하여 새 토큰을 발급한다.
     *
     * @param alias 계정 식별자
     * @return 유효한 액세스 토큰 문자열
     * @throws IllegalArgumentException 등록되지 않은 alias인 경우
     * @throws KisTokenIssueException 토큰 발급 최종 실패 시
     */
    public String getValidToken(String alias) {
        // 캐시 히트(다수 경로)는 락 없이 즉시 반환. 캐시 미스 시 issueOne() 내부 DCL에서 findToken()을 재호출하므로
        // Redis GET이 2회 발생하지만, 락 경쟁 없는 빠른 경로를 위한 의도적 트레이드오프.
        return kisTokenRepository.findToken(alias).orElseGet(() -> issueOne(findCredential(alias)));
    }

    private KisAccountCredential findCredential(String alias) {
        return kisProperties.accounts().stream()
                .filter(a -> a.alias().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 alias: " + alias));
    }

    private void validateResponse(String alias, KisTokenResponse response) {
        if (response == null) {
            throw new KisApiResponseException(alias, "응답이 null");
        }
        if (isNullOrBlank(response.accessToken())) {
            throw new KisApiResponseException(alias, "accessToken이 null 또는 blank");
        }
        if (isNullOrBlank(response.accessTokenTokenExpired())) {
            throw new KisApiResponseException(alias, "accessTokenTokenExpired가 null 또는 blank");
        }
    }

    private boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }
}
