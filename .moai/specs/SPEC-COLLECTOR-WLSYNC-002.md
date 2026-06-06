---
id: SPEC-COLLECTOR-WLSYNC-002
version: 0.2.0
status: planned
created: 2026-06-06
updated: 2026-06-06
author: taekgeun@ideaware.co.kr
priority: high
issue_number: null
---

# SPEC-COLLECTOR-WLSYNC-002 — WatchlistWriter 부분 실패 시 제거 마킹 차단

## HISTORY

- 2026-06-06: 최초 작성. `feat/1-5-watchlist-sync` 브랜치 코드 리뷰 후속 조치.
- 2026-06-06 (v0.2.0): REQ-001(마켓 스코프 한정) 삭제. 한국 symbol(6자리 숫자)과 미국 symbol(알파벳)은 namespace가 설계상 교집합이 없어 symbol 충돌이 불가능하므로, 관련 문제 설명·해결책·범위·시나리오·테스트 요건 전체 제거.

## 1. Problem Statement (왜 중요한가)

그룹 조회가 부분 실패하여 `fetchStocksAsync`가 빈 리스트를 반환하면, 해당 실패 그룹의 종목들은 `touchedIds`에 들어가지 않는다. 현재 로직은 이를 "관심종목에서 제거됨"으로 간주하여 실패 그룹의 종목 전량을 `markWatchlistRemoved`로 마킹한다. 즉 일시적 API 장애가 영구적 데이터 손상(전량 제거 마킹)으로 번진다.

## 2. Solution Summary (무엇을 바꾸는가)

**부분 실패 시 제거 마킹 차단**: 그룹 조회가 1개라도 실패하면(`fetchStocksAsync` 빈 리스트 반환) `markRemoved`를 건너뛰고 다음 sync 주기로 미룬다. 실패 그룹 수를 추적하여 제거 마킹 여부를 결정한다.

## 3. Scope

### In Scope
- `WatchlistWriter.java` — `markRemoved` skip 조건 추가
- `WatchlistSyncService.java` — `collectUniqueStocks`에 `failedGroupCount` 추적 추가, `upsertAll`에 전달

### Out of Scope (What NOT to Build)
- 그룹 조회 재시도 로직 변경 (재시도는 SPEC-COLLECTOR-WLSYNC-003에서 별도 정밀화)
- `markWatchlistRemoved` JPQL 쿼리 자체 변경 (시그니처 유지, 호출 조건만 제어)
- 부분 실패 시 알림/Telegram 전송 기능 (로깅으로만 처리)
- 동시 실행 가드 (SPEC-COLLECTOR-WLSYNC-001 소관)

## 4. Requirements (EARS)

- **REQ-001 (Unwanted Behavior)**: IF 그룹 조회가 1개라도 실패하면(`fetchStocksAsync`가 빈 리스트 반환), THEN THE SYSTEM SHALL `markRemoved`를 skip하고 제거 마킹을 다음 sync 주기로 미룬다.
- **REQ-002 (Event-Driven)**: WHEN 모든 그룹 조회가 성공하면, THE SYSTEM SHALL 기존과 동일하게 `markRemoved`를 실행한다.
- **REQ-003 (Event-Driven)**: WHEN 그룹 실패로 `markRemoved`를 skip하면, THE SYSTEM SHALL `log.warn`으로 실패한 그룹 수와 skip 사실을 기록한다.
- **REQ-004 (Ubiquitous)**: THE SYSTEM SHALL add `failedGroupCount` tracking to `WatchlistSyncService.collectUniqueStocks`.
- **REQ-005 (Ubiquitous)**: THE SYSTEM SHALL pass `failedGroupCount` to `WatchlistWriter.upsertAll` so the skip decision can be made.

## 5. Acceptance Criteria

### Given-When-Then

**시나리오 1 — 부분 실패 시 제거 마킹 skip**
- GIVEN 그룹이 3개이고 그 중 1개의 `fetchStocksAsync`가 빈 리스트를 반환 (`failedGroupCount = 1`)
- WHEN `upsertAll`이 호출되면
- THEN `markRemoved`가 호출되지 않고, `log.warn`에 실패 그룹 수(1)와 skip 사실이 기록된다.

**시나리오 2 — 전체 성공 시 정상 제거 마킹**
- GIVEN 모든 그룹 조회 성공 (`failedGroupCount = 0`)
- WHEN `upsertAll`이 호출되면
- THEN `touchedIds`에 없는 기존 종목에 대해 기존과 동일하게 `markRemoved`가 실행된다.

### Edge Cases
- 모든 그룹이 실패한 경우(`failedGroupCount == 그룹 수`)에도 `markRemoved`는 skip된다 (전량 제거 방지).

### Quality Gate
- 기존 테스트 `WatchlistWriterTest`, `WatchlistSyncServiceTest`가 모두 통과한다.
- 부분 실패 시 `markRemoved` skip을 검증하는 신규 테스트가 추가된다 (Mockito `verify(never())`).

## 6. Dependencies

- 없음 (독립 SPEC). SPEC-001, SPEC-003, SPEC-004와 변경 파일이 겹치지 않는다. 단, SPEC-003과 동일 sync 흐름을 다루므로 둘 다 적용 시 `WatchlistSyncService`의 catch 경로 정합성을 함께 검증하는 것을 권장한다.
