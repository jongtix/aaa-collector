---
id: SPEC-COLLECTOR-WLSYNC-001
version: 0.1.0
status: planned
created: 2026-06-06
updated: 2026-06-06
author: taekgeun@ideaware.co.kr
priority: high
issue_number: null
---

# SPEC-COLLECTOR-WLSYNC-001 — WatchlistSyncScheduler 동시 실행 가드 추가

## HISTORY

- 2026-06-06: 최초 작성. `feat/1-5-watchlist-sync` 브랜치 코드 리뷰 후속 조치.

## 1. Problem Statement (왜 중요한가)

`aaa-collector`는 `spring.threads.virtual.enabled: true`로 Virtual Threads를 활성화한다. 이 설정에서 Spring Boot는 `@Scheduled` 실행기를 `ThreadPoolTaskScheduler`가 아닌 `SimpleAsyncTaskScheduler`로 전환한다. `SimpleAsyncTaskScheduler`는 동시 실행 개수에 상한이 없다.

결과적으로 이전 `WatchlistSyncService.sync()`가 완료되기 전에 다음 트리거(`syncMorning` 또는 `syncAfternoon`)가 발생하면 두 sync가 동시에 실행된다. `WatchlistEntryWriter.upsertOne`은 종목별 독립 트랜잭션이므로, 동시 실행 시 동일 종목에 대해 중복 INSERT를 시도할 수 있다.

`stocks` 테이블의 `uk_stocks_symbol_market` 유니크 제약(`Stock.java` 28-30행 확인)이 DB 레벨에서 충돌을 막지만, 충돌한 종목은 `failedKeys`로 분류되어 해당 주기의 갱신이 누락된다. 즉 제약이 데이터 무결성은 보호하지만, 동시 실행 자체가 정상 종목의 갱신 누락을 유발한다.

## 2. Solution Summary (무엇을 바꾸는가)

`WatchlistSyncScheduler`에 in-process 진입 가드를 추가한다. `AtomicBoolean` 한 개로 "현재 sync 실행 중" 상태를 표현하고, 이미 실행 중이면 새 트리거는 즉시 반환한다.

프로덕션은 단일 인스턴스 NAS(UGREEN DXP2800) 배포이므로 ShedLock 등 분산 락은 불필요하다. in-process 가드로 충분하다.

## 3. Scope

### In Scope
- `WatchlistSyncScheduler.java` — 진입 가드 추가

### Out of Scope (What NOT to Build)
- ShedLock, Redis 분산 락 등 외부 잠금 메커니즘 (단일 인스턴스 배포이므로 불필요)
- 기존 cron 스케줄(07:30 KST, 15:45 KST) 변경
- `WatchlistSyncService.sync()` 내부 트랜잭션 구조 변경
- `WatchlistEntryWriter.upsertOne`의 종목별 독립 트랜잭션 모델 변경
- 예외 흡수 후 다음 스케줄 보장하는 기존 `catch (Exception)` 정책 변경

## 4. Requirements (EARS)

- **REQ-001 (Event-Driven)**: WHEN `sync()`가 실행 중인 상태에서 `syncMorning` 또는 `syncAfternoon` 트리거가 발생하면, THE SYSTEM SHALL 새 실행을 즉시 반환하고 `log.warn("이전 sync 실행 중 — 중복 실행 스킵")`을 기록한다.
- **REQ-002 (Event-Driven)**: WHEN 진행 중이던 `sync()`가 완료되면, THE SYSTEM SHALL 다음 트리거를 정상 실행한다.
- **REQ-003 (Unwanted Behavior)**: IF `sync()` 실행 중 예외가 발생하면, THEN THE SYSTEM SHALL `finally` 블록에서 진입 가드 상태를 해제(`false`)하여 다음 실행이 가능하도록 보장한다.
- **REQ-004 (Ubiquitous)**: THE SYSTEM SHALL NOT use ShedLock or any external/distributed locking mechanism.
- **REQ-005 (Ubiquitous)**: THE SYSTEM SHALL maintain the existing cron schedule (`0 30 7 * * *` and `0 45 15 * * *`, `zone = "Asia/Seoul"`).

## 5. Acceptance Criteria

### Given-When-Then

**시나리오 1 — 동시 트리거 스킵**
- GIVEN `sync()`가 실행 중 (진입 가드 = true)
- WHEN 또 다른 `syncMorning`/`syncAfternoon` 트리거가 호출되면
- THEN 새 호출은 `WatchlistSyncService.sync()`를 호출하지 않고 즉시 반환하며, `log.warn("이전 sync 실행 중 — 중복 실행 스킵")`이 1회 기록된다.

**시나리오 2 — 완료 후 정상 재실행**
- GIVEN 이전 `sync()`가 정상 완료되어 진입 가드가 해제됨
- WHEN 다음 트리거가 발생하면
- THEN `WatchlistSyncService.sync()`가 정상 호출된다.

**시나리오 3 — 예외 발생 후 가드 복원**
- GIVEN `sync()`가 실행 중
- WHEN `sync()`가 예외를 던지면
- THEN 예외는 기존 정책대로 흡수/로깅되고, 진입 가드는 `false`로 복원되어 다음 트리거가 정상 실행된다.

### Edge Cases
- `syncMorning` 실행 중 `syncAfternoon` 트리거가 발생하는 교차 케이스도 동일 가드로 차단된다 (가드는 메서드별이 아니라 sync 단위로 공유).
- 가드 해제는 반드시 `finally`에서 수행하여 예외 경로에서도 누수가 없어야 한다.

### Quality Gate
- 단위 테스트로 "실행 중 재진입 시 `WatchlistSyncService.sync()`가 추가 호출되지 않음"을 Mockito `verify(times(1))`로 검증한다.
- 예외 발생 후 재진입이 가능함을 검증하는 테스트가 존재한다.

## 6. Dependencies

- 없음 (독립 SPEC). 변경 파일이 다른 SPEC과 겹치지 않는다.
