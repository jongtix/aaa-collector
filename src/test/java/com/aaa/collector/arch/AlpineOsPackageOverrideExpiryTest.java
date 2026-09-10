package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Dockerfile} 런타임 스테이지의 {@code apk upgrade}(openssl/libcrypto3/libssl3/expat,
 * CVE-2026-14456/76956/76957 대응, 2026-09-10)가 방치되지 않도록 강제하는 만료 게이트.
 *
 * <p>베이스 이미지({@code eclipse-temurin:21-jre-alpine})는 digest로 고정돼 있어 재현 가능하지만, 그 안에 내장된 Alpine OS
 * 패키지는 이미지가 리빌드되기 전까지 그대로 남는다. 2026-09-10 실측 확인 결과 해당 태그의 최신 digest조차 CVE 공개일 이전 빌드라 동일하게 취약했으므로,
 * digest 재고정 대신 {@code Dockerfile}에서 해당 패키지만 타겟 업그레이드했다(업계 관행: 재고정이 1순위이나 통하지 않을 때 타겟 패치가 차선책 —
 * {@link DependencyVersionOverrideExpiryTest}와 동일한 문제 구조).
 *
 * <p><b>만료 시 조치</b>: 베이스 이미지가 리빌드되어 openssl ≥ 3.5.8-r0, libexpat ≥ 2.8.4-r0을 기본 포함하는지 {@code docker
 * pull}+{@code apk info}로 확인한다. 흡수했다면 {@code Dockerfile}의 {@code apk upgrade} 줄과 이 테스트를 함께 삭제한다. 아직
 * 미흡수라면 {@link #REVIEW_BY}를 새 날짜로 갱신한다.
 */
class AlpineOsPackageOverrideExpiryTest {

    private static final LocalDate REVIEW_BY = LocalDate.of(2026, 12, 9);

    @Test
    @DisplayName("Alpine OS 패키지(openssl/libexpat) 타겟 업그레이드 검토 기한이 지나지 않아야 한다")
    void alpineOsPackageOverrideMustBeReviewedBeforeExpiry() {
        assertThat(LocalDate.now())
                .as(
                        "Dockerfile의 openssl/libcrypto3/libssl3/expat apk upgrade(CVE-2026-14456, "
                                + "CVE-2026-76956/76957 대응) 검토 기한이 지났다. eclipse-temurin:21-jre-alpine 최신 "
                                + "digest가 이 CVE들을 흡수했는지 확인하고, 흡수했다면 Dockerfile의 apk upgrade 줄과 이 테스트를 함께 "
                                + "삭제한다. 아직 미흡수이면 REVIEW_BY를 갱신한다.")
                .isBefore(REVIEW_BY);
    }
}
