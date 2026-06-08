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
  - `EtfMetadataWriter`: `REQUIRES_NEW` 독립 트랜잭션 upsert

### Changed

- `StockInfo`: `etfMetaInfo` 필드 추가 (non-ETF 종목은 null)
- `WatchlistEntryWriter`: ETF 종목 처리 시 `EtfMetadataWriter` 위임 + `tr_stop` 갱신

### Technical Notes

- `EtfMetaInfo` 패키지: `watchlist` 대신 `stock.etf` (ArchUnit 순환 의존성 방지)
- `StockGradeRepository` / `DailyOhlcvRepository`: plan.md 오류 정정 (MODIFY → NEW 신규 생성)
- `etf_trgt_nmix_bstp_code` 미문서화 KIS 필드: `underlying_index_code` NULL 허용, 부재 시 symbol fallback
