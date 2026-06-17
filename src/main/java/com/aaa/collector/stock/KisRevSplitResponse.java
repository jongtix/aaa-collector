package com.aaa.collector.stock;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 예탁원정보 액면교체일정 API 응답 (TR HHKDB669105C0).
 *
 * <p>{@code output1}(액면교체 일정 Object Array, 7필드)을 사용한다. CTS 연속조회 구조적 불가 확정(PROBE-1): top-level에
 * {@code cts} 필드 없음, 동일 범위 재호출 시 동일 100건 반환(커서 무진행). → 단일 페이지 호출로 구현.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다.
 *
 * <p>{@link KisDividendScheduleResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisRevSplitResponse(String rtCd, String msgCd, String msg1, List<RevSplitRow> output1)
        implements KisApiResponse {

    /** 방어적 복사 — output1 필드를 불변 리스트로 변환. */
    public KisRevSplitResponse {
        output1 = output1 != null ? List.copyOf(output1) : List.of();
    }

    /**
     * 액면교체 일정 행 (output1 배열 1건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다. 매핑 대상
     * 7필드만 선언한다(REQ-BATCH5-011).
     *
     * <p>미저장 필드({@code td_stop_dt}/{@code list_dt}/{@code isin_name})는 수신은 하지만 매핑 단계에서 버린다(손실 허용 —
     * D-5 확정).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevSplitRow(
            /** 기준일 (YYYYMMDD) → event_date */
            String recordDate,
            /** 단축종목코드 → stock_id(FK 조회) */
            String shtCd,
            /** 종목명 (미저장 — stocks 마스터 보유) */
            String isinName,
            /** 교체 전 면액 (9자리 zero-pad) → 분할비율 계산 분자 */
            String interBfFaceAmt,
            /** 교체 후 면액 (9자리 zero-pad) → face_value + 분할비율 계산 분모 */
            String interAfFaceAmt,
            /** 매매정지일 (미저장) */
            String tdStopDt,
            /** 재상장일 (미저장) */
            String listDt) {}
}
