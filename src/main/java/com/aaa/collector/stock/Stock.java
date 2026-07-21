package com.aaa.collector.stock;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.DelistingReason;
import com.aaa.collector.stock.enums.ListingStatus;
import com.aaa.collector.stock.enums.Market;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 종목 마스터. */
@Entity
@Table(
        name = "stocks",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_stocks_symbol_market",
                        columnNames = {"symbol", "market"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Stock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", length = 16)
    private final String symbol;

    @Column(name = "name_ko", length = 100)
    private String nameKo;

    @Column(name = "name_en", length = 100)
    private String nameEn;

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    @Column(name = "market", length = 10)
    private Market market; // correctable — REQ-STOCKMETA-004,011

    @Column(name = "asset_type", length = 10)
    private final AssetType assetType;

    @Column(name = "listed_date")
    private LocalDate listedDate; // correctable — REQ-STOCKMETA-004,012

    @Column(name = "active")
    private boolean active;

    @Column(name = "watchlist_removed_at")
    private LocalDateTime watchlistRemovedAt;

    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
    @Column(name = "delisted_at")
    private LocalDate delistedAt; // set-only(비가역) — REQ-WLSYNC-144,147,152

    @Column(name = "delisting_reason", length = 30)
    private DelistingReason delistingReason;

    @Builder
    private Stock(
            String symbol,
            String nameKo,
            String nameEn,
            Market market,
            AssetType assetType,
            LocalDate listedDate,
            boolean active,
            LocalDateTime watchlistRemovedAt,
            LocalDate delistedAt,
            DelistingReason delistingReason) {
        super();
        this.symbol = symbol;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.market = market;
        this.assetType = assetType;
        this.listedDate = listedDate;
        this.active = active;
        this.watchlistRemovedAt = watchlistRemovedAt;
        this.delistedAt = delistedAt;
        this.delistingReason = delistingReason;
    }

    /**
     * 관심종목 동기화 시 이름 갱신, 제거 시각 초기화를 수행한다.
     *
     * <p>{@code active} 재활성화는 이 메서드가 더 이상 담당하지 않는다(SPEC-COLLECTOR-WLSYNC-008) — 상폐/거래정지 상태를 무시한 채 매
     * sync마다 무조건 {@code active=true}로 되돌리던 과거 규칙이 상폐 종목을 계속 재활성화시키는 근본 원인이었다. {@code active} 값은
     * {@link #reflectListingStatus(ListingStatus, LocalDate)}만이 갱신한다.
     *
     * @return 하나 이상의 필드가 실제로 변경된 경우 {@code true}
     */
    public boolean syncFromWatchlist(String nameKo, String nameEn) {
        boolean changed = false;

        if (nameKo != null && !Objects.equals(nameKo, this.nameKo)) {
            this.nameKo = nameKo;
            changed = true;
        }
        if (nameEn != null && !Objects.equals(nameEn, this.nameEn)) {
            this.nameEn = nameEn;
            changed = true;
        }
        if (this.watchlistRemovedAt != null) {
            this.watchlistRemovedAt = null; // NOPMD: intentional null reset for JPA dirty checking
            changed = true;
        }

        return changed;
    }

    /**
     * KIS 종목 기본정보에서 감지된 상장 상태를 {@code active}/{@code delisted_at}/{@code delisting_reason}에 원자적으로
     * 반영한다 (SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-144~147,150,152).
     *
     * <p>규칙:
     *
     * <ul>
     *   <li>이미 {@code delistedAt != null}(상폐 확정): 어떤 감지 결과가 와도 no-op(set-only 비가역, REQ-WLSYNC-152).
     *   <li>{@link ListingStatus#DELISTED} + {@code detectedDelistedDate} 있음: {@code active=false},
     *       {@code delistedAt=detectedDelistedDate}, {@code
     *       delistingReason=UNKNOWN}(REQ-WLSYNC-144).
     *   <li>{@link ListingStatus#DELISTED} + {@code detectedDelistedDate} 없음(해외 상폐일자 필드 미확보, §7 미해결
     *       질문): 상폐일자를 임의로 지어내지 않고 {@code active=false}만 반영한다(거래정지와 동등하게 가역 취급).
     *   <li>{@link ListingStatus#HALTED}: {@code active=false}, {@code delistedAt}은 건드리지 않는다(가역,
     *       REQ-WLSYNC-145).
     *   <li>{@link ListingStatus#NORMAL}: {@code active=true}로 복구한다(REQ-WLSYNC-146). 거래정지 해제 회복도 이
     *       분기가 처리한다.
     * </ul>
     *
     * <p>[HARD] 메서드 종료 시 {@code delistedAt != null → active == false} 불변식이 항상 성립한다(REQ-WLSYNC-147)
     * — {@code delistedAt}을 설정하는 유일한 분기가 같은 호출에서 {@code active=false}도 함께 설정하기 때문이다. INSERT
     * (buildStock)·UPDATE(updateIfNeeded) 두 경로 모두 이 메서드 하나로 수렴한다({@code WatchlistEntryWriter}).
     *
     * @param status 감지된 상장 상태
     * @param detectedDelistedDate 상폐 감지 시의 상폐일자. 국내는 {@code lstg_abol_dt} 파싱값이 항상 채워지고, 해외는 전용 필드가
     *     미확보라 null일 수 있다
     * @return 하나 이상의 필드가 실제로 변경된 경우 {@code true}
     */
    // @MX:ANCHOR: [AUTO] 상폐/거래정지 → active 축 반영 불변식 진입점, delisted_at set-only
    // @MX:REASON: REQ-WLSYNC-147 — active/delisted_at을 세팅하는 유일한 경로로 캡슐화해 불변식 우회 경로가
    // 생기지 않도록 한다. WatchlistEntryWriter의 INSERT·UPDATE 두 경로가 이 메서드 하나로 수렴한다.
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
    public boolean reflectListingStatus(ListingStatus status, LocalDate detectedDelistedDate) {
        if (this.delistedAt != null) {
            // 이미 상폐 확정 — set-only 비가역(REQ-WLSYNC-152). 이후 거래정지·정상 감지가 와도 상태 불변.
            return false;
        }

        boolean changed = false;
        switch (status) {
            case DELISTED -> {
                if (detectedDelistedDate != null) {
                    this.delistedAt = detectedDelistedDate;
                    this.delistingReason = DelistingReason.UNKNOWN;
                    changed = true;
                }
                if (this.active) {
                    this.active = false;
                    changed = true;
                }
            }
            case HALTED -> {
                if (this.active) {
                    this.active = false;
                    changed = true;
                }
            }
            case NORMAL -> {
                if (!this.active) {
                    this.active = true;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * 시장 교정 및 상장일 채우기를 수행한다 (REQ-STOCKMETA-004,011,012, SPEC-COLLECTOR-STOCKMETA-001).
     *
     * <p>두 보정 모두 단방향 강화만 수행한다:
     *
     * <ul>
     *   <li>시장 교정: 저장값이 {@code authoritativeMarket}과 다른 경우에만 교정한다. 이미 일치하면 변경하지 않는다.
     *   <li>상장일 채우기: 저장값이 {@code null}이고 {@code authoritative listedDate}가 non-null인 경우에만 채운다.
     *       non-null → non-null 덮어쓰기나 null로의 변경은 수행하지 않는다.
     * </ul>
     *
     * @param authoritativeMarket 권위 시장값 (null이면 시장 교정을 수행하지 않음)
     * @param authoritativeListedDate 권위 상장일 (null이면 상장일 채우기를 수행하지 않음)
     * @return 하나 이상의 필드가 실제로 변경된 경우 {@code true}
     */
    public boolean correctMetadata(Market authoritativeMarket, LocalDate authoritativeListedDate) {
        boolean changed = false;

        // 시장 교정: 저장값 != 권위값인 경우에만 (REQ-STOCKMETA-011)
        if (authoritativeMarket != null && !Objects.equals(this.market, authoritativeMarket)) {
            this.market = authoritativeMarket;
            changed = true;
        }

        // 상장일 채우기: NULL→non-null만 수행 (REQ-STOCKMETA-012)
        if (this.listedDate == null && authoritativeListedDate != null) {
            this.listedDate = authoritativeListedDate;
            changed = true;
        }

        return changed;
    }

    /**
     * 이미 저장된 {@code daily_ohlcv}가 증거로 제시하는 더 이른 날짜로 {@code listed_date}를 하향 보정한다
     * (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-159).
     *
     * <p>{@code min}이 현재 {@code listedDate}보다 이전(더 과거)일 때만 적용하는 **하향 전용 가드**다 — 상향 보정은 결코 하지
     * 않는다(§Exclusions "종목 하드코딩 금지"와 별개로, KIS {@code CTPF1702R lstg_dt}가 현 거래소 상장일이라 최초 거래일보다 늦게 잡히는
     * 거래소 이전 종목류의 메타데이터 과대평가를 정정한다). {@link #correctMetadata(Market, LocalDate)}(NULL→non-null 채우기
     * 전용)와는 별도 메서드다 — 이 메서드는 non-null이 이미 있는 경우에도 더 이른 값으로 하향할 수 있다.
     *
     * @param min 이미 저장된 {@code daily_ohlcv}의 {@code MIN(trade_date)} — 이 값이 현재 {@code listedDate}보다
     *     이르면 그 자체로 과대평가 증거다(KIS 재조회 불요)
     * @return {@code listedDate}가 실제로 하향 변경된 경우 {@code true}
     */
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    public boolean correctListedDateDownTo(LocalDate min) {
        if (min == null || this.listedDate == null || !min.isBefore(this.listedDate)) {
            return false;
        }
        this.listedDate = min;
        return true;
    }
}
