"""Real isolated infrastructure; teardown removes only this test run's containers."""

from datetime import datetime, timezone
import os
from pathlib import Path
import subprocess
import time
from uuid import uuid4

import clickhouse_connect
import psycopg
import pytest

from bcd_warehouse.config import Settings

ROOT = Path(__file__).resolve().parents[2]


def docker(*arguments, env=None):
    return subprocess.run(["docker", *arguments], check=True, text=True, capture_output=True, env=env).stdout.strip()


def mapped_port(name, container_port):
    return int(docker("port", name, f"{container_port}/tcp").splitlines()[0].rsplit(":", 1)[1])


def await_ready(connect):
    deadline = time.monotonic() + 90
    while True:
        try:
            return connect()
        except Exception:
            if time.monotonic() >= deadline:
                raise RuntimeError("warehouse_test_container_not_ready") from None
            time.sleep(0.25)


@pytest.fixture(scope="session")
def infrastructure():
    run_id = uuid4().hex[:12]
    postgres_name, clickhouse_name = f"bcd-warehouse-it-pg-{run_id}", f"bcd-warehouse-it-ch-{run_id}"
    password = uuid4().hex
    environment = dict(os.environ, POSTGRES_PASSWORD=password, CLICKHOUSE_PASSWORD=password)
    started = []
    try:
        docker("run", "--detach", "--rm", "--name", postgres_name, "--label", f"bcd.test_run={run_id}",
               "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_DB=warehouse_test", "-P", "postgres:18.4-alpine", env=environment)
        started.append(postgres_name)
        docker("run", "--detach", "--rm", "--name", clickhouse_name, "--label", f"bcd.test_run={run_id}",
               "-e", "CLICKHOUSE_PASSWORD", "-e", "CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1",
               "--memory", "2g", "-P", "clickhouse/clickhouse-server:26.8.41.14", env=environment)
        started.append(clickhouse_name)
        port = mapped_port(postgres_name, 5432)
        dsn = f"postgresql://postgres:{password}@127.0.0.1:{port}/warehouse_test"
        source = await_ready(lambda: psycopg.connect(dsn, autocommit=True))
        for migration in ("V1__schema.sql", "V2__warehouse_capture.sql"):
            source.execute((ROOT / "backend/betterclouddrive-start/src/main/resources/db/migration" / migration).read_text(), prepare=False)
        source.execute("""
            INSERT INTO users (username, password_hash) VALUES ('warehouse-test-user', 'test-only');
            INSERT INTO teams (name, owner_id) VALUES ('warehouse-test-team', 1);
            INSERT INTO team_members (team_id, user_id, role) VALUES (1, 1, 'OWNER');
            INSERT INTO files (user_id, file_name, file_type, file_size, storage_path)
                VALUES (1, 'warehouse-test-personal', 'file', 7, 'test-only');
            INSERT INTO files (user_id, team_id, file_name, file_type, file_size, storage_path)
                VALUES (1, 1, 'warehouse-test-team-file', 'file', 11, 'test-only');
            INSERT INTO files (user_id, file_name, file_type, file_size, is_deleted, deleted_at)
                VALUES (1, 'warehouse-test-trash', 'file', 13, CURRENT_TIMESTAMP IS NOT NULL, CURRENT_TIMESTAMP);
            INSERT INTO file_versions (file_id, user_id, version_number, file_size, storage_path, md5_hash)
                VALUES (1, 1, 1, 17, 'test-only', repeat('a', 32));
            INSERT INTO share_links (user_id, file_id, share_code) VALUES (1, 1, 'test-only');
            """, prepare=False)
        source.close()
        clickhouse_port = mapped_port(clickhouse_name, 8123)
        client = await_ready(lambda: clickhouse_connect.get_client(host="127.0.0.1", port=clickhouse_port,
                                                                  username="default", password=password))
        client.close()
        yield dsn, clickhouse_port, password
    finally:
        for name in reversed(started):
            docker("rm", "--force", name)


@pytest.fixture
def warehouse(infrastructure, monkeypatch, tmp_path):
    dsn, port, password = infrastructure
    database = "bcd_it_" + uuid4().hex
    admin = clickhouse_connect.get_client(host="127.0.0.1", port=port, username="default", password=password)
    admin.command(f"CREATE DATABASE {database}")
    admin.close()
    client = clickhouse_connect.get_client(host="127.0.0.1", port=port, username="default", password=password, database=database)
    for statement in (ROOT / "warehouse/sql/001_raw.sql").read_text().split(";"):
        if statement.strip():
            client.command(statement)
    source = psycopg.connect(dsn, autocommit=True)
    source.execute("TRUNCATE warehouse_events")
    settings = Settings(dsn, "127.0.0.1", port, database, "default", password)
    for key, value in {
        "WAREHOUSE_SOURCE_DSN": dsn, "WAREHOUSE_CLICKHOUSE_HOST": "127.0.0.1",
        "WAREHOUSE_CLICKHOUSE_PORT": str(port), "WAREHOUSE_CLICKHOUSE_DATABASE": database,
        "WAREHOUSE_CLICKHOUSE_USER": "default", "WAREHOUSE_CLICKHOUSE_PASSWORD": password,
        "WAREHOUSE_DBT_ARTIFACT_ROOT": str(tmp_path / "dbt"),
    }.items():
        monkeypatch.setenv(key, value)
    try:
        yield source, client, settings
    finally:
        source.close()
        client.close()


def insert_event(source, event_type, *, actor=1, team=None, size=0, attempt=None, mode="NONE", outcome="SUCCEEDED"):
    identity = uuid4()
    source.execute("""
        INSERT INTO warehouse_events (event_id, deduplication_key, event_type, occurred_at,
            actor_user_id, team_id, attempt_id, channel, mode, outcome, byte_count)
        VALUES (%s, %s, %s, %s, %s, %s, %s, 'API', %s, %s, %s)
        """, (identity, str(identity), event_type, datetime.now(timezone.utc), actor, team, attempt, mode, outcome, size))
    return identity
