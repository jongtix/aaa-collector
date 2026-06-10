package com.aaa.collector.stock.etf;

import com.aaa.collector.stock.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ETF 메타데이터 upsert 담당 컴포넌트.
 *
 * <p>WatchlistEntryWriter에서 ETF 종목 처리 시 위임받는다 (REQ-ETFMETA-002).
 *
 * <p>호출자(WatchlistEntryWriter.upsertOne)의 트랜잭션에 합류(REQUIRED)한다. ETF 메타 저장 실패 시 stock upsert도 함께
 * 롤백되며, 이는 호출 상위 WatchlistWriter.upsertAll이 종목 단위로 DataAccessException을 잡아 skip 처리하는 로직(ADR-022 결정
 * 3)과 정합적이다 — 실패 종목은 DB를 부분적으로도 건드리지 않는다.
 *
 * <p>이전에 REQUIRES_NEW를 사용했으나 etf_metadata.fk_etf_metadata_stock (stock_id → stocks.id) FK 검증 시 부모
 * 트랜잭션이 보유한 미커밋 X락을 별도 커넥션에서 대기하는 self-lock이 발생, innodb_lock_wait_timeout(50s) 초과로 모든 ETF 종목이
 * skip되는 장애가 발생하였다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EtfMetadataWriter {

    private final EtfMetadataRepository etfMetadataRepository;

    /**
     * ETF 메타데이터를 upsert한다.
     *
     * <p>기존 레코드가 있으면 변경 분만 갱신 (dirty check). 없으면 신규 INSERT.
     *
     * <p>tr_stop 값도 여기서 함께 갱신된다 (REQ-ETFMETA-002).
     *
     * <p>호출자 트랜잭션에 합류(REQUIRED)하므로 이 메서드에서 예외가 발생하면 stock upsert도 함께 롤백된다.
     * WatchlistWriter.upsertAll에서 종목 단위로 DataAccessException을 잡아 skip하므로 데이터 일관성이 유지된다 (ADR-022 결정
     * 3).
     *
     * @param stock ETF 종목 엔티티
     * @param etfMetaInfo KIS에서 추출한 ETF 메타정보
     */
    @Transactional
    public void upsert(Stock stock, EtfMetaInfo etfMetaInfo) {
        etfMetadataRepository
                .findByStockId(stock.getId())
                .ifPresentOrElse(
                        existing ->
                                existing.updateFrom(
                                        etfMetaInfo.underlyingIndexCode(),
                                        etfMetaInfo.leverage(),
                                        etfMetaInfo.inverse(),
                                        etfMetaInfo.hedged(),
                                        etfMetaInfo.trStop()),
                        () ->
                                etfMetadataRepository.save(
                                        EtfMetadata.builder()
                                                .stock(stock)
                                                .underlyingIndexCode(
                                                        etfMetaInfo.underlyingIndexCode())
                                                .leverage(etfMetaInfo.leverage())
                                                .inverse(etfMetaInfo.inverse())
                                                .hedged(etfMetaInfo.hedged())
                                                .trStop(etfMetaInfo.trStop())
                                                .build()));
        if (log.isDebugEnabled()) {
            log.debug(
                    "ETF metadata upserted — symbol={}, trStop={}",
                    stock.getSymbol(),
                    etfMetaInfo.trStop());
        }
    }
}
