package com.aaa.collector.kis.token;

import com.aaa.collector.common.retry.ExponentialBackoff;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
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
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
@SuppressWarnings("PMD.AvoidCatchingGenericException")
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
            @Qualifier("tokenSafeModeManager") SafeModeManager safeModeManager,
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

    // PMD.UseConcurrentHashMap: 생성자에서 1회 호출되는 빌드 전용 메서드.
    // HashMap은 로컬 변수이며 Map.copyOf()로 즉시 불변 맵으로 교체된다.
    // ruleset 전역 제외 시 Virtual Threads 환경의 실제 동시성 버그를 놓칠 수 있어 메서드 단위로 억제한다.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static Map<String, Lock> buildAccountLocks(
            KisProperties properties, LockFactory lockFactory) {
        Map<String, Lock> locks = new HashMap<>();
        for (KisAccountCredential account : properties.accounts()) {
            locks.put(account.alias(), lockFactory.create(account.alias()));
        }
        return Map.copyOf(locks);
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

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 모든 예외를 재시도 대상으로 포착
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
                    alias, "accessTokenTokenExpired 파싱 실패: " + e.getMessage(), e);
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
     * 전체 계좌에 대해 병렬로 REST access_token을 사전(eager) 발급한다 (REQ-WLSYNC-100,101,103).
     *
     * <p>{@link #issueAllApprovalKeys()}와 동형 패턴이다. Virtual Thread executor로 5계좌를 병렬 제출하며, 각 작업은
     * {@link #issueOne(KisAccountCredential)}을 호출한다(계좌별 Lock + 3회 재시도 + 최종 실패 시 {@link
     * SafeModeManager#enter} 자동 호출). 개별 계좌 발급 실패는 작업 내부에서 흡수(경고 로그)하여 한 계좌 실패가 다른 계좌를 막지 않는다(부분 실패
     * 허용, 전체 미실패). 인터럽트 수신 시 인터럽트 플래그를 복원하고 남은 결과 수집을 즉시 중단한다.
     *
     * <p>발급/재시도/SafeMode 기록 로직은 모두 {@link #issueOne(KisAccountCredential)}에 위임하며 이 메서드는 병렬 오케스트레이션만
     * 담당한다(SPEC-COLLECTOR-TOKEN-001 REQ-TOKEN-002 supersede — §1.4).
     */
    // @MX:ANCHOR: [AUTO] REST access_token 5계좌 병렬 사전발급 진입점 — 토큰 스케줄러가 호출
    // @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-100,101,103 — eager 사전발급으로 ②단계 멀티키 소비처 토큰 준비
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006
    public void issueAllTokens() {
        runForAllAccounts("access_token 사전발급", this::issueOne);
    }

    /**
     * 전체 계좌에 대해 병렬로 WebSocket 승인키를 발급한다.
     *
     * <p>Virtual Thread executor를 사용하며, 개별 계좌 발급 실패는 경고 로그 후 다음 계좌로 진행한다(부분 실패 허용). 인터럽트 수신 시 인터럽트
     * 플래그를 복원하고 남은 결과 수집을 즉시 중단한다.
     */
    public void issueAllApprovalKeys() {
        runForAllAccounts(
                "승인키 발급",
                credential -> {
                    KisApprovalKeyResponse response = kisTokenClient.requestApprovalKey(credential);
                    kisTokenRepository.saveApprovalKey(credential.alias(), response.approvalKey());
                });
    }

    /**
     * 전체 계좌에 대해 {@code task}를 Virtual Thread executor로 병렬 수행한다.
     *
     * <p>{@link #issueAllTokens()}와 {@link #issueAllApprovalKeys()}가 공유하는 병렬 오케스트레이션 골격이다. 개별 계좌 작업
     * 실패는 흡수(경고 로그)하여 한 계좌 실패가 다른 계좌를 막지 않는다(부분 실패 허용, 전체 미실패). 인터럽트 수신 시 인터럽트 플래그를 복원하고 남은 결과 수집을
     * 즉시 중단한다.
     *
     * @param label 로깅용 작업 이름
     * @param task 계좌별로 수행할 작업
     */
    private void runForAllAccounts(String label, Consumer<KisAccountCredential> task) {
        log.info("전 계좌 {} 시작 — 계좌 수: {}", label, kisProperties.accounts().size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>();

            for (KisAccountCredential credential : kisProperties.accounts()) {
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        task.accept(credential);
                                        log.info("[{}] {} 성공", credential.alias(), label);
                                    } catch (Exception e) {
                                        log.warn("[{}] {} 실패", credential.alias(), label, e);
                                    }
                                    return null;
                                }));
            }

            boolean interrupted = false;

            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    log.error(
                            "[{} 실패] 예상치 못한 예외: {}",
                            label,
                            e.getCause().getClass().getName(),
                            e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                }
            }

            if (interrupted) {
                log.warn("{} 중 인터럽트 수신 — 남은 계좌 결과 수집 중단", label);
            } else {
                log.info("전 계좌 {} 완료", label);
            }
        }
    }

    /**
     * WebSocket 승인키를 강제 재발급하고 반환한다 (REQ-WS-042).
     *
     * <p>Redis 캐시를 무시하고 KIS API {@code /oauth2/Approval}를 즉시 호출한다. WebSocket handshake 실패(401 등) 시
     * {@link com.aaa.collector.kis.websocket.KisWebSocketSessionManager}에서 호출된다.
     *
     * @param alias 계정 식별자
     * @return 새로 발급된 WebSocket 승인키 문자열
     * @throws IllegalArgumentException 등록되지 않은 alias인 경우
     */
    public String reissueApprovalKey(String alias) {
        KisAccountCredential credential = findCredential(alias);
        KisApprovalKeyResponse response = kisTokenClient.requestApprovalKey(credential);
        kisTokenRepository.saveApprovalKey(alias, response.approvalKey());
        log.info("[{}] approval_key 강제 재발급 완료", alias);
        return response.approvalKey();
    }

    /**
     * 유효한 WebSocket 승인키를 반환하는 Lazy 갱신 진입점.
     *
     * <p>Redis에 승인키가 존재하면 그대로 반환한다. 존재하지 않으면 {@link KisTokenClient#requestApprovalKey}를 호출하여 새 승인키를
     * 발급하고 저장한 뒤 반환한다.
     *
     * @param alias 계정 식별자
     * @return 유효한 WebSocket 승인키 문자열
     * @throws IllegalArgumentException 등록되지 않은 alias인 경우
     */
    public String getValidApprovalKey(String alias) {
        return kisTokenRepository
                .findApprovalKey(alias)
                .orElseGet(
                        () -> {
                            KisAccountCredential credential = findCredential(alias);
                            KisApprovalKeyResponse response =
                                    kisTokenClient.requestApprovalKey(credential);
                            kisTokenRepository.saveApprovalKey(alias, response.approvalKey());
                            return response.approvalKey();
                        });
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
