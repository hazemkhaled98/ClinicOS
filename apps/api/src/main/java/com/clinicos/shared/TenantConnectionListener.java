package com.clinicos.shared;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

public class TenantConnectionListener implements TransactionExecutionListener {

    private final DataSource dataSource;

    public TenantConnectionListener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        UUID clinicId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException(
                        "No tenant bound to the current thread at transaction start. "
                                + "Refusing to proceed: Postgres RLS would otherwise silently "
                                + "return zero rows instead of failing loudly."));
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL app.clinic_id = '" + clinicId + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set app.clinic_id for tenant " + clinicId, e);
        }
    }
}
