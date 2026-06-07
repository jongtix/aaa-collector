package com.aaa.collector.stock.grade;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ADTV(평균 일 거래대금) 기반 시장 백분위 계산기.
 *
 * <p>백분위 정의: {@code rank / total * 100} (rank는 거래금액 내림차순 순위, 1위가 가장 낮은 백분위 = 상위)
 *
 * <p>KRX와 US 시장은 독립적으로 계산한다 (REQ-005). Stateless — thread-safe.
 */
@Component
public class AdtvPercentileCalculator {

    /**
     * 거래금액 기준 ADTV 백분위를 계산한다.
     *
     * <p>입력 목록이 비어 있거나 1개 종목이면 백분위 계산이 불가능하므로 빈 map을 반환한다. 호출자는 해당 종목을 REQ-009/010 실패 경로로 처리해야 한다.
     *
     * @param entries 종목코드와 거래금액(rankValue) 목록
     * @return symbol → 백분위(0~100) map
     */
    // @MX:NOTE: [AUTO] entries가 null이거나 1개 이하이면 빈 map 반환 — 호출자는 REQ-009/010 실패 경로로 처리
    public Map<String, Double> calculate(List<RankEntry> entries) {
        if (entries == null || entries.size() <= 1) {
            return Map.of();
        }

        // 거래금액 내림차순 정렬 (1위 = 거래금액 최대)
        List<RankEntry> sorted =
                entries.stream()
                        .sorted(Comparator.comparingDouble(RankEntry::rankValue).reversed())
                        .toList();

        int total = sorted.size();
        @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 내 로컬 result 누적 — 동시 접근 없음
        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < total; i++) {
            int rank = i + 1;
            double percentile = (double) rank / total * 100.0;
            result.put(sorted.get(i).symbol(), percentile);
        }
        return result;
    }

    /**
     * 백분위 계산 입력 항목.
     *
     * @param symbol 종목코드
     * @param rankValue 순위 결정에 사용할 값 (거래금액 등, 높을수록 상위)
     */
    public record RankEntry(String symbol, double rankValue) {}
}
