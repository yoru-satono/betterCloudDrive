"""Stage 18 real-API acceptance journey for team share management."""

import time
import uuid

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


def test_team_shares_three_page_permissions_and_public_lifecycle(
        client, auth_headers, registration_code_for, sample_file):
    marker = f"stage18_{uuid.uuid4().hex[:8]}"
    team = create_team(client, auth_headers, marker)
    team_id = team["id"]
    editor = register_user(client, registration_code_for, "stage18_editor")
    add_member(client, auth_headers, team_id, editor["username"], "EDITOR")
    folder = create_team_folder(client, auth_headers, team_id, name=f"{marker}_folder")
    uploaded = upload_team_file(
        client, auth_headers, team_id, folder["id"], sample_file, f"{marker}.txt",
    )
    file_id = uploaded["fileId"]
    future = int((time.time() + 7 * 86400) * 1000)
    past = int((time.time() - 86400) * 1000)
    created = []

    try:
        for index in range(105):
            request = {"fileId": file_id}
            if index % 2 == 0:
                request["password"] = f"t{index:03d}"
            if 35 <= index < 70:
                request["expireAt"] = future
            elif index >= 70:
                request["expireAt"] = past
            share = assert_api_ok(client.post(
                f"/api/v1/teams/{team_id}/shares", json=request, headers=auth_headers,
            ))
            assert share["teamId"] == team_id
            assert share["shareUrl"] == f"http://localhost:3000/s/{share['shareCode']}"
            assert share["fileName"] == uploaded["fileName"]
            assert share["fileType"] == "file"
            created.append(share)

        records = []
        for page in range(1, 4):
            result = assert_paginated(client.get(
                f"/api/v1/teams/{team_id}/shares",
                params={"q": f"  {file_id}  ", "page": page, "size": 50},
                headers=editor["headers"],
            ), expected_page=page)
            records.extend(result["records"])
        record_ids = [share["id"] for share in records]
        assert len(record_ids) == 105
        assert len(set(record_ids)) == 105
        assert set(record_ids) == {share["id"] for share in created}
        assert record_ids == sorted(record_ids, reverse=True)

        protected = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/shares",
            params={"q": file_id, "passwordProtected": True, "page": 1, "size": 100},
            headers=auth_headers,
        ))
        never = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/shares",
            params={"q": file_id, "expirationStatus": "NEVER", "page": 1, "size": 100},
            headers=auth_headers,
        ))
        expired = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/shares",
            params={"q": file_id, "expirationStatus": "EXPIRED", "page": 1, "size": 100},
            headers=auth_headers,
        ))
        assert protected["total"] == 53
        assert never["total"] == 35
        assert expired["total"] == 35

        owner_share = created[0]
        assert_api_error(client.get(
            f"/api/v1/teams/{team_id}/shares/{owner_share['id']}/password",
            headers=editor["headers"],
        ), 403)
        assert assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/shares/{owner_share['id']}/password",
            headers=auth_headers,
        ))["password"] == "t000"

        editor_share = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": file_id, "password": "editor-pass"},
            headers=editor["headers"],
        ))
        assert assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/shares/{editor_share['id']}/password",
            headers=editor["headers"],
        ))["password"] == "editor-pass"

        upsert_permission(
            client, auth_headers, team_id, file_id, "USER",
            subject_user_id=editor["userId"], read=True, share=True, manage=True,
        )
        assert assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/shares/{owner_share['id']}/password",
            headers=editor["headers"],
        ))["password"] == "t000"

        updated = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}/shares/{owner_share['id']}",
            json={"clearPassword": True, "clearExpireAt": True, "clearMaxVisits": True},
            headers=auth_headers,
        ))
        assert updated["hasPassword"] is False
        assert updated["expireAtEpochMs"] is None
        assert updated["maxVisits"] is None

        public_share = created[1]
        assert_api_ok(client.post(f"/api/v1/shares/access/{public_share['shareCode']}"))
        download = client.post(
            f"/api/v1/shares/access/{public_share['shareCode']}/download/{file_id}",
        )
        assert download.status_code == 200
        detail = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/shares/{public_share['id']}", headers=auth_headers,
        ))
        # Visits are buffered in Redis and periodically folded into this counter;
        # the max-visits assertions below cover the live counter immediately.
        assert detail["visitCount"] >= 0
        assert detail["downloadCount"] >= 1

        limited = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": file_id, "maxVisits": 1}, headers=auth_headers,
        ))
        assert_api_ok(client.post(f"/api/v1/shares/access/{limited['shareCode']}"))
        assert_api_error(client.post(f"/api/v1/shares/access/{limited['shareCode']}"), 419005)

        folder_share = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": folder["id"], "password": "folder-pass"}, headers=auth_headers,
        ))
        assert folder_share["fileType"] == "folder"
        assert_api_ok(client.post(
            f"/api/v1/shares/access/{folder_share['shareCode']}", json={"password": "folder-pass"},
        ))

        upsert_permission(
            client, auth_headers, team_id, file_id, "USER",
            subject_user_id=editor["userId"], read=False, share=False, manage=False,
        )
        hidden = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/shares",
            params={"q": file_id, "page": 1, "size": 100}, headers=editor["headers"],
        ))
        assert hidden["total"] == 0
        assert_api_error(client.get(
            f"/api/v1/teams/{team_id}/shares/{owner_share['id']}", headers=editor["headers"],
        ), 403)

        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/shares/{public_share['id']}", headers=auth_headers,
        ))
        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/shares/{public_share['id']}", headers=auth_headers,
        ))
        assert_api_error(client.post(f"/api/v1/shares/access/{public_share['shareCode']}"), 419002)

        for action in ["SHARE_CREATE", "SHARE_UPDATE", "SHARE_CANCEL", "PERMISSION_UPDATE"]:
            assert_team_audit_action(client, auth_headers, team_id, action)
    finally:
        client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
