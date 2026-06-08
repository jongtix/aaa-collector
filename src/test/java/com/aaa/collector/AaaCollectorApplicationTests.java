package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.etf.EtfMetadataRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.stock.grade.StockGradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles({"test", "smoke"})
class AaaCollectorApplicationTests {

    @Autowired private ApplicationContext context;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    @SuppressWarnings("unused")
    private StockRepository stockRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private EtfMetadataRepository etfMetadataRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DailyOhlcvRepository dailyOhlcvRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private StockGradeRepository stockGradeRepository;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
