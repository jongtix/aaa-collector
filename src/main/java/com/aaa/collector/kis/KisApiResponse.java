package com.aaa.collector.kis;

/**
 * KIS REST API 공통 응답 인터페이스.
 *
 * <p>관심종목 조회, 국내/해외 배치 수집 등 KIS 데이터 API는 모두 {@code rt_cd}, {@code msg_cd}, {@code msg1} 필드를 공통으로
 * 포함한다. 토큰 발급 API({@code KisTokenResponse})는 구조가 다르므로 이 인터페이스를 구현하지 않는다.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다.
 */
public interface KisApiResponse {

    String rtCd();

    String msgCd();

    String msg1();

    /** {@code rt_cd}가 {@code "0"}이 아니면 {@link IllegalStateException}을 던진다. */
    default void validateRtCd() {
        if (!"0".equals(rtCd())) {
            throw new IllegalStateException(
                    "KIS API 오류 응답 — rt_cd=%s, msg_cd=%s, msg1=%s"
                            .formatted(rtCd(), msgCd(), msg1()));
        }
    }
}
