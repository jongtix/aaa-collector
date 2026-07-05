package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.math.BigDecimal;

/**
 * FINRA CDN 파일 1행 파싱 결과 (SPEC-COLLECTOR-BACKFILL-008 T3, DECIMAL 전환
 * SPEC-COLLECTOR-SHORTSALE-DECIMAL-001).
 *
 * <p>{@code ShortExemptVolume}은 V7 스키마에 컬럼이 없어 파싱하지 않는다(REQ-BACKFILL-106) — 본 레코드가 3개 필드만 보유하는 것이 그
 * 계약이다. 거래량은 2026-02-23 FINRA 소수 전환 대응으로 {@link BigDecimal}(소수 6자리 무손실)로 보관한다(REQ-SSD-007).
 *
 * @param symbol FINRA 원본 심볼(정규화 이전, 슬래시 클래스주식 표기 가능)
 * @param shortVolume 공매도 거래량(비음수 무손실 소수)
 * @param totalVolume 전체 거래량(비음수 무손실 소수)
 */
public record ParsedRow(String symbol, BigDecimal shortVolume, BigDecimal totalVolume) {}
