package com.aaa.collector.kis.token;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KIS Open API 접속 설정.
 *
 * @param baseUrl KIS Open API 기본 URL (HTTPS 필수)
 * @param userId 한국투자증권 MTS 사용자 ID — 관심 그룹·종목 조회 시 사용 예정
 * @param accounts 계좌별 인증 정보 목록
 */
@ConfigurationProperties(prefix = "kis")
public record KisProperties(String baseUrl, String userId, List<KisAccountCredential> accounts) {

    public KisProperties {
        List<String> errors = new ArrayList<>();

        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            errors.add("kis.base-url must use HTTPS scheme, got: " + baseUrl);
        }
        if (userId == null || userId.isBlank()) {
            errors.add("kis.user-id must not be null or blank");
        }
        if (accounts == null || accounts.isEmpty()) {
            errors.add("kis.accounts must not be null or empty");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", errors));
        }

        accounts = List.copyOf(accounts);
    }
}
