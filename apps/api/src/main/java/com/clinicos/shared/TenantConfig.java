package com.clinicos.shared;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration
public class TenantConfig {

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.addListener(new TenantConnectionListener(dataSource));
        return transactionManager;
    }
}
