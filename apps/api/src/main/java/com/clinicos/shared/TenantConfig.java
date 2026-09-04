package com.clinicos.shared;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * Wires {@link TenantConnectionListener} into the application's single
 * {@link JdbcTransactionManager}. Defining this bean explicitly makes
 * Spring Boot's own jOOQ/JDBC auto-configuration back off (it only creates
 * a transaction manager when none is present), so this is the one place
 * the tenant-scoping mechanism is attached.
 */
@Configuration
public class TenantConfig {

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.addListener(new TenantConnectionListener(dataSource));
        return transactionManager;
    }
}
