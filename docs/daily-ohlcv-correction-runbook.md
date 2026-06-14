# 일봉 OHLCV 정정·재적재 런북

**대상 로그**: `[domestic-daily] 불일치 탐지 — symbol=..., date=...`

## 배경

aaa-collector는 KIS `FHKST03010100` TR에서 **원주가(`FID_ORG_ADJ_PRC=1`)** 로 수집한다.
원주가는 결정론적이므로 정상 재조회는 항상 저장값과 일치한다. 저장값 ≠ 재조회값(WARN)은
데이터 오류 신호다. aaa-collector는 **행을 수정·삭제하지 않는다** — DB 계정에 UPDATE/DELETE
권한이 없고(SELECT·INSERT 전용, 멱등 `INSERT IGNORE`), 정정은 항상 out-of-band 권한 경로로만 수행한다(ADR-025·ADR-026).

## 1단계 — 상황 파악

```
# 최근 24시간 불일치 WARN 확인
docker compose -f aaa-infra/docker-compose.yml logs --since 24h aaa-collector \
  | grep "불일치 탐지"
```

출력 예시:
```
[domestic-daily] 불일치 탐지 — symbol=005930, date=2026-05-20,
  stored=[open=.., high=.., low=.., close=75000, volume=.., tradingValue=..],
  refetched=[open=.., high=.., low=.., close=74500, volume=.., tradingValue=..]
```

## 2단계 — 원인 판단

| 상황 | 원인 |
|------|------|
| 1~2개 종목, 과거 날짜 | 수정주가 잔재(재적재 전) 또는 데이터 오류 — KIS 재조회로 정확값 확인 |
| 다수 종목, 오늘 날짜 | KIS 장중 데이터 오류 — 장 마감 후 재확인 |
| 지속 반복 | KIS API 이상 — KIS 개발자 포털 점검 후 문의 |

## 3단계 — 정정 (out-of-band)

**실행 주체**: aaa-collector 서비스 계정으로는 불가(UPDATE/DELETE 권한 없음).
정정은 **`flyway` 계정(DELETE/DDL 보유) 또는 관리자/DBA 계정**으로 통제·감사되는 out-of-band 경로에서만 실행한다.

정정 방식은 **UPDATE가 아니라 "행 삭제 후 원주가 재수집"** 이다(원주가는 결정론적이므로 재수집이 곧 정정이며,
프로젝트 권한 경계상 UPDATE 경로를 도입하지 않는다).

```sql
-- (1) 대상 행 확인 (collector 계정으로도 SELECT 가능)
SELECT trade_date, open_price, high_price, low_price, close_price, volume, trading_value
FROM daily_ohlcv
WHERE stock_id = (SELECT id FROM stock WHERE symbol = '005930')
  AND trade_date = '2026-05-20';

-- (2) 행 삭제 — flyway/admin 계정으로 실행 (collector 불가)
DELETE FROM daily_ohlcv
WHERE stock_id = (SELECT id FROM stock WHERE symbol = '005930')
  AND trade_date = '2026-05-20';
```

**(3) 원주가 재수집**:
- `trade_date`가 **최근 14일 이내**(`DomesticDailyOhlcvCollectionService`의 수집 윈도우)면, 다음 스케줄 cron 실행 시 원주가로 자동 재삽입된다(멱등 `INSERT IGNORE`).
- `trade_date`가 **14일보다 과거**면 정규 스케줄러가 재수집하지 못한다(전구간 백필 트리거 미구현). 데이터가 소량인 현 단계에서는 정확값을 직접 INSERT하거나 후속 백필 과제로 처리한다.

> 자동 정정 도구는 의도적으로 구현하지 않는다. 정정은 위 통제된 수동 절차로만 수행한다.

## 4단계 — 수정주가→원주가 일회성 재적재

수정주가(`FID_ORG_ADJ_PRC=0`)로 적재된 기존 행과 원주가 신규 행의 혼재를 해소하는 일회성 작업.

- **범위**: 현재 prod 데이터가 소량이므로 전체 대상.
- **방식**: `flyway`/admin 계정으로 기존 행 DELETE → 정규 수집이 원주가로 재적재(3단계와 동일하게 14일 윈도우 의존). **반드시 DELETE 먼저** — 그렇지 않으면 `INSERT IGNORE`가 기존(수정주가) 행을 무시해 혼재가 잔존한다.
- **권한**: collector 서비스 계정에 UPDATE/DELETE를 신규 부여하지 않는다(고정 제약).

## 5단계 — 확인

정정·재적재 후 다음 수집 주기에 해당 종목·날짜의 불일치 WARN이 재출력되지 않으면 완료.
