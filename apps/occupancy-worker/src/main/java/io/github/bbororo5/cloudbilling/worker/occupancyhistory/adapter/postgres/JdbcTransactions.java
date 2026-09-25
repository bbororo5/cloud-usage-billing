package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.TransactionRunner;
import java.sql.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

public final class JdbcTransactions implements TransactionRunner {
  private final DataSource dataSource;
  private final ThreadLocal<Connection> active = new ThreadLocal<>();

  public JdbcTransactions(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Connection connection() {
    var c = active.get();
    if (c == null) throw new IllegalStateException("No transaction");
    return c;
  }

  public <T> T write(Supplier<T> work) {
    return execute(false, work);
  }

  public <T> T read(Supplier<T> work) {
    return execute(true, work);
  }

  private <T> T execute(boolean readOnly, Supplier<T> work) {
    if (active.get() != null) throw new IllegalStateException("Nested transaction");
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(readOnly);
      c.setTransactionIsolation(
          readOnly
              ? Connection.TRANSACTION_REPEATABLE_READ
              : Connection.TRANSACTION_READ_COMMITTED);
      active.set(c);
      try {
        if (!readOnly)
          try (var s = c.createStatement()) {
            s.execute("set local synchronous_commit=on");
          }
        T value = work.get();
        c.commit();
        return value;
      } catch (RuntimeException | Error | SQLException e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        if (e instanceof SQLException sql) throw new StorageFailure(sql);
        throw e;
      } finally {
        active.remove();
      }
    } catch (SQLException e) {
      throw new StorageFailure(e);
    }
  }

  public static final class StorageFailure extends RuntimeException {
    public StorageFailure(SQLException cause) {
      super("Occupancy storage failure", cause);
    }
  }
}
