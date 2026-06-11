"""Tests for /api/v1/shares endpoints (CRUD + public access)."""

import pytest
import time
from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated


@pytest.fixture
def shared_file(client, auth_headers, uploaded_file):
    """Create a share for the uploaded file and return share info."""
    r = client.post("/api/v1/shares", json={"fileId": uploaded_file["fileId"]}, headers=auth_headers)
    data = assert_api_ok(r)
    return {"shareId": data["id"], "shareCode": data["shareCode"], "fileId": uploaded_file["fileId"]}


class TestCreateShare:
    def test_create_share_basic(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={"fileId": uploaded_file["fileId"]}, headers=auth_headers)
        data = assert_api_ok(r)
        assert "shareCode" in data
        assert len(data["shareCode"]) == 8

    def test_create_share_with_password(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "password": "pass123"
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["passwordHash"] is not None

    def test_create_share_with_expiry(self, client, auth_headers, uploaded_file):
        future = int((time.time() + 86400 * 7) * 1000)  # 7 days from now
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "expireAt": future
        }, headers=auth_headers)
        assert_api_ok(r)

    def test_create_share_with_max_downloads(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "maxDownloads": 1
        }, headers=auth_headers)
        assert_api_ok(r)

    def test_create_share_nonexistent_file(self, client, auth_headers):
        r = client.post("/api/v1/shares", json={"fileId": 99999}, headers=auth_headers)
        assert_api_error(r, 404001)

    def test_create_share_missing_file_id(self, client, auth_headers):
        r = client.post("/api/v1/shares", json={}, headers=auth_headers)
        assert_api_error(r, 400)


class TestListShares:
    def test_list_shares(self, client, auth_headers, shared_file):
        data = assert_paginated(client.get("/api/v1/shares", headers=auth_headers))
        assert data["total"] >= 1

    def test_list_shares_pagination(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/shares?page=1&size=5", headers=auth_headers))
        assert data["size"] == 5


class TestGetShare:
    def test_get_share(self, client, auth_headers, shared_file):
        r = client.get(f"/api/v1/shares/{shared_file['shareId']}", headers=auth_headers)
        data = assert_api_ok(r)
        assert data["id"] == shared_file["shareId"]

    def test_get_share_nonexistent(self, client, auth_headers):
        r = client.get("/api/v1/shares/99999", headers=auth_headers)
        assert r.json()["code"] != 200


class TestUpdateShare:
    def test_update_share_add_password(self, client, auth_headers, shared_file):
        r = client.put(f"/api/v1/shares/{shared_file['shareId']}", json={
            "password": "newpass"
        }, headers=auth_headers)
        assert_api_ok(r)

    def test_update_share_remove_password(self, client, auth_headers, shared_file):
        r = client.put(f"/api/v1/shares/{shared_file['shareId']}", json={
            "password": ""
        }, headers=auth_headers)
        assert_api_ok(r)


class TestCancelShare:
    def test_cancel_share(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={"fileId": uploaded_file["fileId"]}, headers=auth_headers)
        share_id = r.json()["data"]["id"]
        share_code = r.json()["data"]["shareCode"]

        r2 = client.delete(f"/api/v1/shares/{share_id}", headers=auth_headers)
        assert_api_ok(r2)

        # Access should now fail
        r3 = client.post(f"/api/v1/shares/access/{share_code}")
        assert_api_error(r3, 419002)


class TestAccessShare:
    def test_access_no_password(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={"fileId": uploaded_file["fileId"]}, headers=auth_headers)
        code = r.json()["data"]["shareCode"]
        r2 = client.post(f"/api/v1/shares/access/{code}")
        data = assert_api_ok(r2)
        assert "fileId" in data

    def test_access_with_correct_password(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "password": "secret"
        }, headers=auth_headers)
        code = r.json()["data"]["shareCode"]
        r2 = client.post(f"/api/v1/shares/access/{code}", json={"password": "secret"})
        assert_api_ok(r2)

    def test_access_with_wrong_password(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "password": "secret"
        }, headers=auth_headers)
        code = r.json()["data"]["shareCode"]
        r2 = client.post(f"/api/v1/shares/access/{code}", json={"password": "wrong"})
        assert_api_error(r2, 419003)

    def test_access_without_password_when_required(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "password": "secret"
        }, headers=auth_headers)
        code = r.json()["data"]["shareCode"]
        r2 = client.post(f"/api/v1/shares/access/{code}")
        assert_api_error(r2, 419003)

    def test_access_invalid_code(self, client):
        r = client.post("/api/v1/shares/access/INVALID1")
        assert_api_error(r, 419002)

    def test_access_expired_share(self, client, auth_headers, uploaded_file):
        past = int((time.time() - 86400) * 1000)  # 1 day ago
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "expireAt": past
        }, headers=auth_headers)
        code = r.json()["data"]["shareCode"]
        r2 = client.post(f"/api/v1/shares/access/{code}")
        assert_api_error(r2, 419004)


class TestListSharedFiles:
    def test_list_shared_files(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={"fileId": uploaded_file["fileId"]}, headers=auth_headers)
        code = r.json()["data"]["shareCode"]
        data = assert_paginated(client.get(f"/api/v1/shares/access/{code}/files"))
        assert data["total"] >= 1

    def test_list_shared_files_invalid_code(self, client):
        r = client.get("/api/v1/shares/access/BADCODE1/files")
        assert_api_error(r, 419002)


class TestDownloadLimit:
    def test_share_download_limit_exceeded(self, client, auth_headers, uploaded_file):
        """maxDownloads=0 means zero downloads are permitted — any access returns 419005."""
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"], "maxDownloads": 0
        }, headers=auth_headers)
        code = r.json()["data"]["shareCode"]

        r2 = client.post(f"/api/v1/shares/access/{code}")
        assert_api_error(r2, 419005)


class TestShareNotification:
    def test_create_share_with_notify_email(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/shares", json={
            "fileId": uploaded_file["fileId"],
            "notifyEmail": "friend@example.com"
        }, headers=auth_headers)
        assert_api_ok(r)
        # Mailpit would capture the email; we verify the share was created
