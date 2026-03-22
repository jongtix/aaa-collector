package com.aaa.collector;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@SuppressWarnings("PMD.UseUtilityClass")
public class AaaCollectorApplication {

    public static void main(String[] args) {
        // JVM 전역 시간대를 KST로 고정 (build.gradle.kts의 -Duser.timezone과 이중 보장, 프로덕션 java -jar 환경 담당)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(AaaCollectorApplication.class, args);
    }
}
