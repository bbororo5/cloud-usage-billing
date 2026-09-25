package io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Sql {
  interface Mapper<T> {
    T map(ResultSet row) throws SQLException;
  }

  private final JdbcTransactions tx;

  Sql(JdbcTransactions tx) {
    this.tx = tx;
  }

  private PreparedStatement statement(String query, Object... args) throws SQLException {
    var s = tx.connection().prepareStatement(query);
    s.setQueryTimeout(10);
    try {
      for (int i = 0; i < args.length; i++)
        s.setObject(i + 1, args[i] instanceof Instant t ? Timestamp.from(t) : args[i]);
      return s;
    } catch (SQLException e) {
      s.close();
      throw e;
    }
  }

  int update(String query, Object... args) {
    try (var s = statement(query, args)) {
      return s.executeUpdate();
    } catch (SQLException e) {
      throw new JdbcTransactions.StorageFailure(e);
    }
  }

  <T> List<T> list(String query, Mapper<T> mapper, Object... args) {
    try (var s = statement(query, args);
        var r = s.executeQuery()) {
      var result = new ArrayList<T>();
      while (r.next()) result.add(mapper.map(r));
      return result;
    } catch (SQLException e) {
      throw new JdbcTransactions.StorageFailure(e);
    }
  }
}
