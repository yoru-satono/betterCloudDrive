"""Tests for /api/v1/admin endpoints (requires ROLE_ADMIN).

The admin_user fixture creates and promotes an isolated test account when ADMIN_TOKEN is unset.
"""

import uuid

from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated


def assert_page_has_id(page, item_id):
    assert any(item["id"] == item_id for item in page["records"]), (
        f"Item {item_id} not found in page records: {page['records']}"
    )


class TestAdminAccess:
    def test_admin_access_denied_for_normal_user(self, client, auth_headers):
        r = client.get("/api/v1/admin/stats", headers=auth_headers)
        # Spring Security denies with 403 (may not have JSON body)
        assert r.status_code == 403


class TestUserManagement:
    def test_list_users(self, client, admin_headers):
        r = client.get("/api/v1/admin/users", headers=admin_headers)
        assert_paginated(r)

    def test_list_users_keyword_filter(self, client, admin_headers, test_user):
        r = client.get(f"/api/v1/admin/users?keyword={test_user['username'][:5]}", headers=admin_headers)
        assert_paginated(r)

    def test_list_users_status_filter(self, client, admin_headers):
        r = client.get("/api/v1/admin/users?status=1", headers=admin_headers)
        assert_paginated(r)

    def test_list_users_pagination(self, client, admin_headers):
        data = assert_paginated(client.get("/api/v1/admin/users?page=1&size=2", headers=admin_headers))
        assert data["size"] == 2

    def test_update_user_status(self, client, admin_headers, admin_user):
        r = client.patch(f"/api/v1/admin/users/{admin_user['userId']}/status", json={
            "status": 1
        }, headers=admin_headers)
        assert_api_ok(r)


class TestAdminFiles:
    def test_list_user_files(self, client, admin_headers, admin_user):
        r = client.get(f"/api/v1/admin/users/{admin_user['userId']}/files", headers=admin_headers)
        assert_paginated(r)

    def test_admin_get_file_nonexistent(self, client, admin_headers):
        assert_api_error(client.get("/api/v1/admin/files/99999", headers=admin_headers), 404001)


class TestAdminLogs:
    def test_list_logs(self, client, admin_headers):
        assert_paginated(client.get("/api/v1/admin/logs", headers=admin_headers))


class TestAdminStats:
    def test_get_stats(self, client, admin_headers):
        data = assert_api_ok(client.get("/api/v1/admin/stats", headers=admin_headers))
        assert "totalUsers" in data
        assert "activeUsers" in data
        assert "totalStorageUsed" in data

    def test_update_user_quota(self, client, admin_headers, admin_user):
        r = client.patch(f"/api/v1/admin/users/{admin_user['userId']}/quota", json={
            "storageQuota": 21474836480  # 20GB
        }, headers=admin_headers)
        assert_api_ok(r)


class TestAdminTeams:
    def test_admin_team_detail_members_files_audit_and_quota(self, client, admin_headers):
        team_name = f"admin_team_{uuid.uuid4().hex[:8]}"
        team = assert_api_ok(client.post("/api/v1/teams", json={"name": team_name}, headers=admin_headers))
        team_id = team["id"]

        detail = assert_api_ok(client.get(f"/api/v1/admin/teams/{team_id}", headers=admin_headers))
        assert detail["id"] == team_id

        members = assert_api_ok(client.get(f"/api/v1/admin/teams/{team_id}/members", headers=admin_headers))
        assert any(member["role"] == "OWNER" for member in members)

        folder = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/files/folder",
            json={"parentId": None, "folderName": f"admin-folder-{uuid.uuid4().hex[:6]}"},
            headers=admin_headers,
        ))
        files = assert_paginated(client.get(f"/api/v1/admin/teams/{team_id}/files", headers=admin_headers))
        assert any(item["id"] == folder["id"] for item in files["records"])

        new_quota = 32212254720
        quota = assert_api_ok(client.patch(
            f"/api/v1/admin/teams/{team_id}/quota",
            json={"storageQuota": new_quota},
            headers=admin_headers,
        ))
        assert quota["storageQuota"] == new_quota

        audit = assert_paginated(client.get(
            f"/api/v1/admin/teams/{team_id}/audit-logs?action=QUOTA_UPDATE",
            headers=admin_headers,
        ))
        assert any(item["action"] == "QUOTA_UPDATE" for item in audit["records"])

    def test_admin_team_full_lifecycle(self, client, admin_headers):
        team_name = f"admin_lifecycle_{uuid.uuid4().hex[:8]}"
        team = assert_api_ok(client.post("/api/v1/teams", json={"name": team_name}, headers=admin_headers))
        team_id = team["id"]

        active = assert_paginated(client.get(
            f"/api/v1/admin/teams?keyword={team_name}&status=active",
            headers=admin_headers,
        ))
        assert_page_has_id(active, team_id)

        detail = assert_api_ok(client.get(f"/api/v1/admin/teams/{team_id}", headers=admin_headers))
        assert detail["id"] == team_id
        assert detail["name"] == team_name

        members = assert_api_ok(client.get(f"/api/v1/admin/teams/{team_id}/members", headers=admin_headers))
        assert any(member["role"] == "OWNER" for member in members)

        folder = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/files/folder",
            json={"parentId": None, "folderName": f"admin-full-{uuid.uuid4().hex[:6]}"},
            headers=admin_headers,
        ))
        files = assert_paginated(client.get(
            f"/api/v1/admin/teams/{team_id}/files?keyword={folder['fileName']}",
            headers=admin_headers,
        ))
        assert_page_has_id(files, folder["id"])

        new_quota = 42949672960
        quota = assert_api_ok(client.patch(
            f"/api/v1/admin/teams/{team_id}/quota",
            json={"storageQuota": new_quota},
            headers=admin_headers,
        ))
        assert quota["storageQuota"] == new_quota

        audit = assert_paginated(client.get(
            f"/api/v1/admin/teams/{team_id}/audit-logs?action=QUOTA_UPDATE",
            headers=admin_headers,
        ))
        assert any(item["action"] == "QUOTA_UPDATE" for item in audit["records"])
        export = client.get(
            f"/api/v1/admin/teams/{team_id}/audit-logs/export?action=QUOTA_UPDATE",
            headers=admin_headers,
        )
        assert export.status_code == 200
        assert "text/csv" in export.headers.get("content-type", "")
        assert "team-" in export.headers.get("content-disposition", "")
        assert "QUOTA_UPDATE" in export.text

        archived = assert_api_ok(client.post(f"/api/v1/admin/teams/{team_id}/archive", headers=admin_headers))
        assert archived["archivedAt"] is not None
        archived_page = assert_paginated(client.get(
            f"/api/v1/admin/teams?keyword={team_name}&status=archived",
            headers=admin_headers,
        ))
        assert_page_has_id(archived_page, team_id)

        unarchived = assert_api_ok(client.post(f"/api/v1/admin/teams/{team_id}/unarchive", headers=admin_headers))
        assert unarchived["archivedAt"] is None

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=admin_headers))
        deleted_page = assert_paginated(client.get(
            f"/api/v1/admin/teams?keyword={team_name}&status=deleted",
            headers=admin_headers,
        ))
        assert_page_has_id(deleted_page, team_id)

        restored = assert_api_ok(client.post(f"/api/v1/admin/teams/{team_id}/restore", headers=admin_headers))
        assert restored["deletedAt"] is None
        restored_detail = assert_api_ok(client.get(f"/api/v1/admin/teams/{team_id}", headers=admin_headers))
        assert restored_detail["id"] == team_id

        assert_api_ok(client.delete(f"/api/v1/teams/{team_id}", headers=admin_headers))
        assert_api_ok(client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers))
        assert_api_error(client.get(f"/api/v1/admin/teams/{team_id}", headers=admin_headers), 404)
