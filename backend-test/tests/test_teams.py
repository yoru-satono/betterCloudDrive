"""Black-box tests for /api/v1/teams endpoints."""

import hashlib
import uuid

import pytest

from tests.helpers.assert_helper import assert_api_ok, assert_paginated


def assert_api_error_in(response, expected_codes):
    body = response.json()
    assert body["code"] in expected_codes, f"Expected one of {expected_codes}, got {body}"
    return body


def register_user(client, registration_code_for, prefix="team_user"):
    username = f"{prefix}_{uuid.uuid4().hex[:10]}"
    password = "TestPass123!"
    email = f"{username}@test.local"
    verification_code = registration_code_for(email)

    r = client.post("/api/v1/auth/register", json={
        "username": username,
        "password": password,
        "email": email,
        "verificationCode": verification_code,
    })
    assert_api_ok(r)

    login = client.post("/api/v1/auth/login", json={
        "username": username,
        "password": password,
    })
    data = assert_api_ok(login)
    return {
        "username": username,
        "password": password,
        "email": email,
        "userId": data.get("userId"),
        "headers": {"Authorization": f"Bearer {data['accessToken']}"},
    }


def create_team(client, headers, name=None):
    team_name = name or f"team_{uuid.uuid4().hex[:8]}"
    data = assert_api_ok(client.post("/api/v1/teams", json={"name": team_name}, headers=headers))
    assert data["name"] == team_name
    assert data["role"] == "OWNER"
    return data


def add_member(client, owner_headers, team_id, username, role):
    return assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/members",
        json={"username": username, "role": role},
        headers=owner_headers,
    ))


def create_team_folder(client, headers, team_id, parent_id=None, name=None):
    folder_name = name or f"folder_{uuid.uuid4().hex[:6]}"
    data = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/files/folder",
        json={"parentId": parent_id, "folderName": folder_name},
        headers=headers,
    ))
    assert data["fileName"] == folder_name
    assert data["fileType"] == "folder"
    return data


def upload_team_file(client, headers, team_id, parent_id, sample_file, name=None):
    file_name = name or f"team_{uuid.uuid4().hex[:6]}.txt"
    content = sample_file.read_bytes()
    md5 = hashlib.md5(content).hexdigest()
    init = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/upload/init",
        json={
            "parentId": parent_id,
            "fileName": file_name,
            "fileSize": len(content),
            "md5Hash": md5,
            "totalChunks": 1,
        },
        headers=headers,
    ))
    session_id = init["sessionId"]
    with sample_file.open("rb") as file_handle:
        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/upload/{session_id}/chunk?chunkNumber=0",
            files={"file": file_handle},
            headers=headers,
        ))
    completed = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/upload/{session_id}/complete",
        headers=headers,
    ))
    return {
        "fileId": completed["fileId"],
        "fileName": file_name,
        "content": content,
    }


def assert_team_audit_action(client, headers, team_id, action):
    data = assert_paginated(client.get(
        f"/api/v1/teams/{team_id}/audit-logs?action={action}&page=1&size=100",
        headers=headers,
    ))
    assert any(record["action"] == action for record in data["records"]), (
        f"Audit action {action} not found in {data['records']}"
    )


def assert_page_has_id(page, item_id):
    assert any(item["id"] == item_id for item in page["records"]), (
        f"Item {item_id} not found in page records: {page['records']}"
    )


@pytest.fixture
def extra_user(client, registration_code_for):
    return register_user(client, registration_code_for)


class TestTeamLifecycle:
    def test_create_update_members_and_soft_delete(self, client, auth_headers, test_user):
        team = create_team(client, auth_headers)
        team_id = team["id"]

        listed = assert_api_ok(client.get("/api/v1/teams", headers=auth_headers))
        assert any(item["id"] == team_id for item in listed)

        detail = assert_api_ok(client.get(f"/api/v1/teams/{team_id}", headers=auth_headers))
        assert detail["id"] == team_id
        assert detail["role"] == "OWNER"

        renamed = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}",
            json={"name": f"renamed_{uuid.uuid4().hex[:6]}"},
            headers=auth_headers,
        ))
        assert renamed["id"] == team_id
        assert renamed["name"].startswith("renamed_")

        members = assert_api_ok(client.get(f"/api/v1/teams/{team_id}/members", headers=auth_headers))
        assert any(member["username"] == test_user["username"] and member["role"] == "OWNER" for member in members)

        assert_team_audit_action(client, auth_headers, team_id, "TEAM_CREATE")
        assert_team_audit_action(client, auth_headers, team_id, "TEAM_UPDATE")

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers))

        after_delete = assert_api_ok(client.get("/api/v1/teams", headers=auth_headers))
        assert all(item["id"] != team_id for item in after_delete)
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}", headers=auth_headers), {404})
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}/files", headers=auth_headers), {404})
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}/audit-logs", headers=auth_headers), {404})


class TestTeamPermissions:
    def test_viewer_read_only_and_admin_audit_access(self, client, auth_headers, registration_code_for):
        viewer = register_user(client, registration_code_for, "viewer")
        admin = register_user(client, registration_code_for, "admin")
        outsider = register_user(client, registration_code_for, "outsider")
        team = create_team(client, auth_headers)
        team_id = team["id"]

        add_member(client, auth_headers, team_id, viewer["username"], "VIEWER")
        add_member(client, auth_headers, team_id, admin["username"], "ADMIN")

        viewer_detail = assert_api_ok(client.get(f"/api/v1/teams/{team_id}", headers=viewer["headers"]))
        assert viewer_detail["role"] == "VIEWER"
        assert_paginated(client.get(f"/api/v1/teams/{team_id}/files", headers=viewer["headers"]))

        assert_api_error_in(client.post(
            f"/api/v1/teams/{team_id}/files/folder",
            json={"folderName": "viewer_cannot_write"},
            headers=viewer["headers"],
        ), {403})
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}/audit-logs", headers=viewer["headers"]), {403})

        assert_paginated(client.get(f"/api/v1/teams/{team_id}/audit-logs", headers=admin["headers"]))
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}", headers=outsider["headers"]), {403})


class TestTeamFilesSharesTagsFavoritesAudit:
    def test_team_collaboration_flow_and_audit_logs(self, client, auth_headers, sample_file):
        team = create_team(client, auth_headers)
        team_id = team["id"]
        folder = create_team_folder(client, auth_headers, team_id)
        uploaded = upload_team_file(client, auth_headers, team_id, folder["id"], sample_file)
        file_id = uploaded["fileId"]

        files = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/files?parentId={folder['id']}",
            headers=auth_headers,
        ))
        assert_page_has_id(files, file_id)

        search = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/files/search?q={uploaded['fileName'][:5]}",
            headers=auth_headers,
        ))
        assert_page_has_id(search, file_id)

        preview = client.get(f"/api/v1/teams/{team_id}/preview/{file_id}", headers=auth_headers)
        assert preview.status_code == 200
        assert preview.content == uploaded["content"]

        ticket = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/download/{file_id}/ticket",
            headers=auth_headers,
        ))
        assert ticket["url"].startswith(f"/api/v1/teams/{team_id}/download/{file_id}")
        download = client.get(ticket["url"], headers=auth_headers)
        assert download.status_code == 200
        assert download.content == uploaded["content"]

        assert_api_ok(client.request(
            "DELETE",
            f"/api/v1/teams/{team_id}/files",
            json={"fileIds": [file_id]},
            headers=auth_headers,
        ))
        recycled = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/recycle-bin?q={uploaded['fileName'][:5]}",
            headers=auth_headers,
        ))
        assert_page_has_id(recycled, file_id)
        assert_api_ok(client.post(f"/api/v1/teams/{team_id}/recycle-bin/{file_id}/restore", headers=auth_headers))

        share = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": file_id, "password": "pass1234"},
            headers=auth_headers,
        ))
        assert share["teamId"] == team_id
        assert share["fileId"] == file_id
        assert share["hasPassword"] is True

        shares = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/shares?q={share['shareCode']}",
            headers=auth_headers,
        ))
        assert_page_has_id(shares, share["id"])

        password = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/shares/{share['id']}/password",
            headers=auth_headers,
        ))
        assert password["password"] == "pass1234"

        updated_share = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}/shares/{share['id']}",
            json={"maxVisits": 5},
            headers=auth_headers,
        ))
        assert updated_share["maxVisits"] == 5
        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}/shares/{share['id']}", headers=auth_headers))

        tag = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/tags",
            json={"tagName": f"tag_{uuid.uuid4().hex[:6]}", "color": "#00ff00"},
            headers=auth_headers,
        ))
        assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/tags/{tag['id']}/files",
            json={"fileIds": [file_id]},
            headers=auth_headers,
        ))
        tagged = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/tags/{tag['id']}/files",
            headers=auth_headers,
        ))
        assert_page_has_id(tagged, file_id)

        assert_api_ok(client.post(f"/api/v1/teams/{team_id}/favorites/{file_id}", headers=auth_headers))
        favorite_status = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/favorites/{file_id}/status",
            headers=auth_headers,
        ))
        assert favorite_status is True
        favorites = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/favorites?q={uploaded['fileName'][:5]}",
            headers=auth_headers,
        ))
        assert_page_has_id(favorites, file_id)
        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}/favorites/{file_id}", headers=auth_headers))

        for action in [
            "FILE_CREATE_FOLDER",
            "UPLOAD_COMPLETE",
            "FILE_DELETE",
            "FILE_RESTORE",
            "SHARE_CREATE",
            "SHARE_UPDATE",
            "SHARE_CANCEL",
            "TAG_CREATE",
            "TAG_ATTACH",
            "FAVORITE_ADD",
            "FAVORITE_REMOVE",
        ]:
            assert_team_audit_action(client, auth_headers, team_id, action)
