---
id: SPEC-COLLECTOR-WLSYNC-003
version: 0.1.0
status: planned
created: 2026-06-06
updated: 2026-06-06
author: taekgeun@ideaware.co.kr
priority: high
issue_number: null
---

# SPEC-COLLECTOR-WLSYNC-003 — KisWatchlistClient @Retryable 비즈니스 오류 분리

## HISTORY

- 2026-06-06: 최초 작성. `feat/1-5-watchlist-sync` 브랜치 코드 리뷰 후속 조치.

## 1. Problem Statement (왜 중요한가)

`KisWatchlistClient`의 `@Retryable` `retryFor`에 `IllegalStateException`이 포함되어 있다. `KisApiResponse.validateRtCd()`(`KisApiResponse.java` 21-27행 확인)는 `rt_cd != "0"`인 모든 KIS 비즈니스 오류(인증 실패, 잘못된 그룹코드 등)에 대해 `IllegalStateException`을 던진다.

문제는 이들이 결정론적(deterministic) 오류라는 점이다. 인증 실패나 잘못된 그룹코드는 재시도해도 동일하게 실패한다. 그럼에도 현재 설정은 이를 3회 재시도한다. 특히 인증 실패가 짧은 간격으로 반복되면 KIS 측에서 계정 차단을 트리거할 위험이 있다.

`KisApiResponseException`이 이미 `com.aaa.collector.kis.token` 패키지에 존재하지만 이는 토큰 발급 전용이며, 일반 KIS 데이터 API 응답 검증에는 사용되지 않는다. 토큰 발급과 데이터 조회는 서로 다른 관심사이므로 재사용하지 않는다.

## 2. Solution Summary (무엇을 바꾸는가)

비즈니스 오류(재시도 무의미)와 일시적 오류(재시도 유효)를 예외 타입으로 분리한다.

1. **신규 예외 도입**: `KisApiResponse.validateRtCd()`가 `IllegalStateException` 대신 `KisApiBusinessException`(신규, `RuntimeException` 상속, non-retryable 의미)을 던지도록 변경한다.
2. **재시도 정밀화**: `KisWatchlistClient`의 `retryFor`에서 `IllegalStateException`을 제거하고, 일시적 오류(네트워크/HTTP 서버 오류 — `RestClientException`)만 재시도하도록 한정한다.
3. **동일 적용**: `KisStockInfoClient`에도 같은 분리 정책을 적용한다.

## 3. Scope

### In Scope
- `KisApiResponse.java` — `validateRtCd()`가 `KisApiBusinessException`을 던지도록 변경
- `KisWatchlistClient.java` — `retryFor`에서 `IllegalStateException` 제거, 일시적 오류만 재시도
- `KisStockInfoClient.java` — 동일 정책 적용
- **신규 파일**: `src/main/java/com/aaa/collector/kis/KisApiBusinessException.java` (`RuntimeException` 상속)

### Out of Scope (What NOT to Build)
- `com.aaa.collector.kis.token.KisApiResponseException` 수정 (토큰 전용, 별개 관심사 — 절대 변경 금지)
- `@Retryable`의 최대 재시도 횟수(3회) 또는 backoff 정책 변경 (기존 동작 유지)
- `WatchlistSyncService`의 sync 흐름/제거 마킹 로직 변경 (SPEC-COLLECTOR-WLSYNC-002 소관)
- 재시도 소진 후 알림/Telegram 전송 기능

## 4. Requirements (EARS)

- **REQ-001 (Event-Driven)**: WHEN KIS API가 `rt_cd != "0"` 응답을 반환하면, THE SYSTEM SHALL `KisApiBusinessException`을 던진다.
- **REQ-002 (Unwanted Behavior)**: IF `KisApiBusinessException`이 발생하면, THEN THE SYSTEM SHALL `@Retryable` 재시도를 수행하지 않는다(`retryFor`에서 제외).
- **REQ-003 (Event-Driven)**: WHEN `RestClientException`(네트워크/HTTP 오류)이 발생하면, THE SYSTEM SHALL `KisWatchlistClient`에서 최대 3회 재시도한다 (기존 동작 유지).
- **REQ-004 (Event-Driven)**: WHEN `KisApiBusinessException`이 발생하면, THE SYSTEM SHALL `WatchlistSyncService`에서 기존 `IllegalStateException`과 동일한 catch 경로로 처리한다.
- **REQ-005 (Ubiquitous)**: THE SYSTEM SHALL NOT modify `KisApiResponseException` in the `kis/token/` package (token-specific, separate concern).
- **REQ-006 (Optional)**: WHERE `KisStockInfoClient`에도 동일한 재시도 분리가 필요한 경우, THE SYSTEM SHALL `KisWatchlistClient`와 동일한 정책을 적용한다.

## 5. Acceptance Criteria

### Given-When-Then

**시나리오 1 — 비즈니스 오류는 재시도 안 함**
- GIVEN KIS API가 인증 실패(`rt_cd != "0"`)를 반환
- WHEN `KisWatchlistClient`가 호출되면
- THEN `KisApiBusinessException`이 발생하고, API 호출은 1회만 수행된다 (재시도 없음).

**시나리오 2 — 일시적 오류는 재시도**
- GIVEN HTTP 호출이 `RestClientException`(서버 오류/네트워크)을 던짐
- WHEN `KisWatchlistClient`가 호출되면
- THEN 최대 3회까지 재시도한다 (기존 동작 유지).

**시나리오 3 — 상위 catch 경로 정합성**
- GIVEN `KisApiBusinessException`이 던져짐
- WHEN `WatchlistSyncService`가 이를 받으면
- THEN 기존 `IllegalStateException` catch와 동일한 경로로 처리되어 sync 흐름에 회귀가 없다.

**시나리오 4 — 토큰 예외 불변**
- GIVEN `kis/token/KisApiResponseException`
- WHEN 본 SPEC을 적용하면
- THEN 해당 파일은 수정되지 않는다.

### Edge Cases
- `validateRtCd()`를 사용하는 다른 KIS 응답 구현체(예: `KisGroupListResponse`, `KisStockListByGroupResponse`, `KisDomesticStockInfoResponse`, `KisOverseasStockInfoResponse`)도 동일하게 `KisApiBusinessException`을 전파한다 — 변경이 default 메서드 한 곳이므로 자동 적용.
- `KisApiBusinessException`은 `rt_cd`, `msg_cd`, `msg1`을 메시지에 포함하여 진단 가능성을 유지한다.

### Quality Gate
- 기존 테스트 `KisWatchlistClientTest`, `KisStockInfoClientTest`가 모두 통과한다.
- 비즈니스 오류 시 재시도하지 않음을 검증하는 테스트가 추가된다 (호출 1회).
- 일시적 오류 시 3회 재시도를 검증하는 테스트가 유지/추가된다.

## 6. Dependencies

- 없음 (독립 SPEC). SPEC-002와 `WatchlistSyncService.java`를 공유하므로(SPEC-002의 `failedGroupCount` 추가 + 본 SPEC의 catch 경로 정합성), 둘을 함께 적용할 경우 SPEC-003을 먼저 적용한 뒤 SPEC-002의 sync 흐름 변경을 얹는 순서를 권장한다.
