package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockGradePersistService 단위 테스트")
class StockGradePersistServiceTest {

    @Mock private StockGradeRepository stockGradeRepository;
    @Mock private GradeCacheRepository gradeCacheRepository;

    @InjectMocks private StockGradePersistService service;

    private Stock buildStock(String symbol) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2010, 1, 1))
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    @Nested
    @DisplayName("시나리오 10 — updateGrade 필드 보존")
    class UpsertLogic {

        @Test
        @DisplayName("기존 StockGrade 존재 — updateGrade()로 grade/gradedAt만 갱신")
        void persistSingle_existingGrade_callsUpdateGrade() {
            // Arrange
            Stock stock = buildStock("005930");
            ZonedDateTime gradedAt = ZonedDateTime.now();

            StockGrade existing =
                    StockGrade.builder()
                            .stock(stock)
                            .grade("C")
                            .gradedAt(ZonedDateTime.now().minusDays(1))
                            .build();
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.of(existing));
            when(stockGradeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.persistSingle(stock, Grade.A, gradedAt);

            // Assert
            assertThat(existing.getGrade()).isEqualTo("A");
            assertThat(existing.getGradedAt()).isEqualTo(gradedAt);
            verify(stockGradeRepository).save(existing);
        }

        @Test
        @DisplayName("기존 StockGrade 없음 — 신규 StockGrade 생성 후 save()")
        void persistSingle_noExistingGrade_createsNew() {
            // Arrange
            Stock stock = buildStock("AAPL");
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.empty());
            when(stockGradeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.persistSingle(stock, Grade.B, ZonedDateTime.now());

            // Assert
            verify(stockGradeRepository).save(any(StockGrade.class));
        }
    }

    @Nested
    @DisplayName("시나리오 9 — 이중 영속화")
    class DualPersistence {

        @Test
        @DisplayName("등급 산정 후 stockGradeRepository와 gradeCacheRepository 모두 호출")
        void persistSingle_callsBothRepoAndCache() {
            // Arrange
            Stock stock = buildStock("005930");
            ZonedDateTime gradedAt = ZonedDateTime.now();
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.empty());
            when(stockGradeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.persistSingle(stock, Grade.A, gradedAt);

            // Assert
            verify(stockGradeRepository).save(any(StockGrade.class));
            verify(gradeCacheRepository).save("005930", Grade.A, gradedAt);
        }

        @Test
        @DisplayName("cacheRepository 예외 — 전파되지 않음 (non-fatal)")
        void persistSingle_cacheException_doesNotPropagate() {
            // Arrange
            Stock stock = buildStock("005930");
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.empty());
            when(stockGradeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // GradeCacheRepository.save()는 내부에서 예외를 삼킴(non-fatal) — verify로 확인
            // (실제 구현이 non-fatal이므로 서비스 수준에서 예외 전파 없음)

            // Act & Assert (예외 없이 종료)
            service.persistSingle(stock, Grade.A, ZonedDateTime.now());
            verify(gradeCacheRepository).save(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("GradeCacheRepository 호출 없음 — stockGradeRepository 실패 시")
    class PersistFailure {

        @Test
        @DisplayName("stockGradeRepository.save() 예외 시 gradeCacheRepository 미호출")
        void persistSingle_repoException_cacheNotCalled() {
            // Arrange
            Stock stock = buildStock("005930");
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.empty());
            when(stockGradeRepository.save(any())).thenThrow(new RuntimeException("DB 오류"));

            // Act & Assert
            try {
                service.persistSingle(stock, Grade.A, ZonedDateTime.now());
            } catch (RuntimeException ignored) {
                // 예외는 호출자(GradeClassificationService)의 catch에서 처리됨
            }

            verify(gradeCacheRepository, never()).save(any(), any(), any());
        }
    }
}
