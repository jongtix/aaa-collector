# Changelog

이 프로젝트의 주요 변경사항을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.

## [Unreleased]

### Added

- `notification_log` 테이블 Flyway 마이그레이션 신설 (SPEC-NOTIFIER-SCHEMA-001 M1, REQ-NOTIFIER-SCHEMA-001~007/011~012)
  - `src/main/resources/db/migration/V49__notifier_create_notification_log.sql` — aaa-notifier(Phase 3 알림 서비스)의 텔레그램 발송 사건을 이벤트 소싱식(INSERT-ONLY)으로 저장하는 테이블 신설. 1행 = 발송 사건 1건, 갱신형 `send_status` 컬럼 없이 `trace_id`로 같은 알림의 이력을 연결
  - 이 레포 최초의 `notifier_` 접두사 마이그레이션(기존 V1~V48은 전부 `collector_`/`analyzer_` 접두사) — [ADR-016](../../aaa-infra/docs/ADR/ADR-016-flyway-schema-migration.md) 결정 2의 중앙집중 Flyway 소유 구조에 따라 collector가 notifier분 DDL도 단독 소유
  - collector는 이 테이블에 대응하는 JPA 엔티티를 갖지 않음(Hibernate `ddl-auto=validate` 검증 범위 밖) — notifier가 직접 접근하고 collector 도메인 모델과 무관하기 때문
  - 라이브 NAS 배포 확인 완료(2026-09-29): `flyway_schema_history` version=49 success, `DESCRIBE notification_log` 16개 컬럼 전건 스펙 일치

### Changed

- docker.yml에 Trivy CVE 게이트/빌드 실패 시 독립 Telegram 알림 스텝 추가(SPEC-INFRA-CVE-SCAN-004 M1) — deploy.yml의 workflow_run 게이팅과 무관하게 항상 발동, 기존 시스템봇 재사용

### Added

- CI/CD 룰셋 강화 (SPEC-INFRA-CICD-002)
  - `main` 브랜치 룰셋(`main-protection`) 신설 — 선형 히스토리 강제, 강제 푸시/삭제 차단, `test` 상태 체크 필수
  - `release.yml`의 test job에 `pull_request` 트리거 추가 — PR에서 머지 전 실제 CI 검증
  - GitHub App(`aaa-ci-release-bot`)이 `actions/create-github-app-token`으로 보호된 `main`을 우회해 릴리스 태그/커밋을 푸시(룰셋 `bypass_actors`에 유일하게 등재), 사람은 PR 경로만 허용
  - `docker.yml` 트리거를 `workflow_run: ["Release"]`에서 `push: tags: ['v*']`로 변경 — `workflow_run` 3단 체인(GitHub 문서상 깊이 제한)을 2단으로 축소, App이 푸시한 태그로도 안정적으로 빌드 발화. 중복 태그 탐색용 2중 체크아웃 로직 제거
  - `deploy.yml`/`release.yml`에 `concurrency` 그룹 추가 — 배포/릴리스 중복 실행 방지
  - `dependabot-auto-merge.yml` 신규 — non-major Dependabot PR을 CI 통과 후 자동 머지(`dependabot/fetch-metadata` + `gh pr merge --auto --rebase`), `dependabot.yml`에 3일 쿨다운 추가
  - `tag-protection` 룰셋 신설(`refs/tags/v*`) — 릴리스 태그 삭제·재태그 차단
  - 체크아웃 스텝에 `persist-credentials: false` 추가(푸시가 필요 없는 스텝 한정)
  - 릴리스 커밋백(commit-back) 메커니즘 제거 — `.releaserc.js`에서 `@semantic-release/exec`/`@semantic-release/git` 제거, 버전은 Docker 빌드 시점에 `ARG VERSION` → `-Pversion=${VERSION}`로 주입. `gradle.properties`의 정적 버전 필드는 이제 비활성 placeholder(`0.0.0+placeholder`, 코드에서 미참조)
- 🐛 fix(ci): `deploy.yml`의 `workflow_run.head_branch == 'main'` 게이트가 태그 트리거 Docker 실행 시 `head_branch`가 태그명으로 보고되는 것을 놓쳐 M5 적용 후 모든 릴리스에서 Deploy가 조용히 스킵되던 결함 수정 — `startsWith(github.event.workflow_run.head_branch, 'v')` 조건으로 교체. v1.80.2 배포로 라이브 검증 완료

### Changed

- Flyway 마이그레이션 자격증명 노출점 제거 (SPEC-INFRA-DB-BACKUP-001, REQ-MIG-001[HARD])
  - `.github/workflows/deploy.yml`의 `docker exec -e MYSQL_PWD="$FLYWAY_PASS"` 패턴(`docker inspect`로 프로세스 환경변수 노출 가능)을 `--defaults-extra-file=/run/secrets/flyway.cnf`(aaa-infra docker-compose.yml ro 마운트) 방식으로 대체
  - 워크플로 셸에서 더 이상 패스워드를 직접 읽지 않음
