# Changelog: aaa-collector

모든 주목할 만한 변경 사항을 이 파일에 기록합니다.

---

## [Unreleased] — feature/SPEC-ETF-001

### Added

- **ETF 대표 선정 알고리즘** (SPEC-ETF-001): `stock/etf/` 서브패키지 신규
  - `EtfMetadata`, `EtfRepresentativeHistory` JPA 엔티티 + Flyway V19/V20 마이그레이션
  - `EtfRepresentativeService.recalculate()`: group_key(거래소:기초지수:배수:방향:환헤지) 기준 그룹화 → ADTV(20거래일) → 상장일 ASC → symbol ASC tie-breaker로 대표 선정
  - `EtfRepresentativeScheduler`: 매주 월요일 07:50 KST (`@Scheduled(cron = "0 50 7 * * MON")`) + AtomicBoolean 중복 실행 가드
  - `GradeCacheRepository`: `cache:grade:{symbol}` Redis 갱신 (non-fatal, REQ-ETFCACHE-002)
  - `StockGradeRepository`: native `INSERT ... ON DUPLICATE KEY UPDATE` delta upsert
  - `DailyOhlcvRepository`: ADTV 집계 쿼리 (`findAdtvByStockIds`)
- **ETF 메타데이터 수집** (REQ-ETFMETA-002): `WatchlistSyncService.resolveOne()` 통합
  - `KisDomesticStockInfoResponse`, `KisOverseasStockInfoResponse`: ETF 관련 필드 추가 (`etf_chas_erng_rt_dbnb`, `etf_type_cd`, `tr_stop_yn` 등)
  - `EtfMetaInfo` DTO: leverage/inverse/hedged/tr_stop 파생 (미문서화 KIS 필드 기반 — `@MX:WARN`)
  - `EtfMetadataWriter`: `REQUIRED` 트랜잭션 모드 (부모 트랜잭션과 동일 커넥션 공유로 자기 잠금 해소)

### Changed

- `StockInfo`: `etfMetaInfo` 필드 추가 (non-ETF 종목은 null)
- `WatchlistEntryWriter`: ETF 종목 처리 시 `EtfMetadataWriter` 위임 + `tr_stop` 갱신

### Technical Notes

- `EtfMetaInfo` 패키지: `watchlist` 대신 `stock.etf` (ArchUnit 순환 의존성 방지)
- `StockGradeRepository` / `DailyOhlcvRepository`: plan.md 오류 정정 (MODIFY → NEW 신규 생성)
- `etf_trgt_nmix_bstp_code` 미문서화 KIS 필드: `underlying_index_code` NULL 허용, 부재 시 symbol fallback

## [Unreleased]

### Added

- **`short_sale_domestic.acml_vol`(누적 거래량) 원본 보존 컬럼 추가** (SPEC-COLLECTOR-SHORTSALE-ACMLVOL-001, AC-1~AC-6, 6건):
  KIS `daily-short-sale`(FHPST04830000) 응답의 `acml_vol`(누적 거래량, 수정주가 기준) 원본 값을
  변환·클램핑 없이 저장하는 신규 nullable 컬럼을 추가했다. `short_sell_vol_rate`(`ssts_vol_rlim`)가
  원주 기준 `ssts_cntg_qty`를 수정주가 기준 `acml_vol`로 나누는 KIS API 필드 단위 불일치로 100%를
  초과하는 왜곡(aaa-infra#61, 659행, 최대 472.51%)이 발생하는데, 이 컬럼은 그 진단·정정에 필요한
  분모를 확보할 뿐이다 — 이 SPEC은 `ssts_vol_rlim` 값 자체를 정정하지 않는다(out of scope).
  - **스키마**: Flyway `V42__collector_add_acml_vol_to_short_sale_domestic.sql` — `ADD COLUMN acml_vol BIGINT NULL`(DEFAULT 없음). 전방향 전용(forward-only) — 배포 이전 행은 `acml_vol = NULL`로 유지, 과거분 백필 없음.
  - **엔티티/DTO/매퍼/인서터**: `ShortSaleDomestic.acmlVol`(boxed `Long`, nullable) 필드, `KisShortSaleResponse.ShortSaleRow.acmlVol` 레코드 컴포넌트, `ShortSaleRowMapper.toEntity` 파싱 배선, `ShortSaleInserter` `INSERT IGNORE` 컬럼 목록 확장.
  - **가용성 회귀 방지**(post-sync-audit 수정): `acml_vol`이 결측(키 없음 또는 blank)인 경우 행 전체를 폐기하지 않고 `acmlVol = null`로 보존한다. 값이 존재하는데 파싱 실패하는 진짜 훼손 데이터만 기존과 동일하게 행 전체 제외 + WARN 로그(REQ-SSAV-005, 특례 없음).
  - 관련: aaa-infra#61

- **SI(반월 공매도 잔고) 이력 수집 상시화 + Interest 경로 티커재사용 게이트** (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003, AC-01~AC-15 + AC-07a/AC-11a, 총 17건):
  기존 40일 슬라이딩 윈도우 방식이던 FINRA `consolidatedShortInterest` 폴링을 전 보존 구간(2017-12-29~) 상시 백필로 전환하고, Interest 경로에 없던 티커재사용 방어 게이트를 신설했다.
  - **M1 — Interest 경로 상장일 게이트 신설** (`ShortSaleOverseasInterestCollectionService`): `isGatedOut` 판정 로직 추가 — 종목의 `listedDate` 이전 정산일 데이터를 제외해 FINRA 티커 재사용 오염(예: SERV, SPCX 사례)을 방지. `BatchMetrics.recordInterestGateSkips(long)` 신규 메트릭.
  - **M2 — 스키마 확장** (Flyway `V41__collector_add_short_interest_metrics_to_short_sale_overseas.sql`): `short_sale_overseas`에 `days_to_cover`(DECIMAL(10,2))·`avg_daily_volume`(BIGINT) nullable 컬럼 추가. `ShortSaleOverseas` 엔티티/리포지토리/DTO/파서(`FinraQuantityParser`) 확장.
  - **M3 — 전 보존 구간 폴링 전환** (`FinraShortSaleClient`, `ShortSaleOverseasInterestCollectionService`): 40일 슬라이딩 윈도우 → 보존 하한 상수(`INTEREST_RETENTION_FLOOR = 2017-12-29`) 기준 상시 폴링. M2의 `daysToCoverQuantity`/`averageDailyVolumeQuantity` 프로덕션 와이어링 완료.
  - **M4 — 진단 로깅**: 게이트 제외 행의 `issueName`을 로그 라인에 추가(DB 미저장)해 SI 오염 트리아지 1차 신호 제공.
  - **M5 — 구현 시점 검증** (코드 변경 없음): 워치리스트 81심볼 단일 `domainFilters` 콜 청크 분할 불필요 확인, SPCX 티커재사용 게이트 자동 처리 확인. 실측 결과는 `api-specs/finra/01-공매도잔고.md` 기록.
  - **M6 — 운영 LOCF retro-UPDATE** (코드 변경 없음, DB 운영 작업): 신규 컬럼 배포 이전 이력 약 127,877행에 대해 range-join 방식 LOCF 백필 실행. 실행 절차·검증 결과는 `runbook-locf-retro-update.md` 기록.
  - 사전조사 문서 기반 착수(명시적 aaa-infra 이슈 번호 없음): `.moai/reports/pre-spec/shortsale-overseas-si-backfill.md`

### Fixed

- **ECOS M/Q 날짜 포맷 및 ECOS/FRED 오류 분류·백필 오귀속 결함 수정** (SPEC-COLLECTOR-ECOS-DATEFMT-001, AC-1~AC-8):
  `ECOS_CPI`·`ECOS_GDP_QOQ`·`ECOS_CURRENT_ACCOUNT` 3종이 도입 이래 전기간 0행이던 결함(aaa-infra#130)을
  4중 결함 체인 전체에서 수정했다.
  - **M1 — ECOS 오류 응답 분류**: `EcosStatisticSearchResponse`가 최상위 `RESULT`(`CODE`/`MESSAGE`)를 더 이상
    `ignoreUnknown`으로 폐기하지 않는다. `RESULT.CODE`가 `ERROR-`로 시작하면 `EcosApiException`으로 예외
    승격해 시리즈 단위 격리·실패 집계 경로를 태우고(기존에는 `INFO-200`과 동일하게 정상 0건으로 오분류됨),
    `INFO-200`은 기존대로 0건 skip 유지. 응답 본문 `null` 또는 `RESULT`/`StatisticSearch` 부재 응답은 실패
    처리. 실패 시리즈는 `attempted`에 계상되어 `macro-external` 배치 fail 메트릭에 드러난다.
  - **M2 — ECOS 주기별 날짜 포맷**: `buildUrl()`이 전 주기 `BASIC_ISO_DATE`(8자리) 고정이던 것을 D=8자리,
    M=6자리(`YYYYMM`), Q=`YYYYQN`으로 분기(당일 수집·백필 양 경로 공통). 백필 시작일도 전 주기 공통
    `19000101` 대신 D=`19000101`/M=`190001`/Q=`1900Q1` 주기별 리터럴 사용. 미지원 주기 코드는 무음 폴백
    대신 예외. 날짜 포맷 계산 로직을 `EcosPeriodDateFormatter`로 분리.
  - **M3 — ECOS 백필 오귀속 해소**: `EcosCollectionService.collectAllForIndicator()` 신규 진입점으로
    백필 오케스트레이터의 ECOS 분기가 8개 시리즈 일괄 `collectAll()` 대신 해당 `indicator_code` 1개만
    수집하도록 전환. D주기 5종 성공분이 M/Q 3종 성공으로 오귀속되던 문제와 code당 8배 호출 낭비 해소.
  - **M4 — FRED 실패 인식·집계 정합성**: `FredCollectionService.collectSeries()`의 `response == null` 케이스를
    정상 0건 흡수에서 `FredApiException` 실패로 전환. `attempted` 계상으로 `macro-external` fail 메트릭에
    반영. 예외 메시지에 `apiKey` 미노출. 기존 5개 시리즈 수집·`value="."` skip·`DFF` 주말 저장·`BigDecimal`
    변환 동작 회귀 없음.
  - **M5 — FRED 백필 오귀속 해소**: `FredCollectionService.collectAllForIndicator()` 신규 진입점으로 백필
    오케스트레이터의 FRED 분기가 5개 시리즈 일괄 `collectAll()` 대신 해당 code 1개만 수집하도록 전환.
    succeeded 오귀속·code당 5배 호출 낭비 해소 — 오케스트레이터가 ECOS·FRED 양쪽 모두 시리즈 단위 처리로
    대칭 회복.
  - 관련: aaa-infra#130
