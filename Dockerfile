# === Build stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:21-jdk-alpine@sha256:fd10ef3691adde33aa57cd1070eedd4ecbe7eff025e0bc82503fdd15e0e70f47 AS build
WORKDIR /collector

# Gradle wrapper + 빌드 설정 (의존성 레이어 캐시용 — src 변경 시 재다운로드 방지)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon

# 소스 복사 및 빌드 (테스트는 CI에서 실행)
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

# === Runtime stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:21-jre-alpine@sha256:693c22ea458d62395bac47a2da405d0d18c77b205211ceec4846a550a37684b6

# 비루트 유저 생성 + 로그 디렉토리 준비 (read_only 컨테이너에서 collector 유저 쓰기 권한 보장)
RUN addgroup -S collector && adduser -S collector -G collector \
    && mkdir -p /var/log/aaa-collector/dump && chown -R collector:collector /var/log/aaa-collector

# 애플리케이션 JAR 복사
WORKDIR /collector
COPY --chown=collector:collector --from=build /collector/build/libs/aaa-collector.jar aaa-collector.jar

USER collector
EXPOSE 8080

# 헬스체크: Spring Actuator /actuator/health (Alpine BusyBox wget 사용)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# JVM 옵션: TECHSPEC 10.3절 기준
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx384m", \
  "-XX:MaxMetaspaceSize=160m", "-XX:MaxDirectMemorySize=64m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/var/log/aaa-collector/dump/", \
  "-XX:ErrorFile=/var/log/aaa-collector/dump/hs_err_pid%p.log", \
  "-Duser.timezone=Asia/Seoul", \
  "-jar", "aaa-collector.jar"]
