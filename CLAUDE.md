# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Service Overview

한국/미국 주식 시장 데이터 수집 서비스. KIS Open API를 통해 실시간(WebSocket) 및 배치(REST) 데이터를 수집하여 MySQL + Redis에 저장한다.

## Tech Stack

- Java 21, Virtual Threads
- Spring Boot (버전: `gradle/libs.versions.toml` 참조)
- Spring Data JPA (MySQL 8.4 LTS)
- Spring Data Redis (Redis 8.6)
- Gradle 8.14.4

## Build & Run

```bash
./gradlew build
./gradlew bootRun
```

## Key Conventions

- 스케줄링: `@Scheduled` cron만 사용 (`fixedDelay` 금지 — Virtual Threads 버그)
- 시간대: KST 통일
- 프로파일 설정: 민감 값은 환경변수(`${VAR}`)로 주입, YAML에 하드코딩 금지
- 패키지 루트: `com.aaa.collector`
- 버전 관리: 의존성 버전은 `gradle/libs.versions.toml`을 단일 소스로 관리. README에 버전 중복 기재 금지 (stale 문서 방지)

## JPA Entity Conventions

- `@Column(name=...)` 명시 — naming strategy 의존 제거, Flyway SQL과 매핑 명확화
- `nullable` 미사용 — NOT NULL 제약은 Flyway DDL이 단독 관리 (`ddl-auto=validate`에서 미검증)
- `precision`, `scale`, `length` 명시 — `ddl-auto=validate` 검증 대상
- `BaseEntity`의 `createdAt`/`updatedAt`은 `nullable=false`, `updatable=false` 유지 — JPA Auditing 동작에 필요
- `DEFAULT` 값은 Flyway DDL이 단독 관리 — 엔티티에서 `columnDefinition`으로 중복 선언하지 않음

## Project Documents

- 프로젝트 전체 문서: `aaa-infra/docs/` — 상위 `aaa/CLAUDE.md` 참고
- 서비스 특화 문서: `docs/`
