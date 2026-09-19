package io.github.bbororo5.cloudbilling.access;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PostgresAccessTest {
    private static final String BFF = "billing_bff";
    private static final String BATCH = "billing_batch";
    private static final String USER_A = "00000000-0000-0000-0000-000000000001";
    private static final String USER_B = "00000000-0000-0000-0000-000000000004";
    private static final String NEW_RUN = "00000000-0000-0000-0003-000000000001";

    record Step(String sql, String state, Long count, String scalar) {}
    record Scenario(String role, String name, List<Step> steps) {
        @Override public String toString() { return role + ": " + name; }
    }
    record Table(String name, String column, String bff, String batch) {}

    // Independent allowlist from the approved responsibility matrix, not parsed from GRANTs.
    private static final List<Table> TABLES = List.of(
        new Table("app_user", "email", "S", ""),
        new Table("billing_account", "billing_account_name", "S", "S"),
        new Table("billing_membership", "role", "SIUD", ""),
        new Table("spring_session", "last_access_time", "SIUD", ""),
        new Table("spring_session_attributes", "attribute_bytes", "SIUD", ""),
        new Table("security_audit_event", "reason_code", "I", ""),
        new Table("clickhouse_price_rate_export", "unit_price", "", "S"),
        new Table("settlement_job", "next_attempt_at", "", "SIU"),
        new Table("settlement_attempt", "status", "", "SIU"),
        new Table("settlement_validation", "expected_cost", "", "SI"),
        new Table("monthly_settlement", "billed_cost", "S", "SI"),
        new Table("pricing_sku", "active", "", ""),
        new Table("price_rate", "valid_to", "", ""),
        new Table("producer_credential", "secret_hash", "", "")
    );

    static Step rows(String sql, long n) { return new Step(sql, null, n, null); }
    static Step value(String sql, String value) { return new Step(sql, null, null, value); }
    static Step denied(String sql) { return new Step(sql, "42501", null, null); }
    static Scenario scenario(String role, String name, Step... steps) {
        return new Scenario(role, name, List.of(steps));
    }

    private Connection connect(String role) throws SQLException {
        var c = DriverManager.getConnection(System.getenv("BILLING_ACCESS_TEST_URL"), role, "local-dev-only");
        c.setAutoCommit(false);
        assertEquals(role, scalar(c, "select session_user"));
        assertEquals(role, scalar(c, "select current_user"));
        return c;
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var result = s.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            String value = result.getString(1);
            assertFalse(result.next(), "Expected one result row: " + sql);
            return value;
        }
    }

    private static void scope(Connection c, String tenant, String user) throws SQLException {
        try (var s = c.prepareStatement("select set_config('app.billing_account_id', ?, true), set_config('app.current_user_id', ?, true)")) {
            s.setString(1, tenant);
            s.setString(2, user);
            s.execute();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void actualLoginEnforcesAccess(Scenario test) throws SQLException {
        try (var c = connect(test.role())) {
            try {
                scope(c, "a", test.role().equals(BFF) ? USER_A : "");
                for (var step : test.steps()) {
                    var savepoint = c.setSavepoint();
                    if (step.state() != null) {
                        var error = assertThrows(SQLException.class, () -> {
                            try (var s = c.createStatement()) { s.execute(step.sql()); }
                        }, step.sql());
                        assertEquals(step.state(), error.getSQLState(), step.sql() + ": " + error.getMessage());
                        c.rollback(savepoint);
                    } else if (step.scalar() != null) {
                        assertEquals(step.scalar(), scalar(c, step.sql()), step.sql());
                    } else {
                        try (var s = c.createStatement()) {
                            assertEquals(step.count().longValue(), s.executeUpdate(step.sql()), step.sql());
                        }
                    }
                    c.releaseSavepoint(savepoint);
                }
            } finally { c.rollback(); }
        }
    }

    static Stream<Scenario> scenarios() {
        var cases = new ArrayList<Scenario>();
        for (String role : List.of(BFF, BATCH)) {
            for (var table : TABLES) {
                String allowed = role.equals(BFF) ? table.bff() : table.batch();
                for (char operation : "SIUD".toCharArray()) {
                    if (allowed.indexOf(operation) >= 0) continue;
                    String target = "billing." + table.name();
                    String sql = switch (operation) {
                        case 'S' -> "select * from " + target;
                        case 'I' -> "insert into " + target + " default values";
                        case 'U' -> "update " + target + " set " + table.column() + " = " + table.column() + " where false";
                        default -> "delete from " + target + " where false";
                    };
                    // This join view is rejected as non-updatable before privilege checking.
                    // Independently assert the missing privilege as well; 55000 alone is not proof.
                    if (table.name().equals("clickhouse_price_rate_export") && operation != 'S') {
                        String privilege = switch (operation) {
                            case 'I' -> "INSERT";
                            case 'U' -> "UPDATE";
                            default -> "DELETE";
                        };
                        cases.add(scenario(role, "deny " + operation + " " + table.name(),
                            value("select has_table_privilege(current_user, 'billing.clickhouse_price_rate_export', '" + privilege + "')", "f"),
                            new Step(sql, "55000", null, null)));
                    } else {
                        cases.add(scenario(role, "deny " + operation + " " + table.name(), denied(sql)));
                    }
                }
            }
            cases.add(scenario(role, "own company and final amount",
                value("select billing_account_id from billing.billing_account", "a"),
                value("select billed_cost from billing.monthly_settlement", "100.000000"),
                value("select count(*) from billing.monthly_settlement where billing_account_id = 'b'", "0")));
            cases.add(scenario(role, "cannot assume owner or peer identity",
                denied("set role billing_owner"), denied("set role " + (role.equals(BFF) ? BATCH : BFF))));
            cases.add(scenario(role, "cannot assume guard identities or attach privileged triggers",
                denied("set role billing_membership_guard"),
                denied("set role billing_settlement_guard"),
                rows("create temporary table guard_probe (id integer)", 0),
                denied("create trigger probe before insert on guard_probe for each row execute function billing.protect_last_admin()"),
                denied("create trigger probe before insert on guard_probe for each row execute function billing.enforce_monthly_settlement()"),
                denied("alter function billing.protect_last_admin() security invoker"),
                denied("alter function billing.enforce_monthly_settlement() security invoker")));
        }

        cases.add(scenario(BFF, "user and membership reads",
            value("select email from billing.app_user where user_id = '" + USER_A + "'", "user1@example.test"),
            value("select count(*) from billing.billing_membership", "3")));
        cases.add(scenario(BFF, "membership insert update delete",
            rows("insert into billing.billing_membership (billing_account_id, user_id, role) values ('a', '00000000-0000-0000-0000-000000000007', 'BILLING_ACCOUNT_VIEWER')", 1),
            rows("update billing.billing_membership set role = 'BILLING_ACCOUNT_ADMIN' where user_id = '00000000-0000-0000-0000-000000000003'", 1),
            rows("delete from billing.billing_membership where user_id = '00000000-0000-0000-0000-000000000007'", 1)));
        cases.add(scenario(BFF, "demote admin while another admin remains",
            rows("update billing.billing_membership set role = 'BILLING_ACCOUNT_VIEWER' where user_id = '00000000-0000-0000-0000-000000000002'", 1)));
        cases.add(scenario(BFF, "last admin remains protected with hostile search path",
            rows("create temporary table billing_account (billing_account_id text)", 0),
            rows("create temporary table billing_membership (role text)", 0),
            rows("set local search_path = pg_temp, public", 0),
            rows("update billing.billing_membership set role = 'BILLING_ACCOUNT_VIEWER' where user_id = '00000000-0000-0000-0000-000000000002'", 1),
            new Step("update billing.billing_membership set role = 'BILLING_ACCOUNT_VIEWER' where user_id = '" + USER_A + "'", "23514", null, null),
            value("select count(*) from billing.billing_membership where role = 'BILLING_ACCOUNT_ADMIN'", "1"),
            value("select current_user", BFF)));
        cases.add(scenario(BFF, "other company membership writes",
            rows("update billing.billing_membership set role = 'BILLING_ACCOUNT_ADMIN' where billing_account_id = 'b'", 0),
            rows("delete from billing.billing_membership where billing_account_id = 'b'", 0),
            denied("insert into billing.billing_membership (billing_account_id, user_id, role) values ('b', '00000000-0000-0000-0000-000000000007', 'BILLING_ACCOUNT_VIEWER')"),
            denied("update billing.billing_membership set billing_account_id = 'b' where user_id = '00000000-0000-0000-0000-000000000003'"),
            value("select count(*) from billing.billing_membership", "3")));
        cases.add(scenario(BFF, "session lifecycle",
            rows("insert into billing.spring_session values ('test-primary', 'test-session', 1, 1, 60, 61, 'user1')", 1),
            rows("insert into billing.spring_session_attributes values ('test-primary', 'context', decode('00', 'hex'))", 1),
            value("select count(*) from billing.spring_session_attributes", "1"),
            rows("update billing.spring_session set last_access_time = 2", 1),
            value("select last_access_time from billing.spring_session", "2"),
            rows("update billing.spring_session_attributes set attribute_bytes = decode('01', 'hex')", 1),
            rows("delete from billing.spring_session_attributes", 1),
            rows("delete from billing.spring_session", 1)));
        cases.add(scenario(BFF, "audit append only in own company",
            rows("insert into billing.security_audit_event (audit_event_id, billing_account_id, actor_user_id, event_type, denied_action, reason_code) values ('00000000-0000-0000-0004-000000000001', 'a', '" + USER_A + "', 'ACCESS_DENIED', 'members.write', 'ROLE')", 1),
            denied("insert into billing.security_audit_event (audit_event_id, billing_account_id, actor_user_id, event_type, denied_action, reason_code) values ('00000000-0000-0000-0004-000000000002', 'b', '" + USER_B + "', 'ACCESS_DENIED', 'members.write', 'ROLE')")));

        cases.add(scenario(BATCH, "price export and own settlement history",
            value("select unit_price from billing.clickhouse_price_rate_export", "0.001000000000000000"),
            value("select count(*) from billing.settlement_job", "1"),
            value("select count(*) from billing.settlement_attempt", "1"),
            value("select count(*) from billing.settlement_validation", "1")));
        cases.add(scenario(BATCH, "create and fail an attempt",
            newJob(), newAttempt(),
            rows("update billing.settlement_attempt set status = 'FAILED', error_code = 'TEST', finished_at = now() where run_id = '" + NEW_RUN + "'", 1),
            rows("update billing.settlement_job set next_attempt_at = now() where billing_month = '2026-08-01'", 1),
            value("select status from billing.settlement_attempt where run_id = '" + NEW_RUN + "'", "FAILED")));
        cases.add(scenario(BATCH, "validate and atomically finalize month",
            newJob(), newAttempt(),
            rows("update billing.settlement_attempt set status = 'VALIDATED', finished_at = now() where run_id = '" + NEW_RUN + "'", 1),
            rows("insert into billing.settlement_validation (run_id, billing_account_id, billing_month, expected_cost, recalculated_cost, input_data_as_of) values ('" + NEW_RUN + "', 'a', '2026-08-01', 100, 100, '2026-09-01Z')", 1),
            rows("insert into billing.monthly_settlement (billing_account_id, billing_month, run_id, billed_cost) values ('a', '2026-08-01', '" + NEW_RUN + "', 100)", 1),
            rows("update billing.settlement_job set status = 'FINALIZED', finalized_at = now() where billing_month = '2026-08-01'", 1),
            rows("set constraints all immediate", 0),
            value("select billed_cost from billing.monthly_settlement where billing_month = '2026-08-01'", "100.000000")));
        cases.add(scenario(BATCH, "other company job writes",
            newJob(),
            rows("update billing.settlement_job set next_attempt_at = now() where billing_account_id = 'b'", 0),
            rows("update billing.settlement_attempt set status = 'FAILED', error_code = 'TEST', finished_at = now() where billing_account_id = 'b'", 0),
            denied("insert into billing.settlement_job (billing_account_id, billing_month) values ('b', '2026-08-01')"),
            denied("update billing.settlement_job set billing_account_id = 'b' where billing_month = '2026-08-01'")));
        cases.add(scenario(BATCH, "nonzero validation still blocks finalization",
            newJob(), newAttempt(),
            rows("update billing.settlement_attempt set status = 'VALIDATED', finished_at = now() where run_id = '" + NEW_RUN + "'", 1),
            rows("insert into billing.settlement_validation (run_id, billing_account_id, billing_month, expected_cost, recalculated_cost, input_data_as_of) values ('" + NEW_RUN + "', 'a', '2026-08-01', 100, 101, '2026-09-01Z')", 1),
            new Step("insert into billing.monthly_settlement (billing_account_id, billing_month, run_id, billed_cost) values ('a', '2026-08-01', '" + NEW_RUN + "', 101)", "23514", null, null),
            value("select count(*) from billing.monthly_settlement where billing_month = '2026-08-01'", "0"),
            value("select current_user", BATCH)));
        return cases.stream();
    }

    static Step newJob() {
        return rows("insert into billing.settlement_job (billing_account_id, billing_month) values ('a', '2026-08-01')", 1);
    }
    static Step newAttempt() {
        return rows("insert into billing.settlement_attempt (run_id, billing_account_id, billing_month, attempt_number, status) values ('" + NEW_RUN + "', 'a', '2026-08-01', 1, 'RUNNING')", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {BFF, BATCH})
    void connectionContextDoesNotSurviveTransaction(String role) throws SQLException {
        try (var c = connect(role)) {
            scope(c, "a", role.equals(BFF) ? USER_A : "");
            assertEquals("a", scalar(c, "select billing_account_id from billing.billing_account"));
            c.commit();
            assertEquals("0", scalar(c, "select count(*) from billing.billing_account"));
            assertEquals("0", scalar(c, "select count(*) from billing.monthly_settlement"));
            if (role.equals(BATCH)) {
                for (String table : List.of("settlement_job", "settlement_attempt", "settlement_validation")) {
                    assertEquals("0", scalar(c, "select count(*) from billing." + table));
                }
            }
            String unscopedInsert = role.equals(BFF)
                ? "insert into billing.billing_membership (billing_account_id, user_id, role) values ('a', '00000000-0000-0000-0000-000000000007', 'BILLING_ACCOUNT_VIEWER')"
                : "insert into billing.settlement_job (billing_account_id, billing_month) values ('a', '2026-08-01')";
            var noScope = c.setSavepoint();
            var rejected = assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) { s.execute(unscopedInsert); }
            });
            assertEquals("42501", rejected.getSQLState());
            c.rollback(noScope);
            if (role.equals(BFF)) {
                assertEquals("0", scalar(c, "select count(*) from billing.billing_membership"));
                scope(c, "", USER_B);
                assertEquals("1", scalar(c, "select count(*) from billing.billing_membership"));
                assertEquals(USER_B, scalar(c, "select user_id from billing.billing_membership"));
            }
            scope(c, "b", role.equals(BFF) ? USER_B : "");
            assertEquals("b", scalar(c, "select billing_account_id from billing.billing_account"));
            c.rollback();
            assertEquals("0", scalar(c, "select count(*) from billing.billing_account"));
            c.rollback();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {BFF, BATCH})
    void loginHasNoPrivilegeEscalationPath(String role) throws SQLException {
        try (var c = connect(role)) {
            assertEquals("0", scalar(c, "select count(*) from pg_roles where rolname = current_user and (rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication)"));
            assertEquals("0", scalar(c, "select count(*) from pg_roles where rolname <> current_user and pg_has_role(current_user, oid, 'MEMBER')"));
            assertEquals("0", scalar(c, "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'billing' and pg_has_role(current_user, c.relowner, 'MEMBER')"));
            c.rollback();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {BFF, BATCH})
    void grantedOperationsMatchIndependentAllowlist(String role) throws SQLException {
        try (var c = connect(role)) {
            for (var table : TABLES) {
                String allowed = role.equals(BFF) ? table.bff() : table.batch();
                for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    boolean expected = privilege.length() > 0 && "SIUD".indexOf(privilege.charAt(0)) >= 0
                        && allowed.indexOf(privilege.charAt(0)) >= 0;
                    assertEquals(expected ? "t" : "f", scalar(c,
                        "select has_table_privilege(current_user, 'billing." + table.name() + "', '" + privilege + "')"),
                        role + " " + table.name() + " " + privilege);
                    if (List.of("SELECT", "INSERT", "UPDATE", "REFERENCES").contains(privilege)) {
                        assertEquals(expected ? "t" : "f", scalar(c,
                            "select has_any_column_privilege(current_user, 'billing." + table.name() + "', '" + privilege + "')"),
                            "Column grants must not bypass the table allowlist: " + table.name() + " " + privilege);
                    }
                }
            }
            c.rollback();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {BFF, BATCH})
    void guardsHaveBoundedAuthority(String role) throws SQLException {
        try (var c = connect(role)) {
            for (String function : List.of("protect_last_admin", "enforce_monthly_settlement")) {
                String owner = function.equals("protect_last_admin") ? "billing_membership_guard" : "billing_settlement_guard";
                assertEquals(owner, scalar(c, "select pg_get_userbyid(proowner) from pg_proc where oid = 'billing." + function + "()'::regprocedure"));
                assertEquals("t", scalar(c, "select prosecdef and 'search_path=\"\"' = any(proconfig) and 'row_security=on' = any(proconfig) from pg_proc where oid = 'billing." + function + "()'::regprocedure"));
                assertEquals("f", scalar(c, "select has_function_privilege(current_user, 'billing." + function + "()', 'EXECUTE')"));
                assertEquals("0", scalar(c, "select count(*) from pg_roles where rolname = '" + owner + "' and (rolcanlogin or rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication)"));
                assertEquals("0", scalar(c, "select count(*) from pg_roles where rolname <> '" + owner + "' and pg_has_role('" + owner + "', oid, 'MEMBER')"));
                assertEquals("0", scalar(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='billing' and pg_has_role('" + owner + "', c.relowner, 'MEMBER')"));
            }
            c.rollback();
        }
    }
}
