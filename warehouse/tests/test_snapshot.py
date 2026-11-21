from datetime import datetime, timezone
from uuid import uuid4

import pytest

from bcd_warehouse.config import Settings
from bcd_warehouse.ingest import DataQualityError, effective_deleted, normalize_snapshot, source_instant


def file(identity, parent=None, deleted=False, team=None, owner=1, kind="file", size=5):
    return dict(id=identity, parent_id=parent, is_deleted=deleted, team_id=team,
                user_id=owner, file_type=kind, file_size=size)


def test_deleted_ancestor_hides_live_child_and_team_members_may_differ():
    rows = [file(1, deleted=True, kind="folder"), file(2, parent=1),
            file(3, team=8, owner=1, kind="folder"), file(4, parent=3, team=8, owner=2)]
    assert effective_deleted(rows) == {1: True, 2: True, 3: False, 4: False}


@pytest.mark.parametrize("rows,reason", [
    ([file(1, parent=1, kind="folder")], "file_ancestor_cycle"),
    ([file(1, parent=2)], "file_parent_missing"),
    ([file(1, kind="folder"), file(2, parent=1, owner=2)], "file_parent_scope_mismatch"),
    ([file(1, kind="folder"), file(2, parent=1, team=8)], "file_parent_scope_mismatch"),
    ([file(1), file(2, parent=1)], "file_parent_scope_mismatch"),
])
def test_invalid_ancestry_stops_publication(rows, reason):
    with pytest.raises(DataQualityError, match=reason):
        effective_deleted(rows)


def test_source_local_timestamp_conversion_does_not_change_instant():
    value = source_instant(datetime(2026, 11, 19, 8), "Asia/Shanghai")
    assert value == datetime(2026, 11, 19, tzinfo=timezone.utc)
    assert source_instant(value, "Asia/Shanghai") == value


def test_snapshot_separates_files_versions_and_zero_byte_folders():
    source = {name: [] for name in ("users", "teams", "members", "files", "versions", "shares")}
    source["files"] = [file(1, kind="folder", size=0), file(2, parent=1, size=7)]
    source["versions"] = [dict(id=3, file_id=2, user_id=1, team_id=None, file_size=11)]
    rows = normalize_snapshot(source, uuid4(), datetime.now(timezone.utc), "UTC")
    assert [(row[2], row[7], row[11]) for row in rows] == [("file", "folder", 0), ("file", "file", 7), ("version", "", 11)]


def test_settings_repr_and_validation_do_not_disclose_credentials():
    settings = Settings("postgresql://user:secret@host/db", clickhouse_password="another-secret")
    assert "secret" not in repr(settings)
    with pytest.raises(ValueError):
        Settings("source", clickhouse_database="name; DROP DATABASE x")
