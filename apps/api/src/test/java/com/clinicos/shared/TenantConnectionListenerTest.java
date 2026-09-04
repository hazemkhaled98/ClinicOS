package com.clinicos.shared;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

/**
 * Unit-level coverage for the {@code afterBegin} failure path that
 * {@code TenantConnectionListenerIT} can't exercise, since it needs a real
 * {@code SQLException} from the {@code SET LOCAL} statement -- not
 * reachable through a healthy Testcontainers Postgres.
 */
class TenantConnectionListenerTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void afterBeginMarksTransactionRollbackOnlyInsteadOfThrowingWhenSetLocalFails() throws SQLException {
        TenantContext.set(UUID.randomUUID());

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("connection reset"));

        TransactionExecution transaction = mock(TransactionExecution.class);
        TenantConnectionListener listener = new TenantConnectionListener(dataSource);

        listener.afterBegin(transaction, null);

        verify(transaction).setRollbackOnly();
    }

    @Test
    void afterBeginDoesNotMarkRollbackOnlyWhenSetLocalSucceeds() throws SQLException {
        TenantContext.set(UUID.randomUUID());

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(false);

        TransactionExecution transaction = mock(TransactionExecution.class);
        TenantConnectionListener listener = new TenantConnectionListener(dataSource);

        listener.afterBegin(transaction, null);

        verify(transaction, never()).setRollbackOnly();
    }
}
