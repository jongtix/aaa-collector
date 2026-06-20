package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSource;
import com.aaa.collector.market.indicator.SensitiveDataSanitizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 한국수출입은행 환율 API USDKRW Primary 클라이언트 (SPEC-COLLECTOR-MARKETIND-001, REQ-010~013).
 *
 * <p>URL: {@code
 * /site/program/financial/exchangeJSON?authkey={key}&searchdate={yyyyMMdd}&data=AP01}. {@code
 * cur_unit=="USD"} 필터, 쉼표 제거 후 BigDecimal 4자리, close만/source="KOREAEXIM"(REQ-013). 빈 배열 시 전날 영업일
 * 재시도(상한 {@code empty-retry-max}, REQ-012).
 *
 * <p>{@code fetchHistory()}는 단일 호출 미지원 소스이므로 빈 리스트를 반환한다(REQ-041).
 */
@Slf4j
@Component
public class KoreaeximExchangeRateClient implements MarketIndicatorSource {

    private static final String SOURCE = "KOREAEXIM";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PATH =
            "/site/program/financial/exchangeJSON?authkey={key}&searchdate={date}&data=AP01";

    private final RestClient koreaeximRestClient;
    private final String apiKey;
    private final int emptyRetryMax;

    public KoreaeximExchangeRateClient(
            RestClient koreaeximRestClient,
            @Value("${aaa.market-indicator.koreaexim-api-key:}") String apiKey,
            @Value("${aaa.market-indicator.usdkrw.empty-retry-max:5}") int emptyRetryMax) {
        this.koreaeximRestClient = koreaeximRestClient;
        this.apiKey = apiKey;
        this.emptyRetryMax = emptyRetryMax;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    /**
     * 지정 날짜의 USDKRW 환율을 수집한다. 빈 배열이면 전날 영업일(주말 skip)로 재시도한다(REQ-012).
     *
     * @param date 수집 시작 날짜
     * @return USD 행 목록 (성공 시 1건, 상한 초과 시 빈 리스트)
     */
    @Override
    public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        LocalDate target = date;
        for (int attempt = 0; attempt <= emptyRetryMax; attempt++) {
            List<MarketIndicatorRow> rows = fetchOnDate(target);
            if (!rows.isEmpty()) {
                return rows;
            }
            log.warn(
                    "[koreaexim] {} 빈 결과 — 전날 영업일로 재시도 ({}/{})",
                    target,
                    attempt + 1,
                    emptyRetryMax);
            target = prevBusinessDay(target);
        }
        log.warn("[koreaexim] 재시도 상한({}) 초과 — 빈 결과 반환", emptyRetryMax);
        return List.of();
    }

    /** 전체 이력 단일 호출 미지원 — 빈 리스트 반환(REQ-041). 백필은 날짜 루프로 수행한다. */
    @Override
    public List<MarketIndicatorRow> fetchHistory() {
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<MarketIndicatorRow> fetchOnDate(LocalDate date) {
        String dateStr = date.format(DATE_FMT);
        List<Map<String, String>> body =
                koreaeximRestClient
                        .get()
                        .uri(PATH, apiKey, dateStr)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        return body.stream()
                .filter(m -> "USD".equals(m.get("cur_unit")))
                .map(m -> toRow(m, date))
                .filter(r -> r != null)
                .toList();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 행별 파싱 실패 격리
    private MarketIndicatorRow toRow(Map<String, String> m, LocalDate date) {
        try {
            String raw = m.get("deal_bas_r");
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String cleaned = raw.replace(",", "");
            BigDecimal close = new BigDecimal(cleaned).setScale(4, RoundingMode.HALF_UP);
            if (close.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[koreaexim] close 0 이하 — skip: {}", m);
                return null;
            }
            return new MarketIndicatorRow(
                    IndicatorCode.USDKRW, date, null, null, null, close, SOURCE);
        } catch (Exception e) {
            log.warn(
                    "[koreaexim] 행 파싱 실패 — skip: {}, 오류: {}",
                    m,
                    SensitiveDataSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    /** 이전 영업일: 토요일이면 -1(금), 일요일이면 -2(금), 그 외 -1일. */
    static LocalDate prevBusinessDay(LocalDate date) {
        LocalDate prev = date.minusDays(1);
        while (prev.getDayOfWeek() == DayOfWeek.SATURDAY
                || prev.getDayOfWeek() == DayOfWeek.SUNDAY) {
            prev = prev.minusDays(1);
        }
        return prev;
    }
}
