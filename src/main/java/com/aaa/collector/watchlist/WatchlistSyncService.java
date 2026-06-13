package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisRateLimiter;
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
 * <p>①단계(그룹/종목 목록 조회)는 isa 단일키 + 전역 {@code kisRateLimiter}를 유지한다. ②단계(종목별 기본정보 해석)는 {@link
 * WatchlistStockResolver}에 위임한다(SPEC-COLLECTOR-WLSYNC-007에서 추출). 본 서비스는 ①단계 + 위임 오케스트레이션 + classify
 * 트리거만 담당하는 얇은 오케스트레이터다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistSyncService {

    private final KisRateLimiter kisRateLimiter;
    private final KisWatchlistClient kisWatchlistClient;
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
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<KisStockListByGroupResponse.Stock>>> futures =
                    groups.stream()
                            .map(group -> fetchStocksAsync(group, executor, failedGroupCount))
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
            ExecutorService executor,
            AtomicInteger failedGroupCount) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                kisRateLimiter.consume();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                log.warn(
                                        "rate limit 대기 중 인터럽트 — group={}({})",
                                        group.interGrpName(),
                                        group.interGrpCode());
                                failedGroupCount.incrementAndGet();
                                return List.<KisStockListByGroupResponse.Stock>of();
                            }
                            // 세마포어 획득 완료 — 반드시 finally에서 반환해야 한다
                            try {
                                return kisWatchlistClient.fetchStocksByGroup(group.interGrpCode());
                            } finally {
                                kisRateLimiter.release();
                            }
                        },
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
