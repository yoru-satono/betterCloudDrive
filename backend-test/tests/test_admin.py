"""Tests for /api/v1/admin endpoints (requires ROLE_ADMIN)."""

import uuid
import pytest
from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated

# NOTE: These tests require a user with ROLE_ADMIN.
# Run this SQL before executing:
#   UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'your_admin_user';
#
# The tests use an environment variable ADMIN_TOKEN for authentication.
# Set ADMIN_TOKEN after logging in as the admin user.

import os


def _check_admin_or_skip(response):
    """Skip test if admin access is not available."""
    if response.status_code == 403:
        pytest.skip("Admin access not available (set ADMIN_TOKEN env var)")
    if "application/json" in response.headers.get("Content-Type", ""):
        if response.json().get("code") == 403:
            pytest.skip("Admin access not available (set ADMIN_TOKEN env var)")


def _get_admin_user_id(token):
    """Extract userId from JWT payload (base64 decode the middle part)."""
    import base64, json
    try:
        payload = token.split(".")[1]
        # Add padding
        payload += "=" * (4 - len(payload) % 4)
        decoded = base64.urlsafe_b64decode(payload)
        return json.loads(decoded)["sub"]
    except Exception:
        return "1"


@pytest.fixture(scope="session")
def admin_user(client, test_user):
    """Use ADMIN_TOKEN env var or fall back to test_user token."""
    admin_token = os.getenv("ADMIN_TOKEN", test_user["accessToken"])
    admin_id = _get_admin_user_id(admin_token)
    return {
        "adminToken": admin_token,
        "userId": int(admin_id),
    }


@pytest.fixture
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
        _check_admin_or_skip(r)
        assert_paginated(r)

    def test_list_users_keyword_filter(self, client, admin_headers, test_user):
        r = client.get(f"/api/v1/admin/users?keyword={test_user['username'][:5]}", headers=admin_headers)
        _check_admin_or_skip(r)
        assert_paginated(r)

    def test_list_users_status_filter(self, client, admin_headers):
        r = client.get("/api/v1/admin/users?status=1", headers=admin_headers)
        _check_admin_or_skip(r)
        assert_paginated(r)

    def test_list_users_pagination(self, client, admin_headers):
        r = client.get("/api/v1/admin/users?page=1&size=2", headers=admin_headers)
        _check_admin_or_skip(r)
        data = assert_paginated(r)
        assert data["size"] == 2

    def test_update_user_status(self, client, admin_headers, admin_user):
        r = client.patch(f"/api/v1/admin/users/{admin_user['userId']}/status", json={
            "status": 1
        }, headers=admin_headers)
        _check_admin_or_skip(r)
        assert_api_ok(r)


class TestAdminFiles:
    def test_list_user_files(self, client, admin_headers, admin_user):
        r = client.get(f"/api/v1/admin/users/{admin_user['userId']}/files", headers=admin_headers)
        _check_admin_or_skip(r)
        assert_paginated(r)

    def test_admin_get_file_nonexistent(self, client, admin_headers):
        r = client.get("/api/v1/admin/files/99999", headers=admin_headers)
        _check_admin_or_skip(r)
        assert_api_error(r, 404001)


class TestAdminLogs:
    def test_list_logs(self, client, admin_headers):
        r = client.get("/api/v1/admin/logs", headers=admin_headers)
        _check_admin_or_skip(r)
        assert_paginated(r)


class TestAdminStats:
    def test_get_stats(self, client, admin_headers):
        r = client.get("/api/v1/admin/stats", headers=admin_headers)
        _check_admin_or_skip(r)
        data = assert_api_ok(r)
        assert "totalUsers" in data
        assert "activeUsers" in data
        assert "totalStorageUsed" in data

    def test_update_user_quota(self, client, admin_headers, admin_user):
        r = client.patch(f"/api/v1/admin/users/{admin_user['userId']}/quota", json={
            "storageQuota": 21474836480  # 20GB
        }, headers=admin_headers)
        _check_admin_or_skip(r)
        assert_api_ok(r)
