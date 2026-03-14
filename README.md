# aaa-collector

AAA (Algorithmic Alpha Advisor) 프로젝트의 데이터 수집 서비스.

한국(KOSPI/KOSDAQ)·미국(NYSE/NASDAQ) 주식 시장 데이터를 KIS Open API 및 외부 API를 통해 실시간(WebSocket) 및 배치(REST)로 수집하여 MySQL + Redis에 저장한다.

## 기술 스택

> 버전 정보는 `build.gradle.kts` 참조. README에 중복 기재하지 않음.

| 구성 요소 | 비고 |
|-----------|------|
| Java 21 | Virtual Threads 활성화 |
| Spring Boot | Web |
| Gradle | Kotlin DSL |

## 전제조건

- Java 21+

## Quick Start

```bash
# Git hook 설치 (최초 1회)
./gradlew installGitHooks

# 빌드
./gradlew build

# 실행
./gradlew bootRun
```

## 코드 품질 도구

| 도구 | 용도 | 실행 방법 |
|------|------|-----------|
| Spotless | 코드 포맷 자동화 (Google Java Format AOSP) | `./gradlew spotlessApply` |
| SpotBugs | 버그 패턴 탐지 + FindSecBugs | `./gradlew spotbugsMain` |
| PMD | 코드 품질 검사 | `./gradlew pmdMain` |
| pre-commit / pre-push hook | `.git/hooks`에 설치 (최초 1회 수동 실행) | `./gradlew installGitHooks` |

## 디렉토리 구조

```
aaa-collector/
├── config/                 — 정적 분석 도구 설정 (PMD, SpotBugs)
├── docs/                   — 서비스 로컬 문서 (TODO)
├── scripts/                — Git hook 스크립트
├── src/main/               — 애플리케이션 소스 및 설정
├── src/test/               — 단위 및 아키텍처 테스트
├── build.gradle.kts
└── README.md
```

## 관련 문서

- [PRD](../aaa-infra/docs/PRD.md) — 제품 요구사항
- [MILESTONE](../aaa-infra/docs/MILESTONE.md) — Phase별 마일스톤
- [TODO](docs/TODO.md) — 현재 작업 진행 상태
