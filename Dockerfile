# === Build stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /collector

# 릴리스 태그 버전 주입 (docker.yml이 --build-arg VERSION=<태그-v제거> 로 전달)
# 미전달 시 0.0.0 — gradle.properties의 version은 더 이상 릴리스 커밋백으로 갱신되지 않는다
ARG VERSION=0.0.0

# Gradle wrapper + 빌드 설정 (의존성 레이어 캐시용 — src 변경 시 재다운로드 방지)
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon

# 소스 복사 및 빌드
# -x check: 정적 분석(spotbugsMain, spotbugsTest, pmdMain, pmdTest, spotlessCheck)과
#           테스트는 CI(release.yml)에서 실행하므로 Docker 빌드에서는 JAR 생성만 수행
COPY src/ src/
RUN ./gradlew build -x check --no-daemon -Pversion=${VERSION}

# === Runtime stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699

# 베이스 이미지(Alpine 3.24) 내장 OS 패키지 CVE 대응 (CVE-2026-14456, CVE-2026-76956/76957, 2026-09-10) —
# 위 digest 재조회로 해소되는지 실측 확인한 결과, 현재 태그의 최신 digest도 CVE 공개일 이전 빌드라
# 동일하게 취약한 버전(openssl 3.5.7-r0, libexpat 2.8.3-r0)을 그대로 포함한다(2026-09-10 pull+apk info 확인).
# Alpine 3.24 저장소에는 수정판(3.5.8-r0, 2.8.4-r0)이 이미 배포돼 있으므로, 베이스 이미지 리빌드를
# 기다리지 않고 해당 패키지만 타겟 업그레이드한다 — JDK/JRE 계층의 digest 고정(재현성)은 그대로 유지.
# 만료 게이트: AlpineOsPackageOverrideExpiryTest(src/test/.../arch/)가 REVIEW_BY(2026-12-09)까지
# 미해소 시 ./gradlew test 실패, 그 전에 베이스 이미지가 리빌드되어 이 CVE들을 흡수했는지 확인한다 —
# 흡수했다면 이 RUN 줄과 해당 테스트를 함께 삭제한다.
RUN apk upgrade --no-cache openssl libcrypto3 libssl3 libexpat

# 비루트 유저 생성 + 로그 디렉토리 준비 (read_only 컨테이너에서 collector 유저 쓰기 권한 보장)
RUN addgroup -S -g 1004 collector && adduser -S -u 1004 collector -G collector \
    && mkdir -p /var/log/aaa-collector/dump && chown -R collector:collector /var/log/aaa-collector

# 애플리케이션 JAR 복사
WORKDIR /collector
COPY --chown=collector:collector --from=build /collector/build/libs/aaa-collector.jar aaa-collector.jar

USER collector
EXPOSE 8080

# 헬스체크: Spring Actuator /actuator/health/liveness (Alpine BusyBox wget 사용)
# liveness 그룹은 livenessState만 포함 — Redis(소프트 의존성) 장애가 컨테이너 재시작을 유발하지 않도록 분리
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# JVM 옵션: TECHSPEC 10.3절 기준 (NAS RAM 32GB 업그레이드 반영, 관련: aaa-infra#119)
# Xmx 384m→512m, MaxMetaspaceSize 160m→192m: docker-compose.yml 컨테이너 limit을 800M→1G로
#   상향한 데 맞춰 조정. MaxDirectMemorySize는 64m 유지 — 30일 VictoriaMetrics 실측 피크가
#   4.1MiB에 불과해(WS 5세션 포함) 상향 근거 없음.
# AIA chasing: koreaexim.go.kr 등은 TLS 체인에 중간 CA를 미전송한다. 최신 JDK는
#   AIA(Authority Information Access)로 중간 CA를 자동 보완하지만 기본 비활성이며,
#   활성화해도 caIssuer URL 접근이 deny-by-default다. 두 프로퍼티를 함께 지정해야
#   체인이 완성된다(enableAIAcaIssuers=true 단독으로는 PKIX path building failed).
#   호스트 단위 허용으로 CA 파일명 변경에 견고하게 대응.
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx512m", \
  "-XX:MaxMetaspaceSize=192m", "-XX:MaxDirectMemorySize=64m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/var/log/aaa-collector/dump/", \
  "-XX:ErrorFile=/var/log/aaa-collector/dump/hs_err_pid%p.log", \
  "-Duser.timezone=Asia/Seoul", \
  "-Dcom.sun.security.enableAIAcaIssuers=true", \
  "-Dcom.sun.security.allowedAIALocations=http://cacerts.thawte.com", \
  "-jar", "aaa-collector.jar"]
