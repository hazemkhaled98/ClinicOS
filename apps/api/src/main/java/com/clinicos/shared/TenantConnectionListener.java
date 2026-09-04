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
 * <p>The "no tenant bound" check lives in {@link #beforeBegin}, not here, and
 * that split is load-bearing, not stylistic. Spring's
 * {@code AbstractPlatformTransactionManager.startTransaction()} calls
 * {@code beforeBegin} before the real {@code doBegin} (connection acquisition
 * and JDBC {@code BEGIN}) and has an exception handler around that section, so
 * throwing there is a clean, fully-unwound failure. {@code afterBegin} runs
 * only after {@code doBegin} succeeded and
 * {@code TransactionSynchronizationManager} has already been marked active,
 * with no exception handler around it — a {@code TransactionExecutionListener}
 * that throws from {@code afterBegin} leaks that "active" flag and the bound
 * connection forever on the current thread, silently misrouting every
 * subsequent transaction on it through {@code handleExistingTransaction}
 * (which never invokes {@code afterBegin} at all), so this class must never
 * throw from {@code afterBegin}.
 *
 * <p>An unset {@code app.clinic_id} does not error in Postgres — RLS simply
 * matches nothing and every query in the transaction silently returns zero
 * rows. Failing loudly in {@code beforeBegin} turns that silent failure into a
 * clear error, unless {@link TenantContext#isAuthMode()} is true: auth mode is
 * the exception, and {@code afterBegin} binds the nil UUID
 * (00000000-0000-0000-0000-000000000000) so login queries can run before a
 * clinic is selected, with the guarantee that no RLS-scoped table matches any
 * rows for that value.
 */
public class TenantConnectionListener implements TransactionExecutionListener {

    private final DataSource dataSource;

    public TenantConnectionListener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void beforeBegin(TransactionExecution transaction) {
        if (TenantContext.get().isEmpty() && !TenantContext.isAuthMode()) {
            throw new IllegalStateException(
                    "No tenant bound to the current thread at transaction start. "
                            + "Refusing to proceed: Postgres RLS would otherwise silently "
                            + "return zero rows instead of failing loudly.");
        }
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        // beforeBegin already guaranteed one of these holds; the nil UUID is
        // for the auth-mode case, since TenantContext.get() is empty there.
        UUID clinicId = TenantContext.get().orElseGet(() -> new UUID(0, 0));
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
