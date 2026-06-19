package com.aaa.collector.stock.supply;

import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.Stock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KIS 공매도 응답({@link KisShortSaleResponse})의 행들을 검증·매핑하여 저장 대상 {@link ShortSaleDomestic} 목록을 산출한다
 * (REQ-BATCH2-041/042/060~063, REQ-BATCH2-025).
 *
 * <p>행 단위 검증·매핑·윈도우 필터·경계 커버리지 관측 책임을 수집 서비스에서 분리하여 {@code ShortSaleCollectionService}의 결합도를 임계값
 * 이하로 유지한다(동일 패키지 내 격리 — {@code MismatchDetector}와 동일 패턴). 검증 규칙: null/blank trade_date·비율 절댓값 ≥
 * 1000(DECIMAL(7,4) 경계) 초과·음수 수량/금액·숫자 파싱 실패 행은 저장 제외(WARN 로그). 14일 윈도우 밖 행 제외.
 */
// @MX:ANCHOR: [AUTO] 공매도 행→엔티티 검증·매핑·윈도우 필터 — 수집 서비스 결합도 격리
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 — ShortSaleCollectionService가 종목별로 호출
@Slf4j
@Component
public class ShortSaleRowMapper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 검증 통과·윈도우 내 행만 엔티티로 매핑하여 반환하고, 경계 커버리지를 관측한다.
     *
     * <p>경계 커버리지 산정용 {@code tradeDates}는 파싱 성공한 날짜만 포함(NumberFormatException 제외, 윈도우 밖·검증 실패 포함).
     *
     * @param windowStart 14일 윈도우 하단(포함)
     * @param today 윈도우 상단(포함)
     * @return 저장 대상 엔티티 목록(없으면 빈 목록)
     */
    public List<ShortSaleDomestic> collectValid(
            Stock stock,
            String symbol,
            KisShortSaleResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<ShortSaleDomestic> validEntities = new ArrayList<>();
        List<LocalDate> tradeDates = new ArrayList<>();
        for (KisShortSaleResponse.ShortSaleRow row : response.output2()) {
            // null/blank trade_date — coverage 산정 제외, 저장 제외
            if (row.stckBsopDate() == null || row.stckBsopDate().isBlank()) {
                log.warn("[short-sale] 검증 실패 (trade_date null) — symbol={}", symbol);
                continue;
            }
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            tradeDates.add(tradeDate); // 윈도우 밖·검증 실패 포함, NumberFormatException 제외
            if (tradeDate.isBefore(windowStart) || tradeDate.isAfter(today)) {
                continue; // 윈도우 밖 — 저장 제외
            }
            try {
                ShortSaleDomestic entity = toEntity(stock, symbol, tradeDate, row);
                if (entity != null) {
                    validEntities.add(entity);
                }
            } catch (NumberFormatException e) {
                log.warn(
                        "[short-sale] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}",
                        symbol,
                        row.stckBsopDate());
            }
        }
        // REQ-BATCH2-025: 경계 커버리지 관측 (단일 응답 윈도우 하단 미커버 시 WARN)
        WindowCoverageChecker.check("short-sale", symbol, tradeDates, windowStart);
        return validEntities;
    }

    /**
     * 검증 통과 시 엔티티를 반환한다. 검증 실패 시 {@code null}(로그 후). 숫자 파싱 실패 시 {@link NumberFormatException} 전파.
     *
     * @param tradeDate 이미 파싱·윈도우 필터 완료된 거래일
     */
    private ShortSaleDomestic toEntity(
            Stock stock,
            String symbol,
            LocalDate tradeDate,
            KisShortSaleResponse.ShortSaleRow row) {
        long shortSellQty = Long.parseLong(row.sstsCntgQty());
        long shortSellAmt = Long.parseLong(row.sstsTrPbmn());
        long shortSellAccQty = Long.parseLong(row.acmlSstsCntgQty());
        long shortSellAccAmt = Long.parseLong(row.acmlSstsTrPbmn());
        BigDecimal shortSellVolRate = new BigDecimal(row.sstsVolRlim());
        BigDecimal shortSellAmtRate = new BigDecimal(row.sstsTrPbmnRlim());
        BigDecimal shortSellAccQtyRate = new BigDecimal(row.acmlSstsCntgQtyRlim());
        BigDecimal shortSellAccAmtRate = new BigDecimal(row.acmlSstsTrPbmnRlim());

        if (SupplyDemandValidator.anyNegative(
                shortSellQty, shortSellAmt, shortSellAccQty, shortSellAccAmt)) {
            log.warn("[short-sale] 검증 실패 (음수 수량/금액) — symbol={}, date={}", symbol, tradeDate);
            return null;
        }

        if (!SupplyDemandValidator.allRatesWithinBounds(
                shortSellVolRate, shortSellAmtRate, shortSellAccQtyRate, shortSellAccAmtRate)) {
            log.warn(
                    "[short-sale] 검증 실패 (비율 DECIMAL(7,4) 경계 초과) — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return null;
        }

        return ShortSaleDomestic.builder()
                .stock(stock)
                .tradeDate(tradeDate)
                .shortSellQty(shortSellQty)
                .shortSellVolRate(shortSellVolRate)
                .shortSellAmt(shortSellAmt)
                .shortSellAmtRate(shortSellAmtRate)
                .shortSellAccQty(shortSellAccQty)
                .shortSellAccQtyRate(shortSellAccQtyRate)
                .shortSellAccAmt(shortSellAccAmt)
                .shortSellAccAmtRate(shortSellAccAmtRate)
                .build();
    }
}
