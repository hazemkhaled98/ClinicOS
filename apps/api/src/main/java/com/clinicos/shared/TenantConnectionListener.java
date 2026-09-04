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

/**
 * Sets the {@code app.clinic_id} session GUC as the first thing inside every
 * database transaction so Postgres row-level security (V9__rls_policies.sql)
 * scopes each statement to one tenant.
 *
 * <p>{@link #afterBegin} fires after the transaction's {@code BEGIN}
 * (autocommit already off) and before any business SQL runs — the exact
 * ordering {@code SET LOCAL} requires. Setting it earlier (e.g. from a
 * {@code DataSource} decorator around {@code getConnection()}) is a subtle
 * bug: while autocommit is still on, {@code SET LOCAL} applies only to a
 * single implicit transaction and is gone before the real one starts.
 *
 * <p>If no tenant is bound, this throws rather than proceeding: an unset
 * {@code app.clinic_id} does not error in Postgres — RLS simply matches
 * nothing and every query in the transaction silently returns zero rows.
 * Failing loudly here turns that silent failure into a clear error.
 */
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
        // `connection` is the transaction-bound connection the transaction
        // manager already owns and will commit/rollback and close itself; we
        // must NOT release/close it here (that returns it to the pool
        // mid-transaction and the later commit fails with "Connection is
        // closed"). Only the Statement is closed.
        try (Statement statement = connection.createStatement()) {
            // clinicId is a java.util.UUID, never raw user input -- string
            // concatenation is safe, and SET does not accept JDBC bind params.
            statement.execute("SET LOCAL app.clinic_id = '" + clinicId + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set app.clinic_id for tenant " + clinicId, e);
        }
    }
}
