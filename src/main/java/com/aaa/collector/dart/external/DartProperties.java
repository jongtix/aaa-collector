package com.aaa.collector.dart.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenDART API 설정 프로퍼티 (SPEC-COLLECTOR-DART-001 REQ-DART-001).
 *
 * <p>{@code aaa.dart.*} 프리픽스로 바인딩된다. [HARD] {@code api-key}는 {@code ${DART_API_KEY:}} 환경변수 주입만 허용 —
 * 평문 하드코딩 금지(REQ-DART-001, AC-6).
 */
@ConfigurationProperties(prefix = "aaa.dart")
public class DartProperties {

    /** OpenDART API 인증키 — 환경변수 {@code DART_API_KEY}로 주입. */
    private String apiKey = "";

    /** 공시 유형 필터 — 미설정 시 전체 공시(REQ-DART-032, AC-7). */
    private String pblntfTy = "";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getPblntfTy() {
        return pblntfTy;
    }

    public void setPblntfTy(String pblntfTy) {
        this.pblntfTy = pblntfTy;
    }
}
