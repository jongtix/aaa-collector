package com.aaa.collector.kis.token;

import com.aaa.collector.common.logging.LogMaskingUtils;

/**
 * KIS Open API {@code POST /oauth2/tokenP} 응답 DTO.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다.
 */
record KisTokenResponse(
        String accessToken, String tokenType, int expiresIn, String accessTokenTokenExpired) {

    @Override
    public String toString() {
        return "KisTokenResponse{accessToken='%s', tokenType='%s', expiresIn=%d, accessTokenTokenExpired='%s'}"
                .formatted(
                        LogMaskingUtils.maskFrontBack(accessToken),
                        tokenType,
                        expiresIn,
                        accessTokenTokenExpired);
    }
}
