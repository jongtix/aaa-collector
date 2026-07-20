package com.aaa.collector.observability;

/**
 * 침묵 드롭 로깅용으로 한 행을 공개 식별 문자열로 변환하는 콜백 인터페이스 (REQ-OBSV-027).
 *
 * <p>{@link SilentDropWarningCounter}가 비-1062 경고를 발견한 행에 한해 지연 호출하여, 로그의 {@code row=} 값을 조립한다.
 * 인서터별로 자신의 행 타입에 맞는 공개 식별자(시세/이벤트 데이터의 symbol/date/종목ID 등)를 조합해 위임한다.
 *
 * <p><b>[경고] 보안 계약 (REQ-OBSV-028) — 구현체는 반드시 준수할 것</b>: {@link #describe}의 반환값은 그대로 WARN 로그의 {@code
 * row=} 필드에 실려 VictoriaLogs 등 로그 저장소에 영구 보관된다. 구현체는 다음을 절대 포함하면 안 된다.
 *
 * <ul>
 *   <li>시크릿 — appkey, secret, access_token 등 인증 자격증명
 *   <li>개인식별정보(PII) — HTS_ID, 계좌번호 등 사용자 식별 정보
 *   <li>본문 — 뉴스 title/content 등 원문 텍스트
 * </ul>
 *
 * <p>시세/이벤트 데이터의 공개 식별자(symbol·date·종목ID 등)만 조합해 사용한다. 새 인서터를 추가할 때 이 계약을 어기면 시크릿·PII·본문이 로그로 유출된다
 * — 리뷰 시 반드시 확인할 것.
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
