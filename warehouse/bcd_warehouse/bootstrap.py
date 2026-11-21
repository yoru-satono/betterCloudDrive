"""One-shot privileged initialization, isolated from all normal processing services."""

import os
from pathlib import Path

import clickhouse_connect
import psycopg
from psycopg import sql


def main():
    with psycopg.connect(os.environ["WAREHOUSE_BOOTSTRAP_SOURCE_DSN"], autocommit=True) as source:
        source.execute("SET log_min_duration_statement = -1")
        source.execute("SET log_statement = 'none'")
        source.execute("SET log_min_error_statement = 'panic'")
        if source.execute("SELECT to_regclass('public.warehouse_events')").fetchone()[0] is None:
            raise RuntimeError("source_migration_not_ready")
        role = "warehouse_exporter"
        exists = source.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (role,)).fetchone()
        command = "ALTER ROLE" if exists else "CREATE ROLE"
        source.execute(sql.SQL(command + " {} LOGIN PASSWORD {}").format(
            sql.Identifier(role), sql.Literal(os.environ["WAREHOUSE_EXPORTER_PASSWORD"])))
        source.execute(sql.SQL("GRANT USAGE ON SCHEMA public TO {}").format(sql.Identifier(role)))
        for name in ("users", "teams", "members", "files", "versions", "shares"):
            source.execute(sql.SQL("GRANT SELECT ON {} TO {}").format(
                sql.Identifier("warehouse_source_" + name), sql.Identifier(role)))
        source.execute(sql.SQL("GRANT SELECT ON warehouse_events TO {}").format(sql.Identifier(role)))
        source.execute(sql.SQL("GRANT UPDATE (export_batch_id, export_lease_until, exported_at) ON warehouse_events TO {}")
                       .format(sql.Identifier(role)))
    client = clickhouse_connect.get_client(
        host=os.getenv("WAREHOUSE_CLICKHOUSE_HOST", "clickhouse"),
        port=int(os.getenv("WAREHOUSE_CLICKHOUSE_PORT", "8123")),
        username="warehouse_admin", password=os.environ["WAREHOUSE_CLICKHOUSE_ADMIN_PASSWORD"],
        database="bcd_warehouse",
        settings={"log_queries": 0},
    )
    try:
        for statement in (Path(__file__).resolve().parents[1] / "sql/001_raw.sql").read_text().split(";"):
            if statement.strip():
                client.command(statement)
        for role in ("writer", "reader"):
            # Passwords are transmitted as parameters, never shell arguments or emitted SQL.
            client.command(f"CREATE USER IF NOT EXISTS warehouse_{role} IDENTIFIED WITH sha256_password BY {{password:String}}",
                           parameters={"password": os.environ[f"WAREHOUSE_{role.upper()}_PASSWORD"]})
            client.command(f"ALTER USER warehouse_{role} IDENTIFIED WITH sha256_password BY {{password:String}}",
                           parameters={"password": os.environ[f"WAREHOUSE_{role.upper()}_PASSWORD"]})
        client.command("GRANT SELECT, INSERT, ALTER, CREATE TABLE, CREATE VIEW, DROP TABLE, DROP VIEW, TRUNCATE ON bcd_warehouse.* TO warehouse_writer")
        for table in ("tables", "columns", "databases"):
            client.command(f"GRANT SELECT ON system.{table} TO warehouse_writer")
        for table in ("ads_usage_daily", "ads_capacity_daily", "publications", "pipeline_runs"):
            client.command(f"GRANT SELECT ON bcd_warehouse.{table} TO warehouse_reader")
        client.command("""
            ALTER USER warehouse_reader SETTINGS readonly = 1,
                max_execution_time = 5, max_result_rows = 10000, result_overflow_mode = 'throw',
                max_bytes_to_read = 268435456, max_memory_usage = 268435456,
                output_format_json_quote_64bit_integers = 0
            """)
    finally:
        client.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("warehouse_bootstrap_failed; verify service health and local configuration") from None
