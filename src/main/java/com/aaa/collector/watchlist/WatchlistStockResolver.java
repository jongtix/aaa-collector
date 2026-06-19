package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * ②단계 종목 해석 조율자 — 종목별 기본정보를 건강 키 least-busy lease 멀티키 경로로 조회하여 {@link ResolvedStock} 목록을 산출한다
 * (WatchlistSyncService에서 추출, SPEC-COLLECTOR-WLSYNC-007 행위 보존).
 *
 * <p>SPEC-COLLECTOR-KISGATE-001: 건강한 키 집합을 {@link KeyLeaseRegistry#openSession()} per-batch 스냅샷으로
 * 1회 고정하고, 종목별 기본정보 조회를 {@link GuardedKisExecutor} 단일 게이트로 수행한다(least-busy 동적 lease). 기존 정적
 * distributor 라운드로빈을 동적 lease로 대체한 것이며, graceful skip(REQ-WLSYNC-134) 종단 동작은 보존한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistStockResolver {

    private final KisStockInfoClient kisStockInfoClient;
    private final StockAssetTypeClassifier stockAssetTypeClassifier;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * ②단계: 종목별 기본정보를 건강한 키 least-busy lease 멀티키 경로로 조회하여 {@link ResolvedStock} 목록을 산출한다
     * (REQ-WLSYNC-120,130~134).
     *
     * <p>입력 종목이 비어있으면 게이트 세션을 열지 않고 빈 목록을 반환한다(무로그, 레거시 보존). 건강 키 스냅샷은 {@link
     * KeyLeaseRegistry#openSession()}으로 per-batch 1회 고정한다(REQ-KISGATE-006a). 건강한 키가 0개이면 ②단계 분산을
     * graceful skip하고(경고 로깅, 예외 미전파) 빈 목록을 반환한다(REQ-WLSYNC-134, REQ-KISGATE-024).
     */
    // @MX:ANCHOR: [AUTO] ②단계 멀티키 분산 진입점 — per-batch 스냅샷 + 게이트 lease 호출 (정적 distributor 대체)
    // @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-120,131,132,134,
    // SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-006a,-024 — 게이트 경유 graceful skip
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006, SPEC-COLLECTOR-KISGATE-001
    public List<ResolvedStock> resolve(List<KisStockListByGroupResponse.Stock> rawStocks) {
        // 빈 입력(빈 워치리스트 또는 ①단계 그룹조회 전체 실패) early guard:
        // 레거시는 건강 키가 있어도 rawStocks가 비면 아무 로그 없이 빈 목록을 반환했다. 이 가드로 무로그 빈 입력 경로를 보존한다.
        if (rawStocks.isEmpty()) {
            return List.of();
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        // REQ-KISGATE-024, REQ-WLSYNC-134 보존: 빈 스냅샷 = 건강 키 0개 → graceful skip(경고 로깅, 예외 미전파)
        if (session.isEmpty()) {
            log.warn("②단계 건강한 키 0개 — 멀티키 분산 graceful skip (REQ-WLSYNC-134)");
            return List.of();
        }

        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션 공유(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ResolveResult>> futures =
                    rawStocks.stream()
                            .map(stock -> resolveAsync(stock, session, executor))
                            .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .peek(
                            r -> {
                                if (log.isDebugEnabled() && r instanceof ResolveResult.Skipped s) {
                                    log.debug("종목 skip — {}", s.reason());
                                }
                            })
                    .filter(r -> r instanceof ResolveResult.Success)
                    .map(r -> ((ResolveResult.Success) r).stock())
                    .toList();
        }
    }

    private CompletableFuture<ResolveResult> resolveAsync(
            KisStockListByGroupResponse.Stock stock,
            LeaseSession session,
            ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> resolveOne(stock, session), executor);
    }

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    private ResolveResult resolveOne(
            KisStockListByGroupResponse.Stock stock, LeaseSession session) {
        Market routingMarket =
                KisMarketResolver.resolve(
                        stock.fidMrktClsCode(), stock.exchCode(), stock.jongCode());
        if (routingMarket == null) {
            return new ResolveResult.Skipped(
                    "알 수 없는 마켓: fid=" + stock.fidMrktClsCode() + ", exch=" + stock.exchCode());
        }
        StockInfo info = fetchStockInfo(stock, routingMarket, session);

        // 권위 시장 결정 (REQ-STOCKMETA-010, A안):
        // StockInfo.market()이 존재하면 그것이 권위 (parseDomestic의 mket_id_cd 기반 확정값 또는 해외 라우팅 시장).
        // StockInfo가 null(조회 실패) 또는 market()이 null(정적 자산유형 경로)이면 coarse routingMarket 사용.
        Market authoritative =
                (info != null && info.market() != null) ? info.market() : routingMarket;
        return new ResolveResult.Success(
                new ResolvedStock(stock.jongCode(), stock.htsKorIsnm(), authoritative, info));
    }

    private StockInfo fetchStockInfo(
            KisStockListByGroupResponse.Stock stock, Market market, LeaseSession session) {
        Optional<AssetType> staticAssetType =
                stockAssetTypeClassifier.classify(market, stock.jongCode());
        if (staticAssetType.isPresent()) {
            return new StockInfo(staticAssetType.get(), null, null);
        }
        try {
            return kisStockInfoClient.fetchStockInfo(
                    stock.jongCode(), market, gateFetcher(session, stock.jongCode()));
        } catch (DateTimeParseException ex) {
            log.warn(
                    "날짜 파싱 실패로 종목 skip — symbol={}, market={}: {}",
                    stock.jongCode(),
                    market,
                    ex.getMessage());
            return null;
        } catch (KisTokenIssueException
                | KisApiBusinessException
                | IllegalStateException
                | RestClientException ex) {
            log.warn(
                    "종목 기본정보 조회 실패로 skip — symbol={}, market={}: {}",
                    stock.jongCode(),
                    market,
                    ex.getMessage());
            return null;
        }
    }

    /**
     * ②단계 멀티키 호출 전략: per-batch 스냅샷 세션으로 {@link GuardedKisExecutor}를 경유한다(least-busy lease + per-key
     * rate limiter + EGW00201 재시도). 게이트는 소진 시 retryable 예외를 던지므로, 본 fetcher가 기존 {@code
     * BatchRestExecutor} 종단 동작과 동일하게 {@link BatchResult#skip}으로 변환한다(graceful skip 보존 — {@code
     * KisStockInfoClient}가 !isSuccess를 null로 처리). {@link InterruptedException}은 플래그 복원 후 skip 변환.
     */
    private KisStockInfoClient.StockInfoFetcher gateFetcher(LeaseSession session, String symbol) {
        return new KisStockInfoClient.StockInfoFetcher() {
            @Override
            public <T extends KisApiResponse> BatchResult<T> fetch(
                    Function<UriBuilder, URI> uriCustomizer, String trId, Class<T> responseType) {
                try {
                    T response =
                            guardedKisExecutor.execute(session, uriCustomizer, trId, responseType);
                    return BatchResult.success(response);
                } catch (KisRateLimitException | RestClientException e) {
                    // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip(기존 BatchResult.skip 등가)
                    return BatchResult.skip(symbol, "재시도 소진: " + e.getMessage());
                } catch (InterruptedException e) {
                    // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip(전파 아님)
                    Thread.currentThread().interrupt();
                    return BatchResult.skip(symbol, "인터럽트");
                } catch (NoHealthyKeyException e) {
                    // 방어적: resolve()의 isEmpty 단락으로 정상 운용에서는 도달하지 않음
                    return BatchResult.skip(symbol, "건강 키 0개");
                }
            }
        };
    }
}
