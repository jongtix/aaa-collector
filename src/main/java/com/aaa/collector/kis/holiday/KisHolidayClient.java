package com.aaa.collector.kis.holiday;

import com.aaa.collector.kis.KisApiExecutor;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KIS 국내휴장일조회 API 클라이언트 (TR_ID=CTCA0903R).
 *
 * <p>엔드포인트: {@code GET /uapi/domestic-stock/v1/quotations/chk-holiday}. 단일 호출에 기준일부터 ~24일치를 반환한다 —
 * 페이징({@code ctx_area_nk})은 구현하지 않는다(SPEC-COLLECTOR-OBSV-001 §1.6 호출 정책, KisApiExecutor 확장 금지).
 *
 * <p>[★주의] KIS 원장서비스 연관으로 가급적 1일 1회 호출을 권고한다. 일 1회 갱신·캐시 후 메모리 판정으로 사용한다.
 */
@Component
@RequiredArgsConstructor
public class KisHolidayClient {

    private static final String TR_ID = "CTCA0903R";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisApiExecutor kisApiExecutor;

    /**
     * 기준일부터의 국내 휴장/개장 일자 배열을 조회한다 (REQ-OBSV-031).
     *
     * <p>단일 호출로 기준일부터 ~24일치를 반환한다. 페이징은 구현하지 않는다.
     *
     * @param baseDate 조회 기준일 (KST)
     * @return 일자별 개장/휴장 정보 목록 (응답 output 그대로, 빈 목록 방어 포함)
     */
    public List<KisHolidayResponse.HolidayRow> fetchCalendar(LocalDate baseDate) {
        String bassDt = baseDate.format(DATE_FORMAT);
        KisHolidayResponse response =
                kisApiExecutor.executeGet(
                        uri ->
                                uri.path("/uapi/domestic-stock/v1/quotations/chk-holiday")
                                        .queryParam("BASS_DT", bassDt)
                                        .queryParam("CTX_AREA_NK", "")
                                        .queryParam("CTX_AREA_FK", "")
                                        .build(),
                        TR_ID,
                        KisHolidayResponse.class);

        List<KisHolidayResponse.HolidayRow> output = response.output();
        return output != null ? output : List.of();
    }
}
