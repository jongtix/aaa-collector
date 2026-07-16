package com.aaa.collector.stock.shortsale.overseas.backfill;

import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.shortsale.overseas.FinraSymbolNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 하루치 FINRA CDN 파일 본문을 종목별로 합산·매칭·UPSERT하는 기본 구현체 (REQ-BACKFILL-104/-117/-118/-119).
 *
 * <p>{@link FinraCdnShortSaleBackfillOrchestrator}의 {@code loadDate}에서 추출했다(코드리뷰 — PMD
 * CouplingBetweenObjects 완화, 억제 주석 대신 구조 추출). 파싱·심볼 매칭·UPSERT 책임을 이 클래스로 좁혀, 오케스트레이터는 {@link
 * FinraCdnDailyLoader} 인터페이스 1개 타입만 참조한다.
 *
 * <p>{@code kept}/{@code raw}는 TASK-005a 실측 결론에 따른 신호다(SPEC-COLLECTOR-BACKFILL-011 §2.6) — {@code
 * kept}는 저장 시도가 예외 없이 완료된 매칭 심볼 수({@code upsertDaily}가 {@code INSERT ... ON DUPLICATE KEY UPDATE}라
 * 호출 성공=저장 확정, 중복 삽입 시도 포함이라는 kept 정의와 부합), {@code raw}는 병합 이전 파일별 파싱 성공 행수 합("API 원본 응답 행수"는 병합·매칭
 * 이전 시점을 가리킨다는 코디네이터 확정 정의).
 */
@Component
@RequiredArgsConstructor
public class FinraCdnDailyLoaderImpl implements FinraCdnDailyLoader {

    private final FinraCdnFileParser parser;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;

    @Override
    @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 빌드 전용, 이후 읽기만 함
    public FinraCdnDailyLoadOutcome loadDate(
            LocalDate date, List<String> fileBodies, Map<String, Stock> symbolMap) {
        // REQ-SSD-007: 시설/행 합산을 소수 산술로 수행해 정밀도 보존(Long::sum → BigDecimal::add)
        Map<String, BigDecimal> shortSums = new HashMap<>();
        Map<String, BigDecimal> totalSums = new HashMap<>();
        int skipped = 0;
        int raw = 0;
        for (String body : fileBodies) {
            ParsedFileResult parsed = parser.parse(body);
            skipped += parsed.skippedCount();
            raw += parsed.rows().size(); // TASK-005a 후보 A — 병합 전 파일별 파싱 성공 행수 합
            for (ParsedRow row : parsed.rows()) {
                String normalized = FinraSymbolNormalizer.normalize(row.symbol());
                shortSums.merge(normalized, row.shortVolume(), BigDecimal::add);
                totalSums.merge(normalized, row.totalVolume(), BigDecimal::add);
            }
        }

        int unmatched = 0;
        int kept = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, BigDecimal> entry : shortSums.entrySet()) {
            String symbol = entry.getKey();
            Stock stock = symbolMap.get(symbol);
            if (stock == null) {
                unmatched++;
                continue;
            }
            shortSaleOverseasRepository.upsertDaily(
                    stock.getId(), date, entry.getValue(), totalSums.get(symbol), now, null, null);
            kept++; // ON DUPLICATE KEY UPDATE 호출이 예외 없이 완료 = 저장 확정(§2.6 kept, 중복 삽입 시도 포함)
        }
        return new FinraCdnDailyLoadOutcome(kept, raw, skipped, unmatched);
    }
}
