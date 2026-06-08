package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목 등급 영속화 서비스.
 *
 * <p>{@link GradeClassificationService}에서 트랜잭션 범위를 KIS API 호출과 분리하기 위해 추출했다. 종목당 독립 트랜잭션으로 실행되므로 한
 * 종목의 DB 제약 위반이 다른 종목의 영속화를 오염시키지 않는다 (REQ-009/010).
 */
@Service
@RequiredArgsConstructor
public class StockGradePersistService {

    private final StockGradeRepository stockGradeRepository;
    private final GradeCacheRepository gradeCacheRepository;

    /**
     * 단일 종목 등급을 DB와 Redis에 영속화한다.
     *
     * <p>기존 등급이 있으면 {@link StockGrade#updateGrade(String, ZonedDateTime)}으로 갱신하고, 없으면 신규 생성한다.
     * 트랜잭션은 종목 단위로 독립 적용된다 — 호출자인 {@link GradeClassificationService#classify()}는 트랜잭션을 보유하지 않으므로
     * self-invocation 문제가 없다.
     *
     * @param stock 등급을 저장할 종목
     * @param grade 분류된 등급
     * @param gradedAt 등급 산정 시각 (KST)
     */
    // @MX:NOTE: [AUTO] 종목별 독립 트랜잭션. GradeClassificationService.classify() 바깥에서 호출 — KIS API 점유와 완전
    // 분리
    @Transactional
    public void persistSingle(Stock stock, Grade grade, ZonedDateTime gradedAt) {
        Optional<StockGrade> existing = stockGradeRepository.findByStock(stock);
        StockGrade stockGrade;
        if (existing.isPresent()) {
            stockGrade = existing.get();
            stockGrade.updateGrade(grade.name(), gradedAt);
        } else {
            stockGrade =
                    StockGrade.builder()
                            .stock(stock)
                            .grade(grade.name())
                            .gradedAt(gradedAt)
                            .build();
        }
        stockGradeRepository.save(stockGrade);
        gradeCacheRepository.save(stock.getSymbol(), grade, gradedAt);
    }
}
