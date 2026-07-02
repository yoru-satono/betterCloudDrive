"""Black-box tests for /api/v1/teams endpoints."""

import base64
import hashlib
import json
import re
import uuid

import pytest

from tests.helpers.assert_helper import assert_api_ok, assert_paginated


def _decode_user_id(token):
    try:
        payload = token.split(".")[1]
        payload += "=" * ((4 - len(payload) % 4) % 4)
        return int(json.loads(base64.urlsafe_b64decode(payload))["sub"])
    except Exception:
        return None


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
    access_token = data["accessToken"]
    return {
        "username": username,
        "password": password,
        "email": email,
        "userId": data.get("userId") or _decode_user_id(access_token),
        "headers": {"Authorization": f"Bearer {access_token}"},
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


def upsert_permission(
        client,
        headers,
        team_id,
        file_id,
        subject_type,
        *,
        subject_user_id=None,
        subject_role=None,
        read=True,
        write=False,
        share=False,
        delete=False,
        manage=False):
    return assert_api_ok(client.put(
        f"/api/v1/teams/{team_id}/files/{file_id}/permissions",
        json={
            "subjectType": subject_type,
            "subjectUserId": subject_user_id,
            "subjectRole": subject_role,
            "canRead": read,
            "canWrite": write,
            "canShare": share,
            "canDelete": delete,
            "canManage": manage,
        },
        headers=headers,
    ))


def create_invite(client, owner_headers, team_id, email, role):
    data = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/invites",
        json={"email": email, "role": role},
        headers=owner_headers,
    ))
    assert data["teamId"] == team_id
    assert data["email"] == email.lower()
    assert data["role"] == role
    assert data["status"] == "PENDING"
    return data


def invite_token_from_notifications(client, invited_headers, team_id):
    notifications = assert_paginated(client.get(
        "/api/v1/notifications?type=TEAM_INVITE&page=1&size=20",
        headers=invited_headers,
    ))
    for notification in notifications["records"]:
        if notification.get("teamId") != team_id:
            continue
        match = re.search(r"team-invites/([A-Za-z0-9]+)", notification.get("link") or "")
        if match:
            return match.group(1)
    raise AssertionError(f"Invite notification for team {team_id} not found: {notifications['records']}")


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


def assert_member_role(client, headers, team_id, username, role):
    members = assert_api_ok(client.get(f"/api/v1/teams/{team_id}/members", headers=headers))
    member = next((item for item in members if item["username"] == username), None)
    assert member is not None, f"Member {username} not found in {members}"
    assert member["role"] == role
    return member


def assert_effective_permissions(client, headers, team_id, file_id, **expected):
    data = assert_api_ok(client.get(
        f"/api/v1/teams/{team_id}/files/{file_id}/effective-permission",
        headers=headers,
    ))
    for key, value in expected.items():
        assert data[key] is value, f"Expected {key}={value}, got {data}"
    return data


def assert_office_editable(client, headers, team_id, file_id, expected):
    config = assert_api_ok(client.get(
        f"/api/v1/teams/{team_id}/office/files/{file_id}/config",
        headers=headers,
    ))
    assert config["editable"] is expected
    editor_config = config["config"]["editorConfig"]
    assert editor_config["mode"] == ("edit" if expected else "view")
    return config


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

    @pytest.mark.admin
    def test_full_lifecycle_invite_permissions_delete_and_restore(
            self, client, auth_headers, admin_headers, registration_code_for, sample_file):
        editor = register_user(client, registration_code_for, "lifecycle_editor")
        rejecter = register_user(client, registration_code_for, "lifecycle_rejecter")
        team = create_team(client, auth_headers)
        team_id = team["id"]

        create_invite(client, auth_headers, team_id, rejecter["email"], "VIEWER")
        reject_token = invite_token_from_notifications(client, rejecter["headers"], team_id)
        reject_detail = assert_api_ok(client.get(f"/api/v1/team-invites/{reject_token}", headers=rejecter["headers"]))
        assert reject_detail["status"] == "PENDING"
        assert_api_ok(client.post(f"/api/v1/team-invites/{reject_token}/reject", headers=rejecter["headers"]))
        rejected_invites = assert_api_ok(client.get(f"/api/v1/teams/{team_id}/invites", headers=auth_headers))
        assert any(invite["email"] == rejecter["email"] and invite["status"] == "REJECTED"
                   for invite in rejected_invites)

        create_invite(client, auth_headers, team_id, editor["email"], "EDITOR")
        accept_token = invite_token_from_notifications(client, editor["headers"], team_id)
        accept_detail = assert_api_ok(client.get(f"/api/v1/team-invites/{accept_token}", headers=editor["headers"]))
        assert accept_detail["role"] == "EDITOR"
        assert_api_ok(client.post(f"/api/v1/team-invites/{accept_token}/accept", headers=editor["headers"]))
        editor_member = assert_member_role(client, auth_headers, team_id, editor["username"], "EDITOR")

        folder = create_team_folder(client, editor["headers"], team_id)
        uploaded = upload_team_file(client, editor["headers"], team_id, folder["id"], sample_file)
        file_id = uploaded["fileId"]

        updated_member = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}/members/{editor_member['userId']}",
            json={"role": "VIEWER"},
            headers=auth_headers,
        ))
        assert updated_member["role"] == "VIEWER"
        assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/files?parentId={folder['id']}",
            headers=editor["headers"],
        ))
        assert_api_error_in(client.post(
            f"/api/v1/teams/{team_id}/files/folder",
            json={"folderName": f"blocked_{uuid.uuid4().hex[:6]}"},
            headers=editor["headers"],
        ), {403})

        share = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": file_id},
            headers=auth_headers,
        ))
        assert share["fileId"] == file_id

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

        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/members/{editor_member['userId']}",
            headers=auth_headers,
        ))
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}", headers=editor["headers"]), {403})

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers))
        assert_api_error_in(client.get(f"/api/v1/teams/{team_id}", headers=auth_headers), {404})
        restored = assert_api_ok(client.post(f"/api/v1/admin/teams/{team_id}/restore", headers=admin_headers))
        assert restored["id"] == team_id
        assert restored["deletedAt"] is None
        restored_detail = assert_api_ok(client.get(f"/api/v1/teams/{team_id}", headers=auth_headers))
        assert restored_detail["id"] == team_id

        for action in [
            "TEAM_CREATE",
            "INVITE_CREATE",
            "INVITE_REJECT",
            "INVITE_ACCEPT",
            "FILE_CREATE_FOLDER",
            "UPLOAD_COMPLETE",
            "MEMBER_ROLE_UPDATE",
            "SHARE_CREATE",
            "FILE_DELETE",
            "FILE_RESTORE",
            "MEMBER_REMOVE",
            "TEAM_DELETE",
            "TEAM_RESTORE",
        ]:
            assert_team_audit_action(client, auth_headers, team_id, action)

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers))
        assert_api_ok(client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers))


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

    @pytest.mark.admin
    def test_permission_matrix_overrides_inheritance_and_office_editing(
            self, client, auth_headers, admin_headers, test_user, registration_code_for, sample_file):
        team_admin = register_user(client, registration_code_for, "matrix_admin")
        editor = register_user(client, registration_code_for, "matrix_editor")
        viewer = register_user(client, registration_code_for, "matrix_viewer")
        team = create_team(client, auth_headers)
        team_id = team["id"]
        add_member(client, auth_headers, team_id, team_admin["username"], "ADMIN")
        add_member(client, auth_headers, team_id, editor["username"], "EDITOR")
        add_member(client, auth_headers, team_id, viewer["username"], "VIEWER")

        folder = create_team_folder(client, auth_headers, team_id)
        uploaded = upload_team_file(client, auth_headers, team_id, folder["id"], sample_file, name="matrix.docx")
        file_id = uploaded["fileId"]

        assert_effective_permissions(
            client, team_admin["headers"], team_id, file_id,
            canRead=True, canWrite=True, canShare=True, canDelete=True, canManage=True)
        assert_effective_permissions(
            client, editor["headers"], team_id, file_id,
            canRead=True, canWrite=True, canShare=True, canDelete=True, canManage=False)
        assert_effective_permissions(
            client, viewer["headers"], team_id, file_id,
            canRead=True, canWrite=False, canShare=False, canDelete=False, canManage=False)

        upsert_permission(
            client, auth_headers, team_id, folder["id"], "ROLE",
            subject_role="EDITOR", read=True, write=False, share=False, delete=False, manage=False)
        assert_effective_permissions(
            client, editor["headers"], team_id, file_id,
            canRead=True, canWrite=False, canShare=False, canDelete=False, canManage=False)
        assert_api_error_in(client.put(
            f"/api/v1/teams/{team_id}/files/{file_id}",
            json={"newName": "editor-blocked.docx"},
            headers=editor["headers"],
        ), {403})
        assert_office_editable(client, editor["headers"], team_id, file_id, False)

        viewer_role_permission = upsert_permission(
            client, auth_headers, team_id, file_id, "ROLE",
            subject_role="VIEWER", read=True, write=True, share=False, delete=False, manage=False)
        assert viewer_role_permission["subjectRole"] == "VIEWER"
        assert_effective_permissions(
            client, viewer["headers"], team_id, file_id,
            canRead=True, canWrite=True, canShare=False, canDelete=False, canManage=False)
        assert_office_editable(client, viewer["headers"], team_id, file_id, True)
        renamed = assert_api_ok(client.put(
            f"/api/v1/teams/{team_id}/files/{file_id}",
            json={"newName": "matrix-viewer.docx"},
            headers=viewer["headers"],
        ))
        assert renamed["fileName"] == "matrix-viewer.docx"
        assert_api_error_in(client.post(
            f"/api/v1/teams/{team_id}/shares",
            json={"fileId": file_id},
            headers=viewer["headers"],
        ), {403})

        viewer_user_permission = upsert_permission(
            client, auth_headers, team_id, file_id, "USER",
            subject_user_id=viewer["userId"], read=True, write=False, share=False, delete=False, manage=False)
        assert viewer_user_permission["subjectUserId"] == viewer["userId"]
        assert_effective_permissions(
            client, viewer["headers"], team_id, file_id,
            canRead=True, canWrite=False, canShare=False, canDelete=False, canManage=False)
        assert_office_editable(client, viewer["headers"], team_id, file_id, False)
        assert_api_error_in(client.put(
            f"/api/v1/teams/{team_id}/files/{file_id}",
            json={"newName": "viewer-blocked.docx"},
            headers=viewer["headers"],
        ), {403})

        permissions = assert_api_ok(client.get(
            f"/api/v1/teams/{team_id}/files/{file_id}/permissions",
            headers=auth_headers,
        ))
        assert any(item["id"] == viewer_role_permission["id"] for item in permissions)
        assert any(item["id"] == viewer_user_permission["id"] for item in permissions)

        assert_api_error_in(client.put(
            f"/api/v1/teams/{team_id}/files/{file_id}/permissions",
            json={
                "subjectType": "ROLE",
                "subjectRole": "VIEWER",
                "canRead": False,
                "canWrite": True,
                "canShare": False,
                "canDelete": False,
                "canManage": False,
            },
            headers=auth_headers,
        ), {400})
        assert_api_error_in(client.put(
            f"/api/v1/teams/{team_id}/files/{file_id}/permissions",
            json={
                "subjectType": "USER",
                "subjectUserId": test_user["userId"],
                "canRead": True,
                "canWrite": True,
                "canShare": True,
                "canDelete": True,
                "canManage": True,
            },
            headers=auth_headers,
        ), {400})
        assert_api_error_in(client.put(
            f"/api/v1/teams/{team_id}/files/{file_id}/permissions",
            json={
                "subjectType": "ROLE",
                "subjectRole": "ADMIN",
                "canRead": True,
                "canWrite": True,
                "canShare": True,
                "canDelete": True,
                "canManage": False,
            },
            headers=team_admin["headers"],
        ), {400})

        assert_api_ok(client.delete(
            f"/api/v1/teams/{team_id}/files/{file_id}/permissions/{viewer_user_permission['id']}",
            headers=auth_headers,
        ))
        assert_effective_permissions(
            client, viewer["headers"], team_id, file_id,
            canRead=True, canWrite=True, canShare=False, canDelete=False, canManage=False)

        for action in ["PERMISSION_UPDATE", "PERMISSION_DELETE"]:
            assert_team_audit_action(client, auth_headers, team_id, action)

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers))
        assert_api_ok(client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers))


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
