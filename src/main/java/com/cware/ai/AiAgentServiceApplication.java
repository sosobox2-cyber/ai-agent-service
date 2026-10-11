package com.cware.ai;

import com.cware.ai.config.InferenceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InferenceProperties.class)
/** 웹 API와 추론·보안 설정을 함께 시작하는 Spring Boot 진입점이다. */
public class AiAgentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAgentServiceApplication.class, args);
    }
}
