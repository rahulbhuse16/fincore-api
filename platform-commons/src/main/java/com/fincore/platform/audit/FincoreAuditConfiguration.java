package com.fincore.platform.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class FincoreAuditConfiguration {
    @Bean
    AuditLogger auditLogger(JdbcTemplate jdbc) {
        return new AuditLogger(jdbc);
    }
}
