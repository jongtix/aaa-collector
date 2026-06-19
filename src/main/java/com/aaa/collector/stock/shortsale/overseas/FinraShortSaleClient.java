package com.aaa.collector.stock.shortsale.overseas;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * FINRA REST Query API({@code regShoDaily}/{@code consolidatedShortInterest}) 호출 클라이언트.
 *
 * <p>인증 없이(REQ-SSO-005) FINRA 전용 {@link RestClient}로 호출하며, 응답 헤더 {@code record-total}을 읽어 {@code
 * offset}을 {@link #PAGE_LIMIT}씩 증가시키는 루프로 전 페이지를 누적한다(D13, AC-PAGE-1). 빈 응답({@code
 * record-total=0})이면 빈 리스트를 반환한다(AC-EMPTY-1). 페이징 종료 조건은 {@code offset >= record-total}이다.
 */
@Component
@RequiredArgsConstructor
public class FinraShortSaleClient {

    private static final String REG_SHO_DAILY_PATH = "/data/group/otcMarket/name/regShoDaily";
    private static final String CONSOLIDATED_SHORT_INTEREST_PATH =
            "/data/group/otcMarket/name/consolidatedShortInterest";

    /** FINRA 페이지 최대 크기({@code record-max-limit:5000}, 실측 2026-06-19). */
    private static final int PAGE_LIMIT = 5000;

    private static final String RECORD_TOTAL_HEADER = "record-total";

    private static final ParameterizedTypeReference<List<FinraRegShoDailyResponse>>
            DAILY_LIST_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<FinraConsolidatedShortInterestResponse>>
            INTEREST_LIST_TYPE = new ParameterizedTypeReference<>() {};

    private final RestClient finraRestClient;

    /**
     * {@code regShoDaily}를 대상 거래일({@code tradeReportDate} EQUAL 필터)로 호출해 하루치 전종목 행을 페이징 누적한다.
     *
     * @param tradeReportDate 조회 거래일
     * @return 전 페이지를 합친 일별 공매도 거래량 행(빈 응답이면 빈 리스트)
     */
    public List<FinraRegShoDailyResponse> fetchRegShoDaily(LocalDate tradeReportDate) {
        return fetchAllPages(
                REG_SHO_DAILY_PATH,
                offset ->
                        Map.of(
                                "compareFilters",
                                List.of(
                                        Map.of(
                                                "compareType", "EQUAL",
                                                "fieldName", "tradeReportDate",
                                                "fieldValue", tradeReportDate.toString())),
                                "limit",
                                PAGE_LIMIT,
                                "offset",
                                offset),
                DAILY_LIST_TYPE);
    }

    /**
     * {@code consolidatedShortInterest}를 {@code settlementDate} {@code dateRangeFilters} 범위로 호출해 최근
     * 발표분 전종목 행을 페이징 누적한다(D16).
     *
     * @param from 범위 시작 settlementDate(포함)
     * @param to 범위 끝 settlementDate(포함)
     * @return 전 페이지를 합친 공매도 잔고 행(빈 응답이면 빈 리스트)
     */
    public List<FinraConsolidatedShortInterestResponse> fetchConsolidatedShortInterest(
            LocalDate from, LocalDate to) {
        return fetchAllPages(
                CONSOLIDATED_SHORT_INTEREST_PATH,
                offset ->
                        Map.of(
                                "dateRangeFilters",
                                List.of(
                                        Map.of(
                                                "fieldName", "settlementDate",
                                                "startDate", from.toString(),
                                                "endDate", to.toString())),
                                "limit",
                                PAGE_LIMIT,
                                "offset",
                                offset),
                INTEREST_LIST_TYPE);
    }

    /**
     * {@code record-total} 헤더 기반 offset 페이징 루프. {@code offset}을 0/5000/10000…으로 증가시키며 {@code offset
     * >= record-total}일 때 종료하고, 전 페이지 행을 누적해 반환한다.
     *
     * @param path FINRA 데이터셋 경로
     * @param bodyForOffset offset → 요청 바디 매핑
     * @param listType 응답 본문 리스트 타입
     * @param <T> 응답 행 타입
     * @return 누적된 전 페이지 행
     */
    private <T> List<T> fetchAllPages(
            String path,
            IntFunction<Map<String, Object>> bodyForOffset,
            ParameterizedTypeReference<List<T>> listType) {
        List<T> accumulated = new ArrayList<>();
        int offset = 0;
        long recordTotal;
        do {
            ResponseEntity<List<T>> entity =
                    finraRestClient
                            .post()
                            .uri(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(bodyForOffset.apply(offset))
                            .retrieve()
                            .toEntity(listType);

            List<T> page = entity.getBody();
            if (page != null) {
                accumulated.addAll(page);
            }
            recordTotal = parseRecordTotal(entity);
            offset += PAGE_LIMIT;
        } while (offset < recordTotal);

        return accumulated;
    }

    /** {@code record-total} 헤더를 long으로 파싱한다. 헤더가 없거나 비숫자면 0(더 이상 페이지 없음)으로 간주한다. */
    private static long parseRecordTotal(ResponseEntity<?> entity) {
        String value = entity.getHeaders().getFirst(RECORD_TOTAL_HEADER);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
