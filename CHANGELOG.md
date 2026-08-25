# Changelog

이 프로젝트의 주요 변경사항을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.

## [Unreleased]

### Changed

- Flyway 마이그레이션 자격증명 노출점 제거 (SPEC-INFRA-DB-BACKUP-001, REQ-MIG-001[HARD])
  - `.github/workflows/deploy.yml`의 `docker exec -e MYSQL_PWD="$FLYWAY_PASS"` 패턴(`docker inspect`로 프로세스 환경변수 노출 가능)을 `--defaults-extra-file=/run/secrets/flyway.cnf`(aaa-infra docker-compose.yml ro 마운트) 방식으로 대체
  - 워크플로 셸에서 더 이상 패스워드를 직접 읽지 않음
