# === Build stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS build
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
FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c

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
