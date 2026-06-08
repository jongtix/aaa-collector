package com.aaa.collector.stock.etf;

import com.aaa.collector.stock.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ETF 메타데이터 upsert 담당 컴포넌트.
 *
 * <p>WatchlistEntryWriter에서 ETF 종목 처리 시 위임받는다 (REQ-ETFMETA-002).
 *
 * <p>REQUIRES_NEW 전파: 메타데이터 저장 실패가 stock upsert 트랜잭션에 영향을 주지 않도록 분리.
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
     * @param stock ETF 종목 엔티티
     * @param etfMetaInfo KIS에서 추출한 ETF 메타정보
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
