# === Build stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /collector

# Gradle wrapper + 빌드 설정 (의존성 레이어 캐시용 — src 변경 시 재다운로드 방지)
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon

# 소스 복사 및 빌드
# -x check: 정적 분석(spotbugsMain, spotbugsTest, pmdMain, pmdTest, spotlessCheck)과
#           테스트는 CI(release.yml)에서 실행하므로 Docker 빌드에서는 JAR 생성만 수행
COPY src/ src/
RUN ./gradlew build -x check --no-daemon

# === Runtime stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0

# OS 패키지 업그레이드: base digest 자체는 최신이나 상류 이미지가 재빌드되지 않아
# alpine 패키지(libexpat, p11-kit 등)가 배포판 최신 패치를 반영하지 못한 상태로 남을 수 있다.
# 최종 런타임 스테이지에서만 적용 — 빌드 스테이지는 재현성을 위해 불변 유지.
RUN apk upgrade --no-cache

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
