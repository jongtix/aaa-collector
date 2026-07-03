package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * FINRA CDN 하루치 공매도 Daily 파일 취득 클라이언트 (SPEC-COLLECTOR-BACKFILL-008 T2,
 * REQ-BACKFILL-100~104/115/115a).
 *
 * <p>CNMS 통합 파일을 먼저 GET해 200이면 그것만 사용하고(REQ-BACKFILL-102, 2018-08-01 이중 존재 이중계상 방지), CNMS가 403/404면
 * {@code facilityCodes} 각각을 GET해 200인 파일만 반환한다(REQ-BACKFILL-103). REST {@code regShoDaily} 엔드포인트로는
 * 요청하지 않는다(REQ-BACKFILL-100a) — 전용 무인증 {@code finraCdnRestClient}만 사용한다.
 */
// @MX:NOTE: [AUTO] CNMS 우선 규칙 = 2018-08-01 CNMS·시설 파일 이중 존재 이중계상 방지; 403/404 구분은 관측성 목적이며
// 종료 신호로 해석하지 않음(REQ-BACKFILL-115a)
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-008
@Slf4j
@Component
@RequiredArgsConstructor
public class FinraCdnDailyFileClient {

    private static final String CNMS_FACILITY = "CNMS";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestClient finraCdnRestClient;
    private final FinraCdnShortSaleBackfillProperties properties;

    /**
     * 지정 거래일의 CDN 파일을 취득한다.
     *
     * @param tradeDate 취득 대상 거래일
     * @return CNMS 또는 시설 파일 본문 목록({@link FinraCdnFetchResult.Found}), 전부 부재면 403/404 구분 결과({@link
     *     FinraCdnFetchResult.Absent})
     */
    public FinraCdnFetchResult fetch(LocalDate tradeDate) {
        String dateSegment = tradeDate.format(DATE_FORMAT);

        FetchAttempt cnmsAttempt = attempt(fileUrl(CNMS_FACILITY, dateSegment));
        if (cnmsAttempt.transientError()) {
            return new FinraCdnFetchResult.Absent(
                    FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR);
        }
        if (cnmsAttempt.status().is2xxSuccessful()) {
            return new FinraCdnFetchResult.Found(List.of(cnmsAttempt.body()));
        }

        List<String> fileBodies = new ArrayList<>();
        for (String facilityCode : properties.getFacilityCodes()) {
            FetchAttempt attempt = attempt(fileUrl(facilityCode, dateSegment));
            if (attempt.transientError()) {
                return new FinraCdnFetchResult.Absent(
                        FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR);
            }
            if (attempt.status().is2xxSuccessful()) {
                fileBodies.add(attempt.body());
            }
        }

        if (!fileBodies.isEmpty()) {
            return new FinraCdnFetchResult.Found(fileBodies);
        }

        return new FinraCdnFetchResult.Absent(absenceReasonFor(cnmsAttempt.status()));
    }

    private static FinraCdnFetchResult.AbsenceReason absenceReasonFor(HttpStatusCode cnmsStatus) {
        return cnmsStatus.value() == 403
                ? FinraCdnFetchResult.AbsenceReason.FLOOR_BEFORE_403
                : FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404;
    }

    private static String fileUrl(String facilityCode, String dateSegment) {
        return "/equity/regsho/daily/" + facilityCode + "shvol" + dateSegment + ".txt";
    }

    /**
     * 단일 URL을 GET하고 HTTP 상태·본문을 반환한다. 403/404는 예외로 승격하지 않고 상태 코드로 흡수한다(REQ-BACKFILL-115).
     * 5xx·타임아웃·연결 실패는 일시적 오류로 흡수해 {@link FetchAttempt#transientError()}로 반환한다(코드리뷰 Fix 1) — 사이클 전체가
     * 아니라 그날 하루만 실패로 처리되도록 {@link #fetch(LocalDate)}가 즉시 중단한다. 응답 본문이 {@code
     * properties.maxFileSizeBytes}를 초과하면 동일하게 일시적 오류로 흡수한다(코드리뷰 Fix 2).
     */
    private FetchAttempt attempt(String url) {
        try {
            ResponseEntity<String> response =
                    finraCdnRestClient.get().uri(url).retrieve().toEntity(String.class);
            String body = response.getBody();
            if (body != null && body.length() > properties.getMaxFileSizeBytes()) {
                log.warn(
                        "[finra-cdn-backfill] 응답 크기 상한 초과 — url={}, size={}, limit={}",
                        url,
                        body.length(),
                        properties.getMaxFileSizeBytes());
                return new FetchAttempt(null, null, true);
            }
            return new FetchAttempt(response.getStatusCode(), body, false);
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.NotFound e) {
            return new FetchAttempt(e.getStatusCode(), null, false);
        } catch (HttpServerErrorException e) {
            log.warn(
                    "[finra-cdn-backfill] FINRA CDN 5xx 응답 — url={}, message={}",
                    url,
                    e.getMessage());
            return new FetchAttempt(null, null, true);
        } catch (ResourceAccessException e) {
            log.warn(
                    "[finra-cdn-backfill] FINRA CDN 연결/타임아웃 오류 — url={}, message={}",
                    url,
                    e.getMessage());
            return new FetchAttempt(null, null, true);
        }
    }

    private record FetchAttempt(HttpStatusCode status, String body, boolean transientError) {}
}
