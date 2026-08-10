# Changelog: aaa-collector

모든 주목할 만한 변경 사항을 이 파일에 기록합니다.

---

## [Unreleased] — feature/SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001

### Fixed

- **해외 현금배당 수집 가시성 윈도우 레이스 수정** (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001, AC-ODW-001~003/010/011, 원인①):
  `OverseasRightsCollectionService.fetch()`가 KIS `rights-by-ice`(`HHDFS78330900`)를 `ST_YMD`/`ED_YMD` 공백 파라미터로 조회하면
  종목당 현재/다음 1개 회차만 반환되어, 다음 배당이 공시(`anno_dt`)되는 즉시 아직 미확정인 이전 회차가 응답에서 사라지는 구조적 결함이 있었다
  (공시 간격이 긴 종목 — TSM 120~125일, AMAT 58~71일 — 은 확정 TR `CTRGT011R`이 확정하기 전에 다음 공시로 대체되어 영구 누락).
  `fetch(session, symbol)`을 `fetch(session, symbol, startDate, endDate)`로 확장해 명시적 달력 범위(`오늘±WINDOW_MONTHS±WINDOW_PADDING_DAYS`)로
  조회하도록 수정했다 — `DividendAmountPrefetcher.WINDOW_MONTHS`와 동일 상수를 재사용해 두 TR의 윈도우를 항상 동기화한다.
  `DividendAmountPrefetcher.WINDOW_MONTHS`도 `3`→`4`(개월)로 확장해(TSM류 확정 지연 여유) 안전 마진을 넓혔다.
  정기 수집의 기존 짧은 공시 간격 종목(O·KO·JPM 등) 매칭 로직·저장 행은 회귀 없이 유지된다.

### Added

- **해외 현금배당 종목지정 과거 백필 fetch/persist 신설** (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001, AC-ODW-004~009/012~015, 원인②):
  기존 해외 배당 백필 경로 부재(수집기 가동 시작 2026-04-30 이전 이력·원인①이 이미 발생시킨 과거 누락분 소급 불가)를 해소했다.
  - `OverseasDividendBackfillService`(신규): `rights-by-ice`(날짜 소스, `SYMB=<심볼>` 서브윈도우 청킹)와 `CTRGT011R`(금액 소스, `PDNO=<심볼>` 03/75 단일 광폭 페이징)을 조합해 완전한 `CorporateEvent`를 구성한다.
    - `rights-by-ice` 서브윈도우 청킹(기본 24개월, 인접 청크 경계일 1일 중첩) — 2026-08-07 프로브로 확인된 광폭 단일콜 ~50건 상한 조용한 절단을 회피.
    - `CTRGT011R` 응답을 `pdno == 요청 심볼` 완전 일치로 필터링 — PDNO 접두어 매칭 노이즈(예: `PDNO=V` 조회 시 VRNS/VONG 등) 제외.
    - persist는 Tier-1 `INSERT IGNORE`(`uk_corporate_events` 4컬럼 유니크 키)로만 쓰고 `@Transactional(propagation = MANDATORY)`로 `BackfillWindowExecutor.routePersist` 트랜잭션 경계 밖 호출 시 즉시 실패한다.
    - `rights-by-ice` 청크 또는 `CTRGT011R` 조회가 실패하면 `rawRowCount`를 조작해 폐기하지 않고 `OverseasDividendBackfillPrefetchFailedException`을 던져 `BackfillOrchestrator`가 재시도(`IN_PROGRESS` 유지)하도록 한다.
  - `OverseasDividendBackfillFetch`(신규 record DTO), `OverseasDividendBackfillPrefetchFailedException`(신규 예외).
  - `DividendAmountPrefetcher`: `pdno` 파라미터화 + 신규 `prefetchForBackfill(session, pdno, from, to)` — 기존 정기 수집 `prefetch()` 호출부는 무변경.
  - **배선**: `BackfillGroup.GROUP_C_TABLES`·`BackfillStatusSeeder.OVERSEAS_DATA_TABLES`·`BackfillWindowExecutor.routeFetch`/`routePersist`에 신규 `data_table` 키 `corporate_events_dividend_overseas`를 편입 — 기존 `"corporate_events"`(SPLIT)·`"corporate_events_dividend"`(국내 배당) 경로는 무변경.
  - **테스트**: `OverseasDividendBackfillServiceTest`/`OverseasDividendBackfillTest`(청크 경계·PDNO 필터·실패 전파), `OverseasDividendBackfillIntegrationTest`(Testcontainers MySQL — MANDATORY 트랜잭션 가드 + `INSERT IGNORE` 멱등성 2건: 동일 fetch 재실행 시 행 수 불변, 청크 경계 중복 반환 행이 단일 저장으로 흡수됨), `BackfillGroupTest`/`BackfillStatusSeederTest`/`BackfillWindowExecutorTest` 라우팅·시딩 회귀.

### Fixed

- **코드리뷰 remediation W-1/W-2/W-3** (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001, `/moai review` PASS-WITH-DEBT 후속):
  - **W-1**: `OverseasDividendBackfillService.fetchAllChunks`에 청크 원본 응답 행수 절단 감지 임계(`BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS=40`) 추가 — 임계 이상이면 fail-closed(`OverseasDividendBackfillPrefetchFailedException`).
  - **W-2a**: 백필 시작일(`floor`)을 고정 anchor 대신 `stock.getListedDate()`가 더 최근이면 상장일로 클램프해 불필요한 조회 범위를 줄임.
  - **W-2b**: `OverseasDividendBackfillPrefetchFailedException`에 누적 시도 횟수(`attemptCount`) 기반 재시도 상한(10회, 스케줄 1일 1회 기준 약 10일 여유)을 도입 — 판정 로직은 `BackfillWindowExecutor`(PMD `TooManyMethods` 임계 회피)가 아닌 `BackfillOrchestrator.isRetryableWithinCeiling`(private)에 위치.
  - **W-3**: `OverseasDividendBackfillService`에 청크별 debug 로그·절단 의심 warn·완료 info·실패 warn 로그 추가(`OverseasSplitBackfillService`와 동일 `[overseas-*]` 태그 컨벤션).
  - 부수 리팩터: `BackfillWindowExecutor.isRetryable` 오버로드(2인자)를 원래 1인자 서명으로 원복하고, attemptCount 기반 판정을 `BackfillOrchestrator`로 재배치 — 서명 확장만으로 발생한 PMD `TooManyMethods`(임계 20, `BackfillWindowExecutor`가 이미 임계에 근접) 위반을 신규 suppression 없이 해소.

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

- **SINGLE_DATE 갭 walk OVERSEAS(FINRA Daily) 정밀 판정 접근자 확장** (SPEC-COLLECTOR-BACKFILL-016, AC-SDWALK2-001~008, 8건):
  `CoveredRangeService.walkGapForward()`의 `SINGLE_DATE` 모드 사전 skip 판정(`singleDateWalkDecision()`)이
  OVERSEAS(FINRA Daily) 캘린더 도메인에 한해 여전히 캐시 판정 접근자(`UsMarketOpenGate.isOpenDay()`,
  캐시 범위 밖 날짜를 무조건 개장으로 오판하는 fail-open)를 사용하던 구조적 결함을 정정했다(aaa-infra#138,
  SPEC-COLLECTOR-BACKFILL-015가 DOMESTIC에서 정정한 것과 동일 근본 메커니즘의 OVERSEAS 변형). OVERSEAS는
  주말 판정이 항상 순수 요일 계산이고 휴장일 캐시 범위도 DOMESTIC보다 넓어(`[올해 1월 1일, 내년 12월 31일]`)
  노출 조건이 훨씬 좁지만(연도 경계를 넘는 장기 지연 + 그 해 NYSE 개별 휴장일 적중이 동시에 필요), 동일한
  자기 회복 불가능 고착 체인(오판 개장 → 필러 호출 → 정상 빈 응답 → `kept==0` → 즉시 중단 → 다음 회차 반복)에
  걸릴 수 있는 구조는 동일했다.
  - **수정**: `singleDateWalkDecision()`의 도메인 조건부(OVERSEAS만 캐시 판정, 나머지는 정밀 판정)를 제거하고
    두 도메인 모두 기존 `openDayStrictState()` 헬퍼(→ `UsMarketOpenGate.isOpenDayStrict(LocalDate): Optional<Boolean>`)로
    일원화했다 — `isOpenDayStrict()`는 SPEC-COLLECTOR-CALENDAR-001이 이미 구현·배포했고 `evaluateFrontGap()`이
    이미 사용 중이던 기존 접근자이므로, 신규 캘린더 백엔드·캐시·저장소·인터페이스 메서드는 전혀 추가하지 않는
    순수 배선 변경이다. 조건부 제거로 유일한 호출부를 잃은 캐시 판정 위임 전용 private 헬퍼
    `isOpenDay(CoveredCalendarDomain, LocalDate)`도 함께 제거했다(dead code).
  - OVERSEAS도 DOMESTIC과 동일한 개장/휴장/"모름" 3분기 처리를 적용받는다 — 휴장이면 skip 후 walk 계속 진행,
    개장이면 기존과 동일하게 데이터 저장 단계 진행(회귀 없음), "모름"(`market_calendar` NYSE 행 없음)이면
    개장으로 낙관 해석하지 않고 이번 호출의 walk 진행을 즉시 중단하며(`covered_until_date` 미갱신, 다음
    회차에 동일 커서부터 재시도) `CoveredWalkAnomalyKind.CALENDAR_UNKNOWN` 이상 신호를 발생시킨다.
  - **DOMESTIC(USDKRW)·`RANGE` 모드·`evaluateFrontGap()`·라이브 배치 쿼터 소진 스킵 로직은 완전 무변경**:
    DOMESTIC의 판정 결과·3분기 동작은 SPEC-015가 이미 확정했고 이 SPEC 이후에도 동일하다(회귀 고정). STOCK
    범위형 4종(`daily_ohlcv`·`investor_trend`·`short_sale_domestic`·`credit_balance`)의 `RANGE` 모드 판정
    경로와 앞단 미도달 판정(`evaluateFrontGap()` 계열 — OVERSEAS 분기는 이미 정밀 판정 접근자 사용 중이었음)도
    변경하지 않는다.
  - 관련: aaa-infra#138(이 SPEC이 정정, aaa-infra#134와 동일 근본 메커니즘의 OVERSEAS 변형). 선행:
    SPEC-COLLECTOR-BACKFILL-015(DOMESTIC 정정 완료)·SPEC-COLLECTOR-CALENDAR-001(`isOpenDayStrict()` 양 도메인
    동시 신설)·SPEC-COLLECTOR-BACKFILL-011(정방향 갭 walk·`evaluateFrontGap()` "모름" 패턴 확립). 이 커밋은
    코드 수정 배포만 다룬다 — FINRA Daily(`short_sale_overseas`)의 실제 결손 구간 조사·복구는 이 SPEC 범위 밖이다.

- **SINGLE_DATE 정방향 갭 walk 캘린더 판정 fail-open → "모름" 구분 전환** (SPEC-COLLECTOR-BACKFILL-015, AC-SDWALK-001~008, 8건):
  `CoveredRangeService.walkGapForward()`의 `SINGLE_DATE` 모드(USDKRW·FINRA Daily) 사전 skip 판정이 좁은 캐시
  기반 시장 개장일 게이트(`MarketOpenGate.isOpenDay()`, 매일 `[오늘−14일, 오늘+20일]`만 재조회하는 인메모리
  캐시)를 사용해, 캐시 범위 **밖** 날짜를 실제 개장 여부와 무관하게 무조건 개장으로 오판(fail-open)하던 근본원인을
  정정했다(aaa-infra#134). 정방향 갭 walk 커서가 쿼터 캡 등으로 캐시 하한보다 오래된 과거로 뒤처지면, 오판된
  "개장일"에 대해 데이터 저장 단계가 호출되고 대상 API가 정상적으로 빈 응답(실제 휴장일이므로)을 반환해
  `kept==0`으로 즉시 중단되는데, `covered_until_date`가 전진하지 않아 다음 회차도 동일 커서에서 이 패턴을
  그대로 반복하는 자기 회복 불가능한 영구 고착이 발생했다(DB write 없는 무흔적 장애) — 이 메커니즘이
  `market_indicators` USDKRW의 2026-07-18~ 결손 구간을 유발했다.
  - **수정**: `DOMESTIC`(USDKRW) 캘린더 도메인에 한해, 사전 skip 판정을 이미 존재하던 검증 전용 판정
    접근자(`MarketOpenGate.isOpenDayStrict(LocalDate): Optional<Boolean>`, SPEC-COLLECTOR-CALENDAR-001)로
    전환했다 — 신규 캘린더 백엔드·캐시·저장소는 신설하지 않고 `evaluateFrontGap()`이 이미 사용하던 접근자를
    재사용했다. 판정 결과를 개장/휴장/"모름"(`Optional.empty()`) 3분기로 처리하는
    `singleDateWalkDecision()` 헬퍼를 신설했다: 휴장이면 정상 skip 후 walk 계속 진행(DEBUG 로그만), 개장이면
    기존과 동일하게 데이터 저장 단계 진행(회귀 없음), "모름"이면 그 날짜를 개장으로 낙관 해석하지 않고 이번
    호출의 walk 진행을 즉시 중단하며(`covered_until_date` 미갱신, 다음 회차에 동일 커서부터 재시도) WARN
    로그 + `CoveredWalkAnomalyKind.CALENDAR_UNKNOWN` 이상 신호를 정확히 1회 발생시킨다(`evaluateFrontGap()`과
    동일한 관측 관례).
  - **OVERSEAS(FINRA Daily) 및 `RANGE` 모드는 완전 무변경**: `OVERSEAS` 캘린더 도메인(FINRA Daily)의
    사전 skip 판정은 기존 캐시 판정 접근자(`UsMarketOpenGate.isOpenDay()`, fail-open)를 그대로 유지한다 —
    동일 결함 메커니즘을 공유하지만 노출 조건이 훨씬 좁아(연도 경계를 넘는 장기 지연 + 그 해 NYSE 공휴일 적중이
    동시에 필요) 별도 이슈로 분리했다(aaa-infra#138, 이 SPEC의 범위 밖). STOCK 범위형 4종(`daily_ohlcv`·
    `investor_trend`·`short_sale_domestic`·`credit_balance`)이 사용하는 `RANGE` 모드 판정 경로 및 앞단
    미도달 판정(`evaluateFrontGap()` 계열)·라이브 배치 쿼터 소진 스킵 로직도 변경하지 않는다.
  - 관련: aaa-infra#134 (근본원인, 이 SPEC이 정정). aaa-infra#138 (FINRA Daily 동일 메커니즘, 별도 SPEC 소관).
    이 커밋은 코드 수정 배포만 다룬다 — USDKRW 결손 구간(2026-07-18~)의 실제 자동 복구 관찰 및 수동 SQL
    백필은 이 SPEC 범위 밖이다.

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
