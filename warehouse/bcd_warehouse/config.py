"""Runtime configuration. Connection secrets are never represented in logs."""

from dataclasses import dataclass, field
import os
import re
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class Settings:
    source_dsn: str = field(repr=False)
    clickhouse_host: str = "clickhouse"
    clickhouse_port: int = 8123
    clickhouse_database: str = "bcd_warehouse"
    clickhouse_user: str = "warehouse_writer"
    clickhouse_password: str = field(default="", repr=False)
    business_zone: str = "Asia/Shanghai"
    source_zone: str = "UTC"
    batch_size: int = 1000

    def __post_init__(self):
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", self.clickhouse_database):
            raise ValueError("Invalid warehouse database identifier")
        if not self.source_dsn or not 1 <= self.batch_size <= 10000:
            raise ValueError("Source connection and bounded batch size are required")
        ZoneInfo(self.business_zone)
        ZoneInfo(self.source_zone)

    @classmethod
    def from_env(cls):
        return cls(
            source_dsn=os.environ["WAREHOUSE_SOURCE_DSN"],
            clickhouse_host=os.getenv("WAREHOUSE_CLICKHOUSE_HOST", "clickhouse"),
            clickhouse_port=int(os.getenv("WAREHOUSE_CLICKHOUSE_PORT", "8123")),
            clickhouse_database=os.getenv("WAREHOUSE_CLICKHOUSE_DATABASE", "bcd_warehouse"),
            clickhouse_user=os.getenv("WAREHOUSE_CLICKHOUSE_USER", "warehouse_writer"),
            clickhouse_password=os.environ["WAREHOUSE_CLICKHOUSE_PASSWORD"],
            source_zone=os.getenv("WAREHOUSE_SOURCE_ZONE", "UTC"),
            batch_size=int(os.getenv("WAREHOUSE_BATCH_SIZE", "1000")),
        )
