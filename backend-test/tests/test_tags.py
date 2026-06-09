"""Tests for /api/v1/tags endpoints."""

import uuid
import pytest
from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated


@pytest.fixture
def created_tag(client, auth_headers):
    name = f"tag_{uuid.uuid4().hex[:6]}"
    r = client.post("/api/v1/tags", json={"tagName": name, "color": "#ff0000"}, headers=auth_headers)
    return assert_api_ok(r)


class TestCreateTag:
    def test_create_tag(self, client, auth_headers):
        name = f"tag_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/tags", json={"tagName": name, "color": "#00ff00"}, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["tagName"] == name
        assert data["color"] == "#00ff00"

    def test_create_tag_default_color(self, client, auth_headers):
        name = f"tag_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/tags", json={"tagName": name}, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["color"] == "#1890ff"

    def test_create_tag_empty_name(self, client, auth_headers):
        r = client.post("/api/v1/tags", json={"tagName": ""}, headers=auth_headers)
        assert_api_error(r, 400)

    def test_create_tag_duplicate_name(self, client, auth_headers, created_tag):
        r = client.post("/api/v1/tags", json={"tagName": created_tag["tagName"]}, headers=auth_headers)
        assert_api_error(r, 409)


class TestListTags:
    def test_list_tags(self, client, auth_headers, created_tag):
        r = client.get("/api/v1/tags", headers=auth_headers)
        data = assert_api_ok(r)
        assert len(data) >= 1

    def test_list_tags_empty(self, client, auth_headers):
        r = client.get("/api/v1/tags", headers=auth_headers)
        data = assert_api_ok(r)
        assert isinstance(data, list)


class TestUpdateTag:
    def test_update_tag_name(self, client, auth_headers, created_tag):
        new_name = f"updated_{uuid.uuid4().hex[:6]}"
        r = client.put(f"/api/v1/tags/{created_tag['id']}", json={
            "tagName": new_name
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["tagName"] == new_name

    def test_update_tag_nonexistent(self, client, auth_headers):
        r = client.put("/api/v1/tags/99999", json={"tagName": "x"}, headers=auth_headers)
        assert r.json()["code"] != 200


class TestDeleteTag:
    def test_delete_tag(self, client, auth_headers):
        name = f"deltag_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/tags", json={"tagName": name}, headers=auth_headers)
        tag_id = r.json()["data"]["id"]
        r2 = client.delete(f"/api/v1/tags/{tag_id}", headers=auth_headers)
        assert_api_ok(r2)


class TestTagFiles:
    def test_tag_files(self, client, auth_headers, created_tag, uploaded_file):
        r = client.post(f"/api/v1/tags/{created_tag['id']}/files", json={
            "fileIds": [uploaded_file["fileId"]]
        }, headers=auth_headers)
        assert_api_ok(r)

    def test_tag_files_nonexistent_tag(self, client, auth_headers, uploaded_file):
        r = client.post("/api/v1/tags/99999/files", json={
            "fileIds": [uploaded_file["fileId"]]
        }, headers=auth_headers)
        assert r.json()["code"] != 200

    def test_untag_file(self, client, auth_headers, created_tag, uploaded_file):
        client.post(f"/api/v1/tags/{created_tag['id']}/files", json={
            "fileIds": [uploaded_file["fileId"]]
        }, headers=auth_headers)
        r = client.delete(f"/api/v1/tags/{created_tag['id']}/files/{uploaded_file['fileId']}", headers=auth_headers)
        assert_api_ok(r)

    def test_list_files_by_tag(self, client, auth_headers, created_tag, uploaded_file):
        client.post(f"/api/v1/tags/{created_tag['id']}/files", json={
            "fileIds": [uploaded_file["fileId"]]
        }, headers=auth_headers)
        data = assert_paginated(client.get(f"/api/v1/tags/{created_tag['id']}/files", headers=auth_headers))
        assert data["total"] >= 1

    def test_list_files_by_tag_empty(self, client, auth_headers, created_tag):
        data = assert_paginated(client.get(f"/api/v1/tags/{created_tag['id']}/files", headers=auth_headers))
        assert data["total"] == 0
