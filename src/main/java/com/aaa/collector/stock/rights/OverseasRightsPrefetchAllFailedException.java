package com.aaa.collector.stock.rights;

/**
 * 해외 권리 정기 수집에서 CTRGT011R 프리페치 세 유형(03/75/74)이 모두 실패했을 때 던지는 예외.
 *
 * <p>일부 유형만 실패하면 해당 유형만 폐기하고 배치는 정상 완료한다(fail-closed, REQ-ODA-017). 반면 세 유형이 전부 실패하면 금액 맵이 비어 해외 배당
 * 행이 하나도 생성되지 않으므로, 배치를 완료로 계측해 "스케줄 실행 누락" 알람을 가리지 않도록 예외로 올려 {@link OverseasRightsScheduler}의 예외
 * 분기(완료 미계측)를 타게 한다. 다음 실행 때 재시도된다.
 */
public class OverseasRightsPrefetchAllFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OverseasRightsPrefetchAllFailedException(String message) {
        super(message);
    }
}
