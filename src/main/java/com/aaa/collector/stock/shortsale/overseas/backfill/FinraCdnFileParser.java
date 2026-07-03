package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * FINRA CDN 공매도 Daily 파일 헤더 기반 파서 (SPEC-COLLECTOR-BACKFILL-008 T3, REQ-BACKFILL-105/106/107).
 *
 * <p>파일 첫 행(헤더)을 파싱해 컬럼명 기준으로 {@code Symbol}/{@code ShortVolume}/{@code TotalVolume}을 추출한다 — 컬럼 위치를
 * 하드코딩하지 않으므로 5컬럼(2011-02-25 이전, {@code ShortExemptVolume} 없음) 시대와 6컬럼(2011-02-28 이후) 시대를 단일 로직으로
 * 처리한다(REQ-BACKFILL-105). {@code ShortExemptVolume}은 V7 스키마에 컬럼이 없어 추출하지 않는다(REQ-BACKFILL-106). 요약
 * 행({@code {Date}|TOTALS|...}), 빈 값, 음수, 파싱 실패(소수부 존재 등) 행은 예외를 던지지 않고 skip 집계한다(REQ-BACKFILL-107).
 */
@Component
public class FinraCdnFileParser {

    private static final String DELIMITER = "\\|";
    private static final String SYMBOL_HEADER = "Symbol";
    private static final String SHORT_VOLUME_HEADER = "ShortVolume";
    private static final String TOTAL_VOLUME_HEADER = "TotalVolume";
    private static final String TOTALS_SYMBOL = "TOTALS";

    /**
     * 파일 본문을 파싱한다.
     *
     * @param fileBody CDN 파일 전체 본문(첫 행이 헤더)
     * @return 유효 행 목록 + skip 카운트
     */
    public ParsedFileResult parse(String fileBody) {
        if (fileBody == null || fileBody.isBlank()) {
            return new ParsedFileResult(List.of(), 0);
        }

        String[] lines = fileBody.split("\r?\n");
        Map<String, Integer> headerIndex = headerIndex(lines[0]);
        Integer symbolIdx = headerIndex.get(SYMBOL_HEADER);
        Integer shortIdx = headerIndex.get(SHORT_VOLUME_HEADER);
        Integer totalIdx = headerIndex.get(TOTAL_VOLUME_HEADER);
        if (symbolIdx == null || shortIdx == null || totalIdx == null) {
            // 헤더에 필수 컬럼이 없으면 데이터 행 전체를 skip 처리한다(예외 없음, REQ-BACKFILL-107).
            return new ParsedFileResult(List.of(), Math.max(0, lines.length - 1));
        }

        List<ParsedRow> rows = new ArrayList<>();
        int skipped = 0;
        ColumnIndex columnIndex = new ColumnIndex(symbolIdx, shortIdx, totalIdx);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            ParsedRow row = parseLine(line, columnIndex);
            if (row == null) {
                skipped++;
            } else {
                rows.add(row);
            }
        }

        return new ParsedFileResult(rows, skipped);
    }

    /** 데이터 행 1건을 파싱한다. 컬럼 부족·요약 행·빈값/음수/소수부는 {@code null}(skip)로 반환한다(REQ-BACKFILL-107). */
    private static ParsedRow parseLine(String line, ColumnIndex columnIndex) {
        String[] fields = line.split(DELIMITER, -1);
        if (fields.length < columnIndex.minRequiredFields()) {
            return null;
        }
        String symbol = fields[columnIndex.symbolIdx()];
        if (TOTALS_SYMBOL.equals(symbol)) {
            return null;
        }
        Long shortVolume = toNonNegativeLong(fields[columnIndex.shortIdx()]);
        Long totalVolume = toNonNegativeLong(fields[columnIndex.totalIdx()]);
        if (shortVolume == null || totalVolume == null) {
            return null;
        }
        return new ParsedRow(symbol, shortVolume, totalVolume);
    }

    private static Map<String, Integer> headerIndex(String headerLine) {
        String[] headers = headerLine.split(DELIMITER, -1);
        Map<String, Integer> index = new ConcurrentHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            index.put(headers[i].trim(), i);
        }
        return index;
    }

    /** 헤더 기반 컬럼 위치 묶음 — {@link #parseLine} 인자 개수를 줄이기 위한 내부 값 객체. */
    private record ColumnIndex(int symbolIdx, int shortIdx, int totalIdx) {
        int minRequiredFields() {
            return 1 + Math.max(symbolIdx, Math.max(shortIdx, totalIdx));
        }
    }

    // @MX:NOTE: [AUTO] 의도적 중복 — 라이브
    // FinraQuantityParser(com.aaa.collector.stock.shortsale.overseas)와
    // 동일 시맨틱, package-private라 서브패키지에서 재사용 불가. 추적: GitHub jongtix/aaa-infra#67 — 이슈 해결(DECIMAL
    // 전환 또는 공용 유틸 추출) 시 이 중복 코드도 함께 정리 대상.
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-008
    /**
     * {@link BigDecimal} 문자열을 음수 아님·무손실 {@code long}으로 변환한다. {@code null}/공백/음수/소수부 존재(2026-06-30
     * FINRA CNMS AAPL ShortVolume=7,546,181.247857 실측 케이스 포함)는 {@code null}을 반환해 호출자가 skip 처리하게
     * 한다(REQ-BACKFILL-107).
     *
     * @param raw 원본 필드 문자열
     * @return 변환된 {@code long}, 또는 변환 불가 시 {@code null}
     */
    private static Long toNonNegativeLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() < 0) {
                return null;
            }
            return value.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
