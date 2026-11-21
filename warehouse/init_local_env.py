"""Create missing local configuration with independent secrets; never replace files.

Run from the repository root. This tool never prints values or connection strings.
"""

import base64
import os
from pathlib import Path
import secrets
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config/env-files"


def read_values(path):
    if not path.exists():
        return {}
    return {key.strip(): value.strip().strip('"').strip("'")
            for line in path.read_text(encoding="utf-8-sig").splitlines()
            if line.strip() and not line.lstrip().startswith("#") and "=" in line
            for key, value in [line.split("=", 1)]}


def ensure(path, template, replacements):
    if path.exists():
        return read_values(path)
    text = template.read_text(encoding="utf-8-sig")
    output = []
    seen = set()
    for line in text.splitlines():
        key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else None
        seen.add(key)
        output.append(f"{key}={replacements[key]}" if key in replacements else line)
    output.extend(f"{key}={value}" for key, value in replacements.items() if key not in seen)
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(output) + "\n")
    if os.name != "nt":
        path.chmod(0o600)
    print(f"Created local configuration: {path.relative_to(ROOT)}")
    return read_values(path)


def secret():
    return secrets.token_hex(32)


def main():
    root = ensure(ROOT / ".env", ROOT / ".env.example", {
        "ONLYOFFICE_JWT_SECRET": secret(), "COMPOSE_PROJECT_NAME": "bcd_warehouse_dev",
    })
    database_password = secret()
    database = ensure(CONFIG / "database.env", CONFIG / "database.env.example", {
        "POSTGRES_PASSWORD": database_password, "DB_PASSWORD": database_password, "DATA_SOURCE_PASS": database_password,
    })
    rabbit_password = secret()
    ensure(CONFIG / "rabbitmq.env", CONFIG / "rabbitmq.env.example", {
        "RABBITMQ_DEFAULT_PASS": rabbit_password, "RABBITMQ_PASSWORD": rabbit_password,
    })
    storage_key, storage_secret = "bcd_" + secrets.token_hex(8), secret()
    ensure(CONFIG / "storage.env", CONFIG / "storage.env.example", {
        "S3_ACCESS_KEY": storage_key, "STORAGE_S3_ACCESS_KEY": storage_key,
        "S3_SECRET_KEY": storage_secret, "STORAGE_S3_SECRET_KEY": storage_secret,
    })
    ensure(CONFIG / "security.env", CONFIG / "security.env.example", {
        key: base64.b64encode(secrets.token_bytes(32)).decode()
        for key in ("JWT_ACCESS_SECRET", "JWT_REFRESH_SECRET", "SHARE_PASSWORD_ENCRYPTION_KEY")
    })
    ensure(CONFIG / "backend.env", CONFIG / "backend.env.example", {"ONLYOFFICE_JWT_SECRET": root["ONLYOFFICE_JWT_SECRET"]})
    ensure(CONFIG / "observability.env", CONFIG / "observability.env.example", {
        "GRAFANA_AUTH_PROXY_SECRET": secret(), "GF_SECURITY_ADMIN_PASSWORD": secret(),
    })
    for name in ("frontend", "mail"):
        ensure(CONFIG / f"{name}.env", CONFIG / f"{name}.env.example", {})
    # Partial warehouse configuration requires explicit repair: generating a new
    # password for only one half of an existing pair would silently break services.
    names = ("warehouse", "warehouse-database", "warehouse-bootstrap", "warehouse-reader")
    existing = [name for name in names if (CONFIG / f"{name}.env").exists()]
    if existing and len(existing) != len(names):
        raise RuntimeError("Partial warehouse configuration exists; reconcile its paired secrets without overwriting files")
    if existing:
        return
    exporter, writer, reader, admin, airflow_database = (secret() for _ in range(5))
    source_db = database["POSTGRES_DB"]
    source_host = database.get("DB_HOST", "postgres")
    source_port = database.get("DB_PORT", "5432")
    source_admin = "postgresql://{}:{}@{}:{}/{}".format(
        quote(database["POSTGRES_USER"], safe=""), quote(database["POSTGRES_PASSWORD"], safe=""),
        source_host, source_port, quote(source_db, safe=""))
    ensure(CONFIG / "warehouse.env", CONFIG / "warehouse.env.example", {
        "WAREHOUSE_SOURCE_DSN": f"postgresql://warehouse_exporter:{exporter}@{source_host}:{source_port}/{quote(source_db, safe='')}",
        "WAREHOUSE_CLICKHOUSE_PASSWORD": writer,
        "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN": f"postgresql+psycopg2://warehouse_airflow:{airflow_database}@warehouse-airflow-postgres:5432/warehouse_airflow",
        "AIRFLOW__CORE__FERNET_KEY": base64.urlsafe_b64encode(secrets.token_bytes(32)).decode(),
        "AIRFLOW__API_AUTH__JWT_SECRET": secret(), "WAREHOUSE_AIRFLOW_ADMIN_PASSWORD": secret(),
    })
    ensure(CONFIG / "warehouse-database.env", CONFIG / "warehouse-database.env.example", {
        "CLICKHOUSE_PASSWORD": admin, "POSTGRES_PASSWORD": airflow_database,
    })
    ensure(CONFIG / "warehouse-bootstrap.env", CONFIG / "warehouse-bootstrap.env.example", {
        "WAREHOUSE_BOOTSTRAP_SOURCE_DSN": source_admin, "WAREHOUSE_EXPORTER_PASSWORD": exporter,
        "WAREHOUSE_CLICKHOUSE_ADMIN_PASSWORD": admin, "WAREHOUSE_WRITER_PASSWORD": writer, "WAREHOUSE_READER_PASSWORD": reader,
    })
    ensure(CONFIG / "warehouse-reader.env", CONFIG / "warehouse-reader.env.example", {"WAREHOUSE_QUERY_PASSWORD": reader})


if __name__ == "__main__":
    main()
