package com.fincore.ledger;

import com.fincore.platform.EnableFincorePlatform;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@EnableFincorePlatform
@ConfigurationPropertiesScan
public class LedgerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
