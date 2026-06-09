"""Tests for /api/v1/favorites endpoints."""

from tests.helpers.assert_helper import assert_api_ok, assert_paginated


class TestFavorites:
    def test_add_favorite(self, client, auth_headers, uploaded_file):
        r = client.post(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        assert_api_ok(r)

    def test_add_favorite_duplicate(self, client, auth_headers, uploaded_file):
        client.post(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        r = client.post(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        assert_api_ok(r)  # Idempotent

    def test_add_favorite_nonexistent_file(self, client, auth_headers):
        r = client.post("/api/v1/favorites/99999", headers=auth_headers)
        assert r.json()["code"] != 200

    def test_remove_favorite(self, client, auth_headers, uploaded_file):
        client.post(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        r = client.delete(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        assert_api_ok(r)

    def test_remove_favorite_not_favorited(self, client, auth_headers, uploaded_file):
        r = client.delete(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        assert_api_ok(r)  # Idempotent

    def test_list_favorites(self, client, auth_headers, uploaded_file):
        client.post(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        data = assert_paginated(client.get("/api/v1/favorites", headers=auth_headers))
        assert data["total"] >= 1

    def test_list_favorites_empty(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/favorites", headers=auth_headers))
        assert isinstance(data["records"], list)

    def test_favorite_status_true(self, client, auth_headers, uploaded_file):
        client.post(f"/api/v1/favorites/{uploaded_file['fileId']}", headers=auth_headers)
        r = client.get(f"/api/v1/favorites/{uploaded_file['fileId']}/status", headers=auth_headers)
        data = assert_api_ok(r)
        assert data is True

    def test_favorite_status_false(self, client, auth_headers):
        r = client.get("/api/v1/favorites/99999/status", headers=auth_headers)
        data = assert_api_ok(r)
        assert data is False
