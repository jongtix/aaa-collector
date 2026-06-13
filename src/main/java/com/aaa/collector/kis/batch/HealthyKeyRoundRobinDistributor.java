package com.aaa.collector.kis.batch;

import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 건강 키 기반 라운드로빈 아이템 분산기 (SPEC-COLLECTOR-KEYDIST-001).
 *
 * <p>{@link HealthyKeySelector#selectHealthy()}로 건강 키 집합을 조회하고, 결정적 규칙 {@code item[i] →
 * healthyKeys.get(i % healthyKeys.size())}에 따라 아이템→키 할당을 반환한다.
 *
 * <p>제네릭 설계로 BATCH-001(종목) · BATCH-002(수급 등) 모두에서 재사용 가능하다 (REQ-KEYDIST-030).
 *
 * <p>[단일책임] 이 컴포넌트는 분산 할당만 담당한다. 건강 키 집합이 비어있으면 빈 할당을 반환하며, skip-all 처리 · ERROR 로그 · 전체 키 폴백은
 * 호출자(caller)의 책임이다 (REQ-KEYDIST-004).
 */
@Component
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 건강 키 라운드로빈 분산기 — BATCH-001·BATCH-002 공용 재사용 컴포넌트
// @MX:REASON: SPEC-COLLECTOR-KEYDIST-001 REQ-KEYDIST-001,-002,-003,-030 — 죽은 키 제외 분산, BATCH-002 재사용
// @MX:SPEC: SPEC-COLLECTOR-KEYDIST-001
public class HealthyKeyRoundRobinDistributor {

    private final HealthyKeySelector healthyKeySelector;

    /**
     * 아이템 목록을 건강한 키에만 라운드로빈으로 분산하여 아이템→키 할당 맵을 반환한다.
     *
     * <p>건강 키 집합이 비어있으면 빈 맵을 반환한다. 빈 아이템 목록이면 빈 맵을 반환한다. skip-all 처리·ERROR 로그는 이 메서드가 아닌 호출자가 담당한다.
     *
     * @param <T> 분산 대상 아이템 타입 (Stock, 수급 대상 등)
     * @param items 분산 대상 아이템 목록
     * @return 키별 할당 아이템 목록 (빈 건강 키 집합 또는 빈 아이템 → 빈 맵)
     */
    public <T> Map<KisAccountCredential, List<T>> distribute(List<T> items) {
        List<KisAccountCredential> healthyKeys = healthyKeySelector.selectHealthy();

        if (healthyKeys.isEmpty() || items.isEmpty()) {
            return Collections.emptyMap();
        }

        int keyCount = healthyKeys.size();
        // Deterministic round-robin: item[i] → healthyKeys.get(i % healthyKeys.size())
        // Stream-based grouping avoids in-loop ArrayList instantiation and mutable map literals.
        return Collections.unmodifiableMap(
                IntStream.range(0, items.size())
                        .boxed()
                        .collect(
                                Collectors.groupingBy(
                                        i -> healthyKeys.get(i % keyCount),
                                        Collectors.mapping(items::get, Collectors.toList()))));
    }
}
