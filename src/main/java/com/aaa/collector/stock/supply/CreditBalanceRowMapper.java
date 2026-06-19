package com.aaa.collector.stock.supply;

import com.aaa.collector.stock.CreditBalance;
import com.aaa.collector.stock.Stock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KIS 신용잔고 응답({@link KisCreditBalanceResponse})의 행들을 검증·매핑하여 저장 대상 {@link CreditBalance} 목록을 산출한다
 * (REQ-BATCH2-051~053/060~063, REQ-BATCH2-025).
 *
 * <p>행 단위 검증·매핑·윈도우 필터·경계 커버리지 관측 책임을 수집 서비스에서 분리하여 {@code CreditBalanceCollectionService}의 결합도를
 * 임계값 이하로 유지한다(동일 패키지 내 격리 — {@code MismatchDetector}와 동일 패턴). 검증 규칙: null/blank deal_date·비율 절댓값 ≥
 * 1000(DECIMAL(7,4) 경계) 초과·음수 수량/금액·숫자 파싱 실패 행은 저장 제외(WARN 로그). 14일 윈도우 밖 행(deal_date 기준) 제외.
 */
// @MX:ANCHOR: [AUTO] 신용잔고 행→엔티티 검증·매핑·윈도우 필터 — 수집 서비스 결합도 격리
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 — CreditBalanceCollectionService가 종목별로 호출
@Slf4j
@Component
public class CreditBalanceRowMapper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 검증 통과·윈도우 내 행만 엔티티로 매핑하여 반환하고, 경계 커버리지를 관측한다(deal_date 기준).
     *
     * <p>경계 커버리지 산정용 {@code tradeDates}는 파싱 성공한 날짜만 포함(NumberFormatException 제외, 윈도우 밖·검증 실패 포함).
     *
     * @param windowStart 14일 윈도우 하단(포함)
     * @param today 윈도우 상단(포함)
     * @return 저장 대상 엔티티 목록(없으면 빈 목록)
     */
    public List<CreditBalance> collectValid(
            Stock stock,
            String symbol,
            KisCreditBalanceResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<CreditBalance> validEntities = new ArrayList<>();
        List<LocalDate> tradeDates = new ArrayList<>();
        for (KisCreditBalanceResponse.CreditBalanceRow row : response.output()) {
            // [HARD] trade_date는 deal_date(매매일자) — stlm_date가 아님 (REQ-BATCH2-052)
            if (row.dealDate() == null || row.dealDate().isBlank()) {
                log.warn("[credit-balance] 검증 실패 (deal_date null) — symbol={}", symbol);
                continue;
            }
            LocalDate tradeDate = LocalDate.parse(row.dealDate(), DATE_FMT);
            tradeDates.add(tradeDate);
            if (tradeDate.isBefore(windowStart) || tradeDate.isAfter(today)) {
                continue;
            }
            try {
                CreditBalance entity = toEntity(stock, symbol, tradeDate, row);
                if (entity != null) {
                    validEntities.add(entity);
                }
            } catch (NumberFormatException e) {
                log.warn(
                        "[credit-balance] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}",
                        symbol,
                        row.dealDate());
            }
        }
        // REQ-BATCH2-025: 경계 커버리지 관측 (단일 응답 윈도우 하단 미커버 시 WARN — deal_date 기준)
        WindowCoverageChecker.check("credit-balance", symbol, tradeDates, windowStart);
        return validEntities;
    }

    /**
     * 검증 통과 시 엔티티를 반환한다. 검증 실패 시 {@code null}(로그 후). 숫자 파싱 실패 시 {@link NumberFormatException} 전파.
     *
     * @param tradeDate deal_date(매매일자) — REQ-BATCH2-052
     */
    private CreditBalance toEntity(
            Stock stock,
            String symbol,
            LocalDate tradeDate,
            KisCreditBalanceResponse.CreditBalanceRow row) {
        long loanNewQty = Long.parseLong(row.wholLoanNewStcn());
        long loanRepayQty = Long.parseLong(row.wholLoanRdmpStcn());
        long loanBalanceQty = Long.parseLong(row.wholLoanRmndStcn());
        long loanNewAmt = Long.parseLong(row.wholLoanNewAmt());
        long loanRepayAmt = Long.parseLong(row.wholLoanRdmpAmt());
        long loanBalanceAmt = Long.parseLong(row.wholLoanRmndAmt());
        long lendNewQty = Long.parseLong(row.wholStlnNewStcn());
        long lendRepayQty = Long.parseLong(row.wholStlnRdmpStcn());
        long lendBalanceQty = Long.parseLong(row.wholStlnRmndStcn());
        long lendNewAmt = Long.parseLong(row.wholStlnNewAmt());
        long lendRepayAmt = Long.parseLong(row.wholStlnRdmpAmt());
        long lendBalanceAmt = Long.parseLong(row.wholStlnRmndAmt());
        BigDecimal loanBalanceRate = new BigDecimal(row.wholLoanRmndRate());
        BigDecimal loanSupplyRate = new BigDecimal(row.wholLoanGvrt());
        BigDecimal lendBalanceRate = new BigDecimal(row.wholStlnRmndRate());
        BigDecimal lendSupplyRate = new BigDecimal(row.wholStlnGvrt());

        if (SupplyDemandValidator.anyNegative(
                loanNewQty,
                loanRepayQty,
                loanBalanceQty,
                loanNewAmt,
                loanRepayAmt,
                loanBalanceAmt,
                lendNewQty,
                lendRepayQty,
                lendBalanceQty,
                lendNewAmt,
                lendRepayAmt,
                lendBalanceAmt)) {
            log.warn("[credit-balance] 검증 실패 (음수 수량/금액) — symbol={}, date={}", symbol, tradeDate);
            return null;
        }

        if (!SupplyDemandValidator.allRatesWithinBounds(
                loanBalanceRate, loanSupplyRate, lendBalanceRate, lendSupplyRate)) {
            log.warn(
                    "[credit-balance] 검증 실패 (비율 DECIMAL(7,4) 경계 초과) — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return null;
        }

        return CreditBalance.builder()
                .stock(stock)
                .tradeDate(tradeDate)
                .loanNewQty(loanNewQty)
                .loanRepayQty(loanRepayQty)
                .loanBalanceQty(loanBalanceQty)
                .loanNewAmt(loanNewAmt)
                .loanRepayAmt(loanRepayAmt)
                .loanBalanceAmt(loanBalanceAmt)
                .loanBalanceRate(loanBalanceRate)
                .loanSupplyRate(loanSupplyRate)
                .lendNewQty(lendNewQty)
                .lendRepayQty(lendRepayQty)
                .lendBalanceQty(lendBalanceQty)
                .lendNewAmt(lendNewAmt)
                .lendRepayAmt(lendRepayAmt)
                .lendBalanceAmt(lendBalanceAmt)
                .lendBalanceRate(lendBalanceRate)
                .lendSupplyRate(lendSupplyRate)
                .build();
    }
}
