package com.aaa.collector.observability;

/**
 * 침묵 드롭 로깅용으로 한 행을 공개 식별 문자열로 변환하는 콜백 인터페이스 (REQ-OBSV-027).
 *
 * <p>{@link SilentDropWarningCounter}가 비-1062 경고를 발견한 행에 한해 지연 호출하여, 로그의 {@code row=} 값을 조립한다.
 * 인서터별로 자신의 행 타입에 맞는 공개 식별자(시세/이벤트 데이터의 symbol/date/종목ID 등)를 조합해 위임한다.
 *
 * <p><b>보안 계약 (REQ-OBSV-028)</b>: 구현체는 시크릿(appkey/secret/token)·개인식별정보(HTS_ID·계좌번호)·본문(뉴스 title 등)을
 * 식별자에 포함하면 안 된다 — 로그로 유출되기 때문이다. 시세/이벤트 데이터의 공개 식별자만 사용한다.
 *
 * @param <T> 행 타입
 */
// @MX:ANCHOR: [AUTO] 행 식별자 위임 계약 — SilentDropWarningCounter + 14개 Tier-1 inserter가 소비(fan_in≥3)
// @MX:REASON: REQ-OBSV-027 — 침묵 드롭 로그의 row 값 조립을 인서터별로 위임하는 확장 지점
// @MX:SPEC: SPEC-COLLECTOR-OBSV-002
@FunctionalInterface
public interface RowDescriber<T> {

    /**
     * 한 행을 로그용 공개 식별 문자열로 변환한다.
     *
     * <p>비-1062 경고를 발견한 행에서만 호출된다(지연 호출). 시크릿·개인식별정보·본문을 포함하지 않는다(REQ-OBSV-028).
     *
     * @param row 침묵 드롭이 발견된 행
     * @return 로그의 {@code row=} 값으로 사용될 공개 식별 문자열
     */
    String describe(T row);
}
