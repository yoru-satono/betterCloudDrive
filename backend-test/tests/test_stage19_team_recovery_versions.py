"""Stage 19 real-API acceptance journey for team recovery and file versions."""

import subprocess
import uuid
from pathlib import Path

from tests.helpers.assert_helper import assert_api_error, assert_api_ok, assert_paginated
from tests.test_teams import (
    add_member,
    assert_team_audit_action,
    create_team,
    create_team_folder,
    register_user,
    upload_team_file,
    upsert_permission,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


def _seed_team_versions(file_id, user_id):
    sql = f"""
    INSERT INTO file_versions
        (file_id, user_id, version_number, file_size, md5_hash, storage_path, created_at)
    SELECT id, {user_id}, version_number, file_size, md5_hash, storage_path,
           CURRENT_TIMESTAMP - (version_number * INTERVAL '1 minute')
    FROM files CROSS JOIN (VALUES (1), (2)) AS versions(version_number)
    WHERE id = {file_id}
    ON CONFLICT (file_id, version_number) DO NOTHING;
    UPDATE files SET version_count = 3 WHERE id = {file_id};
    """
    result = subprocess.run(
        [
            "docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
            "-U", "bcd_user", "-d", "better_cloud_drive", "-c", sql,
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_team_recycle_bin_versions_permissions_and_three_page_lifecycle(
        client, auth_headers, test_user, registration_code_for, sample_file):
    marker = f"stage19_{uuid.uuid4().hex[:8]}"
    team = create_team(client, auth_headers, marker)
    team_id = team["id"]
    editor = register_user(client, registration_code_for, "stage19_editor")
    viewer = register_user(client, registration_code_for, "stage19_viewer")
    add_member(client, auth_headers, team_id, editor["username"], "EDITOR")
    add_member(client, auth_headers, team_id, viewer["username"], "VIEWER")

    try:
        roots = [
            create_team_folder(client, auth_headers, team_id, name=f"{marker}_{index:03d}")
            for index in range(105)
        ]
        assert_api_ok(client.request(
            "DELETE",
            f"/api/v1/teams/{team_id}/files",
            json={"fileIds": [item["id"] for item in roots]},
            headers=auth_headers,
        ))

        recycled = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                f"/api/v1/teams/{team_id}/recycle-bin",
                params={"q": f"  {marker}  ", "page": page, "size": 50},
                headers=viewer["headers"],
            ), expected_page=page)
            recycled.extend(result["records"])
        recycled_ids = [item["id"] for item in recycled]
        assert len(recycled_ids) == 105
        assert len(set(recycled_ids)) == 105
        assert recycled_ids == sorted(recycled_ids, reverse=True)
        assert "storagePath" not in recycled[0]
        assert "userId" not in recycled[0]
        assert "teamId" not in recycled[0]

        hidden_id = roots[0]["id"]
        upsert_permission(
            client, auth_headers, team_id, hidden_id, "USER",
            subject_user_id=viewer["userId"], read=False,
        )
        filtered = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/recycle-bin",
            params={"q": marker, "page": 1, "size": 100},
            headers=viewer["headers"],
        ))
        assert filtered["total"] == 104
        assert hidden_id not in {item["id"] for item in filtered["records"]}
        assert_api_error(client.post(
            f"/api/v1/teams/{team_id}/recycle-bin/{roots[1]['id']}/restore",
            headers=viewer["headers"],
        ), 403)

        restored_root = roots[-1]
        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/recycle-bin/{restored_root['id']}/restore",
            headers=editor["headers"],
        ))
        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/recycle-bin/{roots[-2]['id']}",
            headers=auth_headers,
        ))
        assert_api_error(client.delete(
            f"/api/v1/teams/{team_id}/recycle-bin/{roots[-3]['id']}",
            headers=viewer["headers"],
        ), 403)

        version_folder = create_team_folder(
            client, auth_headers, team_id, name=f"{marker}_versions",
        )
        uploaded = upload_team_file(
            client, auth_headers, team_id, version_folder["id"], sample_file,
            name=f"{marker}.txt",
        )
        file_id = uploaded["fileId"]
        _seed_team_versions(file_id, test_user["userId"])

        versions = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions",
            headers=viewer["headers"],
        ))
        assert [item["versionNumber"] for item in versions] == [2, 1]
        assert all("storagePath" not in item and "userId" not in item for item in versions)
        download_path = (
            f"/api/v1/teams/{team_id}/files/{file_id}/versions/1/download"
        )
        ranged = client.get(download_path, headers={**viewer["headers"], "Range": "bytes=1-4"})
        assert ranged.status_code == 206
        assert ranged.content == uploaded["content"][1:5]
        assert ranged.headers["Content-Range"] == f"bytes 1-4/{len(uploaded['content'])}"

        assert_api_error(client.post(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions/1/restore",
            headers=viewer["headers"],
        ), 403)
        assert_api_error(client.delete(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions/1",
            headers=viewer["headers"],
        ), 403)
        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions/1/restore",
            headers=editor["headers"],
        ))
        after_restore = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions",
            headers=editor["headers"],
        ))
        assert len(after_restore) == 3
        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions/2",
            headers=editor["headers"],
        ))

        assert_api_ok(client.request(
            "DELETE",
            f"/api/v1/teams/{team_id}/files",
            json={"fileIds": [version_folder["id"]]},
            headers=auth_headers,
        ))
        nested_recycle = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/recycle-bin",
            params={"q": marker, "page": 1, "size": 100},
            headers=auth_headers,
        ))
        nested_ids = {item["id"] for item in nested_recycle["records"]}
        assert version_folder["id"] in nested_ids
        assert file_id not in nested_ids
        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/recycle-bin/{version_folder['id']}/restore",
            headers=editor["headers"],
        ))
        restored_children = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/files",
            params={"parentId": version_folder["id"], "page": 1, "size": 50},
            headers=editor["headers"],
        ))
        assert file_id in {item["id"] for item in restored_children["records"]}

        upsert_permission(
            client, auth_headers, team_id, file_id, "USER",
            subject_user_id=viewer["userId"], read=False,
        )
        assert_api_error(client.get(
            f"/api/v1/teams/{team_id}/files/{file_id}/versions",
            headers=viewer["headers"],
        ), 403)

        assert_api_error(client.delete(
            f"/api/v1/teams/{team_id}/recycle-bin", headers=editor["headers"],
        ), 403)
        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/recycle-bin", headers=auth_headers,
        ))
        emptied = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/recycle-bin",
            params={"q": marker, "page": 1, "size": 50},
            headers=auth_headers,
        ))
        assert emptied["total"] == 0

        for action in [
            "FILE_DELETE", "FILE_RESTORE", "FILE_PERMANENT_DELETE",
            "RECYCLE_EMPTY", "VERSION_RESTORE", "VERSION_DELETE",
        ]:
            assert_team_audit_action(client, auth_headers, team_id, action)
    finally:
        client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
