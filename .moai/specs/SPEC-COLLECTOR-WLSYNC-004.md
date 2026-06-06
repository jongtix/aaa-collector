---
id: SPEC-COLLECTOR-WLSYNC-004
version: 0.1.0
status: planned
created: 2026-06-06
updated: 2026-06-06
author: taekgeun@ideaware.co.kr
priority: medium
issue_number: null
---

# SPEC-COLLECTOR-WLSYNC-004 — Stock.updateNames 메서드명 책임 불일치 수정

## HISTORY

- 2026-06-06: 최초 작성. `feat/1-5-watchlist-sync` 브랜치 코드 리뷰 후속 조치.

## 1. Problem Statement (왜 중요한가)

`Stock.updateNames(String nameKo, String nameEn)`(`Stock.java` 91-112행 확인)는 이름만 갱신하지 않는다. 실제 동작은:

- `nameKo`/`nameEn` 갱신
- `active = true`로 재활성화 (102-105행)
- `watchlistRemovedAt = null`로 제거 시각 초기화 (106-109행)

메서드명 `updateNames`는 이 중 첫 번째 책임만 드러낸다. 관심종목 동기화 시 "이름 갱신 + 재활성화"라는 복합 의도가 메서드명에 가려져, 호출 측(`WatchlistEntryWriter`)에서 코드를 읽을 때 재활성화 부수효과를 인지하기 어렵다. 이는 가독성/유지보수성 문제이며, 향후 호출자가 의도치 않게 재활성화를 트리거할 위험을 만든다.

## 2. Solution Summary (무엇을 바꾸는가)

메서드명을 책임을 정확히 반영하도록 `syncFromWatchlist(String nameKo, String nameEn)`으로 변경한다. 시그니처(파라미터)와 반환 타입(`boolean`)은 동일하게 유지하며, 메서드 본문 동작도 그대로 유지한다. 순수 리네이밍 + 호출부/테스트 갱신이다.

## 3. Scope

### In Scope
- `Stock.java` — `updateNames` → `syncFromWatchlist` 리네이밍
- `WatchlistEntryWriter.java` — 호출부(`updateIfNeeded()` 내부)를 `syncFromWatchlist` 호출로 변경
- `WatchlistWriterTest.java` — 관련 테스트명/호출부 갱신
- `WatchlistSyncServiceTest.java` — 관련 테스트명/호출부 갱신

### Out of Scope (What NOT to Build)
- 메서드 시그니처 또는 반환 타입 변경 (동일 유지)
- 메서드 본문의 동작 변경 (이름 갱신 + active=true + watchlistRemovedAt=null 그대로)
- 재활성화 책임을 별도 메서드로 분리하는 추가 리팩터링 (이번 SPEC 범위 밖, 향후 별도 SPEC)
- `Stock` 엔티티의 다른 메서드/필드 변경

## 4. Requirements (EARS)

- **REQ-001 (Event-Driven)**: WHEN `Stock.syncFromWatchlist(nameKo, nameEn)`가 호출되면, THE SYSTEM SHALL 기존 `updateNames`와 동일한 동작(이름 갱신 + `active = true` + `watchlistRemovedAt = null`)을 수행하고 변경 여부를 `boolean`으로 반환한다.
- **REQ-002 (Unwanted Behavior)**: IF 본 변경 적용 후, THEN THE SYSTEM SHALL NOT expose a method named `updateNames` on the `Stock` entity.
- **REQ-003 (Ubiquitous)**: THE SYSTEM SHALL NOT change the method signature or return type.
- **REQ-004 (Event-Driven)**: WHEN `WatchlistEntryWriter.updateIfNeeded()`가 종목 정보를 갱신하면, THE SYSTEM SHALL `syncFromWatchlist`를 호출한다.

## 5. Acceptance Criteria

### Given-When-Then

**시나리오 1 — 리네이밍 후 동작 동일**
- GIVEN 제거 마킹된(`active = false`, `watchlistRemovedAt != null`) `Stock` 인스턴스
- WHEN `syncFromWatchlist(nameKo, nameEn)`가 호출되면
- THEN 이름이 갱신되고 `active = true`, `watchlistRemovedAt = null`로 바뀌며, 하나 이상 변경 시 `true`를 반환한다 (기존 `updateNames`와 동일).

**시나리오 2 — 변경 없음 케이스**
- GIVEN 이미 동일 이름이고 `active = true`, `watchlistRemovedAt = null`인 `Stock`
- WHEN `syncFromWatchlist`가 동일 값으로 호출되면
- THEN 아무 필드도 변경되지 않고 `false`를 반환한다.

**시나리오 3 — updateNames 부재**
- GIVEN 변경 적용된 코드베이스
- WHEN `Stock` 엔티티에서 `updateNames` 메서드를 참조하면
- THEN 컴파일 오류가 발생한다 (메서드 부재).

### Edge Cases
- 호출부가 `WatchlistEntryWriter` 외에 더 있는지 확인하여 누락 없이 전부 갱신한다 (Grep으로 `updateNames` 잔존 참조 0건 확인).

### Quality Gate
- 모든 기존 테스트가 메서드명 변경 후 통과한다.
- 코드베이스 전체에서 `updateNames` 잔존 참조가 0건이다 (테스트 코드 포함).

## 6. Dependencies

- 없음 (완전 독립 SPEC). SPEC-001/002/003과 변경 파일이 겹치지 않으며 순서 제약도 없다.
