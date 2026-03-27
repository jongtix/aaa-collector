# TODO: aaa-collector

> 현재 Phase: **Phase 1**
>
> 전체 마일스톤은 [MILESTONE.md](../../aaa-infra/docs/MILESTONE.md) 참조

---

## Phase 1: aaa-collector (데이터 수집)

### 1-1. 프로젝트 기반 설정
- [x] Spring Boot 3.5.11, Java 21 Virtual Threads, Gradle Kotlin DSL 초기 설정
- [x] KST 시간대 통일 설정
- [x] Spotless / PMD / SpotBugs 코드 품질 도구 설정
- [x] pre-commit / pre-push Git 훅 구성
- [x] 구조화 로그(JSON) + 민감 정보 마스킹 설정
- [x] Trace ID 생성 및 로그 포함
- [x] Spring Boot Actuator `health`만 노출 — Swagger UI 프로덕션 비활성화
- [x] 컨테이너 `healthcheck` 설정 (`/actuator/health`)

### 1-2. KIS API 토큰 관리
- [x] 앱키 5개 독립 토큰 발급 및 Redis 저장 구현
- [x] 스케줄 갱신: 평일(월~금) 08:30 KST `@Scheduled` cron으로 일괄 발급
- [x] Lazy 갱신: `getValidToken()` 메커니즘 구현 완료 (401 트리거 연동은 1-6/1-7에서 완료)
- [x] 갱신 실패 처리: 최대 3회 재시도 → 실패 시 안전 모드 진입 + 로그 기록

### 1-3. CI/CD 파이프라인
- [x] GitHub Actions 워크플로우 작성 — Docker 이미지 빌드
- [x] GHCR(GitHub Container Registry) 이미지 푸시 설정
- [x] semantic-release 설정 — 커밋 기반 SemVer 자동 결정 (`feat` → minor, `fix` → patch, `!` → major)
- [x] Gitmoji + Conventional Commits 혼합 포맷 지원 (`headerPattern` 커스텀)
- [x] `gradle-semantic-release-plugin` 연동 — `gradle.properties` 자동 업데이트
- [x] Docker 이미지 3-태그 동시 push: `:v1.2.3` + `:latest` + `:sha-<commit>`
- [x] Dependabot 설정 — `gradle` + `github-actions` 에코시스템
- [ ] GitHub Push → GHCR 이미지 빌드 → Watchtower 자동 업데이트 흐름 동작 확인

### 1-4. DB 스키마 (Phase 1)
- [ ] KIS API 실제 응답 데이터 확인 후 스키마 설계
- [ ] 종목 마스터 테이블 설계 및 생성: `stocks`, `stock_grades`
- [ ] 가격 데이터 테이블: `daily_ohlcv` (`asset_type` ENUM)
- [ ] 시장 지표 테이블: `market_indicators` (환율, VIX)
- [ ] 해외선물 테이블: `futures_daily`
- [ ] 시간외 데이터 테이블: `extended_hours` (`session` ENUM)
- [ ] 수급 데이터 테이블: `investor_trend`, `short_sale`, `credit_balance`
- [ ] 거시경제 테이블: `macro_indicators` (금리종합, 증시자금종합 포함)
- [ ] 재무제표 테이블: `financials`
- [ ] 뉴스 테이블: `news_headlines`
- [ ] 공시 테이블: `disclosures`
- [ ] 기업 이벤트 테이블: `corporate_events`
- [ ] 투자의견 테이블: `analyst_estimates`
- [ ] 모든 시간 컬럼 `DATETIME` 사용 (`TIMESTAMP` 금지)
- [ ] Unique Key 설정 (종목코드 + 타임스탬프) — 중복 INSERT 방지

### 1-5. 관심 종목 동기화
- [ ] 장 시작 전 KIS API → DB 동기화 구현 — 1일 2회(07:30, 15:45 KST)
- [ ] 동기화 실패 시 직전 DB 목록 유지 + 로그 기록
- [ ] 종목 등급 자동 분류 로직 (A/B/C/F)
- [ ] 중복 ETF 대표 선정 알고리즘 구현
- [ ] Redis 캐싱: `cache:stock:list`, `cache:grade:{종목코드}`

### 1-6. KIS WebSocket 실시간 수집
- [ ] 국내 체결 (`H0STCNT0`), 호가 (`H0STASP0`) 구독
- [ ] 해외 체결, 호가(Level 1), VIX 선물 실시간 구독
- [ ] 5세션 × 41건 = 205건 구독 상한 관리
- [ ] 틱 → Redis Streams (`stream:tick:domestic/overseas`) 발행 (`symbol` 필드 포함, MAXLEN으로 메모리 제어)
- [ ] WebSocket 재연결 로직 + 안전 모드 진입 기준 구현
- [ ] Trace ID Redis Streams 헤더 전파

### 1-7. KIS REST 배치 수집
- [ ] 국내 배치: OHLCV 일봉, 투자자별 매매동향, 공매도, 신용잔고, 재무제표, 업종지수, 금리, 증시자금, 배당/증자, 투자의견, 뉴스 제목
- [ ] 해외 배치: OHLCV, 해외선물, 배당/권리, 뉴스 제목
- [ ] Rate Limit 준수: 초당 20건/계좌 × 5계좌 = 100건
- [ ] `@Scheduled` cron 표현식만 사용 (`fixedDelay` 금지)
- [ ] 일봉 수집 완료 시 `stream:daily:complete` 이벤트 발행 (`market` 필드: `domestic` / `overseas`)
- [ ] 과거 데이터 백필: 일봉 OHLCV, 수급 데이터를 종목별 최대 과거까지 수집
- [ ] `backfill_status` 테이블 관리: (대상, 데이터 테이블) 단위로 백필 상태 추적, 미완료 항목 대상 하루 1회 스케줄 실행
- [ ] 외부 API 응답 검증: null/0 이하/극단값 필터 적용, 검증 실패 건 저장 제외 + 로그 기록

### 1-8. 외부 API 수집
- [ ] 환율 USDKRW 일봉 Fallback 체인: 한국수출입은행 → ECOS → yfinance
- [ ] VIX 일봉 Fallback 체인: CBOE CDN → FRED → yfinance
- [ ] Pre/After-Hours 1분봉: yfinance → Alpaca → Polygon.io (스냅샷 2~3회/일)
- [ ] FINRA Daily Short Volume, FINRA Short Interest 수집
- [ ] DART OpenAPI 공시 폴링 (분당 1000회 제한)
- [ ] 한국은행 ECOS: 기준금리, CPI, GDP, 경상수지
- [ ] FRED API: GDP, CPI, DFF, UNRATE, DGS10, VIXCLS (120 req/min)
- [ ] Fallback 전환 시 Redis 카운터 기록
- [ ] 거시경제/환율/VIX 과거 데이터 백필: 각 API 제공 범위 내 최대 과거까지 수집

### 1-9. 장애 감지 및 시스템 알림
- [ ] 수집 정상 여부 Redis 카운터 추적 (마지막 수집 타임스탬프 또는 분당 수집 건수)
- [ ] 장중 일정 시간 이상 수집 건수 0 → 로그 기록
- [ ] 수집 누락률, 수집 지연 p95 Redis 카운터 기록
- [ ] `stream:system:collector` 장애 이벤트 발행
- [ ] NAS 자원 모니터링 (디스크/RAM/CPU) + 3단계 임계치 알림

### 완료 기준
- [ ] GitHub Push → GHCR 이미지 빌드 → Watchtower 자동 업데이트 흐름 동작 확인 (1-3에서 구현)
- [ ] 수집 누락률 < 1% (장중 기준)
- [ ] 데이터 파이프라인 장애 시 자동 복구 (Fallback 체인 동작 확인)
- [ ] 수집 지연 < 5초 (실시간 체결 기준)
- [ ] 일봉 수집 완료 이벤트가 `stream:daily:complete`에 `market` 필드(`domestic`/`overseas`) 포함하여 정상 발행됨을 확인
- [ ] 일일 리포트 항목 (수집 누락률, 지연 p95, Fallback 내역, 자동 복구 횟수) 데이터 산출 확인
- [ ] 모든 관심 종목의 과거 데이터 백필 완료 (`backfill_status` 전 항목 완료 확인)
- [ ] 관심 종목 일봉 수익률 분포 확인 — TECHSPEC 6.1절 초안 경계값의 클래스 비율이 극단적으로 편향되지 않음을 확인
- [ ] 종목 등급 자동 분류 검증 완료 — A/B/C/F 등급 분류 결과를 TECHSPEC 6.5절 기준과 대조하여 오분류 없음 확인
