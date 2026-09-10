package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code gradle/libs.versions.toml}의 {@code tomcat}/{@code netty} 버전
 * 덮어쓰기(CVE-2026-65182/65905/68525, CVE-2026-75595 대응, 2026-09-10)가 방치되지 않도록 강제하는 만료 게이트.
 *
 * <p>Spring Boot 3.5.16(3.5.x 최신)이 관리하는 tomcat-embed-core/netty 버전에 CRITICAL CVE가 있어 카탈로그에서 직접 상위
 * 버전을 명시하고 {@code build.gradle.kts}가 {@code extra["tomcat.version"]}/{@code
 * extra["netty.version"]}로 소비한다. 이 덮어쓰기는 Spring Boot의 다음 패치 릴리스가 같은 버전을 흡수하면 불필요해지는데, 그 시점을 누구도
 * 능동적으로 알려주지 않으므로 방치되면 영구 고착된다({@code .trivyignore} 예외의 REQ-CVE-022 만료 강제와 같은 문제). {@code moai mx
 * query --kind DEBT} 같은 조회형 감사는 사람이 실행해야만 걸리므로 강제력이 없다 — 이 테스트는 그 대신 {@link #REVIEW_BY} 이후 {@code
 * ./gradlew test}/{@code check}를 실제로 실패시켜 CI에서 검토를 강제한다.
 *
 * <p><b>만료 시 조치</b>: Spring Boot 최신판이 tomcat-embed-core ≥ 10.1.59, netty ≥ 4.1.138.Final을 관리하는지
 * 확인한다. 흡수했다면 {@code gradle/libs.versions.toml}의 {@code tomcat}/{@code netty} 항목, {@code
 * build.gradle.kts}의 덮어쓰기 블록, 이 테스트 클래스를 함께 삭제한다(덮어쓰기가 해소된 것이므로 게이트도 함께 제거 — 남겨두면 그 자체가 새로운 방치 위험이
 * 된다). 아직 미흡수라면 {@link #REVIEW_BY}를 새 날짜로 갱신한다.
 */
class DependencyVersionOverrideExpiryTest {

    private static final LocalDate REVIEW_BY = LocalDate.of(2026, 12, 9);

    @Test
    @DisplayName("tomcat-embed/netty 버전 덮어쓰기 검토 기한이 지나지 않아야 한다")
    void tomcatNettyVersionOverrideMustBeReviewedBeforeExpiry() {
        assertThat(LocalDate.now())
                .as(
                        "gradle/libs.versions.toml의 tomcat/netty 버전 덮어쓰기(CVE-2026-65182/65905/68525, "
                                + "CVE-2026-75595 대응) 검토 기한이 지났다. Spring Boot 최신판이 이 CVE들을 흡수했는지 확인하고, "
                                + "흡수했다면 카탈로그의 tomcat/netty 항목, build.gradle.kts의 덮어쓰기 블록, 이 테스트를 함께 "
                                + "삭제한다. 아직 미흡수이면 REVIEW_BY를 갱신한다.")
                .isBefore(REVIEW_BY);
    }
}
