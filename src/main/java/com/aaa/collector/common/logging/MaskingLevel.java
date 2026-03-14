package com.aaa.collector.common.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MDC 키별 마스킹 수준을 정의하는 열거형.
 *
 * <p>FRONT_ONLY: 앞 4자만 노출 (시크릿 등 고위험 값)
 *
 * <p>FRONT_BACK: 앞 4자 + 뒤 4자 노출 (앱키, 토큰 등 식별 필요 값)
 *
 * <p>BACK_ONLY: 뒤 2자리만 노출 (계좌번호 등)
 */
// PMD.UseConcurrentHashMap: static initializer에서만 사용되고 Map.copyOf()로 즉시 불변 맵으로 교체된다.
// 외부에 노출되는 KEY_MAP은 불변이므로 동시 접근 위험이 없다.
// ruleset 전역 제외 시 Virtual Threads 환경의 실제 동시성 버그를 놓칠 수 있어 클래스 단위로 억제한다.
@SuppressWarnings("PMD.UseConcurrentHashMap")
public enum MaskingLevel {

    /** 앞 4자만 노출: {@code s3cr****} */
    FRONT_ONLY(Set.of("appsecret")),

    /** 앞 4자 + 뒤 4자 노출: {@code PSKd****q1xG} */
    FRONT_BACK(Set.of("appkey", "bearerToken", "wsKey")),

    /** 뒤 2자리만 노출: {@code ********01} */
    BACK_ONLY(Set.of("accountNo"));

    private static final Map<String, MaskingLevel> KEY_MAP;

    static {
        Map<String, MaskingLevel> map = new HashMap<>();
        for (MaskingLevel level : values()) {
            for (String key : level.mdcKeys) {
                map.put(key, level);
            }
        }
        KEY_MAP = Map.copyOf(map);
    }

    private final Set<String> mdcKeys;

    MaskingLevel(Set<String> mdcKeys) {
        this.mdcKeys = mdcKeys;
    }

    /**
     * MDC 키에 해당하는 MaskingLevel을 반환한다.
     *
     * <p>null을 전달하면 {@link Optional#empty()}를 반환한다.
     *
     * @param mdcKey MDC 키 이름 (null 허용)
     * @return 등록된 MaskingLevel, null이거나 없으면 empty
     */
    public static Optional<MaskingLevel> of(String mdcKey) {
        if (mdcKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(KEY_MAP.get(mdcKey));
    }
}
