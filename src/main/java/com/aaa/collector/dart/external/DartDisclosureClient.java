package com.aaa.collector.dart.external;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * OpenDART {@code list.json} API 클라이언트 (SPEC-COLLECTOR-DART-001).
 *
 * <p>api-specs/dart/01-공시검색.md 실측 기준(2026-06-25). corp_code 미지정 — 전 종목 대상 공시 목록을 page_count=100 페이지
 * 순회한다(REQ-DART-010).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DartDisclosureClient {

    static final String PATH = "/api/list.json";
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartProperties dartProperties;

    private final RestClient dartRestClient;

    /**
     * 공시 목록 단일 페이지를 조회한다.
     *
     * @param bgnDe 조회 시작일 (YYYYMMDD)
     * @param endDe 조회 종료일 (YYYYMMDD)
     * @param pageNo 페이지 번호 (1-based)
     * @param corpCode DART 고유번호 — null이면 전 종목 대상
     * @return 공시 목록 응답 DTO
     */
    public DartListResponse fetchPage(
            LocalDate bgnDe, LocalDate endDe, int pageNo, String corpCode) {
        String bgnDeStr = bgnDe.format(DATE_FMT);
        String endDeStr = endDe.format(DATE_FMT);

        log.debug(
                "[dart-client] list.json 조회 — bgnDe={}, endDe={}, pageNo={}, corpCode={}",
                bgnDeStr,
                endDeStr,
                pageNo,
                corpCode);

        DartListResponse response =
                dartRestClient
                        .get()
                        .uri(
                                uriBuilder -> {
                                    UriBuilder builder =
                                            uriBuilder
                                                    .path(PATH)
                                                    .queryParam(
                                                            "crtfc_key", dartProperties.getApiKey())
                                                    .queryParam("bgn_de", bgnDeStr)
                                                    .queryParam("end_de", endDeStr)
                                                    .queryParam("page_count", 100)
                                                    .queryParam("page_no", pageNo);
                                    if (corpCode != null && !corpCode.isBlank()) {
                                        builder.queryParam("corp_code", corpCode);
                                    }
                                    String pblntfTy = dartProperties.getPblntfTy();
                                    if (pblntfTy != null && !pblntfTy.isBlank()) {
                                        builder.queryParam("pblntf_ty", pblntfTy);
                                    }
                                    return builder.build();
                                })
                        .retrieve()
                        .body(DartListResponse.class);

        if (response == null) {
            log.warn(
                    "[dart-client] list.json 응답 null — bgnDe={}, endDe={}, pageNo={}",
                    bgnDeStr,
                    endDeStr,
                    pageNo);
            return emptyResponse();
        }
        return response;
    }

    /**
     * 날짜 범위의 모든 페이지에서 공시 항목 목록을 수집한다 — 전 종목 대상 (REQ-DART-012, 폴링).
     *
     * @param bgnDe 조회 시작일
     * @param endDe 조회 종료일
     * @return 수집된 공시 항목 목록
     */
    public List<DartListResponse.DisclosureItem> fetchAllPages(LocalDate bgnDe, LocalDate endDe) {
        return fetchAllPages(bgnDe, endDe, null);
    }

    /**
     * 날짜 범위의 모든 페이지에서 공시 항목 목록을 수집한다 (REQ-DART-012).
     *
     * <p>status != "000" 인 응답은 적재하지 않고 WARN 로그를 남긴다(REQ-DART-031).
     *
     * @param bgnDe 조회 시작일
     * @param endDe 조회 종료일
     * @param corpCode DART 고유번호 — null이면 전 종목 대상 (폴링), 지정 시 해당 종목만 (백필)
     * @return 수집된 공시 항목 목록
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public List<DartListResponse.DisclosureItem> fetchAllPages(
            LocalDate bgnDe, LocalDate endDe, String corpCode) {
        List<DartListResponse.DisclosureItem> result = new java.util.ArrayList<>();
        int pageNo = 1;

        while (true) {
            DartListResponse page;
            try {
                page = fetchPage(bgnDe, endDe, pageNo, corpCode);
            } catch (Exception e) {
                log.warn(
                        "[dart-client] list.json 페이지 조회 실패 — pageNo={}, error={}",
                        pageNo,
                        e.getMessage());
                break;
            }

            // status != "000" 미적재 + WARN (REQ-DART-031)
            if (!"000".equals(page.getStatus())) {
                log.warn(
                        "[dart-client] list.json 비정상 status — status={}, message={}, pageNo={}",
                        page.getStatus(),
                        page.getMessage(),
                        pageNo);
                break;
            }

            result.addAll(page.getList());

            Integer totalPage = page.getTotalPage();
            if (totalPage == null || pageNo >= totalPage) {
                break;
            }
            pageNo++;
        }

        return result;
    }

    private static DartListResponse emptyResponse() {
        return new DartListResponse();
    }
}
