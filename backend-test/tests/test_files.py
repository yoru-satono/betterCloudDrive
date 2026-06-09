"""Tests for /api/v1/files endpoints (CRUD, search)."""

import uuid
from tests.helpers.assert_helper import assert_api_ok, assert_api_error, assert_paginated


class TestListFiles:
    def test_list_root_empty(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/files", headers=auth_headers))
        # Root may or may not be empty depending on test state

    def test_list_with_items(self, client, auth_headers, created_folder, uploaded_file):
        data = assert_paginated(client.get("/api/v1/files", headers=auth_headers))
        assert data["total"] >= 1

    def test_list_pagination(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/files?page=1&size=5", headers=auth_headers))
        assert data["size"] == 5

    def test_list_sort_by_name(self, client, auth_headers):
        r = client.get("/api/v1/files?sortBy=fileName&order=asc", headers=auth_headers)
        assert_api_ok(r)

    def test_list_with_parent_id(self, client, auth_headers, created_folder):
        r = client.get(f"/api/v1/files?parentId={created_folder['id']}", headers=auth_headers)
        assert_paginated(r)


class TestGetFile:
    def test_get_file(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/files/{uploaded_file['fileId']}", headers=auth_headers)
        data = assert_api_ok(r)
        assert data["id"] == uploaded_file["fileId"]

    def test_get_file_nonexistent(self, client, auth_headers):
        assert_api_error(client.get("/api/v1/files/99999", headers=auth_headers), 404001)

    def test_get_folder(self, client, auth_headers, created_folder):
        r = client.get(f"/api/v1/files/{created_folder['id']}", headers=auth_headers)
        data = assert_api_ok(r)
        assert data["fileType"] == "folder"


class TestCreateFolder:
    def test_create_folder_root(self, client, auth_headers):
        name = f"root_folder_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/files/folder", json={"folderName": name}, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["fileType"] == "folder"
        assert data["fileName"] == name

    def test_create_folder_nested(self, client, auth_headers, created_folder):
        name = f"sub_{uuid.uuid4().hex[:6]}"
        r = client.post("/api/v1/files/folder", json={
            "parentId": created_folder["id"], "folderName": name
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["parentId"] == created_folder["id"]

    def test_create_folder_name_conflict(self, client, auth_headers, created_folder):
        r = client.post("/api/v1/files/folder", json={
            "parentId": None, "folderName": created_folder["fileName"]
        }, headers=auth_headers)
        assert_api_error(r, 409001)


class TestRename:
    def test_rename_file(self, client, auth_headers, uploaded_file):
        new_name = f"renamed_{uuid.uuid4().hex[:4]}.txt"
        r = client.put(f"/api/v1/files/{uploaded_file['fileId']}", json={
            "newName": new_name
        }, headers=auth_headers)
        data = assert_api_ok(r)
        assert data["fileName"] == new_name


class TestMove:
    def test_move_file(self, client, auth_headers, uploaded_file, created_folder):
        # Create a subfolder to move into
        sub = client.post("/api/v1/files/folder", json={
            "parentId": created_folder["id"], "folderName": f"dest_{uuid.uuid4().hex[:4]}"
        }, headers=auth_headers)
        dest_id = sub.json()["data"]["id"]

        r = client.post(f"/api/v1/files/{uploaded_file['fileId']}/move", json={
            "targetParentId": dest_id
        }, headers=auth_headers)
        assert_api_ok(r)


class TestCopy:
    def test_copy_file(self, client, auth_headers, uploaded_file, created_folder):
        r = client.post(f"/api/v1/files/{uploaded_file['fileId']}/copy", json={
            "targetParentId": created_folder["id"]
        }, headers=auth_headers)
        assert_api_ok(r)


class TestDelete:
    def test_batch_delete(self, client, auth_headers, created_folder):
        # Create two files and delete them
        names = []
        for _ in range(2):
            names.append(f"del_{uuid.uuid4().hex[:4]}")
        file_ids = []
        for name in names:
            r = client.post("/api/v1/files/folder", json={"folderName": name, "parentId": created_folder["id"]}, headers=auth_headers)
            file_ids.append(r.json()["data"]["id"])

        r = client.request("DELETE", "/api/v1/files", json={"fileIds": file_ids}, headers=auth_headers)
        assert_api_ok(r)


class TestSearch:
    def test_search_by_filename(self, client, auth_headers, uploaded_file):
        r = client.get(f"/api/v1/files/search?q={uploaded_file['fileName'][:5]}", headers=auth_headers)
        assert_paginated(r)

    def test_search_no_results(self, client, auth_headers):
        data = assert_paginated(client.get("/api/v1/files/search?q=zzzzznotexist999", headers=auth_headers))
        assert data["total"] == 0

    def test_search_empty_query(self, client, auth_headers):
        r = client.get("/api/v1/files/search?q=", headers=auth_headers)
        # Backend treats empty query as match-all (returns all user files)
        assert_paginated(r)
