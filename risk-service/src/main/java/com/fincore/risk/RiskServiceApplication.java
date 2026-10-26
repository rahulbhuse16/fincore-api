package com.fincore.risk;

import com.fincore.platform.EnableFincorePlatform;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@EnableFincorePlatform
@ConfigurationPropertiesScan
public class RiskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}
