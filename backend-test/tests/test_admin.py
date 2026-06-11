"""Tests for /api/v1/admin endpoints (requires ROLE_ADMIN).

Set ADMIN_TOKEN env var to a valid admin JWT before running these tests:
  UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'your_admin_user';
  export ADMIN_TOKEN=$(curl .../auth/login ... | jq -r .data.accessToken)

All tests in this module are skipped automatically when ADMIN_TOKEN is unset.
"""

import base64
import json
import os
import pytest
from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated


def _decode_user_id(token: str) -> int:
    try:
        payload = token.split(".")[1]
        payload += "=" * (4 - len(payload) % 4)
        return int(json.loads(base64.urlsafe_b64decode(payload))["sub"])
    except Exception:
        return 1


@pytest.fixture(scope="session")
def admin_user():
    token = os.getenv("ADMIN_TOKEN")
    if not token:
        pytest.skip("ADMIN_TOKEN not set — skipping admin tests")
    return {"userId": _decode_user_id(token), "adminToken": token}


@pytest.fixture(scope="session")
def admin_headers(admin_user):
    return {"Authorization": f"Bearer {admin_user['adminToken']}"}


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
