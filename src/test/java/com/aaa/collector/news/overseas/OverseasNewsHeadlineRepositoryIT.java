package com.aaa.collector.news.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.news.DomesticNewsHeadline;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 해외 뉴스 V25~V27 스키마 검증 IT (Testcontainers MySQL {@code mysql:8.4}).
 *
 * <p><b>범위 한정(S-10/REQ-OVE-064/065/080)</b>: {@link OverseasNewsHeadlineRepositoryTest}가 이미
 * 멱등(news_key INSERT IGNORE)·빈 symbol 저장(REQ-OVE-047)을 커버하므로, 본 IT는 그와 중복을 피해 <b>마이그레이션 후 스키마
 * 검증</b>만 다룬다: (1) V27 신규 테이블 전 컬럼 라운드트립(published_at·info_gb·class_cd·class_name·source· nation_cd
 * 등 11컬럼이 실 MySQL에 보존), (2) V26 RENAME({@code domestic_news_headlines})와 V27 CREATE({@code
 * overseas_news_headlines})가 동일 부팅 스키마에서 공존하여 양 테이블이 모두 질의 가능함.
 *
 * <p>컨텍스트 로드 자체가 Flyway V25~V27 적용 + ddl-auto=validate 통과를 함의한다(부팅 성공 = 엔티티-스키마 정합). H2 미사용 —
 * INSERT IGNORE/네이티브 시맨틱은 MySQL에서만 보장된다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("OverseasNewsHeadlineRepository 스키마 IT — V25~V27 마이그레이션 후 컬럼 라운드트립·RENAME 공존")
@Tag("integration")
class OverseasNewsHeadlineRepositoryIT {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @Autowired private OverseasNewsHeadlineRepository overseasRepository;
    @Autowired private DomesticNewsHeadlineRepository domesticRepository;

    @Nested
    @DisplayName("V27 overseas_news_headlines — 전 컬럼 라운드트립 (스키마 정합)")
    class FullColumnRoundTrip {

        @Test
        @DisplayName("11개 컬럼이 native INSERT 후 실 MySQL에서 원본 그대로 보존된다")
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // 단일 라운드트립의 전 컬럼 보존을 한 테스트에서 검증
        void allColumnsPersistOnRealMysql() {
            // Arrange — V27 전 컬럼을 채운 행 (info_gb 소문자 e·종목 연계 뉴스)
            LocalDateTime publishedAt = LocalDateTime.of(2026, 6, 24, 12, 26, 17);
            OverseasNewsHeadline headline =
                    OverseasNewsHeadline.builder()
                            .newsKey("ICH793864")
                            .publishedAt(publishedAt)
                            .infoGb("e")
                            .classCd("04")
                            .className("ETF")
                            .source("글로벌ETF")
                            .nationCd("US")
                            .exchangeCd("AMS")
                            .symbol("BUFZ")
                            .symbolName("버퍼형 ETF")
                            .title("버퍼형 ETF 신규 상장 — V27 스키마 라운드트립")
                            .build();

            // Act — V27 DDL과 정합하는 native INSERT IGNORE
            overseasRepository.insertIgnoreDuplicate(headline);

            // Assert — 모든 컬럼이 실 MySQL 왕복 후 보존됨 (컬럼 누락·타입 절단 없음)
            OverseasNewsHeadline saved =
                    overseasRepository.findAll().stream()
                            .filter(n -> "ICH793864".equals(n.getNewsKey()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getPublishedAt()).isEqualTo(publishedAt);
            assertThat(saved.getInfoGb()).isEqualTo("e");
            assertThat(saved.getClassCd()).isEqualTo("04");
            assertThat(saved.getClassName()).isEqualTo("ETF");
            assertThat(saved.getSource()).isEqualTo("글로벌ETF");
            assertThat(saved.getNationCd()).isEqualTo("US");
            assertThat(saved.getExchangeCd()).isEqualTo("AMS");
            assertThat(saved.getSymbol()).isEqualTo("BUFZ");
            assertThat(saved.getSymbolName()).isEqualTo("버퍼형 ETF");
            assertThat(saved.getTitle()).isEqualTo("버퍼형 ETF 신규 상장 — V27 스키마 라운드트립");
        }
    }

    @Nested
    @DisplayName("V26 RENAME + V27 CREATE 공존 — 양 뉴스 테이블 모두 질의 가능")
    class RenameAndCreateCoexist {

        @Test
        @DisplayName(
                "동일 부팅 스키마에서 domestic_news_headlines(RENAME)·overseas_news_headlines(신규)가 독립 질의된다")
        void bothNewsTablesQueryableInSameSchema() {
            // Arrange — 해외 신규 테이블 + RENAME된 국내 테이블에 각각 1건
            overseasRepository.insertIgnoreDuplicate(
                    OverseasNewsHeadline.builder()
                            .newsKey("ICH000777")
                            .publishedAt(LocalDateTime.of(2026, 6, 24, 9, 0, 0))
                            .nationCd("US")
                            .title("해외 공존 검증")
                            .build());
            domesticRepository.insertIgnoreDuplicate(
                    DomesticNewsHeadline.builder()
                            .serialNo("9000000000000000001")
                            .publishedAt(LocalDateTime.of(2026, 6, 24, 9, 0, 0))
                            .providerCode("1")
                            .title("국내 공존 검증")
                            .source("연합뉴스")
                            .build());

            // Assert — V26 RENAME·V27 CREATE가 한 스키마에서 충돌 없이 공존 (각 테이블 1건)
            assertThat(overseasRepository.countAll()).isEqualTo(1L);
            assertThat(domesticRepository.countAll()).isEqualTo(1L);
        }
    }
}
