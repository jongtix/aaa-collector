package com.aaa.collector.watchlist;

import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.grade.GradeClassificationService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * KIS 관심종목을 {@code stocks} 테이블에 동기화하는 서비스.
 *
 * <p>①단계(그룹/종목 목록 조회)는 {@link KisWatchlistClient}를 통해 단일 보호 게이트를 경유한다 (SPEC-COLLECTOR-KISGATE-001).
 * ②단계(종목별 기본정보 해석)는 {@link WatchlistStockResolver}에 위임한다 (SPEC-COLLECTOR-WLSYNC-007에서 추출). 본 서비스는
 * ①단계 + 위임 오케스트레이션 + classify 트리거만 담당하는 얇은 오케스트레이터다.
 *
 * <p><strong>throttle 이중화 제거(DP2/REQ-KISGATE-030a):</strong> 기존 외부 {@code
 * kisRateLimiter.consume()/release()}로 client 내부 retry 전체를 감싸 "슬롯 1개를 retry 3회 내내 점유"하던 코드를 제거했다.
 * throttle은 이제 게이트가 매 시도마다 per-key rate limiter로 수행하므로(REQ-KISGATE-030), 외부 throttle을 남기면 이중 점유가
 * 된다. 본 서비스는 종목 조회 단계의 {@link LeaseSession}을 작업 단위당 1회만 열어({@link KeyLeaseRegistry#openSession()},
 * per-batch 스냅샷 REQ-KISGATE-006a) 그룹별 조회에 공유한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistSyncService {

    private final KisWatchlistClient kisWatchlistClient;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final WatchlistWriter watchlistWriter;
    private final WatchlistStockResolver watchlistStockResolver;
    private final GradeClassificationService gradeClassificationService;

    /** KIS 관심종목 전체를 조회하여 {@code stocks} 테이블에 upsert한다. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // classify 예외를 포착해 sync 계속 진행
    public void sync() {
        List<KisGroupListResponse.Group> groups = kisWatchlistClient.fetchGroups();
        log.info("관심 그룹 조회 완료 — {}개 그룹", groups.size());

        AtomicInteger failedGroupCount = new AtomicInteger(0);
        List<KisStockListByGroupResponse.Stock> rawStocks =
                collectUniqueStocks(groups, failedGroupCount);
        log.info("중복 제거 후 종목 수: {}", rawStocks.size());

        List<ResolvedStock> resolved = watchlistStockResolver.resolve(rawStocks);
        watchlistWriter.upsertAll(resolved, failedGroupCount.get());

        // @MX:NOTE: [AUTO] classify는 failedGroupCount와 무관하게 항상 실행.
        // sync 부분 실패(rate-limit 등)와 등급 분류는 독립 — classify 실패 ≠ sync 실패(REQ-001/002).
        try {
            gradeClassificationService.classify();
        } catch (Exception e) {
            log.warn("종목 등급 분류 실패 — sync 결과에 영향 없음", e);
        }
    }

    private List<KisStockListByGroupResponse.Stock> collectUniqueStocks(
            List<KisGroupListResponse.Group> groups, AtomicInteger failedGroupCount) {
        if (groups.isEmpty()) {
            return List.of();
        }

        // REQ-KISGATE-006a: 종목 조회 단계 per-batch 헬스 스냅샷 1회 고정 → 그룹별 조회가 동일 세션 공유.
        LeaseSession session = keyLeaseRegistry.openSession();
        // REQ-KISGATE-024 보존: 빈 스냅샷(전 키 사망)이면 그룹 전체를 skip 집계하고 종목 조회를 시도하지 않는다.
        if (session.isEmpty()) {
            int total = groups.size();
            failedGroupCount.addAndGet(total);
            log.error("관심종목 동기화 — 모든 키 죽음, {}개 그룹 전체 skip(종목 조회 0회)", total);
            return List.of();
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<KisStockListByGroupResponse.Stock>>> futures =
                    groups.stream()
                            .map(
                                    group ->
                                            fetchStocksAsync(
                                                    group, session, executor, failedGroupCount))
                            .toList();

            Map<String, KisStockListByGroupResponse.Stock> unique = new ConcurrentHashMap<>();
            futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Collection::stream)
                    .forEach(s -> unique.putIfAbsent(s.jongCode() + ":" + s.exchCode(), s));
            return List.copyOf(unique.values());
        }
    }

    private CompletableFuture<List<KisStockListByGroupResponse.Stock>> fetchStocksAsync(
            KisGroupListResponse.Group group,
            LeaseSession session,
            ExecutorService executor,
            AtomicInteger failedGroupCount) {
        // DP2/REQ-KISGATE-030a: 외부 consume/release 제거. throttle은 게이트가 매 시도 per-key로 수행(이중 throttle
        // 방지).
        // 소진 시 게이트가 예외를 전파 → exceptionally에서 그룹 skip + failedGroupCount 증가(REQ-KISGATE-022/AC-7
        // 보존).
        return CompletableFuture.supplyAsync(
                        () -> kisWatchlistClient.fetchStocksByGroup(session, group.interGrpCode()),
                        executor)
                .exceptionally(
                        ex -> {
                            log.warn(
                                    "그룹 {}({}) 조회 실패, skip — {}",
                                    group.interGrpName(),
                                    group.interGrpCode(),
                                    ex.getMessage());
                            failedGroupCount.incrementAndGet();
                            return List.of();
                        });
    }
}
